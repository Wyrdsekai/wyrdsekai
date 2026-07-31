package org.wyrdsekai.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-level coverage for {@link RelayNkeyAdminMain} — focuses on the parts
 * that don't need a live relay: argument parsing, the env-file upsert helper,
 * usage printing. The HTTPS register path is covered by RelayBridgeNkeyAuthIT
 * (between module) + the live-verify pass.
 */
final class RelayNkeyAdminMainTest {

    private record CapturedRun(int exit, String stdout, String stderr) {}

    private static CapturedRun run(Path identityPath, Path confPath, String... args) {
        // Use the same NodeIdentity location for the test by setting the
        // identity-path env override. We can't mutate env on JDK 25 reliably,
        // so we point at the standard home location via system property
        // by invoking a child process — but for these tests we just assert
        // on the env-file helper directly, which doesn't read env.
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var o = new PrintStream(out, true, StandardCharsets.UTF_8);
        var e = new PrintStream(err, true, StandardCharsets.UTF_8);
        int exit = RelayNkeyAdminMain.run(o, e, args);
        return new CapturedRun(exit, out.toString(StandardCharsets.UTF_8),
            err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void no_args_prints_usage_and_returns_1() {
        var r = run(null, null);
        assertEquals(1, r.exit, "no-args is a usage error");
        assertTrue(r.stdout.contains("wyrd relay-nkey print-pubkey"),
            "usage should mention print-pubkey: " + r.stdout);
    }

    @Test
    void help_explicit_returns_0_and_prints_usage() {
        var r = run(null, null, "help");
        assertEquals(0, r.exit, "help is success");
        assertTrue(r.stdout.contains("re-register-existing"),
            "usage should mention the new Phase 2 subcommand: " + r.stdout);
    }

    @Test
    void unknown_subcommand_returns_1_with_usage_on_stderr() {
        // NB: this generates a NodeIdentity at the default home location as a
        // side effect (RelayNkeyAdminMain's `loadOrGenerate` runs before the
        // switch). That's OK in CI/temp-dir scenarios; we just need the exit
        // code to be 1 and the usage block to be emitted.
        var r = run(null, null, "garbage-subcommand");
        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("unknown relay-nkey command"),
            "stderr should explain the rejection: " + r.stderr);
    }

    @Test
    void register_nkey_with_only_url_no_invite_dot_token_fails_user_error(@TempDir Path tmp) {
        // Pass a malformed wyrdrelay URL (no token). This catches the
        // parseInviteUrl branch without needing a real relay.
        var r = run(null, null, "register-nkey", "wyrdrelay://example.com");
        assertEquals(1, r.exit, "missing /<token> is user error");
        assertTrue(r.stderr.contains("missing /<token>"),
            "stderr should explain malformed URL: " + r.stderr);
    }

    @Test
    void upsert_env_inserts_missing_keys(@TempDir Path tmp) throws Exception {
        var envFile = tmp.resolve("env");
        Files.writeString(envFile, "WYRDSEKAI_PORT=7070\n", StandardCharsets.UTF_8);

        var updates = new LinkedHashMap<String, String>();
        updates.put("WYRDSEKAI_RELAY_USE_NKEY", "true");
        updates.put("WYRDSEKAI_RELAY_FINGERPRINT", "AB:CD:EF:01");
        var changed = RelayNkeyAdminMain.upsertEnvFile(envFile, updates,
            /*overwriteExisting=*/false, /*forceOverwriteKeys=*/Set.of());

        assertEquals(2, changed.size(), "both keys should be inserted: " + changed);
        var content = Files.readString(envFile, StandardCharsets.UTF_8);
        assertTrue(content.contains("WYRDSEKAI_PORT=7070"), "existing kept");
        assertTrue(content.contains("WYRDSEKAI_RELAY_USE_NKEY=true"), "new inserted");
        assertTrue(content.contains("WYRDSEKAI_RELAY_FINGERPRINT=AB:CD:EF:01"),
            "fingerprint inserted");
    }

    @Test
    void upsert_env_does_not_overwrite_existing_when_not_forced(@TempDir Path tmp)
            throws Exception {
        var envFile = tmp.resolve("env");
        Files.writeString(envFile,
            "WYRDSEKAI_RELAY_URL=nats://old.example:4222\n"
                + "WYRDSEKAI_RELAY_USE_NKEY=false\n",
            StandardCharsets.UTF_8);

        var updates = new LinkedHashMap<String, String>();
        updates.put("WYRDSEKAI_RELAY_URL", "nats://new.example:4222");
        updates.put("WYRDSEKAI_RELAY_USE_NKEY", "true");

        // overwriteExisting=false, force only USE_NKEY (matching production behaviour).
        var changed = RelayNkeyAdminMain.upsertEnvFile(envFile, updates,
            /*overwriteExisting=*/false,
            /*forceOverwriteKeys=*/Set.of("WYRDSEKAI_RELAY_USE_NKEY"));
        assertEquals(1, changed.size(), "only USE_NKEY should change: " + changed);
        assertEquals("WYRDSEKAI_RELAY_USE_NKEY", changed.getFirst());

        var content = Files.readString(envFile, StandardCharsets.UTF_8);
        assertTrue(content.contains("WYRDSEKAI_RELAY_URL=nats://old.example:4222"),
            "existing URL preserved (not in forceOverwrite)");
        assertTrue(content.contains("WYRDSEKAI_RELAY_USE_NKEY=true"),
            "USE_NKEY upgraded");
    }

    @Test
    void upsert_env_creates_file_when_missing(@TempDir Path tmp) throws Exception {
        var envFile = tmp.resolve("subdir").resolve("env");

        var updates = Map.of("FOO", "bar");
        var changed = RelayNkeyAdminMain.upsertEnvFile(envFile, updates,
            true, Set.of());
        assertEquals(1, changed.size());
        assertEquals("FOO", changed.getFirst());
        assertTrue(Files.exists(envFile), "file should be created");
        assertEquals("FOO=bar\n",
            Files.readString(envFile, StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void doRegister_persists_RELAY_ENABLED_true_alongside_USE_NKEY(@TempDir Path tmp) throws Exception {
        // Regression guard: WYRDSEKAI_RELAY_ENABLED=true MUST be in the
        // auto-update set. Without it, application.conf gates the relay block
        // off (default `enabled = false`) and even a correctly-set RELAY_URL
        // is ignored. mac-node hit this in 2026-04-28 live verify.
        //
        // We don't drive the full register-nkey flow here (needs a relay),
        // but we lock in the contract by reading the source: the four keys
        // the auto-update writes must include both ENABLED and USE_NKEY.
        var src = Files.readString(Path.of(
            "src/main/java/org/wyrdsekai/server/RelayNkeyAdminMain.java"));
        // Find the auto-update block.
        var autoUpdateBlock = src.substring(src.indexOf("Phase 2: auto-update"));
        autoUpdateBlock = autoUpdateBlock.substring(0,
            Math.min(autoUpdateBlock.length(), 2000));
        assertTrue(autoUpdateBlock.contains("WYRDSEKAI_RELAY_ENABLED")
            && autoUpdateBlock.contains("\"true\""),
            "RELAY_ENABLED=true must be persisted by register-nkey auto-update — "
            + "without it the relay block in application.conf stays disabled. "
            + "Source: " + autoUpdateBlock.substring(0, Math.min(500, autoUpdateBlock.length())));
        assertTrue(autoUpdateBlock.contains("WYRDSEKAI_RELAY_USE_NKEY"),
            "USE_NKEY=true also required");
    }

    @Test
    void upsert_env_no_change_when_value_already_matches(@TempDir Path tmp) throws Exception {
        var envFile = tmp.resolve("env");
        Files.writeString(envFile, "WYRDSEKAI_RELAY_USE_NKEY=true\n", StandardCharsets.UTF_8);
        var mtimeBefore = Files.getLastModifiedTime(envFile);

        var changed = RelayNkeyAdminMain.upsertEnvFile(envFile,
            Map.of("WYRDSEKAI_RELAY_USE_NKEY", "true"),
            /*overwriteExisting=*/true, Set.of());
        assertTrue(changed.isEmpty(), "value already matches, no change: " + changed);
        // File should not have been rewritten.
        assertEquals(mtimeBefore, Files.getLastModifiedTime(envFile),
            "no rewrite when no diff");
    }

    // ── — leg-aware persist (append-not-wipe) ──

    @Test
    void leg_index_missing_or_empty_conf_is_leg_0(@TempDir Path tmp) throws Exception {
        assertEquals(0, RelayNkeyAdminMain.resolveRelayLegIndex(
            tmp.resolve("nope"), "nats://a:4222"), "missing file → leg 0");
        var empty = tmp.resolve("env");
        Files.writeString(empty, "# comment only\n", StandardCharsets.UTF_8);
        assertEquals(0, RelayNkeyAdminMain.resolveRelayLegIndex(empty, "nats://a:4222"),
            "no relay lines → leg 0");
    }

    @Test
    void leg_index_same_url_updates_in_place(@TempDir Path tmp) throws Exception {
        var env = tmp.resolve("env");
        Files.writeString(env, """
            WYRDSEKAI_RELAY_URL=nats://relay-node:4222
            WYRDSEKAI_RELAY_URL_2="nats://relay.example.com:4222"
            """, StandardCharsets.UTF_8);
        assertEquals(0, RelayNkeyAdminMain.resolveRelayLegIndex(env, "nats://relay-node:4222"),
            "re-register with leg 0's relay refreshes leg 0 (stale-fp recovery)");
        assertEquals(2, RelayNkeyAdminMain.resolveRelayLegIndex(env, "nats://relay.example.com:4222"),
            "quoted numbered-leg URL matches its own slot");
    }

    @Test
    void leg_index_new_relay_appends_to_lowest_free_slot(@TempDir Path tmp) throws Exception {
        var env = tmp.resolve("env");
        Files.writeString(env, "WYRDSEKAI_RELAY_URL=nats://relay-node:4222\n", StandardCharsets.UTF_8);
        assertEquals(2, RelayNkeyAdminMain.resolveRelayLegIndex(env, "nats://relay.example.com:4222"),
            "occupied leg 0 + new relay → leg 2 (numbering skips 1 by spec)");

        Files.writeString(env, """
            WYRDSEKAI_RELAY_URL=nats://relay-node:4222
            WYRDSEKAI_RELAY_URL_2=nats://relay.example.com:4222
            """, StandardCharsets.UTF_8);
        assertEquals(3, RelayNkeyAdminMain.resolveRelayLegIndex(env, "nats://wyrdsekai.org:4222"),
            "legs 0+2 occupied → leg 3");
    }

    @Test
    void relay_leg_key_suffixing() {
        assertEquals("WYRDSEKAI_RELAY_URL", RelayNkeyAdminMain.relayLegKey("URL", 0));
        assertEquals("WYRDSEKAI_RELAY_FINGERPRINT_2",
            RelayNkeyAdminMain.relayLegKey("FINGERPRINT", 2));
    }

    @Test
    void joining_second_relay_preserves_first_leg_intact(@TempDir Path tmp) throws Exception {
        // THE P5 regression this arc exists for: home-server homed on relay-node joins
        // relay-b. The old flat write kept RELAY_URL=relay-node while
        // force-overwriting RELAY_FINGERPRINT with relay-b's — leg 0 dialing
        // relay-node pinned to relay-b's cert (dead) and no relay-b leg at all.
        // The leg-aware write must leave relay-node's triple untouched and land
        // relay-b complete in the _2 slot.
        var env = tmp.resolve("env");
        Files.writeString(env, """
            WYRDSEKAI_RELAY_ENABLED=true
            WYRDSEKAI_RELAY_URL=nats://relay-node:4222
            WYRDSEKAI_RELAY_FINGERPRINT=AA:BB
            WYRDSEKAI_RELAY_REGISTRATION_URL=https://relay-node:4443
            """, StandardCharsets.UTF_8);

        var natsUrl = "nats://relay.example.com:4222";
        var leg = RelayNkeyAdminMain.resolveRelayLegIndex(env, natsUrl);
        assertEquals(2, leg);
        var updates = new LinkedHashMap<String, String>();
        updates.put("WYRDSEKAI_RELAY_ENABLED", "true");
        updates.put("WYRDSEKAI_RELAY_USE_NKEY", "true");
        updates.put(RelayNkeyAdminMain.relayLegKey("REGISTRATION_URL", leg),
            "https://relay.example.com:4443");
        updates.put(RelayNkeyAdminMain.relayLegKey("FINGERPRINT", leg), "CC:DD");
        updates.put(RelayNkeyAdminMain.relayLegKey("URL", leg), natsUrl);
        RelayNkeyAdminMain.upsertEnvFile(env, updates, /*overwriteExisting=*/true, Set.of());

        var text = Files.readString(env, StandardCharsets.UTF_8);
        assertTrue(text.contains("WYRDSEKAI_RELAY_URL=nats://relay-node:4222\n"),
            "relay-node leg-0 URL untouched: " + text);
        assertTrue(text.contains("WYRDSEKAI_RELAY_FINGERPRINT=AA:BB\n"),
            "relay-node leg-0 fingerprint untouched: " + text);
        assertTrue(text.contains("WYRDSEKAI_RELAY_REGISTRATION_URL=https://relay-node:4443\n"),
            "relay-node leg-0 registration URL untouched: " + text);
        assertTrue(text.contains("WYRDSEKAI_RELAY_URL_2=nats://relay.example.com:4222"),
            "relay-b landed as leg 2: " + text);
        assertTrue(text.contains("WYRDSEKAI_RELAY_FINGERPRINT_2=CC:DD"),
            "relay-b fp landed as leg 2: " + text);
        assertTrue(text.contains("WYRDSEKAI_RELAY_REGISTRATION_URL_2=https://relay.example.com:4443"),
            "relay-b reg URL landed as leg 2: " + text);
    }

    @Test
    void doRegister_persist_block_routes_through_leg_resolution() throws Exception {
        // Same source-reading contract style as the ENABLED=true guard above:
        // the auto-update block must pick its keys via resolveRelayLegIndex/
        // relayLegKey — a revert to flat unsuffixed writes reintroduces the
        // second-join leg-0 corruption.
        var src = Files.readString(Path.of(
            "src/main/java/org/wyrdsekai/server/RelayNkeyAdminMain.java"));
        var autoUpdateBlock = src.substring(src.indexOf("Phase 2: auto-update"));
        autoUpdateBlock = autoUpdateBlock.substring(0,
            Math.min(autoUpdateBlock.length(), 3000));
        assertTrue(autoUpdateBlock.contains("resolveRelayLegIndex"),
            "persist block must resolve the target leg by URL identity");
        assertTrue(autoUpdateBlock.contains("relayLegKey"),
            "persist block must write leg-suffixed keys");
    }

    // ─── Windows/Java parity with the bin/wyrd relay fixes (2026-07-16) ───────────

    /**
     * phone-invite must STAMP the local zone into the invite the relay minted as
     * "unspecified" — Windows forwards phone-invite to this Java class, not through the
     * fixed bash _stamp_invite_zone. An unstamped invite (zone_id="unspecified") makes
     * the app fall to local mode with no error, silently breaking relay login.
     */
    @Test
    void stampZoneIntoInviteUrl_stamps_an_unspecified_zone() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var payload = mapper.createObjectNode();
        payload.put("household_id", "hh-abc");
        payload.put("zone_id", "unspecified");
        payload.put("v", 1);
        var b64 = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mapper.writeValueAsBytes(payload));
        var url = "wyrdphone://192.0.2.105:4443/" + b64;

        var stamped = RelayNkeyAdminMain.stampZoneIntoInviteUrl(url, "hearth", mapper);
        assertNotEquals(url, stamped, "the invite must be rewritten");
        // decode the stamped payload and confirm the zone landed
        var body = stamped.substring(stamped.indexOf('/', "wyrdphone://".length()) + 1);
        var pad = body.length() % 4 == 0 ? body : body + "=".repeat(4 - body.length() % 4);
        var decoded = mapper.readTree(java.util.Base64.getUrlDecoder().decode(pad));
        assertEquals("hearth", decoded.get("zone_id").asText(),
            "zone_id must be stamped to the resolved zone");
        assertEquals("hh-abc", decoded.get("household_id").asText(),
            "other fields must survive the re-encode");
    }

    @Test
    void stampZoneIntoInviteUrl_never_overrides_an_explicit_zone() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var payload = mapper.createObjectNode();
        payload.put("zone_id", "realzone");
        var b64 = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mapper.writeValueAsBytes(payload));
        var url = "wyrdphone://h:4443/" + b64;
        assertEquals(url, RelayNkeyAdminMain.stampZoneIntoInviteUrl(url, "hearth", mapper),
            "an explicitly-set zone must be left untouched");
    }

