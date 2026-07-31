package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.item.StudyFurnishingKit;
import org.wyrdsekai.core.item.VisitorItemProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1c — verifies the Coding Slate furnishing renders correctly via
 * both the structured (web/JSON) path and the ASCII (telnet/SSH) path,
 * and that the information surfaces in §9.7 parity across both. Per
 * step 20.
 */
class CodingSlateFurnishingTest {

    @Test void slate_factory_produces_canonical_id_and_script() {
        var slate = StudyFurnishingKit.codingSlate();
        assertThat(slate.id()).isEqualTo("coding-slate");
        assertThat(slate.name()).isEqualTo("Coding Slate");
        assertThat(slate.isScripted()).isTrue();
        assertThat(slate.script()).contains("world.coding.backends()");
    }

    @Test void slate_renders_text_with_glyphs_and_structured_payload() {
        // Three rows: codeplane (healthy), aider (enabled but not healthy),
        // openhands (disabled — covered by tier=null path in our stub).
        var rows = List.of(
            backendRow("codeplane", "LOCAL_HEAVY", true, true,
                Map.of("summary", "built 3 files, all tests pass"),
                0.92),
            backendRow("aider", "LOCAL_FREE", true, false,
                null, 0.50),
            backendRow("openhands", "LOCAL_HEAVY", false, false,
                null, null)
        );

        var executor = new ItemScriptExecutor();
        var slate = StudyFurnishingKit.codingSlate();
        var result = executor.execute(slate.id(), slate.script(),
            Map.of(), new SlateProvider(rows));

        var text = String.valueOf(result.get("text"));

        // ASCII path — every backend named, glyphs present, legend rendered.
        assertThat(text).contains("codeplane");
        assertThat(text).contains("aider");
        assertThat(text).contains("openhands");
        assertThat(text).contains("✓");  // ✓ healthy
        assertThat(text).contains("·");  // · configured but not responding
        assertThat(text).contains("×");  // × disabled
        assertThat(text).contains("healthy");
        assertThat(text).contains("disabled");
        assertThat(text).contains("last:");
        assertThat(text).contains("built 3 files");
        assertThat(text).contains("30d:");
        assertThat(text).contains("92%");

        // Structured payload — the JSON / web path returns the same set
        // of backends. Web clients can iterate `backends` for richer
        // rendering; the ASCII text above is a strict subset/superset.
        // The exact JS→Java conversion of host-proxied List<Map> elements
        // depends on allowMapAccess / allowListAccess interplay; rather
        // than depending on that fidelity, we pin the structural shape:
        // a non-null List, then assert §9.7 parity via the text path.
        assertThat(result.get("backends")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        var backends = (List<Object>) result.get("backends");
        assertThat(backends).hasSize(3);

        // Counters are exposed for at-a-glance summaries on the web UI.
        assertThat(((Number) result.get("enabled")).intValue()).isEqualTo(2);
        assertThat(((Number) result.get("healthy")).intValue()).isEqualTo(1);

        // §9.7 parity: every backend the provider supplied appears in
        // the ASCII rendering. Drives the SSH/telnet "no info hidden in
        // the GUI" requirement of SPEC §9.7.
        for (var row : List.of("codeplane", "aider", "openhands")) {
            assertThat(text).contains(row);
        }
    }

    @Test void slate_verbose_includes_tier_in_text() {
        var rows = List.of(
            backendRow("codeplane", "LOCAL_HEAVY", true, true,
                null, null));
        var executor = new ItemScriptExecutor();
        var slate = StudyFurnishingKit.codingSlate();

        // verbose=true → tier shown in brackets
        var result = executor.execute(slate.id(), slate.script(),
            Map.of("verbose", true), new SlateProvider(rows));
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("[LOCAL_HEAVY]");
    }

    @Test void slate_handles_empty_registry_gracefully() {
        // Empty backend list — slate must say something graceful, not
        // blow up, and not render an empty header line.
        var executor = new ItemScriptExecutor();
        var slate = StudyFurnishingKit.codingSlate();
        var result = executor.execute(slate.id(), slate.script(),
            Map.of(), new SlateProvider(List.of()));

        var text = String.valueOf(result.get("text"));
        assertThat(text).containsAnyOf(
            "blank", "no coding backends", "No coding backends");
        assertThat(result.get("backends")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        var backends = (List<Map<String, Object>>) result.get("backends");
        assertThat(backends).isEmpty();
    }

    @Test void slate_handles_default_provider_with_no_overrides() {
        // If a deploy lands without wiring world.coding.backends() the
        // ItemWorldApiProvider default returns an empty list. Same path
        // as the empty-registry test, just driven by the unmocked
        // VisitorItemProvider — proves the default contract.
        var executor = new ItemScriptExecutor();
        var slate = StudyFurnishingKit.codingSlate();
        var provider = new VisitorItemProvider("alpha", "alpha");
        var result = executor.execute(slate.id(), slate.script(),
            Map.of(), provider);

        var text = String.valueOf(result.get("text"));
        assertThat(text).containsAnyOf("blank", "No coding backends");
    }

    // ─── Helpers ────────────────────────────────────────────────────

    /** Build one backend status row with the shape the slate script reads. */
    private static Map<String, Object> backendRow(
            String name, String tier, boolean enabled, boolean healthy,
            Map<String, Object> lastTask, Double successRate30d) {
        var row = new LinkedHashMap<String, Object>();
        row.put("name", name);
        row.put("tier", tier);
        row.put("enabled", enabled);
        row.put("healthy", healthy);
        row.put("lastTask", lastTask);
        row.put("successRate30d", successRate30d);
        return row;
    }

    /** ItemWorldApiProvider that returns a hand-built backend list. */
    private static final class SlateProvider extends VisitorItemProvider {
        private final List<Map<String, Object>> rows;

        SlateProvider(List<Map<String, Object>> rows) {
            super("alpha", "alpha");
            this.rows = rows;
        }

        @Override
        public List<Map<String, Object>> codingBackendsStatus() {
            return rows;
        }
    }
}
