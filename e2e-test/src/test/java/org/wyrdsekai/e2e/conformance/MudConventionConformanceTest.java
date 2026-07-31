package org.wyrdsekai.e2e.conformance;

import org.junit.jupiter.api.*;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.e2e.infra.*;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mechanical MUD-convention conformance suite.
 *
 * <p>SPEC: every assertion in this file
 * corresponds to a §/§§ entry in the spec. Failures should map back to a
 * spec line so the contract is the source of truth, not the test.</p>
 *
 * <p>Each test exercises a single contract item across the SSH surface
 * (WyrdShellCommand). WebSocket and virtual-session parity tests are TODO —
 * §10 of the spec demands cross-transport invariance; phase 2 of this
 * suite will mirror each test through {@code WyrdWebSocket}.</p>
 *
 * <p>Runs against an in-process {@link TestServerBootstrap} by default.
 * Set {@code WYRDSEKAI_CONFORMANCE_TARGET=host:port} to point at a live
 * server (e.g. {@code home-server:7022}) — catches drift between embedded and
 * production builds.</p>
 *
 * <p>Tagged {@code conformance} so it can be selected/skipped independently
 * of the broader {@code integration} suite during incremental fix work.</p>
 */
@Tag("conformance")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MudConventionConformanceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static TestServerBootstrap server;
    private static WireMockInferenceServer wireMock;
    private static String sshHost;
    private static int sshPort;
    // Live-target mode passes creds via WYRDSEKAI_CONFORMANCE_USER and _PASS.
    // Embedded mode falls back to the in-test bootstrap user/pass.
    private static String USER = "confuser";
    private static String PASS = "confpass";
    // Second user for multi-user tests (two SSH sessions in one test).
    // Live mode reads WYRDSEKAI_CONFORMANCE_USER2 / PASS2; embedded auto-bootstraps.
    private static String USER2 = "confuser2";
    private static String PASS2 = "confpass2";
    // True when running against a real-foundation server (home-server etc.). The
    // embedded TestServerBootstrap uses a minimal room fixture with no
    // takeable objects — tests that exercise full foundation content
    // (take/drop, federation, etc.) gate on this flag and skip in
    // embedded mode.
    private static boolean liveMode = false;

    @BeforeAll
    static void setUp() throws Exception {
        var override = System.getenv("WYRDSEKAI_CONFORMANCE_TARGET");
        if (override != null && !override.isBlank() && !"embedded".equalsIgnoreCase(override)) {
            // Live-server mode — assume the operator has already created the
            // test user. The spec is the same; only the target changes.
            var parts = override.split(":");
            sshHost = parts[0];
            sshPort = parts.length > 1 ? Integer.parseInt(parts[1]) : 7022;
            var envUser = System.getenv("WYRDSEKAI_CONFORMANCE_USER");
            var envPass = System.getenv("WYRDSEKAI_CONFORMANCE_PASS");
            if (envUser != null && !envUser.isBlank()) USER = envUser;
            if (envPass != null && !envPass.isBlank()) PASS = envPass;
            var envUser2 = System.getenv("WYRDSEKAI_CONFORMANCE_USER2");
            var envPass2 = System.getenv("WYRDSEKAI_CONFORMANCE_PASS2");
            if (envUser2 != null && !envUser2.isBlank()) USER2 = envUser2;
            if (envPass2 != null && !envPass2.isBlank()) PASS2 = envPass2;
            liveMode = true;
            // Live mode: align displayName=username so multi-user tests can
            // address each other by username (same code path as embedded).
            normalizeDisplayNames();
            return;
        }
        USER = "confuser";
        PASS = "confpass";

        // Enable the test-only reset hook BEFORE TestServerBootstrap.start()
        // so AuthRoutes.register sees it and binds /api/auth/test-reset.
        System.setProperty("wyrdsekai.test.reset_enabled", "true");

        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Acknowledged.", 30, 20);

        server = new TestServerBootstrap(List.of(
            new InferenceBackend.LlamaServer("wiremock",
                new InferenceClient(wireMock.baseUrl()), 10, List.of(), null)));
        server.start();
        sshHost = "localhost";
        sshPort = server.sshPort();

        // Bootstrap two test users. Since the F4 hardening, /api/auth/register is
        // open ONLY for the first user (the steward); USER2 must come through the
        // steward's invite/redeem flow. TestUsers wraps both steps.
        var stewardToken = TestUsers.registerStewardToken(
            server.baseUrl(), USER, PASS, "Conf User");
        TestUsers.inviteAndRedeem(
            server.baseUrl(), stewardToken, USER2, PASS2, "Conf User Two");

        normalizeDisplayNames();
    }

    /**
     * §10 cross-transport contracts assume {@code tell <name>} /
     * {@code whisper >name} / {@code examine <name>} work with the same
     * handle the steward types. EntityRegistry indexes by display name;
     * aligning displayName to username here is a test-fixture convenience
     * so multi-user probes can address peers by username. (The
     * product-level fix — `tell <username>` should also work even when
     * displayName has spaces — is tracked separately; see
     * )
     */
    private static void normalizeDisplayNames() throws Exception {
        // Best-effort. Embedded mode's USER2 registration can fail silently
        // (openRegistration auto-closes after the steward); when that
        // happens, single-user tests still run and multi-user tests fail
        // on their own assertions with a clearer message.
        try (var a = loginAs(USER, PASS)) {
            a.sendLine("rename me " + USER);
            Thread.sleep(1000);
        } catch (Exception ignored) {}
        try (var b = loginAs(USER2, PASS2)) {
            b.sendLine("rename me " + USER2);
            Thread.sleep(1000);
        } catch (Exception ignored) {}
    }

    /** REST base URL — derived from server in embedded mode, $sshHost:7070
     * in live mode (overridable via WYRDSEKAI_CONFORMANCE_REST_URL). */
    private static String restBaseUrl() {
        if (server != null) return server.baseUrl();
        var override = System.getenv("WYRDSEKAI_CONFORMANCE_REST_URL");
        if (override != null && !override.isBlank()) return override;
        return "http://" + sshHost + ":7070";
    }

    /**
     * Reset a user's displayName + description to a known seed via the
     * test-only {@code /api/auth/test-reset} hook (
     * §11.11). Lets each multi-user test start from a clean slate without
     * depending on the in-band {@code rename}/{@code @describe} verbs.
     *
     * <p>Requires the server to have {@code -Dwyrdsekai.test.reset_enabled=true}
     * OR {@code WYRDSEKAI_TEST_RESET_ENABLED=true} in its environment.
     * Silent no-op if the endpoint isn't registered — falls back on
     * {@link #normalizeDisplayNames()} for at least the displayName side.</p>
     */
    private static void testResetUser(String username, String displayName, String description) throws Exception {
        var http = HttpClient.newHttpClient();
        var body = "{\"username\":\"" + username + "\","
            + "\"displayName\":\"" + displayName + "\","
            + "\"description\":\"" + description + "\"}";
        var resp = http.send(HttpRequest.newBuilder()
            .uri(URI.create(restBaseUrl() + "/api/auth/test-reset"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            // Endpoint not enabled — best-effort fall back to in-band rename.
            // (description can't be reset without the hook; tests guard their
            // own description-bearing assertions.)
            try (var s = loginAs(USER.equals(username) ? USER : USER2,
                                  USER.equals(username) ? PASS : PASS2)) {
                s.sendLine("rename me " + displayName);
                Thread.sleep(500);
            }
        }
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    // -----------------------------------------------------------------
    // §1.2 Logout / disconnect
    // -----------------------------------------------------------------

    /** §1.2 — `quit` ends the session. */
    @Test @Order(101)
    void quit_closes_session() throws Exception {
        try (var ssh = login()) {
            ssh.sendLine("quit");
            assertChannelClosesWithin(ssh, Duration.ofSeconds(5),
                "After 'quit', server should close the SSH channel");
        }
    }

    /** §1.2 — `exit` is an alias for `quit`. */
    @Test @Order(102)
    void exit_closes_session() throws Exception {
        try (var ssh = login()) {
            ssh.sendLine("exit");
            assertChannelClosesWithin(ssh, Duration.ofSeconds(5),
                "After 'exit', server should close the SSH channel");
        }
    }

    /** §1.2 — `q` is a short alias for `quit`. */
    @Test @Order(103)
    void q_alias_closes_session() throws Exception {
        try (var ssh = login()) {
            ssh.sendLine("q");
            assertChannelClosesWithin(ssh, Duration.ofSeconds(5),
                "After 'q', server should close the SSH channel");
        }
    }

    /**
     * §1.2 — Ctrl-D (ASCII 4) on an empty line ends the session.
     * <p>Pre-fix: silently dropped. Fix in {@code WyrdShellCommand.java:401}
     * input loop adds an EOT branch that returns from {@code runSession()}.</p>
     */
    @Test @Order(104)
    void ctrl_d_on_empty_line_closes_session() throws Exception {
        try (var ssh = login()) {
            sendRawByte(ssh, (byte) 4); // ^D / EOT
            assertChannelClosesWithin(ssh, Duration.ofSeconds(5),
                "After Ctrl-D on empty line, server should close the SSH channel");
        }
    }

    // -----------------------------------------------------------------
    // §2.2 examine / look-at
    // -----------------------------------------------------------------

    /**
     * §2.2 — `examine X` is a passive observation. Output must not contain
     * action-confirmation phrasing like "you use".
     *
     * <p>Pre-fix this fails because {@code examine X} parses to
     * {@code Use(X, null)} and the use-handler's fallback string is
     * {@code "You use the X."} when the script returns no response.</p>
     */
    @Test @Order(201)
    void examine_does_not_emit_use_confirmation() throws Exception {
        try (var ssh = login()) {
            // Target a non-scripted Study object so the use-handler's fallback
            // ("You use the X." from RoomActor.onUseObject → catalog.ui.used)
            // actually fires. Scripted items (Embers, Board) intercept the
            // call inside their GraalJS handler and return their own response,
            // which masks the bug — picking the non-scripted "cost ledger"
            // routes straight through the broken examine→Use path.
            int mark = ssh.mark();
            ssh.sendLine("examine cost ledger");
            Thread.sleep(2000); // allow async room reply
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            assertFalse(output.contains("you use"),
                "§2.2: 'examine' must not emit 'you use' confirmation. " +
                "Currently parses to Use(X, null) and triggers ObjectUsed " +
                "→ ui.used template. Output:\n" + output);
        }
    }

    /**
     * §2.2 — `examine X` reply must not include a full room re-render.
     * <p>Symptom of the broken-as-use path: {@code RoomActor.onUseObject}
     * returned {@code RoomResponse.Ok(snapshot)} containing the full room
     * state; the client painted it as a fresh look. Post-Examine-refactor,
     * handleExamine pulls description directly from snapshot and prints
     * only the description text — no full re-render.</p>
     */
    @Test @Order(202)
    void examine_does_not_rerender_room() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("examine cost ledger");
            Thread.sleep(2000);
            var output = String.join("\n", ssh.linesSince(mark));
            // Re-render heuristic: a fresh room render lists the room's
            // exits. If after `examine X` we see an "Exits:" line, that's
            // the snapshot leaking through.
            boolean exitsListed = output.lines()
                .anyMatch(l -> l.matches("(?i).*exits?:\\s*\\S.*"));
            assertFalse(exitsListed,
                "§2.2: 'examine' must not trigger a room re-render. " +
                "Got 'Exits:' line in reply:\n" + output);
        }
    }

    /**
     * §2.2 — `examine X` on a non-scripted room object returns the
     * object's description text, not a use-confirmation.
     *
     * <p>This is the regression-lock for the Examine refactor. The Study
     * has the {@code cost ledger} object (non-scripted, takeable=false)
     * with a description starting with "A small ledger on the desk
     * corner". Examine must surface that description, not "you use".</p>
     */
    @Test @Order(203)
    void examine_returns_object_description_text() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("examine cost ledger");
            Thread.sleep(2000);
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            // Description in foundation-rooms.json starts with "A small
            // ledger on the desk corner, its pages ruled with neat
            // columns." Look for a stable substring.
            assertTrue(output.contains("ledger") &&
                (output.contains("pages") || output.contains("desk")
                || output.contains("columns") || output.contains("inference")),
                "§2.2: 'examine cost ledger' must return description text. " +
                "Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §7.2 describe me — persistence + visibility
    // -----------------------------------------------------------------

    /**
     * §7.2 — `describe me <text>` sets the caller's description, which
     * persists and is visible to other observers on look-at.
     * <p>This conformance test checks the end-to-end contract that
     * {@code DescribeE2ETest} (parser-level) doesn't reach.</p>
     */
    @Test @Order(301)
    void describe_me_persists_and_is_visible_on_examine() throws Exception {
        var marker = "a quiet scholar with " + System.nanoTime() + " in their eyes";
        try (var ssh = login()) {
            ssh.sendLine("describe me " + marker);
            Thread.sleep(1000);
        }
        // Reconnect, examine self, check description shows up.
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("examine me");
            Thread.sleep(2000);
            var output = String.join("\n", ssh.linesSince(mark));
            assertTrue(output.contains(marker),
                "§7.2: 'describe me <text>' must persist and surface on " +
                "self-examine. Marker '" + marker + "' not found in:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §7.4 rename — proposed, expected to fail until built
    // -----------------------------------------------------------------

    /**
     * §7.4 — `rename <entity> <new-name>` — steward + bondholder verb.
     * <p>Does not exist today. This test exists to drive the design:
     * once implemented, flip the assertion. Until then it's a known-red.</p>
     */
    @Test @Order(401)
    void rename_companion_verb_exists() throws Exception {
        var newName = "RenameProbe" + (System.nanoTime() % 100000);
        try (var ssh = login()) {
            ssh.sendLine("rename me " + newName);
            Thread.sleep(1500);
            // Verify by `who` — if rename actually applied, the display name
            // in the who-list should change. This is stronger than a string
            // match on the rename reply: it asserts effect, not just response.
            int mark = ssh.mark();
            ssh.sendLine("who");
            Thread.sleep(1500);
            var whoOutput = String.join("\n", ssh.linesSince(mark));
            assertTrue(whoOutput.contains(newName),
                "§7.4: 'rename me " + newName + "' must take effect — " +
                "subsequent 'who' should list new name. The current " +
                "ParsedCommand has no Rename record, so the input is " +
                "silently treated as a no-op (or routed to say). " +
                "Got 'who' output:\n" + whoOutput);
        }
    }

    // -----------------------------------------------------------------
    // §2.1 look / §2.4 where / §2.5 exits
    // -----------------------------------------------------------------

    /** §2.1 — bare `look` returns the current room render. */
    @Test @Order(501)
    void look_renders_current_room() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("look");
            Thread.sleep(1500);
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            assertTrue(output.contains("study"),
                "§2.1: bare 'look' must render the room. Got:\n" + output);
        }
    }

    /** §2.4 — `where` returns the room name and zone in one line. */
    @Test @Order(502)
    void where_returns_location() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("where");
            Thread.sleep(1000);
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            assertTrue(output.contains("you are in") || output.contains("study"),
                "§2.4: 'where' must return location. Got:\n" + output);
        }
    }

    /** §2.5 — `exits` returns a single-line list of room exits. */
    @Test @Order(503)
    void exits_lists_room_exits() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("exits");
            Thread.sleep(1000);
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            assertTrue(output.contains("exits"),
                "§2.5: 'exits' must produce an 'Exits:' line. Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §4 inventory / take / drop
    // -----------------------------------------------------------------

    /**
     * §4.2 / §4.3 — Take/drop round-trip on a real takeable object.
     *
     * <p>Navigates Study → out → east (Nexus → Docks), which is seeded with
     * a takeable `compass` in {@code foundation-rooms.json}. Asserts the
     * full lifecycle: object enters inventory after take, exits inventory
     * after drop. This is the §4 contract that error-only coverage
     * (take/drop unknown) doesn't reach.</p>
     *
     * <p>Brittle to the docks-compass seed staying in the foundation room
     * layout — if that changes, route via a different room with a
     * takeable item. The take/drop *protocol* is what's under test, not
     * the specific object.</p>
     */
    @Test @Order(602)
    void take_and_drop_round_trip() throws Exception {
        // Runs in both embedded and live modes. The embedded
        // TestServerBootstrap.foundationRoomSeeds() now mirrors the live
        // foundation's east=Docks layout and seeds a takeable compass in
        // Docks. If the test starts skipping again, check that fixture.
        try (var ssh = login()) {
            // Study → Nexus (out) → Docks (east) — see foundation-rooms.json
            // for the docks-compass seed. Need ~1s after Study landing for
            // the room to fully initialize before navigation works (see
            // existing SshConnectionE2ETest.ssh_navigate_works pattern).
            Thread.sleep(1000);
            ssh.sendLine("out");
            ssh.waitForText("Nexus", TIMEOUT);
            Thread.sleep(1000);
            ssh.sendLine("east");
            ssh.waitForText("Docks", TIMEOUT);
            Thread.sleep(1000);

            ssh.sendLine("take compass");
            Thread.sleep(1500);
            int mark1 = ssh.mark();
            ssh.sendLine("inventory");
            Thread.sleep(1500);
            var inv1 = String.join("\n", ssh.linesSince(mark1)).toLowerCase();
            assertTrue(inv1.contains("compass"),
                "§4.2: 'take compass' must add compass to inventory. " +
                "Got inventory:\n" + inv1);

            ssh.sendLine("drop compass");
            Thread.sleep(1500);
            int mark2 = ssh.mark();
            ssh.sendLine("inventory");
            Thread.sleep(1500);
            var inv2 = String.join("\n", ssh.linesSince(mark2)).toLowerCase();
            assertFalse(inv2.contains("compass"),
                "§4.3: 'drop compass' must remove compass from inventory. " +
                "Got inventory:\n" + inv2);
        }
    }

    /** §4.1 — `inventory` on a fresh session reports nothing carried. */
    @Test @Order(601)
    void inventory_empty_state_is_clean() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("inventory");
            Thread.sleep(1000);
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            // Either "carrying nothing", "empty", or no items listed — accept
            // any clear empty-state signal. Negative space: must NOT list
            // an entity or object name we didn't pick up.
            assertTrue(output.contains("nothing") || output.contains("empty")
                || output.contains("carrying"),
                "§4.1: 'inventory' empty state must produce a clear indicator. " +
                "Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §6 communication — say / tell
    // -----------------------------------------------------------------

    /** §6.1 — `say <text>` echoes back to the speaker's own session. */
    @Test @Order(701)
    void say_echoes_to_speaker() throws Exception {
        var marker = "marker-" + System.nanoTime();
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("say " + marker);
            Thread.sleep(1500);
            var output = String.join("\n", ssh.linesSince(mark));
            assertTrue(output.contains(marker),
                "§6.1: 'say <text>' must echo to speaker. Marker '" + marker
                + "' not found in:\n" + output);
        }
    }

    /** §6.3 — `tell <unknown> <text>` returns a 'not found' error,
     *  not silent acceptance. */
    @Test @Order(702)
    void tell_to_unknown_target_errors() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("tell zzzNotARealPersonzzz hi");
            Thread.sleep(1500);
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            // Accept several reasonable error shapes — the contract is "user
            // gets clear feedback the target isn't reachable", not exact text.
            assertTrue(output.contains("nobody") || output.contains("no such")
                || output.contains("not found") || output.contains("unknown")
                || output.contains("offline") || output.contains("can't find"),
                "§6.3: 'tell <unknown>' must produce a not-found error. Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §2.2 / §2.3 — examine / look-at / read equivalence
    // -----------------------------------------------------------------

    /**
     * §2.2 — `examine X` and `look at X` are semantic synonyms (both
     * passive observation). They must produce the same output shape:
     * description text, not an action confirmation, no room re-render.
     *
     * <p>If a future refactor splits {@code ParsedCommand.Examine} from
     * {@code ParsedCommand.Use}, this test still holds: both verbs should
     * route through the same description path.</p>
     */
    @Test @Order(751)
    void look_at_and_examine_produce_equivalent_output() throws Exception {
        try (var ssh = login()) {
            int markA = ssh.mark();
            ssh.sendLine("examine cost ledger");
            Thread.sleep(1500);
            var outputA = String.join("\n", ssh.linesSince(markA));
            int markB = ssh.mark();
            ssh.sendLine("look at cost ledger");
            Thread.sleep(1500);
            var outputB = String.join("\n", ssh.linesSince(markB));
            // Strip the echoed command prefix so we compare server output.
            // Both should contain matching description fragments. Conservative
            // contract: each output must mention the object name; neither
            // should look like an action confirmation.
            assertTrue(outputA.toLowerCase().contains("ledger"),
                "§2.2: 'examine' must reference the target. Got:\n" + outputA);
            assertTrue(outputB.toLowerCase().contains("ledger"),
                "§2.2: 'look at' must reference the target. Got:\n" + outputB);
            assertFalse(outputA.toLowerCase().contains("you use")
                || outputB.toLowerCase().contains("you use"),
                "§2.2: neither 'examine' nor 'look at' may produce 'you use' " +
                "confirmation. examine:\n" + outputA + "\n---\nlook at:\n" + outputB);
        }
    }

    // -----------------------------------------------------------------
    // §9 error contract — unknown verbs / unknown targets
    // -----------------------------------------------------------------

    /**
     * §9 — `go <bogus-direction>` must report failure, not silently no-op
     * or move the player. The contract is "you can't go that way" or
     * equivalent.
     */
    @Test @Order(791)
    void go_invalid_direction_reports_error() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("go bogusdirection");
            Thread.sleep(1500);
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            assertTrue(output.contains("can't go") || output.contains("cannot go")
                || output.contains("no exit") || output.contains("no way"),
                "§9: 'go <invalid>' must report failure. Got:\n" + output);
        }
    }

    /**
     * §9 — `take <unknown>` must produce a not-found error, never
     * silently add an imaginary item to inventory.
     */
    @Test @Order(792)
    void take_unknown_object_errors() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("take zzzGhostObject" + (System.nanoTime() % 10000));
            Thread.sleep(1500);
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            assertTrue(output.contains("no such") || output.contains("nothing called")
                || output.contains("can't") || output.contains("don't see"),
                "§9: 'take <unknown>' must report not-found. Got:\n" + output);
            // Negative space: inventory must remain empty after a failed take.
            int markInv = ssh.mark();
            ssh.sendLine("inventory");
            Thread.sleep(1000);
            var inv = String.join("\n", ssh.linesSince(markInv)).toLowerCase();
            assertFalse(inv.contains("ghostobject"),
                "§9: failed 'take' must NOT add to inventory. Got:\n" + inv);
        }
    }

    /**
     * §9 — `drop <unknown>` must report not-found / not-carried, never
     * silently create a phantom item in the room.
     */
    @Test @Order(793)
    void drop_unknown_object_errors() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("drop zzzGhostObject" + (System.nanoTime() % 10000));
            Thread.sleep(1500);
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            // Accept any clear signal: explicit error code, "have"/"carrying"
            // negative phrasing, or "not_in_inventory" structured tag. Real
            // response observed: "error [not_in_inventory]: you don''t have
            // that." — that double-apostrophe is a separate i18n
            // MessageFormat-escape bug, filed as known issue #8.
            assertTrue(output.contains("not_in_inventory")
                || output.contains("don") && output.contains("have")
                || output.contains("not carrying")
                || output.contains("no such")
                || output.contains("can't drop"),
                "§9: 'drop <unknown>' must report not-carried. Got:\n" + output);
        }
    }

    /**
     * §9 — `examine <unknown>` must produce a not-found error,
     * not the use-handler's fallback.
     */
    @Test @Order(794)
    void examine_unknown_object_errors_cleanly() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("examine zzzNothingHere" + (System.nanoTime() % 10000));
            Thread.sleep(1500);
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            assertTrue(output.contains("no such") || output.contains("nothing called")
                || output.contains("don't see") || output.contains("can't find"),
                "§9: 'examine <unknown>' must report not-found. Got:\n" + output);
            assertFalse(output.contains("you use"),
                "§9: failed examine must not fall through to 'you use'. Got:\n" + output);
        }
    }

    /**
     * §11.8 — MessageFormat single-quote escape (`''`) must un-escape on
     * output. The `not_in_inventory` error catalog entry is written as
     * `"you don''t have that"` (escape required because the codebase
     * convention treats every message as a MessageFormat pattern); the
     * runtime must resolve `''` to `'` before delivering to the user.
     *
     * <p>Regression-lock for the {@link
     * org.wyrdsekai.scripting.i18n.ScriptMessageCatalog#get(String)} fix:
     * single-arg get now runs through MessageFormat when the pattern
     * contains the escape sequence.</p>
     */
    @Test @Order(795)
    void drop_unknown_does_not_leak_messageformat_escape() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("drop zzzGhostObject" + (System.nanoTime() % 10000));
            Thread.sleep(1500);
            var output = String.join("\n", ssh.linesSince(mark));
            assertFalse(output.contains("don''t") || output.contains("can''t")
                || output.contains("isn''t") || output.contains("aren''t"),
                "§11.8: catalog MessageFormat escape `''` must un-resolve " +
                "to `'` before delivery. Got literal double-apostrophe in:\n"
                + output);
        }
    }

    /**
     * §9 — Unknown verb must NOT silently disappear; user needs a signal.
     * The contract is "either reject explicitly OR route to say with quotes
     * around the typed text", not nothing.
     */
    @Test @Order(801)
    void unknown_verb_does_not_silently_drop() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("wibblewobble nonsense input here");
            Thread.sleep(1500);
            var output = String.join("\n", ssh.linesSince(mark)).trim();
            // The reasonable shapes: explicit "unknown command", or routed
            // through say (so the literal text echoes back). Empty output is
            // the bug.
            assertFalse(output.isEmpty() || output.equals(">"),
                "§9: unknown verbs must not produce silent empty output. " +
                "Got (whitespace-trimmed):\n'" + output + "'");
        }
    }

    // -----------------------------------------------------------------
    // §8 help / actions
    // -----------------------------------------------------------------

    /** §8.1 — `help` produces a usage listing including major verbs. */
    @Test @Order(901)
    void help_lists_core_verbs() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("help");
            Thread.sleep(1500);
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            for (var verb : List.of("look", "go", "say", "tell", "take")) {
                assertTrue(output.contains(verb),
                    "§8.1: 'help' output must mention '" + verb + "'. Got:\n" + output);
            }
        }
    }

    // -----------------------------------------------------------------
    // §7.1 — `who` lists at least the caller as a self-line.
    // -----------------------------------------------------------------

    @Test @Order(701)
    void who_includes_self() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("who");
            Thread.sleep(2000);
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            // §7.1: self-line "You are <Name>." must appear, OR the caller's
            // username must be listed. Accept both shapes; reject silence.
            assertTrue(
                output.contains("you are")
                    || output.contains(USER.toLowerCase())
                    || output.contains("conf user"),
                "§7.1: 'who' must surface the caller's identity. Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §3.2 — `home` from another room teleports back to the caller's
    // Study. The bug we guard against: `home` only working when already
    // in the Study, or silently failing from foundation rooms.
    // -----------------------------------------------------------------

    @Test @Order(702)
    void home_from_nexus_returns_to_study() throws Exception {
        try (var ssh = login()) {
            // Study → Nexus
            Thread.sleep(1000);
            ssh.sendLine("out");
            ssh.waitForText("Nexus", TIMEOUT);
            Thread.sleep(1000);
            // Now from Nexus, `home` should land us back in our Study.
            int mark = ssh.mark();
            ssh.sendLine("home");
            // Give the navigation + room render a moment to settle.
            Thread.sleep(2000);
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            assertTrue(output.contains("study"),
                "§3.2: 'home' from Nexus must teleport back to caller's Study. " +
                "Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §6.1 — empty `say` is a no-op, not an error. The bug we guard:
    // some servers treat empty say as "Huh?" or a stack trace.
    // -----------------------------------------------------------------

    @Test @Order(703)
    void empty_say_is_not_an_error() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("say");
            Thread.sleep(1000);
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            assertFalse(output.contains("exception")
                || output.contains("error")
                || output.contains("stack"),
                "§6.1: empty 'say' must not surface an error. Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §6.4 — whisper to a nonexistent target should surface a clear
    // not-found, not silent acceptance.
    // -----------------------------------------------------------------

    @Test @Order(704)
    void whisper_to_unknown_target_errors_or_warns() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            var ghost = "zzzghost" + (System.nanoTime() % 10000);
            ssh.sendLine(">" + ghost + " hello");
            Thread.sleep(1500);
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            // Either a not-found message lands, or — acceptable v1 fallback —
            // the whisper silently produces no error AND no broadcast.
            // What's NOT acceptable: an exception trace.
            assertFalse(output.contains("exception") || output.contains("stack"),
                "§6.4: whisper to unknown target must not crash. Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §8.3 — `commands` lists known verbs. Distinct from `help` (which
    // includes synopses); `commands` is just verb names.
    // -----------------------------------------------------------------

    @Test @Order(705)
    void commands_lists_verbs_or_is_not_unknown() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("commands");
            Thread.sleep(1500);
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            // Accept v1 fallback: route to `help`. What's not OK: "Huh?"
            // or being treated as unknown.
            assertFalse(output.contains("huh?")
                || output.contains("unknown command"),
                "§8.3: 'commands' must list verbs or alias to help. Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §2.2 — `look at me` should resolve to self-examine, returning the
    // caller's name (and description if set). Pre-fix this parsed to
    // `Use(me, null)`; post-Examine-refactor it's a proper ParsedCommand
    // .Examine("me"), so the self-leg of ExamineLookup fires.
    // -----------------------------------------------------------------

    @Test @Order(801)
    void look_at_me_returns_self() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("look at me");
            Thread.sleep(2000);
            var output = String.join("\n", ssh.linesSince(mark));
            // Expect either the displayName or username, AND no "you use".
            var lower = output.toLowerCase();
            assertFalse(lower.contains("you use"),
                "§2.2: 'look at me' must not slip into use-handler. Got:\n" + output);
            assertTrue(lower.contains("conf user") || lower.contains(USER.toLowerCase())
                || lower.contains("no description set"),
                "§2.2: 'look at me' must surface the caller's name or " +
                "no-description hint. Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §6.4 — whisper to self is harmless. The bug we guard: server treats
    // it as a permission denial or echoes confusingly.
    // -----------------------------------------------------------------

    @Test @Order(802)
    void whisper_to_self_is_harmless() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            // ">name text" is the whisper shape SSH already accepts.
            ssh.sendLine(">" + USER + " test-to-self");
            Thread.sleep(1500);
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            assertFalse(output.contains("exception") || output.contains("stack"),
                "§6.4: whisper to self must not crash. Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §8.2 — selecting a hint number when no hint menu is active should
    // not crash. The bug we guard: bare "1" handled as a panic-trigger.
    // -----------------------------------------------------------------

    @Test @Order(803)
    void bare_digit_with_no_hint_menu_does_not_crash() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("9");
            Thread.sleep(1500);
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            assertFalse(output.contains("exception") || output.contains("stack"),
                "§8.2: bare digit with no active hints must not crash. " +
                "Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §1.2 — `quit` twice in the same session is a no-op (channel
    // already closed). We exercise it by sending quit then immediately
    // trying to send another command; the second should either land in
    // a closed channel (no output) or be swallowed gracefully — never
    // crash. This is a soak test against improperly-cleared state on the
    // server side.
    // -----------------------------------------------------------------

    // -----------------------------------------------------------------
    // §7.2 + §7.4 — set a description, then rename. The description must
    // survive the rename and still surface on `examine me`.
    //
    // Bug shape: rename's UpdateEntityName resets the live entity record
    // to a fresh state with no description, OR authService.updateDisplayName
    // touches the description column, OR examine reads from EntityRegistry
    // (which doesn't carry description) instead of authService.
    // -----------------------------------------------------------------

    /**
     * §7.2 — describe me then examine me in the SAME session, no reconnect.
     * The existing {@code describe_me_persists_and_is_visible_on_examine}
     * test always reconnects before checking. This probe targets the
     * suspected bug: that the description set by {@code describe me} only
     * surfaces after a reconnect, because the in-session examine reads from
     * a stale auth cache while the persisted DB row has the new text.
     */
    @Test @Order(800)
    void describe_then_examine_in_same_session_shows_description()
            throws Exception {
        var marker = "in-session marker " + System.nanoTime();
        try (var ssh = login()) {
            ssh.sendLine("describe me " + marker);
            Thread.sleep(1500);
            int mark = ssh.mark();
            ssh.sendLine("examine me");
            Thread.sleep(2000);
            var output = String.join("\n", ssh.linesSince(mark));
            assertTrue(output.contains(marker),
                "§7.2: 'describe me' set in this session must surface " +
                "immediately on 'examine me' (no reconnect required). " +
                "Marker '" + marker + "' not found in:\n" + output);
        }
    }

    @Test @Order(805)
    void describe_then_rename_preserves_description_on_self_examine()
            throws Exception {
        var marker = "wears a quiet expression " + System.nanoTime();
        var newName = "Renamed" + (System.nanoTime() % 100000);
        try (var ssh = login()) {
            ssh.sendLine("describe me " + marker);
            Thread.sleep(1000);
            ssh.sendLine("rename me " + newName);
            Thread.sleep(1500);
            int mark = ssh.mark();
            ssh.sendLine("examine me");
            Thread.sleep(2000);
            var output = String.join("\n", ssh.linesSince(mark));
            assertTrue(output.contains(marker),
                "§7.2+§7.4: description must survive rename and surface on " +
                "self-examine. Marker '" + marker + "' not found in:\n" + output);
            // Sanity: the new name should also be in the output (the
            // examine-self render leads with the name).
            assertTrue(output.contains(newName),
                "§7.2+§7.4: post-rename self-examine should also show the " +
                "new display name. Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §7.4 — rename boundary: 40-char name accepted, 41-char rejected.
    // The RenameService MAX_NAME_LENGTH constant is the contract here.
    // -----------------------------------------------------------------

    @Test @Order(810)
    void rename_at_40_chars_accepted() throws Exception {
        // 40-char name: "Aaaaa...Aaaaa" exactly 40 chars.
        var fortyChar = "Boundary40CharNamePerfectlyLegalABCDEFGH"; // 40 chars
        Assumptions.assumeTrue(fortyChar.length() == 40,
            "test setup: expected 40-char name");
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("rename me " + fortyChar);
            Thread.sleep(1500);
            var output = String.join("\n", ssh.linesSince(mark));
            assertTrue(output.contains("now known as"),
                "§7.4: 40-char name (at MAX_NAME_LENGTH boundary) must be " +
                "accepted. Got:\n" + output);
        }
    }

    @Test @Order(811)
    void rename_at_41_chars_rejected() throws Exception {
        var fortyOneChar = "Boundary41CharNameOverLimitByOneCharacterX"; // 42 — using >40
        // Ensure clearly over the limit.
        if (fortyOneChar.length() <= 40) fortyOneChar = fortyOneChar + "X";
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("rename me " + fortyOneChar);
            Thread.sleep(1500);
            var output = String.join("\n", ssh.linesSince(mark)).toLowerCase();
            // Should NOT echo "now known as"; should surface the length error.
            assertFalse(output.contains("now known as"),
                "§7.4: name over 40 chars must be rejected, not accepted. " +
                "Got:\n" + output);
            assertTrue(output.contains("visible characters")
                || output.contains("40") || output.contains("too long")
                || output.contains("invalid"),
                "§7.4: name-too-long rejection must surface a clear message. " +
                "Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §7.2 — long description (1KB) persists. Guards against truncation
    // or column-size issues.
    // -----------------------------------------------------------------

    @Test @Order(812)
    void long_description_persists_and_is_retrievable() throws Exception {
        // ~512-char description — well over typical text input but well under
        // any reasonable VARCHAR cap.
        var sb = new StringBuilder();
        sb.append("long-description-marker-").append(System.nanoTime()).append(" ");
        while (sb.length() < 500) {
            sb.append("padding word ");
        }
        var longDesc = sb.toString();
        try (var ssh = login()) {
            ssh.sendLine("describe me " + longDesc);
            Thread.sleep(1500);
            int mark = ssh.mark();
            ssh.sendLine("examine me");
            Thread.sleep(2000);
            var output = String.join("\n", ssh.linesSince(mark));
            assertTrue(output.contains("long-description-marker"),
                "§7.2: a ~500-char description must persist and surface on " +
                "examine. The leading marker was not found in the output.");
        }
    }

    // -----------------------------------------------------------------
    // §6.1 — say accepts text with special characters (quotes, apostrophes,
    // ampersands) without crashing or mangling. The bug shape: an SQL
    // parameter binding or an HTML/JSON escape miss.
    // -----------------------------------------------------------------

    @Test @Order(813)
    void say_with_special_chars_does_not_crash() throws Exception {
        var marker = "marker-" + System.nanoTime();
        var payload = "it's a test " + marker + " with \"quotes\" & ampersand";
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("say " + payload);
            Thread.sleep(1500);
            var output = String.join("\n", ssh.linesSince(mark));
            assertFalse(output.toLowerCase().contains("exception")
                || output.toLowerCase().contains("stack"),
                "§6.1: say with quotes/ampersands must not crash. Got:\n" + output);
            assertTrue(output.contains(marker),
                "§6.1: say with special chars must still echo the speaker's " +
                "marker. Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §6.1 — rapid-fire say (5 messages within a second) must not drop
    // any of them and must not crash. Guards against actor mailbox
    // overflow or session-level rate limiting silently swallowing input.
    // -----------------------------------------------------------------

    @Test @Order(814)
    void rapid_fire_say_preserves_all_messages() throws Exception {
        try (var ssh = login()) {
            int mark = ssh.mark();
            var markers = new String[5];
            for (int i = 0; i < 5; i++) {
                markers[i] = "rapid-" + System.nanoTime() + "-" + i;
                ssh.sendLine("say " + markers[i]);
            }
            Thread.sleep(3000); // allow all 5 to land
            var output = String.join("\n", ssh.linesSince(mark));
            for (var m : markers) {
                assertTrue(output.contains(m),
                    "§6.1: rapid-fire say must preserve every marker. '" +
                    m + "' missing from:\n" + output);
            }
        }
    }

    @Test @Order(806)
    void rename_then_describe_persists_for_new_session() throws Exception {
        var marker = "carries the scent of " + System.nanoTime();
        var newName = "FreshName" + (System.nanoTime() % 100000);
        try (var ssh = login()) {
            ssh.sendLine("rename me " + newName);
            Thread.sleep(1500);
            ssh.sendLine("describe me " + marker);
            Thread.sleep(1500);
        }
        // New session — verify description persisted across logout/login.
        try (var ssh = login()) {
            int mark = ssh.mark();
            ssh.sendLine("examine me");
            Thread.sleep(2000);
            var output = String.join("\n", ssh.linesSince(mark));
            assertTrue(output.contains(marker),
                "§7.2+§7.4: description set after rename must persist " +
                "across reconnect. Marker '" + marker + "' not found in:\n"
                + output);
        }
    }

    @Test @Order(804)
    void quit_then_another_command_does_not_crash_server() throws Exception {
        // Two separate sessions to make sure the first quit cleanly cleans
        // up before the second connects — guards against ghost session state.
        try (var ssh = login()) {
            ssh.sendLine("quit");
            Thread.sleep(2000);
        }
        try (var ssh2 = login()) {
            int mark = ssh2.mark();
            ssh2.sendLine("look");
            Thread.sleep(2000);
            var output = String.join("\n", ssh2.linesSince(mark)).toLowerCase();
            assertFalse(output.contains("exception") || output.contains("stack"),
                "§1.2: post-quit reconnect must produce a clean look. Got:\n" + output);
        }
    }

    // -----------------------------------------------------------------
    // §7.1 + §6.1 — multi-user: two users in the same room. Bootstraps
    // both users into the Nexus and exercises the cross-player surface.
    //
    // Each test opens TWO SSH sessions for the duration of the test. Both
    // sessions stay alive long enough for the broadcast layer to deliver
    // events. Order of close matters less than not orphaning either —
    // we use try-with-resources nested.
    // -----------------------------------------------------------------

    @Test @Order(901)
    void two_users_see_each_other_in_nexus_on_look() throws Exception {
        normalizeDisplayNames();
        try (var a = loginInNexus(USER, PASS)) {
            try (var b = loginInNexus(USER2, PASS2)) {
                Thread.sleep(1000); // let A observe B's EntityEntered
                int markA = a.mark();
                a.sendLine("look");
                Thread.sleep(2000);
                var outA = String.join("\n", a.linesSince(markA)).toLowerCase();
                // A's look should now list user 2 (by username or display name).
                assertTrue(outA.contains(USER2.toLowerCase())
                    || outA.contains("conf user two")
                    || outA.contains("two"),
                    "§7.1: A's 'look' in Nexus should list user-B who is " +
                    "co-present. Got:\n" + outA);
            }
        }
    }

    @Test @Order(902)
    void say_broadcasts_to_other_users_in_same_room() throws Exception {
        normalizeDisplayNames();
        var marker = "cross-user-say-" + System.nanoTime();
        try (var a = loginInNexus(USER, PASS)) {
            try (var b = loginInNexus(USER2, PASS2)) {
                Thread.sleep(800);
                int markB = b.mark();
                a.sendLine("say " + marker);
                Thread.sleep(2000);
                var outB = String.join("\n", b.linesSince(markB));
                assertTrue(outB.contains(marker),
                    "§6.1: B (in same room) must receive A's broadcast " +
                    "containing marker '" + marker + "'. Got:\n" + outB);
            }
        }
    }

    /**
     * §2.2 — third-party examine surfaces target's description across
     * room transitions. Pre-fix, walking to Nexus wiped the description
     * because handleGo's 4-arg EnterRoom didn't carry the field; that
     * fix is in WyrdShellCommand.handleGo. The in-suite flake was
     * cross-test displayName/description pollution, now resolved by
     * the REST {@code /api/auth/test-reset} hook called below before
     * each multi-user assertion.
     */
    @Test @Order(903)
    void third_party_examine_shows_target_description() throws Exception {
        testResetUser(USER, USER, "");
        testResetUser(USER2, USER2, "");
        normalizeDisplayNames();
        var marker = "third-party-marker-" + System.nanoTime();
        // A sets a description in their Study, then walks to Nexus.
        // B logs in, walks to Nexus, then examines A.
        try (var a = loginAs(USER, PASS)) {
            a.sendLine("describe me " + marker);
            Thread.sleep(1500);
            a.sendLine("out");
            a.waitForText("Nexus", TIMEOUT);
            Thread.sleep(800);
            try (var b = loginInNexus(USER2, PASS2)) {
                Thread.sleep(800);
                int markB = b.mark();
                // Examine A by username — the room snapshot's entities()
                // list carries display name OR username; try the username
                // first since it's known stable.
                b.sendLine("examine " + USER);
                Thread.sleep(2000);
                var outB = String.join("\n", b.linesSince(markB));
                assertTrue(outB.contains(marker),
                    "§2.2: B's examine of A in the same room must surface " +
                    "A's description. Marker '" + marker + "' not in:\n" + outB);
            }
        }
    }

    @Test @Order(904)
    void third_party_examine_after_rename_uses_new_name() throws Exception {
        var newName = "ThirdPartyRenamed" + (System.nanoTime() % 100000);
        try (var a = loginAs(USER, PASS)) {
            a.sendLine("rename me " + newName);
            Thread.sleep(1500);
            a.sendLine("out");
            a.waitForText("Nexus", TIMEOUT);
            Thread.sleep(800);
            try (var b = loginInNexus(USER2, PASS2)) {
                Thread.sleep(800);
                int markB = b.mark();
                b.sendLine("look");
                Thread.sleep(2000);
                var outB = String.join("\n", b.linesSince(markB));
                assertTrue(outB.contains(newName),
                    "§7.4: B's 'look' in same room as A must show A's new " +
                    "display name post-rename. '" + newName + "' missing from:\n"
                    + outB);
            }
        }
        // Reset A's name so the suite is idempotent.
        try (var a = loginAs(USER, PASS)) {
            a.sendLine("rename me Conf User");
            Thread.sleep(1500);
        }
    }

    /**
     * §6.3 — cross-room tell from one player to another lands in the
     * recipient's session via the room broadcast. Pre-fix CrossZoneTellService
     * was clobbering valid local lookups with a spurious "Nobody is online"
     * error; the SSH handler's tell handler took that branch and exited
     * before doing the SayInRoom delivery.
     */
    @Test @Order(905)
    void tell_delivered_across_users() throws Exception {
        normalizeDisplayNames();
        var marker = "tell-marker-" + System.nanoTime();
        try (var a = loginAs(USER, PASS)) {
            try (var b = loginAs(USER2, PASS2)) {
                Thread.sleep(800);
                int markB = b.mark();
                // tell <name> <text> — both users have separate Studies,
                // so this exercises the cross-room tell path.
                a.sendLine("tell " + USER2 + " " + marker);
                Thread.sleep(2500);
                var outB = String.join("\n", b.linesSince(markB));
                assertTrue(outB.contains(marker),
                    "§6.3: 'tell <user> <text>' must deliver to recipient " +
                    "even across rooms. Marker '" + marker + "' not in:\n"
                    + outB);
            }
        }
    }

    /**
     * §6.4 — same-room whisper delivers to the recipient. Pre-fix the
     * three transports (SSH/WS/Telnet) all passed the target *name* into
     * RoomCommand.WhisperInRoom whose contract takes an entity *id*, so
     * RoomActor's same-room check failed and whisper went silently.
     */
    @Test @Order(906)
    void whisper_in_same_room_targets_recipient() throws Exception {
        normalizeDisplayNames();
        var marker = "whisper-marker-" + System.nanoTime();
        try (var a = loginInNexus(USER, PASS)) {
            try (var b = loginInNexus(USER2, PASS2)) {
                Thread.sleep(800);
                int markB = b.mark();
                // whisper shape is ">name text"
                a.sendLine(">" + USER2 + " " + marker);
                Thread.sleep(2500);
                var outB = String.join("\n", b.linesSince(markB));
                assertTrue(outB.contains(marker),
                    "§6.4: whisper '>" + USER2 + " <text>' in same room " +
                    "must deliver to recipient. Marker '" + marker +
                    "' not in:\n" + outB);
            }
        }
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private static TestSshClient login() throws Exception {
        return loginAs(USER, PASS);
    }

    /** Login as an arbitrary user. Used for multi-user tests. */
    private static TestSshClient loginAs(String user, String pass) throws Exception {
        var ssh = TestSshClient.connectWithPassword(sshHost, sshPort, user, pass);
        ssh.waitForText("Study", TIMEOUT);
        Thread.sleep(500);
        return ssh;
    }

    /**
     * Login + walk to The Nexus. Used by multi-user tests that need two
     * sessions in the same shared room (Studies are per-player and don't
     * accept knock-less visitors).
     */
    private static TestSshClient loginInNexus(String user, String pass) throws Exception {
        var ssh = loginAs(user, pass);
        Thread.sleep(800);
        ssh.sendLine("out");
        ssh.waitForText("Nexus", TIMEOUT);
        Thread.sleep(800);
        return ssh;
    }

    /**
     * Send a raw byte without line-terminator processing. Used for control
     * chars (Ctrl-D = 4, Ctrl-C = 3, etc.) that the SSH input loop reads
     * before any line framing applies. Reaches through the public sendLine
     * path by reflection on the underlying OutputStream — keeps TestSshClient
     * itself transport-clean.
     */
    private static void sendRawByte(TestSshClient ssh, byte b) throws Exception {
        Field outField = TestSshClient.class.getDeclaredField("out");
        outField.setAccessible(true);
        var out = (OutputStream) outField.get(ssh);
        out.write(b);
        out.flush();
    }

    /**
     * Block up to {@code within} for the SSH channel to indicate the server
     * closed it. We poll the reader-thread's "still running" flag plus the
     * underlying channel state; in the embedded test fixture both are
     * driven by the in-process WyrdShellCommand returning from its input
     * loop, which cascades into channel close.
     */
    private static void assertChannelClosesWithin(TestSshClient ssh,
            Duration within, String message) throws Exception {
        var deadline = System.nanoTime() + within.toNanos();
        while (System.nanoTime() < deadline) {
            if (isChannelClosed(ssh)) return;
            Thread.sleep(100);
        }
        fail(message + " (timeout after " + within.toSeconds() + "s)");
    }

    private static boolean isChannelClosed(TestSshClient ssh) throws IOException {
        try {
            // The channel is held privately; we treat "any I/O on out throws"
            // as proof of closure for the embedded test. Writing a zero byte
            // is a low-impact probe that fails fast on closed channels.
            Field outField = TestSshClient.class.getDeclaredField("out");
            outField.setAccessible(true);
            var out = (OutputStream) outField.get(ssh);
            out.write(0);
            out.flush();
            return false;
        } catch (Exception e) {
            return true;
        }
    }
}
