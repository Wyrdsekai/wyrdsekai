package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.identity.AgentIdentityProvisioner;
import org.wyrdsekai.core.lifecycle.RecoverySeedService;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.soul.BondStore;
import org.wyrdsekai.core.soul.GenomeProfile;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SqlSoulStore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code wyrd seed} over HTTP: only the steward (a steward session, or this machine's
 * operator token) can make, read or restore a Recovery Seed; a member, a stranger and a
 * forged token are refused, and a seed made on one node restores on another.
 */
@Tag("integration")
class RecoverySeedRoutesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASS = "a long enough passphrase";

    @TempDir Path tmp;
    private final List<Javalin> apps = new ArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void tearDown() {
        apps.forEach(Javalin::stop);
        AgentIdentityProvisioner.reset();
        OperatorToken.setForTesting(null);
    }

    private record Node(String base, String jdbc, byte[] secret, SqlSoulStore souls, Path soulsDir,
                        String steward, String member) {
        void activate() {
            AgentIdentityProvisioner.reset();
            AgentIdentityProvisioner.init(jdbc, () -> secret);
        }
    }

    private Node node(String name) throws Exception {
        var dir = tmp.resolve(name);
        Files.createDirectories(dir);
        var jdbc = SchemaInitializer.initialize(dir.resolve("world.db"));
        var secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        var auth = new AuthService(jdbc);
        var steward = auth.register("keeper", "steward-pass-123", "Keeper").orElseThrow().token();
        var member = auth.register("guest", "member-pass-123", "Guest").orElseThrow().token();
        var bonds = new BondStore(jdbc);
        var souls = new SqlSoulStore(jdbc, SqlDialect.fromJdbcUrl(jdbc), null, bonds);
        var soulsDir = dir.resolve("souls");
        var service = new RecoverySeedService(souls, bonds, soulsDir, dir.resolve("recovery-seed"));
        var app = Javalin.create(cfg -> RecoverySeedRoutes.register(cfg, auth, service))
            .start("127.0.0.1", 0);
        apps.add(app);
        return new Node("http://127.0.0.1:" + app.port(), jdbc, secret, souls, soulsDir, steward, member);
    }

    private String bear(Node n, String name, String entityId) throws Exception {
        n.activate();
        var minted = AgentIdentityProvisioner.mint(entityId);
        var identity = AgentIdentityProvisioner.find(minted.did()).orElseThrow();
        var profile = new AgentProfile(name, entityId, "agent", "a companion",
            "You are " + name + ".", 8192, 1024, 0.7, null);
        n.souls().store(SoulManifest.birth(minted.did(), minted.publicKeyMultibase(),
            identity.keyLog(), profile, GenomeProfile.defaults()));
        Files.createDirectories(n.soulsDir());
        Files.writeString(n.soulsDir().resolve(entityId + ".did"), minted.did() + "\n");
        return minted.did();
    }

    private HttpResponse<String> post(Node n, String path, String token, Map<String, Object> body)
            throws Exception {
        var req = HttpRequest.newBuilder(URI.create(n.base() + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (token != null) req.header("Authorization", "Bearer " + token);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> r) throws Exception {
        return MAPPER.readTree(r.body());
    }

    @Test
    void only_the_steward_can_make_a_seed() throws Exception {
        var home = node("home");
        bear(home, "Mia", "mia");
        var ask = Map.<String, Object>of("companion", "Mia", "passphrase", PASS);

        var none = post(home, "/api/seed/generate", null, ask);
        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(none.body()).doesNotContain("\"file\"");

        var member = post(home, "/api/seed/generate", home.member(), ask);
        assertThat(member.statusCode()).isEqualTo(403);
        assertThat(member.body()).doesNotContain("\"file\"");

        var forged = post(home, "/api/seed/generate", "not-a-token", ask);
        assertThat(forged.statusCode()).isEqualTo(401);

        var steward = post(home, "/api/seed/generate", home.steward(), ask);
        assertThat(steward.statusCode()).as(steward.body()).isEqualTo(200);
        assertThat(json(steward).get("file").asText()).isNotBlank();
        assertThat(steward.headers().firstValue("Cache-Control")).contains("no-store");

        // The person on the machine itself, with the node's operator token.
        OperatorToken.setForTesting("operator-token-for-this-test");
        var operator = post(home, "/api/seed/generate", "operator-token-for-this-test", ask);
        assertThat(operator.statusCode()).as(operator.body()).isEqualTo(200);
    }

    @Test
    void a_seed_made_on_one_node_restores_on_another_and_only_for_its_steward() throws Exception {
        var home = node("home");
        var did = bear(home, "Mia", "mia");
        var made = json(post(home, "/api/seed/generate", home.steward(),
            Map.of("passphrase", PASS)));
        var file = made.get("file").asText();

        var fresh = node("fresh");
        fresh.activate();
        var body = Map.<String, Object>of("file", file, "passphrase", PASS);

        assertThat(post(fresh, "/api/seed/restore", null, body).statusCode()).isEqualTo(401);
        assertThat(post(fresh, "/api/seed/restore", fresh.member(), body).statusCode()).isEqualTo(403);
        assertThat(post(fresh, "/api/seed/verify", fresh.member(), body).statusCode()).isEqualTo(403);
        assertThat(fresh.souls().exists(did)).as("a refused restore changes nothing").isFalse();

        var wrong = post(fresh, "/api/seed/restore", fresh.steward(),
            Map.of("file", file, "passphrase", "not the passphrase at all"));
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(json(wrong).get("error").asText()).isEqualTo("wrong_passphrase");

        var verified = post(fresh, "/api/seed/verify", fresh.steward(), body);
        assertThat(verified.statusCode()).as(verified.body()).isEqualTo(200);
        assertThat(json(verified).get("name").asText()).isEqualTo("Mia");
        assertThat(json(verified).get("hereAlready").asBoolean()).isFalse();

        var restored = post(fresh, "/api/seed/restore", fresh.steward(), body);
        assertThat(restored.statusCode()).as(restored.body()).isEqualTo(200);
        assertThat(fresh.souls().exists(did)).isTrue();
        assertThat(AgentIdentityProvisioner.canSign(did)).isTrue();

        var again = post(fresh, "/api/seed/restore", fresh.steward(), body);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(json(again).get("error").asText()).isEqualTo("already_here");
    }

    @Test
    void a_seed_larger_than_javalins_default_body_cap_still_restores() throws Exception {
        var home = node("home");
        home.activate();
        var minted = AgentIdentityProvisioner.mint("mia");
        var identity = AgentIdentityProvisioner.find(minted.did()).orElseThrow();
        var noise = new byte[1_200_000];
        new SecureRandom().nextBytes(noise);
        // Random bytes do not compress, so the sealed seed stays over Javalin's 1 MB body cap.
        var profile = new AgentProfile("Mia", "mia", "agent", Base64.getEncoder().encodeToString(noise),
            "You are Mia.", 8192, 1024, 0.7, null);
        var big = SoulManifest.birth(minted.did(), minted.publicKeyMultibase(), identity.keyLog(),
            profile, GenomeProfile.defaults());
        home.souls().store(big);
        Files.createDirectories(home.soulsDir());
        Files.writeString(home.soulsDir().resolve("mia.did"), minted.did() + "\n");

        var made = post(home, "/api/seed/generate", home.steward(), Map.of("passphrase", PASS));
        assertThat(made.statusCode()).as(made.body()).isEqualTo(200);
        var file = json(made).get("file").asText();
        assertThat(file.length()).isGreaterThan(1_000_000);

        var fresh = node("fresh");
        fresh.activate();
        var restored = post(fresh, "/api/seed/restore", fresh.steward(),
            Map.of("file", file, "passphrase", PASS));
        assertThat(restored.statusCode()).as(restored.body()).isEqualTo(200);
        assertThat(fresh.souls().exists(minted.did())).isTrue();
    }

    @Test
    void a_short_passphrase_or_an_unknown_companion_is_refused_plainly() throws Exception {
        var home = node("home");
        bear(home, "Mia", "mia");
        var shortPass = post(home, "/api/seed/generate", home.steward(),
            Map.of("companion", "Mia", "passphrase", "short"));
        assertThat(shortPass.statusCode()).isEqualTo(400);
        assertThat(json(shortPass).get("error").asText()).isEqualTo("passphrase_too_short");

        var nobody = post(home, "/api/seed/generate", home.steward(),
            Map.of("companion", "Nobody", "passphrase", PASS));
        assertThat(nobody.statusCode()).isEqualTo(404);
        assertThat(json(nobody).get("names").toString()).contains("Mia");
    }
}
