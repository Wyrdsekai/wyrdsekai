package org.wyrdsekai.server;

import org.wyrdsekai.between.NodeIdentity;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * (P4) — the zone→relay signed admin caller.
 *
 * <p>A reusable service (NOT a CLI main) that signs and POSTs admin operations
 * to a relay's signed {@code /admin} endpoint (P3). The acting principal is the
 * zone itself: the node's {@link NodeIdentity} Ed25519 key signs each call, and
 * the relay authorizes the zone's {@code did:key:} against its {@code owner_did}
 * or its local relay-admin grant store.</p>
 *
 * <h2>The P3 canonical signing string</h2>
 * Each call signs the bytes of:
 * <pre>admin:{op}:{ts}:{relay_did}:{sha256_hex(canonical_args)}</pre>
 * where {@code canonical_args} is the op's {@code args} serialized as
 * <strong>compact JSON with sorted keys</strong>, byte-for-byte identical to
 * the Python relay's {@code _canonical_args}:
 * <pre>json.dumps(args, separators=(",", ":"), sort_keys=True, ensure_ascii=True)</pre>
 * To match Python's {@code ensure_ascii=True}, this client enables
 * {@link JsonWriteFeature#ESCAPE_NON_ASCII} (Jackson does not escape non-ASCII
 * by default) in addition to compact output and
 * {@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}. A {@code null}/missing
 * args canonicalizes to the literal {@code "null"} on both sides. See
 * {@link #canonicalArgs(Object)} and {@code RelayAdminClientTest} for the
 * cross-language vector that pins this.
 *
 * <p>The POST body carries {@code {op, args, relay_did, ts, did, signature_b64}}
 * and is sent over a fingerprint-pinned TLS context (the household-CA leaf isn't
 * in the JVM trust store) unless the relay uses a publicly-valid (ACME) cert, in
 * which case system trust is used. Same trust model as
 * {@link RelayNkeyAdminMain}'s {@code deregister}/{@code claim} POSTs.</p>
 */
public final class RelayAdminClient {

    /** Shared, statically-configured canonical-args mapper (sorted keys, compact, ASCII-escaped). */
    private static final ObjectMapper CANON = new ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        .configure(JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature(), true);

    private final NodeIdentity identity;
    private final String adminUrl;       // e.g. https://relay.example.org/admin  (or :port/admin)
    private final String relayDid;       // the relay's stable did:key: identifier
    private final String fingerprint;    // pinned leaf fingerprint, or null/"none" for system trust
    private final ObjectMapper bodyMapper = new ObjectMapper();

    /**
     * @param identity    the signing node identity (the acting zone's key)
     * @param adminUrl    the relay's admin endpoint, e.g.
     *                    {@code https://relay.host[:port]/admin}
     * @param relayDid    the relay's {@code did:key:} (bound into the signed challenge)
     * @param fingerprint pinned leaf-cert SHA-256 fingerprint (colon-hex), or
     *                    {@code null}/{@code "none"} to use system trust (ACME relays)
     */
    public RelayAdminClient(NodeIdentity identity, String adminUrl, String relayDid, String fingerprint) {
        this.identity = identity;
        this.adminUrl = normalizeAdminUrl(adminUrl);
        this.relayDid = relayDid;
        this.fingerprint = fingerprint;
    }

    private static String normalizeAdminUrl(String url) {
        if (url == null) return null;
        var u = url.replaceAll("/+$", "");
        return u.endsWith("/admin") ? u : u + "/admin";
    }

    /**
     * This client's acting DID — the relay authorizes against this. It is the
     * <strong>NKey-derived</strong> DID ({@link NodeIdentity#nkeyDid()}), since
     * the relay verifies signatures (made with {@link NodeIdentity#nkeyAuthHandler()})
     * against the NKey, and stamps that DID on the registration ({@code nkey_to_did}).
     * NOT {@code identity.did()} (the Between-protocol key), which is a different
     * keypair the relay never sees.
     */
    public String actingDid() { return identity.nkeyDid(); }

    /** The relay this client administers. */
    public String relayDid() { return relayDid; }

    // ─── Canonicalization (the byte-for-byte critical path) ──────────────

    /**
     * Build the P3 canonical-args string: compact JSON, sorted keys, ASCII-escaped.
     * {@code null} → the literal {@code "null"}. Matches the Python relay's
     * {@code _canonical_args} byte-for-byte.
     */
    public static String canonicalArgs(Object args) {
        try {
            return lowercaseUnicodeEscapes(CANON.writeValueAsString(args));
        } catch (Exception e) {
            throw new IllegalStateException("canonical-args serialization failed", e);
        }
    }

    /**
     * Jackson's {@code ESCAPE_NON_ASCII} emits UPPERCASE hex ({@code \\u00E9});
     * Python's {@code json.dumps(ensure_ascii=True)} emits lowercase
     * ({@code \\u00e9}). Lowercase the 4 hex digits of every {@code \\uXXXX}
     * escape so the bytes match P3 exactly. Only the escape's hex is touched —
     * literal content is untouched (a real backslash-u in a string value is
     * itself emitted as {@code \\u005c}u… so this is safe).
     */
    private static String lowercaseUnicodeEscapes(String s) {
        var m = UNICODE_ESCAPE.matcher(s);
        var sb = new StringBuilder(s.length());
        while (m.find()) {
            m.appendReplacement(sb, "\\\\u" + m.group(1).toLowerCase());
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static final Pattern UNICODE_ESCAPE =
        Pattern.compile("\\\\u([0-9A-Fa-f]{4})");

    /**
     * Build the P3 canonical signing string for an op + args at timestamp {@code ts}
     * against {@code relayDid}: {@code admin:{op}:{ts}:{relay_did}:{sha256_hex(canonical_args)}}.
     * Exposed (static, no I/O) so tests can pin it against a Python-generated vector.
     */
    public static String canonicalChallenge(String op, Object args, long ts, String relayDid) {
        var canon = canonicalArgs(args);
        var hash = sha256Hex(canon);
        return "admin:" + op + ":" + ts + ":" + relayDid + ":" + hash;
    }

    private static String sha256Hex(String s) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ─── Op methods ──────────────────────────────────────────────────────

    /** Mint an invite (optional {@code ttl} seconds). Requires invite-only+ scope relay-side. */
    public AdminResult invite(Integer ttlSeconds) {
        var args = new LinkedHashMap<String, Object>();
        if (ttlSeconds != null) args.put("ttl", ttlSeconds);
        return call("invite", args.isEmpty() ? null : args);
    }

    /** List the relay's registrations. Requires moderation+ scope relay-side. */
    public AdminResult list() {
        return call("list", null);
    }

    /** Remove (kick) a registration by its NATS pubkey. Requires moderation+ scope. */
    public AdminResult remove(String pubkey) {
        return call("remove", Map.of("pubkey", pubkey));
    }

    /**
     * Grant relay-admin to {@code subjectDid} at {@code scope}
     * (invite-only|moderation|full). Requires full scope relay-side (or owner).
     * This is the push half of §5's grant-sync: the Java Grant remains the
     * zone-side authority; this mutates the relay's local enforcement store.
     */
    public AdminResult grantAdmin(String subjectDid, String scope) {
        return call("grant-admin", Map.of("subject_did", subjectDid, "scope", scope));
    }

    /** Revoke relay-admin from {@code subjectDid}. Requires full scope (or owner). */
    public AdminResult revokeAdmin(String subjectDid) {
        return call("revoke-admin", Map.of("subject_did", subjectDid));
    }

    // ─── P5 stubs (present, not used by the P4 furnishing) ───────────────
    // The relay sidecar already accepts these ops but the backing machinery
    // (trust tiers, reports queue, mode/policy) lands in P5/P6. Kept here so
    // callers can discover them; the furnishing surfaces them as placeholders.

    /** TODO(P5): set the relay's registration mode (invite-only|open|commons). */
    public AdminResult setMode(String mode) {
        return call("set-mode", Map.of("mode", mode));
    }

    /** TODO(P5): set tier/quota policy. */
    public AdminResult setPolicy(Map<String, Object> policy) {
        return call("set-policy", policy);
    }

    /** TODO(P5): promote a DID's trust tier. */
    public AdminResult promote(String subjectDid) {
        return call("promote", Map.of("subject_did", subjectDid));
    }

    /** TODO(P5): demote a DID's trust tier. */
    public AdminResult demote(String subjectDid) {
        return call("demote", Map.of("subject_did", subjectDid));
    }

    // ─── P6 reports queue ──────────────────

    /**
     * File an abuse report against {@code subjectDid} with a free-text
     * {@code reason}. Relay-side this is open to <em>any</em> valid signer (§8) —
     * the zone signs as itself, no relay-admin grant required (the relay's
     * {@code _OPEN_TO_ANY_SIGNER} exemption). The subject need not be registered.
     */
    public AdminResult fileReport(String subjectDid, String reason) {
        var args = new LinkedHashMap<String, Object>();
        if (subjectDid != null) args.put("subject_did", subjectDid);
        if (reason != null) args.put("reason", reason);
        return call("report", args);
    }

    /**
     * Fetch the reports queue (moderator-only relay-side: needs moderation+
     * scope or owner). Open reports only unless {@code includeResolved}.
     */
    public AdminResult reportQueue(boolean includeResolved) {
        return call("report-queue",
            includeResolved ? Map.of("include_resolved", true) : null);
    }

    /**
     * Resolve a report (moderator-only). {@code action} ∈
     * {@code dismiss}|{@code noted}|{@code removed}. {@code removed} is advisory
     * — the actual kick is the separate {@link #remove(String)} op; this only
     * records the verdict + stamps the resolver.
     */
    public AdminResult resolveReport(String reportId, String action) {
        var args = new LinkedHashMap<String, Object>();
        if (reportId != null) args.put("report_id", reportId);
        if (action != null) args.put("action", action);
        return call("resolve-report", args);
    }

    // ─── Generic signed call ─────────────────────────────────────────────

    /**
     * Sign and POST an arbitrary admin op. Visible for tests and for the
     * furnishing's generic dispatch. {@code args} may be null.
     */
    public AdminResult call(String op, Object args) {
        if (adminUrl == null) {
            return new AdminResult(0, false, Map.of("error", "no relay admin URL configured"), null);
        }
        try {
            long ts = Instant.now().getEpochSecond();
            var challenge = canonicalChallenge(op, args, ts, relayDid).getBytes(StandardCharsets.UTF_8);
            byte[] signature = identity.nkeyAuthHandler().sign(challenge);
            var sigB64 = Base64.getEncoder().encodeToString(signature);

            var body = new LinkedHashMap<String, Object>();
            body.put("op", op);
            body.put("args", args);
            body.put("relay_did", relayDid);
            body.put("ts", ts);
            body.put("did", identity.nkeyDid());
            body.put("signature_b64", sigB64);
            var json = bodyMapper.writeValueAsBytes(body);

            var resp = post(adminUrl, json);
            Map<String, Object> parsed;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = bodyMapper.readValue(resp.body(), Map.class);
                parsed = m;
            } catch (Exception parseErr) {
                parsed = Map.of("raw", resp.body());
            }
            boolean ok = resp.statusCode() == 200;
            return new AdminResult(resp.statusCode(), ok, parsed, resp.body());
        } catch (Exception e) {
            return new AdminResult(-1, false, Map.of("error", String.valueOf(e.getMessage())), null);
        }
    }

    private HttpResponse<String> post(String url, byte[] body) throws Exception {
        if (fingerprint == null || fingerprint.isBlank() || fingerprint.equalsIgnoreCase("none")) {
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            var req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        }
        return pinnedPost(url, body, fingerprint);
    }

    /** Fingerprint-pinned POST — mirrors {@link RelayNkeyAdminMain#pinnedHttpsPost}. */
    private static HttpResponse<String> pinnedPost(String url, byte[] body,
                                                   String expectedFingerprint) throws Exception {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        var sslParams = new SSLParameters();
        sslParams.setEndpointIdentificationAlgorithm("");
        var http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .sslContext(buildPinnedSslContext(expectedFingerprint))
            .sslParameters(sslParams)
            .build();
        var req = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static SSLContext buildPinnedSslContext(String expectedFingerprint) throws Exception {
        var expectedHex = expectedFingerprint.replace(":", "").replace(" ", "").toLowerCase();
        var pinner = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {
                throw new UnsupportedOperationException("Client auth not used");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("Empty cert chain");
                }
                try {
                    var sha = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    var actualHex = HexFormat.of().formatHex(sha);
                    if (!actualHex.equalsIgnoreCase(expectedHex)) {
                        throw new CertificateException("Fingerprint mismatch — expected "
                            + expectedHex.substring(0, 16) + "…, got " + actualHex.substring(0, 16) + "…");
                    }
                } catch (NoSuchAlgorithmException e) {
                    throw new CertificateException("SHA-256 not available", e);
                }
            }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        var ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{pinner}, null);
        return ctx;
    }

    /**
     * Parsed result of an admin call. {@code status} is the HTTP code (0 = no
     * URL, -1 = local/network error before a response). {@code ok} is true on
     * HTTP 200. {@code body} is the parsed JSON object (or {@code {raw: ...}}
     * if the relay returned non-JSON). {@code rawBody} is the unparsed text.
     */
    public record AdminResult(int status, boolean ok, Map<String, Object> body, String rawBody) {
        /** A list-shaped sub-field by key, or an empty list. */
        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> listField(String key) {
            var v = body == null ? null : body.get(key);
            return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        }

        public String error() {
            var v = body == null ? null : body.get("error");
            return v == null ? null : String.valueOf(v);
        }
    }
}
