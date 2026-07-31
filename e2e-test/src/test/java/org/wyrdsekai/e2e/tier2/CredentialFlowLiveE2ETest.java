package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;
import org.wyrdsekai.core.external.u.PhaseUAdaptersBootstrap;
import org.wyrdsekai.core.room.TheSafe;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CREDENTIAL FLOW live E2E (W13, task #23) — the
 * Safe → resolver → adapter → item-script chain, end to end with the REAL
 * shipped {@code scripts/items/morning_briefing.js} in a real GraalJS sandbox:
 *
 * <ol>
 *   <li><b>TheSafe.initLocal + storeSlot</b> — a temp-file Safe holds
 *       {@code googlemaps.api_key}; {@link CredentialResolver#resolve} returns
 *       it through the canonical {@code chainedReader}, and the Safe value
 *       BEATS a same-slot {@code WYRDSEKAI_CRED_*} environment value.</li>
 *   <li><b>credential_missing branch</b> — with no credential anywhere, the
 *       morning-briefing scroll dies at its geocode step with the structured
 *       {@code credential_missing} envelope (the honest degrade).</li>
 *   <li><b>past the credential gate</b> — with the Safe slot populated, the
 *       same invocation gets PAST the credential check: the geocode call is
 *       attempted and whatever comes back is anything but
 *       {@code credential_missing}.</li>
 * </ol>
 *
 * <p>No LLM, no network required — the Google Maps adapter's post-credential
 * leg is the registered Phase U surface (currently the structured
 * {@code not_yet_wired} envelope; a live backend would return data or an
 * upstream error — every acceptable outcome is ≠ credential_missing).</p>
 */
@Tag("tier2")
class CredentialFlowLiveE2ETest {

    private static final String SLOT = "googlemaps.api_key";
    private static final String SAFE_VALUE = "safe-key-e2e-12345";
    private static final String ENV_VALUE = "env-key-should-lose";

    private static String briefingScript;
    private static ItemScriptExecutor executor;

    @BeforeAll
    static void setUp() throws Exception {
        var scriptPath = locate("scripts/items/morning_briefing.js");
        assertNotNull(scriptPath, "[HARD] shipped morning_briefing.js must exist");
        briefingScript = Files.readString(scriptPath);

        // Real Phase U adapter surface (idempotent, same as CoreServices.init).
        PhaseUAdaptersBootstrap.init();
        assertTrue(ExternalAdapterRegistry.get().namespaces().contains("maps"),
            "[HARD] maps adapter must be registered");

        executor = new ItemScriptExecutor();
    }

    @AfterAll
    static void tearDownAll() {
        if (executor != null) executor.close();
        CredentialResolver.get().resetForTests();
        TheSafe.resetLocalForTests();
    }

    @AfterEach
    void resetResolver() {
        CredentialResolver.get().resetForTests();
        TheSafe.resetLocalForTests();
    }

    private static Path locate(String rel) {
        for (var base : List.of(Path.of("."), Path.of(".."), Path.of("../.."))) {
            var p = base.resolve(rel);
            if (Files.isRegularFile(p)) return p.toAbsolutePath().normalize();
        }
        return null;
    }

    /**
     * Provider stub whose ONLY real capability is adapter dispatch — routed to
     * the actual {@link ExternalAdapterRegistry}, exactly like production's
     * {@code ItemWorldApiProviderImpl#invokeAdapter}. Everything else is inert.
     */
    static class AdapterOnlyProvider implements ItemWorldApiProvider {
        @Override public List<Map<String, Object>> searchKnowledge(String q, int limit) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return null; }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int n) { return List.of(); }
        @Override public String webFetch(String url, int n) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String t, String i) { return ""; }
        @Override public String llmAnalyze(String t, String p) { return ""; }
        @Override public void agentSpeak(String text) {}
        @Override public void agentRemember(String content) {}
        @Override public void agentTell(String t, String m) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }

        @Override
        public Map<String, Object> invokeAdapter(String namespace, String method,
                                                 Map<String, Object> args) {
            var resp = ExternalAdapterRegistry.get().invoke(new AdapterRequest(
                namespace, method, args == null ? Map.of() : args,
                ItemCapabilitySet.UNRESTRICTED, null));
            return resp.toMap();
        }

        @Override
        public Set<String> adapterNamespaces() {
            return ExternalAdapterRegistry.get().namespaces();
        }
    }

    /** Run the real morning-briefing scroll once and return its result map. */
    private Map<String, Object> invokeBriefing() {
        // Fresh itemId per call — the executor caches compiled Source by id,
        // which is fine, but distinct ids keep failure traces per-scenario.
        var result = executor.execute("morning_briefing_e2e", briefingScript,
            Map.of("address", "1600 Amphitheatre Parkway, Mountain View"),
            new AdapterOnlyProvider());
        assertNotNull(result, "[HARD] script must return a result map");
        return result;
    }

    @SuppressWarnings("unchecked")
    private static String errorCode(Map<String, Object> result) {
        var error = result.get("error");
        if (error instanceof Map<?, ?> m) {
            return String.valueOf(((Map<String, Object>) m).get("code"));
        }
        return error == null ? null : String.valueOf(error);
    }

    // ─── 1. Safe → resolver precedence ───────────────────────────────

    @Test
    void safe_slot_beats_environment_in_resolution_chain() throws Exception {
        var safeFile = Files.createTempFile("credentials-e2e-", ".safe");
        var safe = TheSafe.initLocal(safeFile,
            "e2e-key-material".getBytes(StandardCharsets.UTF_8));
        safe.storeSlot(SLOT, SAFE_VALUE);

        // Fake process env carrying the same slot — the Safe must win.
        var fakeEnv = Map.of("WYRDSEKAI_CRED_GOOGLEMAPS_API_KEY", ENV_VALUE);
        CredentialResolver.get().setSafeReader(CredentialResolver.chainedReader(
            safe::readSlot, fakeEnv::get, System::getProperty));

        assertEquals(Optional.of(SAFE_VALUE), CredentialResolver.get().resolve(SLOT),
            "[HARD] Safe slot must beat the WYRDSEKAI_CRED_* environment value");

        // Chain still degrades: remove the slot → env value surfaces.
        safe.removeSlot(SLOT);
        assertEquals(Optional.of(ENV_VALUE), CredentialResolver.get().resolve(SLOT),
            "[HARD] with the Safe slot gone, the env leg of the chain must answer");

        // Durability: a fresh instance over the same file (same key material)
        // still reads a re-stored slot back.
        safe.storeSlot(SLOT, SAFE_VALUE);
        var reloaded = TheSafe.initLocal(safeFile,
            "e2e-key-material".getBytes(StandardCharsets.UTF_8));
        assertEquals(Optional.of(SAFE_VALUE), reloaded.readSlot(SLOT),
            "[HARD] stored slot must survive a Safe reload from disk");
    }

    // ─── 2. Real scroll dies honestly without the credential ─────────

    @Test
    void morning_briefing_reports_credential_missing_without_key() {
        // Resolver wired to an EMPTY safe + empty env — nothing to find.
        CredentialResolver.get().setSafeReader(CredentialResolver.chainedReader(
            slot -> Optional.empty(), k -> null, k -> null));

        var result = invokeBriefing();
        assertEquals("geocode", String.valueOf(result.get("step")),
            "[HARD] the scroll must die at its geocode step; result: " + result);
        assertEquals("credential_missing", errorCode(result),
            "[HARD] the structured credential_missing envelope must surface; result: " + result);
    }

    // ─── 3. With the Safe populated, the scroll gets PAST the gate ───

    @Test
    void morning_briefing_gets_past_credential_gate_with_safe_slot() throws Exception {
        var safeFile = Files.createTempFile("credentials-e2e-live-", ".safe");
        var safe = TheSafe.initLocal(safeFile,
            "e2e-key-material".getBytes(StandardCharsets.UTF_8));
        safe.storeSlot(SLOT, SAFE_VALUE);
        CredentialResolver.get().setSafeReader(CredentialResolver.chainedReader(
            safe::readSlot, k -> null, k -> null));

        var result = invokeBriefing();
        var code = errorCode(result);

        // The one thing that must NOT happen anymore: the credential gate.
        assertNotEquals("credential_missing", code,
            "[HARD] with the Safe slot populated the geocode call must be attempted "
            + "(any outcome but credential_missing); result: " + result);

        // Current honest contract of the registered Phase U surface: either the
        // structured not_yet_wired envelope from the attempted geocode, an
        // upstream/transport error from a live backend, or a real success.
        var ok = Boolean.TRUE.equals(result.get("ok"));
        assertTrue(ok || code != null,
            "[HARD] past the gate the scroll must return a structured outcome; result: " + result);
        System.out.println("[credential-flow] past-gate geocode outcome: ok=" + ok
            + " code=" + code + " result=" + result);
    }
}
