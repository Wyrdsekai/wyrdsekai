package org.wyrdsekai.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.core.identity.DidKey;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * (P4) — {@link RelayAdminClient} correctness.
 *
 * <p>The load-bearing test is {@link #canonical_args_match_python_p3_byte_for_byte}:
 * a mismatch silently breaks every in-world admin call, because the Java-signed
 * challenge would hash differently from what the Python relay reconstructs. The
 * expected vectors are generated from P3's {@code _canonical_args} in
 * {@code deploy/relay/registration.py}:
 * <pre>
 *   json.dumps(args, separators=(",",":"), sort_keys=True, ensure_ascii=True, default=str)
 * </pre>
 * and hardcoded here (so the JVM test needs no Python). Regenerate with:
 * <pre>
 *   python3 -c 'import json;print(json.dumps(ARGS,separators=(",",":"),sort_keys=True,ensure_ascii=True))'
 * </pre>
 */
class RelayAdminClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- The critical cross-language vector --------------------------------

    @Test
    void canonical_args_match_python_p3_byte_for_byte() {
        // {"subject_did":"did:key:zABC","scope":"moderation"}  (keys sorted)
        var grantArgs = new LinkedHashMap<String, Object>();
        grantArgs.put("subject_did", "did:key:zABC");   // inserted out of sorted order on purpose
        grantArgs.put("scope", "moderation");
        assertThat(RelayAdminClient.canonicalArgs(grantArgs))
            .isEqualTo("{\"scope\":\"moderation\",\"subject_did\":\"did:key:zABC\"}");

        // Mixed keys: sort_keys reorders a,b,pubkey.
        var mixed = new LinkedHashMap<String, Object>();
        mixed.put("pubkey", "UABC123");
        mixed.put("b", 2);
        mixed.put("a", 1);
        assertThat(RelayAdminClient.canonicalArgs(mixed))
            .isEqualTo("{\"a\":1,\"b\":2,\"pubkey\":\"UABC123\"}");

        // ttl invite arg.
        assertThat(RelayAdminClient.canonicalArgs(Map.of("ttl", 3600)))
            .isEqualTo("{\"ttl\":3600}");

        // null args -> literal "null" (matches Python json.dumps(None)).
        assertThat(RelayAdminClient.canonicalArgs(null)).isEqualTo("null");

        // Non-ASCII MUST be \\uXXXX-escaped (ensure_ascii=True parity); nested
        // array order preserved; keys sorted.
        var unicode = new LinkedHashMap<String, Object>();
        unicode.put("z", 1);
        unicode.put("a", List.of(3, 2, 1));
        unicode.put("note", "café — naïve ☃");
        assertThat(RelayAdminClient.canonicalArgs(unicode))
            .isEqualTo("{\"a\":[3,2,1],\"note\":\"caf\\u00e9 \\u2014 na\\u00efve \\u2603\",\"z\":1}");
    }

    @Test
    void canonical_args_sha256_matches_python() {
        // sha256(canonical_args) hex from the Python relay.
        var grantArgs = Map.of("subject_did", "did:key:zABC", "scope", "moderation");
        assertThat(sha256Hex(RelayAdminClient.canonicalArgs(grantArgs)))
            .isEqualTo("10819ba6fad626b78747c61a8fc8fd679e851f17371a1d42c35b70ebbaffbf13");

        assertThat(sha256Hex(RelayAdminClient.canonicalArgs(null)))
            .isEqualTo("74234e98afe7498fb5daf1f36ac2d78acc339464f950703b8c019892f982b90b");
    }

    @Test
    void canonical_challenge_matches_python_string() {
        var args = Map.of("subject_did", "did:key:zABC", "scope", "moderation");
        var challenge = RelayAdminClient.canonicalChallenge(
            "grant-admin", args, 1700000000L, "did:key:zRELAY");
        assertThat(challenge).isEqualTo(
            "admin:grant-admin:1700000000:did:key:zRELAY:"
            + "10819ba6fad626b78747c61a8fc8fd679e851f17371a1d42c35b70ebbaffbf13");
    }

    // --- Signature verifies against the DID's key (relay-side path) --------

    @Test
    void signed_challenge_verifies_against_the_did(@TempDir Path tmp) throws Exception {
        var identity = NodeIdentity.loadOrGenerate(tmp.resolve("id.json"));
        long ts = 1700000000L;
        var args = Map.of("subject_did", "did:key:zABC", "scope", "moderation");
        var relayDid = "did:key:zRELAY";
        var challenge = RelayAdminClient.canonicalChallenge("grant-admin", args, ts, relayDid)
            .getBytes(StandardCharsets.UTF_8);
        // Production signs with the NKey handler (the relay verifies against the
        // NKey-derived DID, not the Between-protocol identity.did()).
        var signature = identity.nkeyAuthHandler().sign(challenge);

        // Verify exactly as the Python relay does: recover the Ed25519 pubkey
        // from the NKey-derived DID, then check the raw signature over the bytes.
        var pub = DidKey.publicKeyFromDid(identity.nkeyDid()).orElseThrow();
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(pub);
        verifier.update(challenge);
        assertThat(verifier.verify(signature)).isTrue();

        // A tampered challenge must NOT verify.
        var bad = "admin:grant-admin:1700000001:did:key:zRELAY:deadbeef"
            .getBytes(StandardCharsets.UTF_8);
        var v2 = Signature.getInstance("Ed25519");
        v2.initVerify(pub);
        v2.update(bad);
        assertThat(v2.verify(signature)).isFalse();
    }

    // --- Happy path + denied path against a stubbed relay endpoint ---------

    @Test
    void happy_path_list_posts_signed_body_and_parses(@TempDir Path tmp) throws Exception {
        var identity = NodeIdentity.loadOrGenerate(tmp.resolve("id.json"));
        var relayDid = "did:key:zRELAY";
        var captured = new AtomicReference<Map<String, Object>>();

        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin", exchange -> {
            var bodyBytes = exchange.getRequestBody().readAllBytes();
            @SuppressWarnings("unchecked")
            Map<String, Object> body = MAPPER.readValue(bodyBytes, Map.class);
            captured.set(body);
            var resp = "{\"registrations\":[{\"did\":\"did:key:zABC\",\"active\":true}],\"_status\":200}";
            var out = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/admin";
            var client = new RelayAdminClient(identity, url, relayDid, "none");
            var res = client.list();

            assertThat(res.ok()).isTrue();
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.listField("registrations")).hasSize(1);

            // The signed body carries the spec-required fields with the right DID.
            var body = captured.get();
            assertThat(body.get("op")).isEqualTo("list");
            assertThat(body.get("relay_did")).isEqualTo(relayDid);
            assertThat(body.get("did")).isEqualTo(identity.nkeyDid());
            assertThat(body.get("signature_b64")).isInstanceOf(String.class);

            // And the signature in the body verifies over the canonical challenge
            // the relay would reconstruct from (op, args=null, ts, relay_did).
            long ts = ((Number) body.get("ts")).longValue();
            var challenge = RelayAdminClient.canonicalChallenge("list", null, ts, relayDid)
                .getBytes(StandardCharsets.UTF_8);
            var sig = Base64.getDecoder().decode((String) body.get("signature_b64"));
            var pub = DidKey.publicKeyFromDid(identity.nkeyDid()).orElseThrow();
            var v = Signature.getInstance("Ed25519");
            v.initVerify(pub);
            v.update(challenge);
            assertThat(v.verify(sig)).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void denied_path_surfaces_relay_403(@TempDir Path tmp) throws Exception {
        var identity = NodeIdentity.loadOrGenerate(tmp.resolve("id.json"));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin", exchange -> {
            var resp = "{\"error\":\"no relay-admin grant for this DID\",\"_status\":403}";
            var out = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/admin";
            var client = new RelayAdminClient(identity, url, "did:key:zRELAY", "none");
            var res = client.grantAdmin("did:key:zSUBJECT", "moderation");
            assertThat(res.ok()).isFalse();
            assertThat(res.status()).isEqualTo(403);
            assertThat(res.error()).contains("no relay-admin grant");
        } finally {
            server.stop(0);
        }
    }

    // --- P6 reports queue --------------------

    @Test
    void report_ops_canonical_args_match_python() {
        // resolve-report: keys sorted (action,report_id).
        assertThat(RelayAdminClient.canonicalArgs(
                Map.of("report_id", "rpt-abc123", "action", "noted")))
            .isEqualTo("{\"action\":\"noted\",\"report_id\":\"rpt-abc123\"}");
        assertThat(sha256Hex(RelayAdminClient.canonicalArgs(
                Map.of("report_id", "rpt-abc123", "action", "noted"))))
            .isEqualTo("08c10d6016c0c9b589b1970c413818e734ef75d15c76643bd403e21875f77050");
        // report-queue include_resolved=true → lowercase JSON true.
        assertThat(RelayAdminClient.canonicalArgs(Map.of("include_resolved", true)))
            .isEqualTo("{\"include_resolved\":true}");
        // file report.
        assertThat(RelayAdminClient.canonicalArgs(
                Map.of("subject_did", "did:key:zSUB", "reason", "spam")))
            .isEqualTo("{\"reason\":\"spam\",\"subject_did\":\"did:key:zSUB\"}");
    }

    @Test
    void report_queue_and_resolve_sign_and_post(@TempDir Path tmp) throws Exception {
        var identity = NodeIdentity.loadOrGenerate(tmp.resolve("id.json"));
        var relayDid = "did:key:zRELAY";
        var captured = new AtomicReference<Map<String, Object>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin", exchange -> {
            var bodyBytes = exchange.getRequestBody().readAllBytes();
            @SuppressWarnings("unchecked")
            Map<String, Object> body = MAPPER.readValue(bodyBytes, Map.class);
            captured.set(body);
            var resp = "{\"reports\":[{\"id\":\"rpt-1\"}],\"open_count\":1,\"_status\":200}";
            var out = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/admin";
            var client = new RelayAdminClient(identity, url, relayDid, "none");

            // report-queue (open only → null args).
            var rq = client.reportQueue(false);
            assertThat(rq.ok()).isTrue();
            assertThat(rq.listField("reports")).hasSize(1);
            var qbody = captured.get();
            assertThat(qbody.get("op")).isEqualTo("report-queue");
            long qts = ((Number) qbody.get("ts")).longValue();
            assertSignedOver(client, identity, "report-queue", null, qts, relayDid,
                (String) qbody.get("signature_b64"));

            // resolve-report carries report_id + action.
            var rr = client.resolveReport("rpt-1", "noted");
            assertThat(rr.ok()).isTrue();
            var rbody = captured.get();
            assertThat(rbody.get("op")).isEqualTo("resolve-report");
            @SuppressWarnings("unchecked")
            var rargs = (Map<String, Object>) rbody.get("args");
            assertThat(rargs).containsEntry("report_id", "rpt-1").containsEntry("action", "noted");
            long rts = ((Number) rbody.get("ts")).longValue();
            assertSignedOver(client, identity, "resolve-report", rargs, rts, relayDid,
                (String) rbody.get("signature_b64"));

            // file report — open to any signer relay-side; client just signs as itself.
            var fr = client.fileReport("did:key:zSUB", "spam");
            assertThat(fr.ok()).isTrue();
            assertThat(captured.get().get("op")).isEqualTo("report");
        } finally {
            server.stop(0);
        }
    }

    /** Verify the posted signature over the reconstructed canonical challenge. */
    private static void assertSignedOver(RelayAdminClient client, NodeIdentity identity,
            String op, Object args, long ts, String relayDid, String sigB64) throws Exception {
        var challenge = RelayAdminClient.canonicalChallenge(op, args, ts, relayDid)
            .getBytes(StandardCharsets.UTF_8);
        var sig = Base64.getDecoder().decode(sigB64);
        var pub = DidKey.publicKeyFromDid(identity.nkeyDid()).orElseThrow();
        var v = Signature.getInstance("Ed25519");
        v.initVerify(pub);
        v.update(challenge);
        assertThat(v.verify(sig)).as("signature must verify over " + op).isTrue();
    }

    @Test
    void no_url_yields_soft_failure(@TempDir Path tmp) throws Exception {
        var identity = NodeIdentity.loadOrGenerate(tmp.resolve("id.json"));
        var client = new RelayAdminClient(identity, null, "did:key:zRELAY", null);
        var res = client.list();
        assertThat(res.ok()).isFalse();
        assertThat(res.status()).isEqualTo(0);
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
