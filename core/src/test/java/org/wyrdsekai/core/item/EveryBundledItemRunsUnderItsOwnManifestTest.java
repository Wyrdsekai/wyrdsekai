package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemCapabilityAudit;
import org.wyrdsekai.scripting.api.ItemManifest;
import org.wyrdsekai.scripting.api.ItemManifestParser;
import org.wyrdsekai.scripting.api.ItemManifestValidator;
import org.wyrdsekai.scripting.api.ItemWorldApi;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every bundled item keeps working now that its manifest is enforced (2026-09-28). Until then no
 * production path built a capability set from a manifest, so the manifests drifted from what the
 * scripts call: sixteen bundled items called {@code world.*} surfaces they did not declare
 * ({@code maps.read} declared, {@code maps.geocode} called; the Study's ward, roster and invite
 * furnishings declared nothing at all).
 *
 * <p>Two checks per item. Statically, every {@code world.<ns>.<method>(} the script contains is
 * mapped to the capability the runtime asks for ({@link ItemCapabilityAudit}) and must be in the
 * item's manifest. Dynamically, the item
 * runs through the real executor, under its own manifest, for every command it declares, and must
 * never be refused.
 */
class EveryBundledItemRunsUnderItsOwnManifestTest {

    private static final Path ITEMS = Path.of("../scripts/items");
    private static final Pattern WORLD_CALL = Pattern.compile("world\\.(\\w+)\\.(\\w+)\\s*\\(");

    /** A provider with every namespace wired to nothing, so adapter calls reach the gate. */
    private static final class QuietProvider implements ItemWorldApiProvider {
        private final Set<String> adapterNamespaces;

        QuietProvider(Set<String> adapterNamespaces) { this.adapterNamespaces = adapterNamespaces; }

        @Override public List<Map<String, Object>> searchKnowledge(String q, int n) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return Map.of(); }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int n) { return List.of(); }
        @Override public String webFetch(String url, int max) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String t, String i) { return ""; }
        @Override public String llmAnalyze(String t, String p) { return ""; }
        @Override public void agentSpeak(String t) {}
        @Override public void agentRemember(String c) {}
        @Override public void agentTell(String t, String m) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) {
            return Map.of();
        }
        @Override public Set<String> adapterNamespaces() { return adapterNamespaces; }
        @Override public Map<String, Object> invokeAdapter(String ns, String m, Map<String, Object> a) {
            return Map.of("ok", true, "success", true, "data", Map.of());
        }
    }

    private static List<Path> items() throws Exception {
        assumeTrue(Files.isDirectory(ITEMS), "run from a source checkout");
        try (Stream<Path> s = Files.list(ITEMS)) {
            return s.filter(p -> p.toString().endsWith(".js")).sorted().toList();
        }
    }

    @Test
    void every_world_call_in_a_bundled_item_is_declared_in_its_manifest() throws Exception {
        var undeclared = new TreeMap<String, List<String>>();
        int gated = 0;
        for (var js : items()) {
            var src = Files.readString(js);
            var manifest = ItemManifestParser.parse(src);
            assertThat(manifest).as(js + " has a manifest the runtime can read").isNotNull();
            assertThat(ItemManifestValidator.validate(manifest).errors())
                .as(js + " manifest validates").isEmpty();
            var m = WORLD_CALL.matcher(src);
            while (m.find()) gated += ItemCapabilityAudit.requiredFor(m.group(1), m.group(2)).size();
            var problems = ItemCapabilityAudit.undeclared(src, manifest);
            if (!problems.isEmpty()) undeclared.put(js.getFileName().toString(), problems);
        }
        assertThat(gated).as("gated calls found in bundled items").isGreaterThan(20);
        assertThat(undeclared).as("world.* calls a bundled item makes but does not declare").isEmpty();
    }

    @Test
    void every_bundled_item_runs_every_declared_command_without_a_refusal() throws Exception {
        var refused = new TreeMap<String, List<String>>();
        int runs = 0;
        try (var executor = new ItemScriptExecutor()) {
            for (var js : items()) {
                var src = Files.readString(js);
                var manifest = ItemManifestParser.parse(src);
                var adapters = new LinkedHashSet<String>();
                var m = WORLD_CALL.matcher(src);
                while (m.find()) {
                    try {
                        ItemWorldApi.class.getField(m.group(1));
                    } catch (NoSuchFieldException e) {
                        adapters.add(m.group(1));
                    }
                }
                var provider = new QuietProvider(adapters);
                for (var args : argsToTry(manifest)) {
                    var params = new LinkedHashMap<String, Object>(
                        CarriedItemUse.params("player-1", args));
                    for (var p : manifest.params()) params.putIfAbsent(p.name(), "Kyoto");
                    params.put("args", args);
                    // The caller sets no ceiling, as for a bundled item a companion or a room uses.
                    var result = executor.execute(manifest.name(), src, params, provider);
                    runs++;
                    var denial = result.get("capability_denied");
                    var error = String.valueOf(result.getOrDefault("error", ""));
                    if (denial != null || error.contains("capability denied")) {
                        refused.computeIfAbsent(js.getFileName().toString(), k -> new ArrayList<>())
                            .add("'" + args + "' → " + (denial != null ? denial : error));
                    }
                }
            }
        }
        assertThat(runs).isGreaterThan(60);
        assertThat(refused).as("bundled items refused under their own manifest").isEmpty();
    }

    private static List<String> argsToTry(ItemManifest manifest) {
        var out = new LinkedHashSet<String>();
        out.add("");
        for (var c : manifest.commands()) {
            if (c.args() != null) out.add(c.args().replaceAll("<[^>]*>", "x").trim());
        }
        return List.copyOf(out);
    }
}
