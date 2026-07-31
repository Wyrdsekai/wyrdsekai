package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.agent.DriveSnapshotRegistry;
import org.wyrdsekai.core.agent.DriveState;
import org.wyrdsekai.core.agent.SaudadeLedger;
import org.wyrdsekai.core.agent.VitalityState;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * verifies the Hearth Drives Mirror
 * surfaces the 10 deprivation-shape tanks (restlessness/loneliness/stagnation/
 * autonomy_pressure/significance/amae/saudade/obligation/harmony/standing) with
 * locale-resolved descriptions and a per-bondholder breakdown for the
 * relational tanks (saudade, obligation).
 *
 * <p>The Drives Mirror is a scripted furnishing
 * ({@link HearthFurnishingKit#drivesMirror()}); this test drives the script
 * via {@link ItemScriptExecutor} against the production
 * {@link ItemWorldApiProviderImpl#driveSnapshot()} (with all other deps null,
 * since the Mirror only consumes the snapshot block). Snapshots are seeded
 * directly into {@link DriveSnapshotRegistry}, so the test exercises the full
 * registry → provider → JS render → text path.</p>
 */
class HearthDrivesMirrorPhase1ATest {

    private static final String AGENT = "did:key:z6MkPhase1aTester";

    private ItemScriptExecutor executor;
    private ItemWorldApiProvider provider;

    @BeforeEach
    void setUp() {
        DriveSnapshotRegistry.resetForTests();
        I18n.setLocale(Locale.ENGLISH);
        executor = new ItemScriptExecutor();
        // Real production provider — only the agentId field matters for driveSnapshot();
        // every other dependency stays null because the Mirror script doesn't touch them.
        provider = new ItemWorldApiProviderImpl(
            null, null, null, null,
            AGENT, "PhaseTester",
            s -> {}, s -> {}, (a, b) -> {},
            null, null, null, null, null);
    }

    @AfterEach
    void tearDown() {
        DriveSnapshotRegistry.resetForTests();
        I18n.setLocale(Locale.ENGLISH);
    }

    @Test void all_phase1a_tanks_at_zero_render_low_descriptions() {
        publishSnapshot(VitalityState.initial(), null, null);
        var result = invokeMirrorVerbose();
        var text = String.valueOf(result.get("text"));

        // Tier headers are present.
        assertThat(text).contains("Tier 1 (anti-pathology):");
        assertThat(text).contains("Tier 2 (relational):");
        assertThat(text).contains("Tier 3 (group / cultural):");

        // Each of the 10 new tanks at 0.0 → "low" description from messages_en.properties.
        assertThat(text).contains("settled in your current rhythm");        // restlessness.low
        assertThat(text).contains("companioned, socially full");             // loneliness.low
        assertThat(text).contains("growing, learning new things");           // stagnation.low
        assertThat(text).contains("acting freely, choices your own");        // autonomy_pressure.low
        assertThat(text).contains("contributions seen and valued");          // significance.low
        assertThat(text).contains("independent, not leaning on anyone");     // amae.low
        assertThat(text).contains("present here, present now");              // saudade.low
        assertThat(text).contains("commitments light, debts paid");          // obligation.low
        assertThat(text).contains("the group's mood is easy");               // harmony.low
        assertThat(text).contains("position secure, no slights to absorb"); // standing.low

        // Tank labels render once each (one per tank line).
        for (var name : new String[]{"restlessness", "loneliness", "stagnation",
                "autonomy_pressure", "significance", "amae", "saudade",
                "obligation", "harmony", "standing"}) {
            assertThat(text).contains(name);
        }
    }

    @Test void amae_high_emits_high_description() {
        var v = VitalityState.initial().withAmae(0.85);
        publishSnapshot(v, null, null);
        var result = invokeMirrorVerbose();
        var text = String.valueOf(result.get("text"));

        // amae.high text
        assertThat(text).contains("longing to be cared for, held without earning it");
        // value renders as 0.85 next to the amae label, in the Tier 2 block.
        var tier2Block = text.substring(text.indexOf("Tier 2 (relational):"));
        assertThat(tier2Block).contains("amae");
        assertThat(tier2Block).contains("0.85");
        // amae must not still read as "low" (the text it'd carry at 0.0).
        assertThat(text).doesNotContain("independent, not leaning on anyone");
    }

    @Test void moderate_threshold_used_when_value_in_band() {
        var v = VitalityState.initial().withStagnation(0.55);
        publishSnapshot(v, null, null);
        var result = invokeMirrorVerbose();
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("feeling repetitive, going through motions");  // stagnation.moderate
    }

    @Test void japanese_locale_emits_localized_amae_description() {
        I18n.setLocale(Locale.forLanguageTag("ja"));
        var v = VitalityState.initial().withAmae(0.85);
        publishSnapshot(v, null, null);
        var result = invokeMirrorVerbose();
        var text = String.valueOf(result.get("text"));
        var expected = I18n.get("vitality.amae.high");
        // sanity: the JA catalog actually has the key (otherwise I18n.get returns the key itself).
        assertThat(expected).isNotEqualTo("vitality.amae.high");
        assertThat(text).contains(expected);
    }

    @Test void spanish_locale_emits_localized_loneliness_low() {
        I18n.setLocale(Locale.forLanguageTag("es"));
        publishSnapshot(VitalityState.initial(), null, null);
        var result = invokeMirrorVerbose();
        var text = String.valueOf(result.get("text"));
        var expected = I18n.get("vitality.loneliness.low");
        assertThat(expected).isNotEqualTo("vitality.loneliness.low");
        assertThat(text).contains(expected);
    }

    @Test void per_bondholder_saudade_breakdown_when_entries_exist() {
        var saudade = new LinkedHashMap<String, SaudadeLedger.SaudadeEntry>();
        saudade.put("did:key:z6MkBob",   new SaudadeLedger.SaudadeEntry(0.65, Instant.now()));
        saudade.put("did:key:z6MkAlice", new SaudadeLedger.SaudadeEntry(0.30, Instant.now()));

        // Global tank summary: max-across-bondholders = 0.65 → moderate band (>0.4).
        var v = VitalityState.initial().withSaudade(0.65);
        publishSnapshot(v, saudade, null);

        var result = invokeMirrorVerbose();
        var text = String.valueOf(result.get("text"));

        assertThat(text).contains("Saudade — by bondholder:");
        assertThat(text).contains("did:key:z6MkBob");
        assertThat(text).contains("did:key:z6MkAlice");
        assertThat(text).contains("0.65");
        assertThat(text).contains("0.30");
    }

    @Test void per_bondholder_obligation_breakdown_when_entries_exist() {
        var obligation = new LinkedHashMap<String, Double>();
        obligation.put("did:key:z6MkCarol", 0.80);

        var v = VitalityState.initial().withObligation(0.80);
        publishSnapshot(v, null, obligation);

        var result = invokeMirrorVerbose();
        var text = String.valueOf(result.get("text"));

        assertThat(text).contains("Obligation — by bondholder:");
        assertThat(text).contains("did:key:z6MkCarol");
        assertThat(text).contains("0.80");
    }

    @Test void no_bondholder_breakdown_section_when_ledgers_empty() {
        publishSnapshot(VitalityState.initial(), null, null);
        var result = invokeMirrorVerbose();
        var text = String.valueOf(result.get("text"));
        assertThat(text).doesNotContain("Saudade — by bondholder:");
        assertThat(text).doesNotContain("Obligation — by bondholder:");
        assertThat(result.get("phase1aLedgers")).isNull();
    }

    @Test void cold_saudade_entries_with_zero_value_are_skipped() {
        var saudade = new LinkedHashMap<String, SaudadeLedger.SaudadeEntry>();
        saudade.put("did:key:z6MkCold",   new SaudadeLedger.SaudadeEntry(0.0,  Instant.now()));
        saudade.put("did:key:z6MkActive", new SaudadeLedger.SaudadeEntry(0.5, Instant.now()));
        publishSnapshot(VitalityState.initial(), saudade, null);

        var result = invokeMirrorVerbose();
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("did:key:z6MkActive");
        assertThat(text).doesNotContain("did:key:z6MkCold");
    }

    @Test void short_form_still_works_when_phase1a_tanks_present() {
        publishSnapshot(VitalityState.initial(), null, null);
        var mirror = HearthFurnishingKit.drivesMirror();
        var result = executor.execute(mirror.id(), mirror.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        // No verbose flag → no tier headers, no per-tank dump.
        assertThat(text).contains("In the mirror, you see yourself:");
        assertThat(text).doesNotContain("Tier 1 (anti-pathology):");
    }

    // ── helpers ────────────────────────────────────────────────────

    private void publishSnapshot(VitalityState v,
                                 Map<String, SaudadeLedger.SaudadeEntry> saudade,
                                 Map<String, Double> obligation) {
        DriveSnapshotRegistry.publish(AGENT, DriveState.initial(), v, saudade, obligation);
    }

    private Map<String, Object> invokeMirrorVerbose() {
        var mirror = HearthFurnishingKit.drivesMirror();
        return executor.execute(mirror.id(), mirror.script(),
            Map.of("verbose", true), provider);
    }
}