    @Test
    void stampZoneIntoInviteUrl_is_a_noop_on_garbage() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals("not a url", RelayNkeyAdminMain.stampZoneIntoInviteUrl("not a url", "z", mapper));
        assertEquals("wyrdphone://h/@@bad@@",
            RelayNkeyAdminMain.stampZoneIntoInviteUrl("wyrdphone://h/@@bad@@", "z", mapper),
            "un-decodable payload returns the url unchanged, never throws");
    }

    /**
     * Tier-1 mint guard: inviteZoneIsUnspecified is the final gate before an invite
     * is printed. It must flag every unroutable shape (missing/blank/"unspecified"
     * zone, non-invite, garbage) as true, and only a real stamped zone as false.
     */
    @Test
    void inviteZoneIsUnspecified_flags_every_unroutable_invite() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.function.Function<String, String> mk = zone -> {
            try {
                var p = mapper.createObjectNode();
                p.put("v", 1);
                if (zone != null) p.put("zone_id", zone);
                return "wyrdphone://h:4443/" + java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mapper.writeValueAsBytes(p));
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        // unroutable → true
        assertTrue(RelayNkeyAdminMain.inviteZoneIsUnspecified(mk.apply("unspecified"), mapper));
        assertTrue(RelayNkeyAdminMain.inviteZoneIsUnspecified(mk.apply(""), mapper));
        assertTrue(RelayNkeyAdminMain.inviteZoneIsUnspecified(mk.apply(null), mapper), "missing zone_id");
        assertTrue(RelayNkeyAdminMain.inviteZoneIsUnspecified(null, mapper));
        assertTrue(RelayNkeyAdminMain.inviteZoneIsUnspecified("https://example.com/x", mapper),
            "a non-wyrdphone url is unroutable");
        assertTrue(RelayNkeyAdminMain.inviteZoneIsUnspecified("wyrdphone://h/@@bad@@", mapper),
            "un-decodable payload is unroutable, never throws");
        // real zone → false
        assertFalse(RelayNkeyAdminMain.inviteZoneIsUnspecified(mk.apply("hearth"), mapper));
    }

    /**
     * register-nkey (and join, which routes through it) must derive the relay leg's NATS
     * port from the response's relay_url, not hardcode 4222 — else an offset relay is
     * unreachable. Source-contract check: the hardcoded ":4222" leg URL is gone.
     */
    @Test
    void doRegister_derives_relay_port_from_the_response() throws Exception {
        var src = Files.readString(Path.of(
            "src/main/java/org/wyrdsekai/server/RelayNkeyAdminMain.java"));
        assertFalse(src.contains("\"nats://\" + parsed.host + \":4222\""),
            "the relay leg URL must NOT hardcode :4222 — derive the port from relay_url");
        assertTrue(src.contains("path(\"relay_url\")"),
            "doRegister must read relay_url from the response to get the real port");
    }
}
