package org.wyrdsekai.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.nats.client.NKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.between.NodeIdentity;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node side of zone binding on the relay (security review 2026-09-28): the proofs it sends, which
 * relay leg's credentials it uses, and the TLS pins it keeps from a register reply. The relay side of
 * the same proofs is tested in deploy/relay/test_registration.py.
 */
final class RelayZoneBindingClientTest {

    @Test
    void tokenMacMatchesTheRelaysHmac() throws Exception {
        // Vector from Python: base64(hmac.new(token, challenge, sha256).digest()) — registration.py's check.
        assertEquals("GAaJcchclm6CDcgjg/PY3NxdFRhIRcHcaf2QI05w1po=",
            RelayNkeyAdminMain.tokenMac("tok-abcdefgh-123", "deregister:1700000000:hh-0123456789ab"));
    }

    @Test
    void aLegsCredentialsAreItsOwnNeverThePrimarys(@TempDir Path dir) throws Exception {
        var conf = dir.resolve("env");
        Files.writeString(conf, """
            WYRDSEKAI_RELAY_URL=nats://a.example:4222
            WYRDSEKAI_RELAY_REGISTRATION_URL=https://a.example:4443
            WYRDSEKAI_RELAY_USER=hh-aaaaaaaaaaaa
            WYRDSEKAI_RELAY_TOKEN=tok-a
            WYRDSEKAI_RELAY_URL_2=nats://b.example:4222
            WYRDSEKAI_RELAY_REGISTRATION_URL_2=https://b.example:4443/
            WYRDSEKAI_RELAY_FINGERPRINT_2=AB:CD
            """);
        assertArrayEquals(new String[]{"hh-aaaaaaaaaaaa", "tok-a"},
            RelayNkeyAdminMain.legCredentials(conf, "https://a.example:4443"));
        assertArrayEquals(new String[]{null, null}, RelayNkeyAdminMain.legCredentials(conf, "https://B.example:4443"),
            "an NKey leg has no password; the primary's must not be used for it");
        assertEquals("AB:CD", RelayNkeyAdminMain.legSetting(conf, "https://b.example:4443", "FINGERPRINT"));
        assertNull(RelayNkeyAdminMain.legSetting(conf, "https://c.example:4443", "USER"));
    }

    @Test
    void theRegisterReplysTlsIdentityIsPinned(@TempDir Path dir) throws Exception {
        var out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        var ca = "a".repeat(64);
        var leaf = "b".repeat(64);
        RelayNkeyAdminMain.recordRelayPins(dir, "relay.example.org:4222",
            "{\"ca_fp\":\"" + ca + "\",\"nats_tls_fp\":\"" + leaf + "\"}", out);
        var pins = Files.readString(dir.resolve("relay-tls-pins"));
        assertTrue(pins.contains("relay.example.org:4222 " + ca), pins);
        assertTrue(pins.contains("relay.example.org:4222 " + leaf), pins);
    }

    @Test
    void bindZoneSignsForEachLegWithItsOwnProof(@TempDir Path dir) throws Exception {
        var identity = NodeIdentity.loadOrGenerate(dir.resolve("node-identity.json"));
        var bodies = new ArrayList<Map<?, ?>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bind-zone", ex -> {
            bodies.add(new ObjectMapper().readValue(ex.getRequestBody(), Map.class));
            var reply = "{\"status\":\"bound\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, reply.length);
            ex.getResponseBody().write(reply);
            ex.close();
        });
        server.start();
        try {
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            Map<String, String> settings = Map.of(
                "WYRDSEKAI_RELAY_REGISTRATION_URL", base,
                "WYRDSEKAI_RELAY_FINGERPRINT", "none",
                "WYRDSEKAI_RELAY_REGISTRATION_URL_2", base + "/",
                "WYRDSEKAI_RELAY_FINGERPRINT_2", "none",
                "WYRDSEKAI_RELAY_USER_2", "hh-0123456789ab",
                "WYRDSEKAI_RELAY_TOKEN_2", "tok-abcdefgh-123");
            List<RelayNkeyAdminMain.BindResult> results =
                RelayNkeyAdminMain.bindZones(identity, "alpha", settings::get, u -> true);
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(RelayNkeyAdminMain.BindResult::ok), results.toString());

            var nkey = bodies.get(0);
            var pub = identity.nkeyPublicKey();
            assertEquals(pub, nkey.get("pubkey"));
            assertEquals("alpha", nkey.get("zone_id"));
            var challenge = "bind-zone:" + nkey.get("ts") + ":" + pub + ":alpha";
            assertTrue(NKey.fromPublicKey(pub.toCharArray()).verify(challenge.getBytes(StandardCharsets.UTF_8),
                Base64.getDecoder().decode((String) nkey.get("signature"))), "NKey signature over the challenge");

            var password = bodies.get(1);
            assertEquals("hh-0123456789ab", password.get("household_id"));
            assertEquals(RelayNkeyAdminMain.tokenMac("tok-abcdefgh-123",
                "bind-zone:" + password.get("ts") + ":hh-0123456789ab:alpha"), password.get("mac"));
            assertTrue(!password.containsKey("token"), "the relay password never crosses the wire");
        } finally {
            server.stop(0);
        }
    }
}
