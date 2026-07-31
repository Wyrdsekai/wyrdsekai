package org.wyrdsekai.server;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.core.identity.HouseholdStore;
import org.wyrdsekai.core.naming.HouseholdIdentity;

import java.io.IOException;
import java.sql.DriverManager;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Headless CLI for NKey operations.
 *
 * <ol>
 *   <li>{@code print-pubkey} — dump the local node's relay NKey public key on
 *       stdout (single line, machine-parseable). Used by {@code bin/wyrd} to
 *       splice the pubkey into a registration request without re-implementing
 *       Ed25519 + NATS NKey encoding in bash.</li>
 *   <li>{@code register-nkey &lt;wyrdrelay://...&gt;} — full client-side
 *       enrollment: parse the invite URL, generate the local NKey if needed,
 *       POST to the relay's {@code /register-nkey}, persist
 *       {@code WYRDSEKAI_RELAY_USE_NKEY=true} + the relay registration URL +
 *       the pinned cert fingerprint into {@code $WYRDSEKAI_CONF}. Idempotent —
 *       running twice with the same invite-URL recovers from drift safely
 *       (relay re-accepts the same pubkey, returns the same identity).</li>
 *   <li>{@code re-enroll &lt;wyrdrelay://...&gt;} — alias for register-nkey
 *       with explicit semantics for "I already have an NKey, the relay just
 *       lost track of me." Same code path; different verb for documentation.</li>
 *   <li>{@code re-register-existing} — drift-recovery without a fresh invite.
 *       Reads the persisted relay URL + fingerprint from {@code $WYRDSEKAI_CONF},
 *       signs a challenge with the local NKey, POSTs to {@code /re-register-nkey}.
 *       Server verifies the signature against the registered pubkey — only
 *       someone holding the seed can re-register. Useful when the relay's
 *       {@code regs.json} was wiped but this node still has its NKey.</li>
 * </ol>
 *
 * <p>Wraps {@code System.exit}: 0 success, 1 user error, 2 internal/network.</p>
 */
public final class RelayNkeyAdminMain {

    private RelayNkeyAdminMain() {}

    public static void main(String[] args) {
        System.exit(run(System.out, System.err, args));
    }

    static int run(PrintStream out, PrintStream err, String... args) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            printUsage(out);
            return args.length == 0 ? 1 : 0;
        }
        // Household-join must represent the SERVER's identity (the one that
        // connects to NATS), resolved from the same $WYRDSEKAI_DATA_DIR the
        // server uses — NOT a throwaway minted at resolveIdentityPath(). Handle
        // it before loadOrGenerate so this path never generates a fresh key.
        if (args[0].equals("household-join")) {
            return doHouseholdJoin(out, err, args);
        }
        var identityPath = resolveIdentityPath();
        try {
            var identity = NodeIdentity.loadOrGenerate(identityPath);
            return switch (args[0]) {
                case "print-pubkey" -> {
                    out.println(identity.nkeyPublicKey());
                    yield 0;
                }
                case "join" -> doJoin(out, err, args);
                case "register-nkey", "re-enroll" -> doRegister(identity, out, err, args);
                case "re-register-existing" -> doReRegisterExisting(identity, out, err, args);
                case "deregister" -> doDeregister(identity, out, err, args);
                case "phone-invite" -> doPhoneInvite(identity, out, err, args);
                case "claim" -> doClaim(identity, out, err, args);
                case "ssh-enable" -> doSshTunnel(identity, out, err, args, true);
                case "ssh-disable" -> doSshTunnel(identity, out, err, args, false);
                default -> {
                    err.println("[wyrd] unknown relay-nkey command: " + args[0]);
                    printUsage(err);
                    yield 1;
                }
            };
        } catch (Exception e) {
            err.println("[wyrd] relay-nkey: " + e.getMessage());
            return 2;
        }
    }

    /**
     * Full relay-homed join as a one-shot CLI command — redeem the join code over
     * HTTPS, verify the relay CA fingerprint against the token, rewrite the dial
     * host to the address we reached, then NKey-enroll + persist conf. Reuses the
     * exact {@link RelayCommandBridge#relayJoin} logic the in-session WS/SSH paths
     * use, so Linux/macOS/Windows share ONE implementation (no per-platform crypto).
     * Usage: {@code relay-nkey join <wyrdjoin://host[:port]/<code>[.<ca_fp>]>}
     *        or {@code relay-nkey join <host[:port]> <code>}
     *        or, commons self-serve (no code — the fingerprint from the relay's
     *        public page is then MANDATORY):
     *        {@code relay-nkey join <host[:port]> --fingerprint <ca_fp>}.
     */
    private static int doJoin(PrintStream out, PrintStream err, String[] args) {
        String hostArg = null;
        String code = null;
        String fingerprint = null;
        for (int i = 1; i < args.length; i++) {
            var a = args[i];
            if (a.equals("--fingerprint") && i + 1 < args.length) {
                fingerprint = args[++i];
            } else if (a.startsWith("--fingerprint=")) {
                fingerprint = a.substring("--fingerprint=".length());
            } else if (hostArg == null) {
                hostArg = a;
            } else if (code == null) {
                code = a;
            }
        }
        if (hostArg == null) {
            err.println("[wyrd] usage: relay-nkey join <wyrdjoin://host[:port]/<code>[.<ca_fp>]> "
                + "| <host[:port]> <code> "
                + "| <host[:port]> --fingerprint <ca_fp>   (commons self-serve)");
            return 1;
        }
        var result = RelayCommandBridge.relayJoin(hostArg, code, fingerprint);
        if (result.ok()) {
            out.println("[wyrd] relay join OK — homed on " + result.inviteUrl());
            return 0;
        }
        err.println("[wyrd] relay join failed: " + result.detail());
        return 1;
    }

    /**
     * "auto add to home zone" join. Enroll THIS node
     * into a hub's household using a pre-shared household key, then mirror the
     * hub + roster locally so the GPU-borrow consumer/provider gates light up.
     *
     * <p>Usage: {@code relay-nkey household-join <host[:port]> --household-key <key>}
     * (default port 7070). POSTs the local identity + key to
     * {@code http://host:port/api/household/join}; on 200, upserts the returned
     * {@code hub} + every {@code members} entry into the local
     * {@code world.db:households} table and persists {@code WYRDSEKAI_NATS_URL},
     * {@code WYRDSEKAI_ZONE_ID}, {@code WYRDSEKAI_INFERENCE_HOUSEHOLD_BORROW=true}.</p>
     */
    private static int doHouseholdJoin(PrintStream out, PrintStream err, String[] args) {
        if (args.length < 2) {
            err.println("[wyrd] usage: relay-nkey household-join <host[:port]> --household-key <key>");
            return 1;
        }
        var hostArg = args[1];
        String key = null;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--household-key") && i + 1 < args.length) key = args[++i];
        }
        if (key == null || key.isBlank()) {
            err.println("[wyrd] household-join: missing --household-key <key>");
            return 1;
        }

        // Resolve the SERVER's node identity from the canonical
        // $WYRDSEKAI_DATA_DIR/node-identity.json (same path Main loads). MUST NOT
        // mint a throwaway: the join has to enroll the node id the running
        // server actually connects to NATS as, or the hub's PROVIDER-side quota
        // exemption (keyed on the requester's node id) misses this node.
        var dataDir = resolveDataDir();
        var idPath = dataDir.resolve("node-identity.json");
        if (!Files.isRegularFile(idPath)) {
            err.println("[wyrd] household-join: no node identity at " + idPath
                + " — start the node once first, or set WYRDSEKAI_DATA_DIR to match the server.");
            return 1;
        }
        final NodeIdentity identity;
        try {
            identity = NodeIdentity.loadOrGenerate(idPath); // file exists → loads, never generates
        } catch (Exception e) {
            err.println("[wyrd] household-join: could not load node identity from " + idPath
                + " — " + e.getMessage());
            return 2;
        }

        String host;
        int port = 7070;
        var colon = hostArg.indexOf(':');
        if (colon > 0) {
            host = hostArg.substring(0, colon);
            try {
                port = Integer.parseInt(hostArg.substring(colon + 1));
            } catch (NumberFormatException e) {
                err.println("[wyrd] household-join: bad port in " + hostArg);
                return 1;
            }
        } else {
            host = hostArg;
        }

        var mapper = new ObjectMapper();
        var node = new LinkedHashMap<String, Object>();
        node.put("nodeId", identity.nodeId());
        node.put("publicKeyB64", identity.publicKeyBase64());
        node.put("fingerprint", fingerprintHex(identity.publicKeyBytes()));
        node.put("didKey", HouseholdIdentity.fromSpkiBytes(identity.publicKeyBytes()).did());
        node.put("x25519PublicKeyB64", identity.x25519PublicKeyBase64());
        var body = new LinkedHashMap<String, Object>();
        body.put("householdKey", key);
        body.put("node", node);

        try {
            var json = mapper.writeValueAsBytes(body);
            var url = "http://" + host + ":" + port + "/api/household/join";
            out.println("[wyrd] household-join: enrolling with " + host + ":" + port);
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            var req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(BodyPublishers.ofByteArray(json))
                .build();
            var resp = http.send(req, BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                err.println("[wyrd] hub rejected household-join (HTTP " + resp.statusCode()
                    + "): " + resp.body());
                return 1;
            }
            var root = mapper.readTree(resp.body());
            var zoneId = root.path("zoneId").asText("");
            var natsUrl = root.path("natsUrl").asText("");

            // Mirror the hub + roster into the LOCAL households table so the
            // GPU-borrow gate (HouseholdStore.get(peer).isPresent()) lights up.
            // Same dataDir resolved above for the node identity.
            var dbUrl = "jdbc:sqlite:" + dataDir.resolve("world.db")
                .toAbsolutePath().toString().replace('\\', '/');
            ensureHouseholdsTable(dbUrl);
            var store = new HouseholdStore(dbUrl);
            var seen = new HashSet<String>();
            if (upsertNode(store, root.path("hub"), identity.nodeId())) {
                seen.add(root.path("hub").path("nodeId").asText(""));
            }
            for (var m : root.path("members")) {
                var id = m.path("nodeId").asText("");
                if (seen.contains(id)) continue;
                if (upsertNode(store, m, identity.nodeId())) seen.add(id);
            }

            var confPath = resolveWritableConfPath();
            if (confPath != null) {
                var updates = new LinkedHashMap<String, String>();
                if (!natsUrl.isBlank()) updates.put("WYRDSEKAI_NATS_URL", natsUrl);
                if (!zoneId.isBlank()) updates.put("WYRDSEKAI_ZONE_ID", zoneId);
                updates.put("WYRDSEKAI_INFERENCE_HOUSEHOLD_BORROW", "true");
                try {
                    upsertEnvFile(confPath, updates, /*overwriteExisting=*/false, updates.keySet());
                    out.println("[wyrd] household-join: persisted "
                        + String.join(", ", updates.keySet()) + " → " + confPath);
                } catch (IOException e) {
                    err.println("[wyrd] household-join: warning — could not write " + confPath
                        + ": " + e.getMessage());
                }
            }
            out.println("[wyrd] joined household '" + zoneId + "' via " + host + " — "
                + seen.size() + " peers mirrored. Apply: wyrd restart");
            return 0;
        } catch (Exception e) {
            err.println("[wyrd] household-join failed — " + e.getMessage());
            return 2;
        }
    }

    /**
     * Upsert one roster entry (JSON {@code {nodeId, publicKeyB64, fingerprint,
     * didKey, x25519PublicKeyB64}}) into the local households table. Skips our
     * own node id (we mirror our own identity at boot) and malformed entries.
     * Returns true if a row was actually upserted.
     */
    private static boolean upsertNode(HouseholdStore store, JsonNode n, String selfNodeId) {
        if (n == null || !n.isObject()) return false;
        var nodeId = n.path("nodeId").asText(null);
        var pubB64 = n.path("publicKeyB64").asText(null);
        if (nodeId == null || nodeId.isBlank() || pubB64 == null || pubB64.isBlank()) return false;
        if (nodeId.equals(selfNodeId)) return false;
        byte[] pub = Base64.getDecoder().decode(pubB64);
        var xNode = n.path("x25519PublicKeyB64");
        byte[] x = (xNode.isMissingNode() || xNode.isNull() || xNode.asText("").isBlank())
            ? null : Base64.getDecoder().decode(xNode.asText());
        store.upsert(nodeId, pub, n.path("fingerprint").asText(null),
            n.path("didKey").asText(null), x);
        return true;
    }

    /** SHA-256 of SPKI bytes as colon-separated lowercase hex — matches Main's mirror. */
    private static String fingerprintHex(byte[] spkiBytes) {
        try {
            var sha = MessageDigest.getInstance("SHA-256").digest(spkiBytes);
            return HexFormat.ofDelimiter(":").formatHex(sha);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    /** Resolve the node data dir (env override else {@code ~/.wyrdsekai}) — mirrors Main. */
    private static Path resolveDataDir() {
        var env = System.getenv("WYRDSEKAI_DATA_DIR");
        if (env != null && !env.isEmpty()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".wyrdsekai");
    }

    /**
     * Best-effort create of the households table in the local world.db, so a
     * fresh node that hasn't booted the full server schema can still mirror a
     * household. Matches the canonical sqlite-create-schema.sql definition.
     */
    private static void ensureHouseholdsTable(String jdbcUrl) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS households("
                + "household_id TEXT PRIMARY KEY, public_key BLOB NOT NULL, "
                + "fingerprint TEXT NOT NULL, did_key TEXT, x25519_public_key BLOB, "
                + "registered_at INTEGER NOT NULL, "
                + "updated_at INTEGER NOT NULL DEFAULT (unixepoch()))");
        } catch (Exception ignored) {
            // best-effort; HouseholdStore.upsert logs if the table is truly missing
        }
    }

    private static int doRegister(NodeIdentity identity, PrintStream out, PrintStream err, String[] args) {
        if (args.length < 2) {
            err.println("[wyrd] usage: relay-nkey register-nkey <wyrdrelay://host[:port]/<token>> "
                + "[--household-tag X] [--zone-id Y] [--node-name Z]");
            return 1;
        }
        var inviteUrl = args[1];
        String householdTag = null, zoneId = null, nodeName = null;
        for (int i = 2; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--household-tag" -> householdTag = args[i + 1];
                case "--zone-id" -> zoneId = args[i + 1];
                case "--node-name" -> nodeName = args[i + 1];
                default -> { /* ignore unknown flags */ }
            }
        }
        // Parse wyrdrelay://host[:port]/<token>
        var parsed = parseInviteUrl(inviteUrl, err);
        if (parsed == null) return 1;

        var pubkey = identity.nkeyPublicKey();
        out.println("[wyrd] relay-nkey: registering pubkey=" + truncate(pubkey)
            + " with " + parsed.host + ":" + parsed.port);

        try {
            var mapper = new ObjectMapper();
            var body = new LinkedHashMap<String, Object>();
            body.put("invite_token", parsed.token);
            body.put("pubkey", pubkey);
            if (householdTag != null) body.put("household_tag", householdTag);
            if (zoneId != null) body.put("zone_id", zoneId);
            if (nodeName != null) body.put("node_name", nodeName);
            var json = mapper.writeValueAsBytes(body);

            // §F2.2 trust model: the invite token's payload carries the
            // relay's leaf-cert SHA-256 fingerprint. We pin TLS verification to that
            // fingerprint — the household-CA leaf isn't in the JVM system trust store,
            // but the invite URL is the trust anchor. Same pattern as `wyrd relay register`
            // (bash) which fetches the cert via openssl and compares fingerprints.
            var expectedFingerprint = extractFingerprintFromToken(parsed.token, mapper);
            if (expectedFingerprint == null) {
                err.println("[wyrd] invite token missing 'fp' field — cannot verify relay identity");
                return 1;
            }
            out.println("[wyrd] relay-nkey: pinning TLS to relay fingerprint "
                + expectedFingerprint.substring(0, 23) + "…");

            var url = "https://" + parsed.host + ":" + parsed.port + "/register-nkey";
            var resp = pinnedHttpsPost(url, json, expectedFingerprint);
            if (resp.statusCode() != 200) {
                err.println("[wyrd] relay rejected registration (HTTP " + resp.statusCode() + "): " + resp.body());
                return 2;
            }
            out.println("[wyrd] relay-nkey: registered. response=" + resp.body());

            // The relay's zone-leg PORT, as it advertises it in the response's relay_url.
            // A co-hosted (offset) relay runs its public leg on e.g. 4322; hardcoding 4222
            // made the node dial its OWN NATS instead of the relay — "registered" but never
            // connected (relay list LIVE=no; found 2026-07-16). Only the port is authoritative
            // (the relay returns 0.0.0.0 for the host); substitute the host ourselves.
            int relayNatsPort = 4222;
            try {
                var relayUrl = new ObjectMapper().readTree(resp.body()).path("relay_url").asText("");
                if (!relayUrl.isBlank()) {
                    var p = URI.create(relayUrl).getPort();
                    if (p > 0) relayNatsPort = p;
                }
            } catch (Exception ignore) { /* keep 4222 */ }

            // Phase 2: auto-update the conf file so the operator doesn't have to.
            // Leg-aware (, append-not-wipe): a zone
            // may home on several relays at once, and enrolling with a SECOND
            // relay must not clobber the first leg. The old flat write left
            // WYRDSEKAI_RELAY_URL pointing at relay A while force-overwriting
            // WYRDSEKAI_RELAY_FINGERPRINT with relay B's — a leg 0 that dials A
            // pinning B's cert (dead), and no leg for B at all. Mirrors
            // bin/wyrd `_relay_add_leg`: same NATS URL → refresh that leg in
            // place (the stale-fingerprint recovery path); different relay →
            // lowest free numbered slot (WYRDSEKAI_RELAY_URL_2, …).
            // Persist (suffix _<n> for legs >= 2, unsuffixed for leg 0):
            //   WYRDSEKAI_RELAY_ENABLED=true                 — shared gate that turns
            //                                                   the relay block on in
            //                                                   application.conf
            //   WYRDSEKAI_RELAY_USE_NKEY=true                — shared opt into NKey auth
            //   WYRDSEKAI_RELAY_REGISTRATION_URL=https://... — for re-register-existing
            //   WYRDSEKAI_RELAY_FINGERPRINT=...              — pin re-register TLS
            //   WYRDSEKAI_RELAY_URL=nats://host[:4222]       — NATS URL for RelayBridge
            var confPath = resolveWritableConfPath();
            if (confPath != null) {
                var registrationUrl = "https://" + parsed.host
                    + (parsed.port == 443 ? "" : ":" + parsed.port);
                var natsUrl = "nats://" + parsed.host + ":" + relayNatsPort;
                try {
                    var leg = resolveRelayLegIndex(confPath, natsUrl);
                    var updates = new LinkedHashMap<String, String>();
                    updates.put("WYRDSEKAI_RELAY_ENABLED", "true");
                    updates.put("WYRDSEKAI_RELAY_USE_NKEY", "true");
                    updates.put(relayLegKey("REGISTRATION_URL", leg), registrationUrl);
                    updates.put(relayLegKey("FINGERPRINT", leg), expectedFingerprint);
                    updates.put(relayLegKey("URL", leg), natsUrl);
                    // Every key overwrites: the target leg was chosen by URL
                    // identity, so this either refreshes the SAME relay's
                    // material in place or fills a fresh slot — it can never
                    // mix one relay's URL with another's fingerprint.
                    var changed = upsertEnvFile(confPath, updates,
                        /*overwriteExisting=*/true, Set.of());
                    if (!changed.isEmpty()) {
                        out.println("[wyrd] relay-nkey: persisted " + String.join(", ", changed)
                            + " → " + confPath
                            + (leg == 0 ? "" : " (appended as relay leg " + leg
                                + " — existing legs kept)"));
                    } else {
                        out.println("[wyrd] relay-nkey: conf file already current at " + confPath);
                    }
                    out.println("[wyrd] restart wyrdsekai to use the registered NKey.");
                } catch (IOException e) {
                    err.println("[wyrd] relay-nkey: warning — could not write " + confPath
                        + ": " + e.getMessage());
                    err.println("[wyrd] manually set WYRDSEKAI_RELAY_USE_NKEY=true and restart.");
                }
            } else {
                out.println("[wyrd] next step: set WYRDSEKAI_RELAY_USE_NKEY=true in your env"
                    + " (no writable conf file detected) and restart wyrdsekai.");
            }
            return 0;
        } catch (Exception e) {
            err.println("[wyrd] relay-nkey: registration failed — " + e.getMessage());
            return 2;
        }
    }

    /**
     * Drift recovery without a fresh invite. Uses the relay URL + cert
     * fingerprint persisted on first registration, signs a challenge with the
     * local NKey, POSTs to {@code /re-register-nkey}. Relay verifies signature
     * against the already-registered pubkey — only someone holding the seed
     * can replay. If the relay doesn't recognise the pubkey, you need a fresh
     * invite (output makes that suggestion).
     *
     * <p>Optional flags allow override of the persisted URL/fingerprint
     * (e.g. relay was redeployed with a new cert, or env file was clobbered):
     * {@code --registration-url https://host[:port]} and
     * {@code --fingerprint AB:CD:...}.</p>
     */
    private static int doReRegisterExisting(NodeIdentity identity, PrintStream out, PrintStream err,
                                            String[] args) {
        String registrationUrl = null;
        String fingerprint = null;
        for (int i = 1; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--registration-url" -> registrationUrl = args[i + 1];
                case "--fingerprint" -> fingerprint = args[i + 1];
                default -> { /* ignore unknown */ }
            }
        }
        // Fall back to the persisted env file.
        var confPath = resolveWritableConfPath();
        if (registrationUrl == null && confPath != null) {
            registrationUrl = readEnvVar(confPath, "WYRDSEKAI_RELAY_REGISTRATION_URL");
        }
        if (fingerprint == null && confPath != null) {
            fingerprint = readEnvVar(confPath, "WYRDSEKAI_RELAY_FINGERPRINT");
        }
        if (registrationUrl == null || fingerprint == null) {
            err.println("[wyrd] re-register-existing: missing relay URL or fingerprint.");
            err.println("[wyrd] Either run `wyrd relay register-nkey <invite>` first, or");
            err.println("[wyrd] pass --registration-url https://host[:port] --fingerprint AB:CD:...");
            return 1;
        }
        if (!registrationUrl.endsWith("/re-register-nkey")) {
            registrationUrl = registrationUrl.replaceAll("/+$", "") + "/re-register-nkey";
        }

        var pubkey = identity.nkeyPublicKey();
        out.println("[wyrd] re-register-existing: pubkey=" + truncate(pubkey)
            + " against " + registrationUrl);

        try {
            // Challenge content: tsSeconds + ":" + pubkey. Server verifies the
            // signature and rejects ts that's > 5 min skew (anti-replay). The
            // pubkey-in-challenge prevents an attacker from cross-replaying a
            // signature minted for one relay against a different one.
            long ts = Instant.now().getEpochSecond();
            var challenge = (ts + ":" + pubkey).getBytes(StandardCharsets.UTF_8);
            byte[] signature = identity.nkeyAuthHandler().sign(challenge);
            var sigB64 = Base64.getEncoder().encodeToString(signature);

            var body = new LinkedHashMap<String, Object>();
            body.put("pubkey", pubkey);
            body.put("ts", ts);
            body.put("signature", sigB64);
            var mapper = new ObjectMapper();
            var json = mapper.writeValueAsBytes(body);

            var resp = pinnedHttpsPost(registrationUrl, json, fingerprint);
            if (resp.statusCode() == 404 || (resp.statusCode() == 401
                && resp.body().toLowerCase().contains("unknown pubkey"))) {
                err.println("[wyrd] relay does not recognise this pubkey ("
                    + truncate(pubkey) + ") — your relay regs were lost AND your invite is gone.");
                err.println("[wyrd] Ask the relay operator for a fresh invite, then run:");
                err.println("[wyrd]   wyrd relay register-nkey <wyrdrelay://...>");
                return 2;
            }
            if (resp.statusCode() != 200) {
                err.println("[wyrd] relay rejected re-registration (HTTP "
                    + resp.statusCode() + "): " + resp.body());
                return 2;
            }
            out.println("[wyrd] re-register-existing: ok. response=" + resp.body());
            return 0;
        } catch (Exception e) {
            err.println("[wyrd] re-register-existing failed — " + e.getMessage());
            return 2;
        }
    }

    /**
     * Voluntary deregistration — the inverse of re-register-existing. Reads the
     * persisted relay URL + fingerprint, signs a {@code deregister:{ts}:{pubkey}}
     * challenge with the local NKey, and POSTs to {@code /deregister}. The relay
     * verifies the signature (only the seed-holder can delete) and hard-deletes
     * the record, pulling the NKey from the live auth config.
     *
     * <p>The {@code deregister:} challenge namespace is DISTINCT from
     * re-register's bare {@code {ts}:{pubkey}}, so a captured re-register
     * signature can't be replayed as a delete.</p>
     *
     * <p>Best-effort by design: this backs {@code wyrd relay leave} /
     * {@code wyrd uninstall}, where a torn-down zone wants its registration
     * gone. A missing persisted URL is a soft failure (exit 1) so the caller
     * can still proceed to strip local config.</p>
     */
    private static int doDeregister(NodeIdentity identity, PrintStream out, PrintStream err,
                                    String[] args) {
        String registrationUrl = null;
        String fingerprint = null;
        for (int i = 1; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--registration-url" -> registrationUrl = args[i + 1];
                case "--fingerprint" -> fingerprint = args[i + 1];
                default -> { /* ignore unknown */ }
            }
        }
        var confPath = resolveWritableConfPath();
        if (registrationUrl == null && confPath != null) {
            registrationUrl = readEnvVar(confPath, "WYRDSEKAI_RELAY_REGISTRATION_URL");
        }
        if (fingerprint == null && confPath != null) {
            fingerprint = readEnvVar(confPath, "WYRDSEKAI_RELAY_FINGERPRINT");
        }
        if (registrationUrl == null) {
            err.println("[wyrd] deregister: no relay registered (no "
                + "WYRDSEKAI_RELAY_REGISTRATION_URL). Nothing to deregister.");
            return 1;
        }
        var endpoint = registrationUrl.replaceAll("/+$", "") + "/deregister";

        var pubkey = identity.nkeyPublicKey();
        out.println("[wyrd] deregister: pubkey=" + truncate(pubkey)
            + " against " + endpoint);
        try {
            long ts = Instant.now().getEpochSecond();
            var challenge = ("deregister:" + ts + ":" + pubkey).getBytes(StandardCharsets.UTF_8);
            byte[] signature = identity.nkeyAuthHandler().sign(challenge);

            var body = new LinkedHashMap<String, Object>();
            body.put("pubkey", pubkey);
            body.put("ts", ts);
            body.put("signature", Base64.getEncoder().encodeToString(signature));
            var mapper = new ObjectMapper();
            var json = mapper.writeValueAsBytes(body);

            HttpResponse<String> resp;
            if (fingerprint == null || fingerprint.isBlank() || fingerprint.equalsIgnoreCase("none")) {
                // ACME relay (publicly valid cert) or explicit opt-out → system trust.
                var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                var req = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(BodyPublishers.ofByteArray(json))
                    .build();
                resp = http.send(req, BodyHandlers.ofString());
            } else {
                resp = pinnedHttpsPost(endpoint, json, fingerprint);
            }
            if (resp.statusCode() != 200) {
                err.println("[wyrd] relay refused deregister (HTTP "
                    + resp.statusCode() + "): " + resp.body());
                return 2;
            }
            out.println("[wyrd] deregister: ok. response=" + resp.body());
            return 0;
        } catch (Exception e) {
            err.println("[wyrd] deregister failed — " + e.getMessage());
            return 2;
        }
    }

    /**
     * mint a phone connection invite from the relay
     * this zone is registered with. Auth = Ed25519 signature over
     * {@code phone-invite:{ts}:{pubkey}} under the household NKey (the
     * domain prefix keeps it distinct from re-register challenges); the
     * relay only mints for registered, active pubkeys. Prints the relay's
     * JSON response on stdout as a single line — {@code bin/wyrd phone
     * invite} parses {@code invite_url} out of it and renders the QR.
     *
     * <p>Relay location comes from the persisted
     * {@code WYRDSEKAI_RELAY_REGISTRATION_URL} + {@code _FINGERPRINT}
     * (overridable via {@code --registration-url} / {@code --fingerprint};
     * {@code --fingerprint none} uses system trust — for ACME relays whose
     * cert is publicly valid and rotates).</p>
     */
    private static int doPhoneInvite(NodeIdentity identity, PrintStream out, PrintStream err,
                                     String[] args) {
        String registrationUrl = null;
        String fingerprint = null;
        for (int i = 1; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--registration-url" -> registrationUrl = args[i + 1];
                case "--fingerprint" -> fingerprint = args[i + 1];
                default -> { /* ignore unknown */ }
            }
        }
        var confPath = resolveWritableConfPath();
        if (registrationUrl == null && confPath != null) {
            registrationUrl = readEnvVar(confPath, "WYRDSEKAI_RELAY_REGISTRATION_URL");
        }
        if (fingerprint == null && confPath != null) {
            fingerprint = readEnvVar(confPath, "WYRDSEKAI_RELAY_FINGERPRINT");
        }
        if (registrationUrl == null) {
            err.println("[wyrd] phone-invite: no relay registered. Join one first:");
            err.println("[wyrd]   wyrd relay join <host> <code>");
            return 1;
        }
        var endpoint = registrationUrl.replaceAll("/+$", "") + "/phone-invite";

        var pubkey = identity.nkeyPublicKey();
        try {
            long ts = Instant.now().getEpochSecond();
            var challenge = ("phone-invite:" + ts + ":" + pubkey).getBytes(StandardCharsets.UTF_8);
            byte[] signature = identity.nkeyAuthHandler().sign(challenge);

            var body = new LinkedHashMap<String, Object>();
            body.put("pubkey", pubkey);
            body.put("ts", ts);
            body.put("signature", Base64.getEncoder().encodeToString(signature));
            // Password-mode proof rides along when present — zones enrolled
            // via `wyrd relay join` (bash /register path) have a household
            // token but no NKey on the relay's books; the relay accepts
            // whichever proof matches its registration record.
            //
            // Multi-homing: a zone homed on several relays holds a DIFFERENT
            // household_id/token per leg (each relay assigns its own at join).
            // The household proof we send MUST be the one for the relay we're
            // actually minting against — pick the leg whose REGISTRATION_URL
            // matches `registrationUrl`, not the primary leg, or a non-primary
            // relay rejects with "unknown household".
            if (confPath != null) {
                var hh = resolveHouseholdForRegistration(confPath, registrationUrl);
                if (hh[0] != null && hh[1] != null) {
                    body.put("household_id", hh[0]);
                    body.put("token", hh[1]);
                }
            }
            var mapper = new ObjectMapper();
            var json = mapper.writeValueAsBytes(body);

            HttpResponse<String> resp;
            if (fingerprint == null || fingerprint.isBlank() || fingerprint.equalsIgnoreCase("none")) {
                // ACME relay (or explicit opt-out): publicly valid cert, system trust.
                var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                var req = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(BodyPublishers.ofByteArray(json))
                    .build();
                resp = http.send(req, BodyHandlers.ofString());
            } else {
                resp = pinnedHttpsPost(endpoint, json, fingerprint);
            }
            if (resp.statusCode() != 200) {
                err.println("[wyrd] relay refused phone-invite (HTTP "
                    + resp.statusCode() + "): " + resp.body());
                return 2;
            }
            // Stamp the local zone into the invite — the relay mints it "unspecified"
            // (its registration record carries no zone label), and an unstamped invite
            // silently breaks the app's relay login. bin/wyrd does this in _stamp_invite_zone;
            // Windows forwards phone-invite HERE, so it must stamp too (2026-07-16).
            var body2 = resp.body();
            String finalInviteUrl = null;
            try {
                var node = mapper.readTree(body2);
                if (node.isObject() && node.has("invite_url")) {
                    var on = (ObjectNode) node;
                    var zone = resolveLocalZoneLabel();
                    if (zone != null) {
                        on.put("invite_url",
                            stampZoneIntoInviteUrl(node.get("invite_url").asText(), zone, mapper));
                        if (on.path("payload").isObject()) {
                            var pl = (ObjectNode) on.get("payload");
                            var z = pl.path("zone_id").asText("");
                            if (z.isEmpty() || z.equals("unspecified")) pl.put("zone_id", zone);
                        }
                        body2 = mapper.writeValueAsString(on);
                    }
                    finalInviteUrl = on.get("invite_url").asText();
                }
            } catch (Exception stampErr) {
                err.println("[wyrd] phone-invite: could not stamp zone (" + stampErr.getMessage() + ")");
            }
            // Tier-1 mint guard: REFUSE to emit a zone-less ("unspecified") invite.
            // Without a zone id the phone can't bank the zone and silently drops to
            // local mode with NO error (found live 2026-07-16) — the single nastiest
            // failure here. Fail loudly at the source rather than ship it (parity with
            // bin/wyrd do_phone).
            if (finalInviteUrl == null || inviteZoneIsUnspecified(finalInviteUrl, mapper)) {
                err.println("[wyrd] phone-invite: refusing to mint an invite with no zone id —");
                err.println("[wyrd]   the phone can't connect to an unnamed zone and would fall back");
                err.println("[wyrd]   to local mode silently. Set WYRDSEKAI_ZONE_ID=<your-zone> (or");
                err.println("[wyrd]   start the zone server so it can be resolved), then retry.");
                return 3;
            }
            out.println(body2);
            return 0;
        } catch (Exception e) {
            err.println("[wyrd] phone-invite failed — " + e.getMessage());
            return 2;
        }
    }

    /**
     * b — redeem an owner-claim token to record this
     * zone's DID as the relay's {@code owner_did}. Signs
     * {@code claim-owner:{ts}:{did}} with the local NodeIdentity (proving we
     * hold the DID's key) and POSTs {@code token,did,ts,signature_b64} to
     * {@code /claim-owner}. The relay verifies the claim token (single-use,
     * TTL'd) AND the signature, then records ownership.
     *
     * <p>Usage: {@code wyrd relay claim <token> [--registration-url
     * https://host[:port]] [--fingerprint AB:CD:…|none]}. The relay URL +
     * fingerprint fall back to the persisted {@code WYRDSEKAI_RELAY_*} env
     * (same resolution as {@code phone-invite}/{@code deregister}); a
     * freshly-deployed remote relay you haven't joined yet needs the explicit
     * {@code --registration-url} + {@code --fingerprint}.</p>
     */
    private static int doClaim(NodeIdentity identity, PrintStream out, PrintStream err,
                               String[] args) {
        String token = null;
        String registrationUrl = null;
        String fingerprint = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--registration-url" -> { if (i + 1 < args.length) registrationUrl = args[++i]; }
                case "--fingerprint" -> { if (i + 1 < args.length) fingerprint = args[++i]; }
                default -> { if (!args[i].startsWith("--") && token == null) token = args[i]; }
            }
        }
        if (token == null || token.isBlank()) {
            err.println("[wyrd] usage: relay-nkey claim <owner-claim-token> "
                + "[--registration-url https://host[:port]] [--fingerprint AB:CD:…|none]");
            return 1;
        }
        var confPath = resolveWritableConfPath();
        if (registrationUrl == null && confPath != null) {
            registrationUrl = readEnvVar(confPath, "WYRDSEKAI_RELAY_REGISTRATION_URL");
        }
        // the owner-claim token embeds the fp of the
        // relay it targets. Prefer it over the conf's WYRDSEKAI_RELAY_FINGERPRINT,
        // which on a multi-homed zone pins leg 0 — a DIFFERENT relay — and so
        // fails the TLS pin against the relay actually being claimed. An explicit
        // --fingerprint still wins (it leaves `fingerprint` non-null here).
        if (fingerprint == null) {
            var tokenFp = extractFingerprintFromToken(token, new ObjectMapper());
            if (tokenFp != null) fingerprint = tokenFp;
        }
        if (fingerprint == null && confPath != null) {
            fingerprint = readEnvVar(confPath, "WYRDSEKAI_RELAY_FINGERPRINT");
        }
        if (registrationUrl == null) {
            err.println("[wyrd] claim: no relay URL. Pass --registration-url https://host[:port]");
            err.println("[wyrd]        (and --fingerprint AB:CD:… for a household-CA relay,");
            err.println("[wyrd]         or --fingerprint none for an ACME relay).");
            return 1;
        }
        var endpoint = registrationUrl.replaceAll("/+$", "") + "/claim-owner";

        // Must be the NKey-derived DID: the relay records owner_did from this and
        // later authorizes admin calls (which sign with the same NKey) against it,
        // and verifies THIS claim signature against the pubkey it recovers from this
        // DID. identity.did() is a different keypair the relay never sees → both the
        // claim verify and every later admin call would fail. Match the admin path.
        var did = identity.nkeyDid();
        out.println("[wyrd] claim: claiming relay ownership as " + did);
        try {
            long ts = Instant.now().getEpochSecond();
            var challenge = ("claim-owner:" + ts + ":" + did).getBytes(StandardCharsets.UTF_8);
            byte[] signature = identity.nkeyAuthHandler().sign(challenge);

            var body = new LinkedHashMap<String, Object>();
            body.put("token", token);
            body.put("did", did);
            body.put("ts", ts);
            body.put("signature_b64", Base64.getEncoder().encodeToString(signature));
            var mapper = new ObjectMapper();
            var json = mapper.writeValueAsBytes(body);

            HttpResponse<String> resp;
            if (fingerprint == null || fingerprint.isBlank() || fingerprint.equalsIgnoreCase("none")) {
                var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                var req = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(BodyPublishers.ofByteArray(json))
                    .build();
                resp = http.send(req, BodyHandlers.ofString());
            } else {
                resp = pinnedHttpsPost(endpoint, json, fingerprint);
            }
            if (resp.statusCode() != 200) {
                err.println("[wyrd] relay refused claim (HTTP " + resp.statusCode() + "): " + resp.body());
                return 2;
            }
            out.println("[wyrd] claim: ok — you are now the relay owner. response=" + resp.body());
            return 0;
        } catch (Exception e) {
            err.println("[wyrd] claim failed — " + e.getMessage());
            return 2;
        }
    }

    /**
     * enable/disable this zone's SSH reverse tunnel
     * on its registered relay. Signs a {@code admin:ssh-{enable,disable}:…}
     * challenge with the node NKey (the same key the relay authorizes admin ops
     * against) and POSTs to {@code /admin}. On enable, generates a dedicated
     * tunnel keypair ({@code ~/.wyrdsekai/ssh_tunnel_key}, separate from the
     * NKey — least privilege) and ships its PUBLIC half; persists the
     * relay-assigned port/topology into the conf so {@code do_start} can spawn
     * the autossh reverse tunnel; prints the connect recipe.
     */
    private static int doSshTunnel(NodeIdentity identity, PrintStream out, PrintStream err,
                                   String[] args, boolean enable) {
        String registrationUrl = null;
        String fingerprint = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--registration-url" -> { if (i + 1 < args.length) registrationUrl = args[++i]; }
                case "--fingerprint" -> { if (i + 1 < args.length) fingerprint = args[++i]; }
                default -> { /* ignore */ }
            }
        }
        var confPath = resolveWritableConfPath();
        if (registrationUrl == null && confPath != null) {
            registrationUrl = readEnvVar(confPath, "WYRDSEKAI_RELAY_REGISTRATION_URL");
        }
        if (fingerprint == null && confPath != null) {
            fingerprint = readEnvVar(confPath, "WYRDSEKAI_RELAY_FINGERPRINT");
        }
        if (registrationUrl == null || registrationUrl.isBlank()) {
            err.println("[wyrd] ssh-" + (enable ? "enable" : "disable")
                + ": no relay registered. Run `wyrd relay register-nkey <invite>` first,");
            err.println("[wyrd]        or pass --registration-url https://host[:port].");
            return 1;
        }
        var baseUrl = registrationUrl.replaceAll("/+$", "");
        var op = enable ? "ssh-enable" : "ssh-disable";
        var mapper = new ObjectMapper();
        // Match Python's json.dumps(sort_keys=True): the relay re-canonicalizes
        // args, but OUR challenge hash must equal the relay's, so serialize the
        // args with sorted keys + compact separators (no spaces).
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

        try {
            var myPubkey = identity.nkeyPublicKey();
            var did = identity.nkeyDid();

            // args = {pubkey} (disable) or {pubkey, ssh_pubkey} (enable).
            var argMap = new TreeMap<String, Object>();
            argMap.put("pubkey", myPubkey);
            String tunnelPub = null;
            if (enable) {
                tunnelPub = ensureSshTunnelKey(out, err);
                if (tunnelPub == null) return 2;
                argMap.put("ssh_pubkey", tunnelPub);
            }

            var relayDid = fetchRelayDid(baseUrl, fingerprint);  // "" if unknown — bound, not validated
            long ts = Instant.now().getEpochSecond();
            var canonical = mapper.writeValueAsString(argMap);
            var argsHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            var challenge = ("admin:" + op + ":" + ts + ":" + relayDid + ":" + argsHash)
                .getBytes(StandardCharsets.UTF_8);
            var signature = identity.nkeyAuthHandler().sign(challenge);

            var body = new LinkedHashMap<String, Object>();
            body.put("op", op);
            body.put("args", argMap);
            body.put("relay_did", relayDid);
            body.put("ts", ts);
            body.put("did", did);
            body.put("signature_b64", Base64.getEncoder().encodeToString(signature));
            var json = mapper.writeValueAsBytes(body);

            out.println("[wyrd] " + op + ": signing as " + did);
            var endpoint = baseUrl + "/admin";
            HttpResponse<String> resp;
            if (fingerprint == null || fingerprint.isBlank() || fingerprint.equalsIgnoreCase("none")) {
                var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                var req = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(BodyPublishers.ofByteArray(json)).build();
                resp = http.send(req, BodyHandlers.ofString());
            } else {
                resp = pinnedHttpsPost(endpoint, json, fingerprint);
            }
            if (resp.statusCode() != 200) {
                err.println("[wyrd] relay refused " + op + " (HTTP " + resp.statusCode()
                    + "): " + resp.body());
                return 2;
            }
            var node = mapper.readTree(resp.body());

            if (!enable) {
                // Persist disabled so do_start won't spawn the tunnel.
                if (confPath != null) {
                    try {
                        upsertEnvFile(confPath, Map.of("WYRDSEKAI_SSH_TUNNEL_ENABLED", "false"),
                            false, Set.of("WYRDSEKAI_SSH_TUNNEL_ENABLED"));
                    } catch (IOException ignored) { /* best effort */ }
                }
                out.println("[wyrd] ssh-disable: ok — tunnel removed. Restart the zone to stop it.");
                return 0;
            }

            var topology = node.path("topology").asText("port");
            var assignedPort = node.path("assigned_port").asInt(0);
            var relaySshPort = node.path("relay_ssh_port").asInt(2222);
            var relayHost = node.path("relay_host").asText("");
            if (relayHost.isBlank()) {
                // RELAY_PUBLIC_HOST may be unset on the relay; fall back to the
                // host we dialed (strip scheme + port from registration URL).
                relayHost = baseUrl.replaceFirst("^https?://", "").replaceFirst(":.*$", "");
            }
            var hostFp = node.path("tunnel_host_fingerprint").asText("");

            if (confPath != null) {
                var updates = new LinkedHashMap<String, String>();
                updates.put("WYRDSEKAI_SSH_TUNNEL_ENABLED", "true");
                updates.put("WYRDSEKAI_SSH_TUNNEL_RELAY_HOST", relayHost);
                updates.put("WYRDSEKAI_SSH_TUNNEL_RELAY_PORT", String.valueOf(relaySshPort));
                updates.put("WYRDSEKAI_SSH_TUNNEL_REMOTE_PORT", String.valueOf(assignedPort));
                updates.put("WYRDSEKAI_SSH_TUNNEL_TOPOLOGY", topology);
                try {
                    upsertEnvFile(confPath, updates, true, updates.keySet());
                    out.println("[wyrd] ssh-enable: persisted tunnel config → " + confPath);
                } catch (IOException e) {
                    err.println("[wyrd] ssh-enable: warning — could not write " + confPath
                        + ": " + e.getMessage());
                }
            }

            out.println();
            out.println("[wyrd] SSH tunnel ENABLED (topology=" + topology
                + ", relay-assigned port=" + assignedPort + ").");
            if (!hostFp.isBlank()) {
                out.println("[wyrd] Relay tunnel-sshd host key fingerprint: " + hostFp);
                out.println("[wyrd]   (verify on first connect; the zone autossh uses accept-new TOFU)");
            }
            out.println("[wyrd] Restart the zone (`wyrd restart`) so it opens the reverse tunnel.");
            out.println();
            if ("jump".equalsIgnoreCase(topology)) {
                // The relay ships the shared forward-only jump private key in the enable
                // response; save it so the emitted stanza is self-contained (IdentityFile).
                var jumpKeyPath = saveJumpKey(node.path("jump_private_key").asText(""), out, err);
                var zoneAlias = safeHostAlias(relayHost);
                var jumpAlias = zoneAlias + "-jump";
                out.println("[wyrd] Reach this zone (commons / ProxyJump). Add to ~/.ssh/config:");
                out.println();
                out.println("    Host " + zoneAlias);
                out.println("        HostName 127.0.0.1");
                out.println("        Port " + assignedPort);
                out.println("        User <your-zone-account>");
                out.println("        ProxyJump " + jumpAlias);
                out.println();
                // The jump leg authenticates as the SYSTEM user `wyrd-tunnel` (the relay's
                // tunnel sshd has `AllowUsers wyrd-tunnel`; `wyrd-jump` is only the
                // authorized_keys comment, NOT a login name), with the relay-issued jump key.
                out.println("    Host " + jumpAlias);
                out.println("        HostName " + relayHost);
                out.println("        Port " + relaySshPort);
                out.println("        User wyrd-tunnel");
                out.println("        IdentityFile " + (jumpKeyPath != null
                    ? jumpKeyPath : "~/.wyrdsekai/jump_key"));
                out.println("        IdentitiesOnly yes");
                out.println();
                out.println("[wyrd] then: ssh " + zoneAlias);
            } else {
                out.println("[wyrd] Reach this zone with a bare ssh:");
                out.println();
                out.println("    ssh -p " + assignedPort + " <your-zone-account>@" + relayHost);
                out.println();
                out.println("[wyrd]   (swap <your-zone-account> for your MUD login on this zone.)");
                out.println("[wyrd] Port " + assignedPort + " is published by the relay and opened in the");
                out.println("[wyrd]   relay host's firewall at deploy — no per-zone `ufw allow` needed.");
                out.println("[wyrd]   If a CLOUD firewall fronts the relay, allow TCP " + assignedPort + " there too.");
            }
            return 0;
        } catch (Exception e) {
            err.println("[wyrd] " + op + " failed — " + e.getMessage());
            return 2;
        }
    }

    /** Generate the dedicated ed25519 tunnel key if absent; return its public half. */
    private static String ensureSshTunnelKey(PrintStream out, PrintStream err) {
        try {
            var dir = Path.of(System.getProperty("user.home"), ".wyrdsekai");
            Files.createDirectories(dir);
            var key = dir.resolve("ssh_tunnel_key");
            var pub = dir.resolve("ssh_tunnel_key.pub");
            if (!Files.isRegularFile(key) || !Files.isRegularFile(pub)) {
                var pb = new ProcessBuilder("ssh-keygen", "-t", "ed25519", "-N", "",
                    "-C", "wyrd-zone-tunnel", "-f", key.toString());
                pb.redirectErrorStream(true);
                var proc = pb.start();
                var rc = proc.waitFor();
                if (rc != 0) {
                    err.println("[wyrd] ssh-enable: ssh-keygen failed (rc=" + rc
                        + ") — is openssh-client installed?");
                    return null;
                }
                out.println("[wyrd] ssh-enable: generated tunnel key " + key);
            }
            return Files.readString(pub, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            err.println("[wyrd] ssh-enable: could not prepare tunnel key — " + e.getMessage());
            return null;
        }
    }

    /**
     * Persist the relay-issued shared jump private key (jump topology) to
     * {@code ~/.wyrdsekai/jump_key} so the emitted ssh_config stanza can reference it
     * via {@code IdentityFile}. Returns the saved path (or null if nothing to save) —
     * the caller falls back to the literal {@code ~/.wyrdsekai/jump_key} on null.
     */
    private static String saveJumpKey(String privateKey, PrintStream out, PrintStream err) {
        if (privateKey == null || privateKey.isBlank()) {
            return null;
        }
        try {
            var dir = Path.of(System.getProperty("user.home"), ".wyrdsekai");
            Files.createDirectories(dir);
            var key = dir.resolve("jump_key");
            // OpenSSH rejects a world-readable private key, so write 0600.
            var body = privateKey.endsWith("\n") ? privateKey : privateKey + "\n";
            Files.writeString(key, body, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                /* non-POSIX FS (Windows): ACL default is acceptable */
            }
            out.println("[wyrd] ssh-enable: saved jump key " + key);
            return key.toString();
        } catch (Exception e) {
            err.println("[wyrd] ssh-enable: could not save jump key — " + e.getMessage());
            return null;
        }
    }

    /** Best-effort fetch of the relay's own DID from GET /status (empty on any failure). */
    private static String fetchRelayDid(String baseUrl, String fingerprint) {
        try {
            var endpoint = baseUrl + "/status";
            HttpResponse<String> resp;
            if (fingerprint == null || fingerprint.isBlank() || fingerprint.equalsIgnoreCase("none")) {
                var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
                var req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10)).GET().build();
                resp = http.send(req, BodyHandlers.ofString());
            } else {
                resp = pinnedHttpsGet(endpoint, fingerprint);
            }
            if (resp.statusCode() != 200) return "";
            return new ObjectMapper().readTree(resp.body()).path("relay_did").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /** GET an HTTPS endpoint with a fingerprint-pinned TLS context (mirror of pinnedHttpsPost). */
    private static HttpResponse<String> pinnedHttpsGet(String url, String expectedFingerprint)
            throws Exception {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        var sslParams = new SSLParameters();
        sslParams.setEndpointIdentificationAlgorithm("");
        var http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .sslContext(buildPinnedSslContext(expectedFingerprint))
            .sslParameters(sslParams)
            .build();
        var req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10)).GET().build();
        return http.send(req, BodyHandlers.ofString());
    }

    /** Sanitize a relay host into a safe ~/.ssh/config Host alias token. */
    private static String safeHostAlias(String host) {
        var h = host == null ? "" : host.replaceAll("[^A-Za-z0-9._-]", "-");
        return h.isBlank() ? "wyrd-zone" : "wyrd-" + h;
    }

    /**
     * POST to an HTTPS endpoint with a fingerprint-pinned TLS context.
     * Hostname verification is disabled — the fingerprint is the trust anchor.
     * Java HttpClient quirk: {@code setEndpointIdentificationAlgorithm(null)}
     * does NOT disable SAN verification; must set the empty string AND the
     * system property {@code jdk.internal.httpclient.disableHostnameVerification}
     * before HttpClient is built. Both together are belt-and-suspenders.
     */
    /**
     * The running node's zone label — the same value the server resolves via
     * {@code WyrdConfig.zoneId()} (a DETERMINISTIC hostname-derived name held in NO
     * file). Fetched from the local server's published manifest so this CLI need not
     * re-implement the JVM resolution. Returns null if the server isn't reachable.
     *
     * <p>Parity with the bin/wyrd {@code _local_zone_id} fix (2026-07-16): without a
     * resolved zone, {@code phone-invite} left {@code zone_id="unspecified"}, which
     * SILENTLY broke the app's relay login (the phone can't bank a zone with no id and
     * falls to local mode). This is the Windows/Java equivalent — Windows forwards
     * {@code phone-invite} here rather than through the fixed bash path.</p>
     */
    private static String resolveLocalZoneLabel() {
        // env → conf → running server, mirroring bin/wyrd _local_zone_id ordering so
        // `WYRDSEKAI_ZONE_ID=<zone> wyrd phone invite` works even when the server is
        // down (the JVM path used to ONLY ask the running server).
        var env = System.getenv("WYRDSEKAI_ZONE_ID");
        if (env != null && !env.isBlank()) return env;
        var confPath = resolveWritableConfPath();
        if (confPath != null) {
            var fromConf = readEnvVar(confPath, "WYRDSEKAI_ZONE_ID");
            if (fromConf != null && !fromConf.isBlank()) return fromConf;
        }
        var port = System.getenv().getOrDefault("WYRDSEKAI_HTTP_PORT", "7070");
        try {
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            var req = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/.well-known/wyrd-zone"))
                .timeout(Duration.ofSeconds(3)).GET().build();
            var resp = http.send(req, BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            var label = new ObjectMapper().readTree(resp.body()).path("zoneLabel").asText("");
            return label.isBlank() ? null : label;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Stamp {@code zone} into a {@code wyrdphone://host/<b64-json>} invite's payload —
     * ONLY when the relay left {@code zone_id} unset/"unspecified" (never overrides an
     * explicit zone). Mirrors bin/wyrd {@code _stamp_invite_zone} exactly: decode the
     * urlsafe base64 payload, set the field, re-encode compact + sorted-keys + no
     * padding. There is no signature over the payload (the CA fingerprints pin TLS,
     * they don't sign this), so re-encoding is safe. Returns the URL unchanged on any
     * parse failure.
     */
    static String stampZoneIntoInviteUrl(String url, String zone, ObjectMapper mapper) {
        if (url == null || zone == null || zone.isBlank()) return url;
        var scheme = "wyrdphone://";
        if (!url.startsWith(scheme)) return url;
        var slash = url.indexOf('/', scheme.length());
        if (slash < 0) return url;
        var host = url.substring(scheme.length(), slash);
        var b64 = url.substring(slash + 1);
        try {
            var pad = b64.length() % 4 == 0 ? b64 : b64 + "=".repeat(4 - b64.length() % 4);
            var jsonBytes = Base64.getUrlDecoder().decode(pad);
            var payload = (ObjectNode) mapper.readTree(jsonBytes);
            var z = payload.path("zone_id").asText("");
            if (!(z.isEmpty() || z.equals("unspecified"))) return url;   // explicit zone — leave it
            payload.put("zone_id", zone);
            var sorted = mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            var reencoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sorted.writeValueAsBytes(payload));
            return scheme + host + "/" + reencoded;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * True if a {@code wyrdphone://host/<b64-json>} invite's {@code zone_id} is
     * missing/blank/"unspecified" (or the payload can't be parsed) — i.e. the invite
     * is unroutable and the phone would silently fall back to local mode. The final
     * gate before we ever print an invite (Tier-1 mint guard). Mirrors bin/wyrd
     * {@code _invite_zone_is_unspecified}.
     */
    static boolean inviteZoneIsUnspecified(String url, ObjectMapper mapper) {
        var scheme = "wyrdphone://";
        if (url == null || !url.startsWith(scheme)) return true;
        var slash = url.indexOf('/', scheme.length());
        if (slash < 0) return true;
        var b64 = url.substring(slash + 1);
        try {
            var pad = b64.length() % 4 == 0 ? b64 : b64 + "=".repeat(4 - b64.length() % 4);
            var payload = mapper.readTree(Base64.getUrlDecoder().decode(pad));
            var z = payload.path("zone_id").asText("");
            return z.isEmpty() || z.equals("unspecified");
        } catch (Exception e) {
            return true;
        }
    }

    private static HttpResponse<String> pinnedHttpsPost(String url, byte[] body,
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
            .POST(BodyPublishers.ofByteArray(body))
            .build();
        return http.send(req, BodyHandlers.ofString());
    }

    /**
     * Decode the invite token's payload (header.signature shape) and pull the
     * relay's leaf-cert SHA-256 fingerprint out of the {@code fp} field.
     * Returns the colon-separated hex string (e.g. "D2:C2:90:…") or null.
     */
    private static String extractFingerprintFromToken(String token, ObjectMapper mapper) {
        try {
            var dot = token.indexOf('.');
            if (dot <= 0) return null;
            var payloadB64 = token.substring(0, dot);
            // Base64URL-decode with padding tolerance.
            int pad = (4 - payloadB64.length() % 4) % 4;
            var padded = payloadB64 + "=".repeat(pad);
            var json = Base64.getUrlDecoder().decode(padded);
            JsonNode node = mapper.readTree(json);
            return node.has("fp") ? node.get("fp").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Build an SSLContext whose TrustManager accepts only X.509 certs whose
     * SHA-256 fingerprint matches the expected value. Hostname verification is
     * still applied by HttpClient — the fingerprint pin is in addition to it.
     */
    private static SSLContext buildPinnedSslContext(String expectedFingerprint) throws Exception {
        var expectedHex = expectedFingerprint.replace(":", "").replace(" ", "").toLowerCase();
        var pinner = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                throw new UnsupportedOperationException("Client auth not used");
            }
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("Empty cert chain");
                }
                try {
                    var sha = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    var actualHex = HexFormat.of().formatHex(sha);
                    if (!actualHex.equalsIgnoreCase(expectedHex)) {
                        throw new CertificateException(
                            "Fingerprint mismatch — expected " + expectedHex.substring(0, 16) + "…, "
                                + "got " + actualHex.substring(0, 16) + "…");
                    }
                } catch (NoSuchAlgorithmException e) {
                    throw new CertificateException("SHA-256 not available", e);
                }
            }
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        var ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{pinner}, null);
        return ctx;
    }

    private record InviteUrl(String host, int port, String token) {}

    private static InviteUrl parseInviteUrl(String url, PrintStream err) {
        if (url == null || !url.startsWith("wyrdrelay://")) {
            err.println("[wyrd] expected wyrdrelay:// URL, got: " + url);
            return null;
        }
        var rest = url.substring("wyrdrelay://".length());
        var slashIdx = rest.indexOf('/');
        if (slashIdx <= 0) {
            err.println("[wyrd] malformed invite URL — missing /<token>");
            return null;
        }
        var hostPort = rest.substring(0, slashIdx);
        var token = rest.substring(slashIdx + 1);
        // The token may contain a fingerprint suffix after '#'; strip it.
        // Format: <invite_token>#<relay_fingerprint>. The fingerprint is used by
        // wyrd relay register for cert pinning; /register-nkey doesn't need it
        // because the underlying TLS connection already pins via system trust.
        var hashIdx = token.indexOf('#');
        if (hashIdx >= 0) token = token.substring(0, hashIdx);

        String host;
        int port = 443;  // default for HTTPS
        var colonIdx = hostPort.indexOf(':');
        if (colonIdx > 0) {
            host = hostPort.substring(0, colonIdx);
            try {
                port = Integer.parseInt(hostPort.substring(colonIdx + 1));
            } catch (NumberFormatException e) {
                err.println("[wyrd] malformed invite URL — bad port");
                return null;
            }
        } else {
            host = hostPort;
        }
        return new InviteUrl(host, port, token);
    }

    private static Path resolveIdentityPath() {
        var override = System.getenv("WYRDSEKAI_NODE_IDENTITY_PATH");
        if (override != null && !override.isEmpty()) return Path.of(override);
        return Path.of(System.getProperty("user.home"), ".wyrdsekai", "node-identity.json");
    }

    /**
     * Path to the env file the auto-update logic will write. Returns null if
     * we can't find a usable location (caller falls back to printing a manual
     * instruction). Honours {@code WYRDSEKAI_CONF} (passed by {@code bin/wyrd}),
     * else tries the standard system locations.
     */
    private static Path resolveWritableConfPath() {
        var override = System.getenv("WYRDSEKAI_CONF");
        if (override != null && !override.isEmpty()) {
            return Path.of(override);
        }
        // Fallbacks: home dir env file (writable for non-root installs) wins
        // over /etc (which usually requires root). The .deb postinstall keeps
        // these in sync, so either is fine for the wyrdsekai server reading.
        var home = Path.of(System.getProperty("user.home"), ".wyrdsekai", "env");
        if (Files.isWritable(home.getParent())) return home;
        var system = Path.of("/etc/wyrdsekai/wyrdsekai.conf");
        return Files.isWritable(system.getParent()) ? system : null;
    }

    /**
     * Read the value of a single VAR=value line from a shell-style env file.
     * Comments (#) and surrounding whitespace are tolerated. Returns null if
     * the file doesn't exist or the var isn't set.
     */
    /**
     * Resolve the household {@code {id, token}} pair for the relay leg whose
     * registration URL matches {@code registrationUrl}. A multi-homed zone
     * stores legs as {@code WYRDSEKAI_RELAY_REGISTRATION_URL} (primary) plus
     * {@code _2}, {@code _3}, … suffixes, each with its own {@code _USER} /
     * {@code _TOKEN}. Returns the primary household as a fallback when no leg
     * matches (single-homed zones, or an ad-hoc {@code --registration-url}).
     */
    private static String[] resolveHouseholdForRegistration(Path confPath, String registrationUrl) {
        String wantUrl = normalizeRegUrl(registrationUrl);
        // Suffix "" is the primary leg; _2.._9 are additional homes.
        String[] suffixes = {"", "_2", "_3", "_4", "_5", "_6", "_7", "_8", "_9"};
        for (var sfx : suffixes) {
            var legUrl = readEnvVar(confPath, "WYRDSEKAI_RELAY_REGISTRATION_URL" + sfx);
            if (legUrl == null) continue;
            if (normalizeRegUrl(legUrl).equals(wantUrl)) {
                var user = readEnvVar(confPath, "WYRDSEKAI_RELAY_USER" + sfx);
                var token = readEnvVar(confPath, "WYRDSEKAI_RELAY_TOKEN" + sfx);
                if (user != null && token != null) return new String[]{user, token};
            }
        }
        // No leg matched — fall back to the primary household.
        return new String[]{
            readEnvVar(confPath, "WYRDSEKAI_RELAY_USER"),
            readEnvVar(confPath, "WYRDSEKAI_RELAY_TOKEN")
        };
    }

    /** Lower-case + strip trailing slashes so leg URLs compare cleanly. */
    private static String normalizeRegUrl(String url) {
        if (url == null) return "";
        return url.trim().replaceAll("/+$", "").toLowerCase();
    }

    private static String readEnvVar(Path envFile, String key) {
        if (envFile == null || !Files.isRegularFile(envFile)) return null;
        try {
            for (var line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
                var trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                var eq = trimmed.indexOf('=');
                if (eq <= 0) continue;
                var k = trimmed.substring(0, eq).trim();
                if (!k.equals(key)) continue;
                var v = trimmed.substring(eq + 1).trim();
                // Strip optional surrounding quotes.
                if ((v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2)
                    || (v.startsWith("'") && v.endsWith("'") && v.length() >= 2)) {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /**
     * Env-var key for one relay-leg field. Leg 0 uses the legacy unsuffixed
     * names ({@code WYRDSEKAI_RELAY_URL}); legs 2..N the numbered suffixes
     * ({@code WYRDSEKAI_RELAY_URL_2}) — the exact layout
     * {@code WyrdConfig.relayLegs()} reads and {@code bin/wyrd _relay_key}
     * writes (the numbering skips 1 by spec).
     */
    static String relayLegKey(String field, int leg) {
        return leg == 0 ? "WYRDSEKAI_RELAY_" + field
                        : "WYRDSEKAI_RELAY_" + field + "_" + leg;
    }

    /**
     * Pick the conf leg this relay's registration should persist into
     * ( append-not-wipe; mirrors {@code bin/wyrd
     * _relay_add_leg}): a leg whose URL already equals {@code natsUrl} →
     * update that leg in place; no leg 0 yet → 0; otherwise the lowest free
     * numbered slot starting at 2. A missing file resolves to leg 0.
     */
    static int resolveRelayLegIndex(Path envFile, String natsUrl) throws IOException {
        if (envFile == null || !Files.isRegularFile(envFile)) return 0;
        var legUrls = new TreeMap<Integer, String>();
        for (var line : Files.readAllLines(envFile, StandardCharsets.UTF_8)) {
            var trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            var eq = trimmed.indexOf('=');
            if (eq <= 0) continue;
            var key = trimmed.substring(0, eq).trim();
            int leg;
            if (key.equals("WYRDSEKAI_RELAY_URL")) {
                leg = 0;
            } else if (key.startsWith("WYRDSEKAI_RELAY_URL_")) {
                try {
                    leg = Integer.parseInt(key.substring("WYRDSEKAI_RELAY_URL_".length()));
                } catch (NumberFormatException e) {
                    continue;
                }
            } else {
                continue;
            }
            var value = trimmed.substring(eq + 1).trim();
            // Strip quotes for compare, same as upsertEnvFile.
            if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            legUrls.putIfAbsent(leg, value);
        }
        for (var e : legUrls.entrySet()) {
            if (e.getValue().equals(natsUrl)) return e.getKey();
        }
        if (!legUrls.containsKey(0)) return 0;
        int n = 2;
        while (legUrls.containsKey(n)) n++;
        return n;
    }

    /**
     * Update or insert {@code KEY=value} lines in a shell-style env file.
     * Existing lines for the same key are replaced in place; missing keys are
     * appended. Returns the list of keys whose values were actually changed
     * (so the caller can log a tight summary).
     *
     * @param envFile           path to the env file (created if missing)
     * @param updates           map of key→value to apply
     * @param overwriteExisting if true, ALL keys overwrite existing values;
     *                          if false, defer to {@code forceOverwriteKeys}
     * @param forceOverwriteKeys keys that always overwrite even when
     *                           {@code overwriteExisting} is false
     */
    static List<String> upsertEnvFile(Path envFile, Map<String, String> updates,
                                      boolean overwriteExisting,
                                      Set<String> forceOverwriteKeys) throws IOException {
        Files.createDirectories(envFile.getParent());
        List<String> existing = Files.isRegularFile(envFile)
            ? new ArrayList<>(Files.readAllLines(envFile, StandardCharsets.UTF_8))
            : new ArrayList<>();
        var changed = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (int i = 0; i < existing.size(); i++) {
            var line = existing.get(i);
            var trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            var eq = trimmed.indexOf('=');
            if (eq <= 0) continue;
            var key = trimmed.substring(0, eq).trim();
            if (!updates.containsKey(key)) continue;
            seen.add(key);
            var newValue = updates.get(key);
            var oldValue = trimmed.substring(eq + 1).trim();
            // Strip quotes for compare.
            if ((oldValue.startsWith("\"") && oldValue.endsWith("\""))
                || (oldValue.startsWith("'") && oldValue.endsWith("'"))) {
                oldValue = oldValue.substring(1, oldValue.length() - 1);
            }
            if (oldValue.equals(newValue)) continue;  // no change
            boolean shouldOverwrite = overwriteExisting
                || (forceOverwriteKeys != null && forceOverwriteKeys.contains(key));
            if (!shouldOverwrite) continue;  // preserve existing
            existing.set(i, key + "=" + newValue);
            changed.add(key);
        }
        for (var entry : updates.entrySet()) {
            if (seen.contains(entry.getKey())) continue;
            existing.add(entry.getKey() + "=" + entry.getValue());
            changed.add(entry.getKey());
        }
        if (!changed.isEmpty()) {
            Files.write(envFile, existing, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        return changed;
    }

    private static String truncate(String s) {
        if (s == null || s.length() < 16) return s;
        return s.substring(0, 8) + "…" + s.substring(s.length() - 4);
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage:");
        out.println("  wyrd relay-nkey print-pubkey");
        out.println("       Print this node's NATS NKey pubkey (machine-parseable).");
        out.println("  wyrd relay-nkey join <wyrdjoin://host[:port]/<code>[.<ca_fp>]> | <host[:port]> <code>");
        out.println("       Full relay-homed household join: redeem the join code, verify the");
        out.println("       relay CA fingerprint, NKey-enroll, and persist relay conf. One-shot");
        out.println("       CLI form of the in-session `/relay join` (shared by all platforms).");
        out.println("  wyrd relay-nkey household-join <host[:port]> --household-key <key>");
        out.println("       Auto-add this node to a hub's home zone using a pre-shared household");
        out.println("       key (default port 7070). Mirrors the hub + roster into the local");
        out.println("       households table and persists WYRDSEKAI_NATS_URL / WYRDSEKAI_ZONE_ID /");
        out.println("       WYRDSEKAI_INFERENCE_HOUSEHOLD_BORROW=true so GPU-borrow lights up.");
        out.println("  wyrd relay-nkey register-nkey <wyrdrelay://host:port/<token>>");
        out.println("                    [--household-tag X] [--zone-id Y] [--node-name Z]");
        out.println("       Register this node's pubkey with the relay using an invite URL.");
        out.println("       Auto-updates $WYRDSEKAI_CONF with WYRDSEKAI_RELAY_USE_NKEY=true,");
        out.println("       WYRDSEKAI_RELAY_REGISTRATION_URL, WYRDSEKAI_RELAY_FINGERPRINT.");
        out.println("  wyrd relay-nkey re-enroll <wyrdrelay://...>");
        out.println("       Alias for register-nkey; semantic for drift recovery via fresh invite.");
        out.println("  wyrd relay-nkey re-register-existing");
        out.println("                    [--registration-url https://host[:port]] [--fingerprint AB:CD:...]");
        out.println("       Drift recovery WITHOUT a fresh invite. Reads the persisted relay URL");
        out.println("       and fingerprint, signs a challenge with the local NKey, POSTs to");
        out.println("       /re-register-nkey. Server verifies signature → only the seed-holder");
        out.println("       can re-register. Use when the relay's regs.json was lost.");
        out.println("  wyrd relay-nkey deregister");
        out.println("                    [--registration-url https://host[:port]] [--fingerprint AB:CD:...|none]");
        out.println("       Voluntarily deregister this node from the relay. Signs a");
        out.println("       deregister:{ts}:{pubkey} challenge and POSTs /deregister; the relay");
        out.println("       hard-deletes the record. Idempotent. Backs `wyrd relay leave`.");
        out.println("  wyrd relay-nkey claim <owner-claim-token>");
        out.println("                    [--registration-url https://host[:port]] [--fingerprint AB:CD:...|none]");
        out.println("       Redeem an owner-claim token: sign");
        out.println("       claim-owner:{ts}:{did} with this node's identity and POST /claim-owner.");
        out.println("       The relay records this zone's DID as its admin owner. Single-use.");
        out.println("  wyrd relay-nkey phone-invite");
        out.println("                    [--registration-url https://host[:port]] [--fingerprint AB:CD:...|none]");
        out.println("       Mint a phone connection invite from the registered relay (NKey-signed");
        out.println("       request to /phone-invite). Prints the relay's JSON response; used by");
        out.println("       `wyrd phone invite` which renders the QR code.");
    }
}
