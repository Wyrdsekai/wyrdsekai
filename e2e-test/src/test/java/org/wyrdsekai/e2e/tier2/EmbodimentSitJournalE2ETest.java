package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.story.Scene;
import org.wyrdsekai.core.story.StoryRegistry;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestSshClient;
import org.wyrdsekai.e2e.infra.WireMockInferenceServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * live-verify E2E (§9.3 in-process variant).
 *
 * <p>Brings up a real TestServerBootstrap with a sandboxed data dir; logs a
 * steward in via SSH; sends {@code sit}/{@code stand} from the SSH session;
 * verifies the companion's StoryService observed the {@code PostureChanged}
 * events as beats; force-closes the scene; asserts the biography markdown
 * file appears under {@code <dataDir>/data/biography/&lt;companion-DID&gt;/&lt;today&gt;.md}
 * with the expected scene block.</p>
 *
 * <p>Sandboxes via {@code -Dwyrdsekai.dataDir=&lt;temp&gt;} BEFORE the bootstrap
 * starts — without this, the test would persist into the real
 * {@code ~/.wyrdsekai} install.</p>
 *
 * <p>Why force-close: the spec rule that closes a scene when the room becomes
 * solo + focal-posture clears requires the scene to have {@code startedNonSolo=true}.
 * The default companion spawns alone in nexus, opens its scene before the
 * player arrives, so {@code startedNonSolo=false}. Closing the scene naturally
 * from the SSH side without that gating would require either a companion
 * relocation hook or a deeper rule-3 patch — orthogonal to this E2E. The
 * force-close models server-shutdown ({@link StoryService#forceCloseAll}) which
 * is a real production code path; the rule-3 gap is captured separately.</p>
 */
@Tag("tier2")
class EmbodimentSitJournalE2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String COMPANION_ENTITY_ID = "companion-wyrd";
    private static final String USER = "embodyuser";
    private static final String PASS = "embodypass";

    private static TestServerBootstrap server;
    private static WireMockInferenceServer wireMock;
    private static Path dataDir;
    private static String savedDataDir;

    @BeforeAll
    static void setUp() throws Exception {
        // Sandbox: every read of SystemPaths.dataDir() routes here, so
        // StoryStore lands its scenes + biographies inside the test dir.
        // CRITICAL: set BEFORE TestServerBootstrap.start() — the boot path
        // resolves dataDir during CoreServices.init().
        dataDir = Files.createTempDirectory("wyrd-e2e-embodiment-");
        savedDataDir = System.getProperty("wyrdsekai.dataDir");
        System.setProperty("wyrdsekai.dataDir", dataDir.toString());

        // Reset StoryRegistry so it picks up the new dataDir (its store is
        // lazily-initialized but cached after first call).
        StoryRegistry.get().reset();

        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Acknowledged.", 30, 20);

        server = new TestServerBootstrap(List.of(
            new InferenceBackend.LlamaServer("wiremock",
                new InferenceClient(wireMock.baseUrl()), 10, List.of(), null)));
        server.start();

        // Register the steward.
        var http = HttpClient.newHttpClient();
        http.send(HttpRequest.newBuilder()
            .uri(URI.create(server.baseUrl() + "/api/auth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"" + USER + "\",\"password\":\"" + PASS
                    + "\",\"displayName\":\"Embody User\"}"))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
        StoryRegistry.get().reset();
        if (savedDataDir == null) {
            System.clearProperty("wyrdsekai.dataDir");
        } else {
            System.setProperty("wyrdsekai.dataDir", savedDataDir);
        }
        // tempDir is left for post-mortem; OS cleans /tmp eventually.
    }

    @Test
    void sit_then_stand_in_nexus_produces_biography_markdown() throws Exception {
        // Steward lands in their Study; walk west → nexus (where the
        // default companion lives).
        try (var ssh = TestSshClient.connectWithPassword(
                "localhost", server.sshPort(), USER, PASS)) {
            ssh.waitForText("Study", TIMEOUT);
            Thread.sleep(500);

            ssh.sendLine("home");          // ensure we're in Study
            Thread.sleep(300);
            ssh.sendLine("go nexus");      // walk to the nexus
            ssh.waitForText("Nexus", TIMEOUT);
            Thread.sleep(500);

            // Body-language flow: sit at the hearth, hold, stand, leave.
            ssh.sendLine("sit at hearth");
            Thread.sleep(400);
            ssh.sendLine("examine me");    // posture line should now show on examine
            ssh.waitForText("settles", Duration.ofSeconds(5));
            Thread.sleep(300);

            ssh.sendLine("stand");
            Thread.sleep(400);

            // Quit cleanly so EntityLeft fires on the player.
            ssh.sendLine("quit");
            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
        }

        // The companion's StoryService observed the posture events as beats.
        // Force-close (production: shutdown/forceCloseAll) so the scene
        // persists + the biography markdown is appended.
        var story = StoryRegistry.get().get(COMPANION_ENTITY_ID);
        assertNotNull(story, "companion StoryService initialized post-event-flow");
        var closed = story.forceCloseAll(Instant.now())
            .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertFalse(closed.isEmpty(),
            "at least one scene closed for the companion focal");
        var nexusScene = closed.stream()
            .filter(s -> "nexus".equals(s.roomId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "expected a closed scene in nexus, got rooms: "
                + closed.stream().map(Scene::roomId).toList()));
        assertTrue(nexusScene.beatCount() >= 1,
            "nexus scene captured at least one beat from the sit/stand flow; got "
            + nexusScene.beatCount() + " beats");

        // Biography markdown written at <dataDir>/data/biography/<focal>/<today>.md
        var today = LocalDate.now(ZoneId.systemDefault());
        var bio = dataDir.resolve("data")
            .resolve("biography")
            .resolve(COMPANION_ENTITY_ID)
            .resolve(today + ".md");
        assertTrue(Files.exists(bio),
            "biography markdown written: " + bio
            + "\n(dataDir tree:\n" + listing(dataDir) + ")");
        var body = Files.readString(bio);
        assertFalse(body.isBlank(), "biography body is non-empty");
        assertTrue(body.contains("nexus") || body.contains("Nexus")
                || body.contains(today.toString()),
            "biography references the nexus scene or today's date: " + body);
    }

    private static String listing(Path root) {
        try (var s = Files.walk(root)) {
            return s.map(Path::toString)
                .reduce("", (a, b) -> a + "\n  " + b);
        } catch (Exception e) {
            return "(walk failed: " + e + ")";
        }
    }
}
