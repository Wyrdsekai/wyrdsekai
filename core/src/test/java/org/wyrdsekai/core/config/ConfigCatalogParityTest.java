package org.wyrdsekai.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The config catalog has ONE source (2026-07-31). {@code scripts/config-catalog.json}
 * is generated from {@code SCROLL_GROUPS} in {@code scripts/rooms/study.js} and is
 * what the CLIs read for {@code wyrd config list --all} — bash and PowerShell alike.
 *
 * <p>Two files describing the same inventory is exactly the drift this repo has
 * paid for before (the root-vs-docs THIRD_PARTY_NOTICES, the partial key lists in
 * WyrdConfigAudit and emitEnvFile). This test is the contract: if the scroll gains,
 * loses, or renames a key and the JSON is not regenerated, it fails and says how to
 * regenerate.</p>
 */
class ConfigCatalogParityTest {

    private static final Pattern GROUP = Pattern.compile(
        "\\{\\s*id:\\s*\"([^\"]+)\"\\s*,\\s*title:\\s*\"([^\"]*)\"\\s*,\\s*keys:\\s*\\[");
    private static final Pattern KEY = Pattern.compile("\\[\\s*\"(WYRDSEKAI_[A-Z0-9_]+)\"");

    private static Path repoRoot() {
        var p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("scripts/rooms/study.js"))) p = p.getParent();
        return p;
    }

    @Test
    void catalogJsonMatchesTheScroll() throws Exception {
        var root = repoRoot();
        assumeTrue(root != null, "run from a source checkout");
        var studyJs = root.resolve("scripts/rooms/study.js");
        var catalogJson = root.resolve("scripts/config-catalog.json");
        assertThat(catalogJson)
            .as("scripts/config-catalog.json must ship — the CLIs read it for `config list --all`")
            .exists();

        // Parse SCROLL_GROUPS out of the room script: group id → ordered key names.
        var src = Files.readString(studyJs, StandardCharsets.UTF_8);
        int start = src.indexOf("var SCROLL_GROUPS = [");
        assertThat(start).as("SCROLL_GROUPS present in study.js").isGreaterThan(0);
        int end = src.indexOf("\n];", start);
        var body = src.substring(start, end);

        var fromScroll = new LinkedHashMap<String, java.util.List<String>>();
        var gm = GROUP.matcher(body);
        var bounds = new java.util.ArrayList<int[]>();
        var ids = new java.util.ArrayList<String>();
        while (gm.find()) { ids.add(gm.group(1)); bounds.add(new int[]{gm.end(), 0}); }
        for (int i = 0; i < bounds.size(); i++) {
            bounds.get(i)[1] = (i + 1 < bounds.size())
                ? bounds.get(i + 1)[0] - 1 : body.length();
            var slice = body.substring(bounds.get(i)[0], bounds.get(i)[1]);
            var keys = new java.util.ArrayList<String>();
            var km = KEY.matcher(slice);
            while (km.find()) keys.add(km.group(1));
            fromScroll.put(ids.get(i), keys);
        }

        @SuppressWarnings("unchecked")
        var json = new ObjectMapper().readValue(
            Files.readString(catalogJson, StandardCharsets.UTF_8), Map.class);
        @SuppressWarnings("unchecked")
        var groups = (java.util.List<Map<String, Object>>) json.get("groups");

        var hint = "\n\nRegenerate with:\n"
            + "  node -e 'const fs=require(\"fs\");const s=fs.readFileSync(\"scripts/rooms/study.js\",\"utf8\");"
            + "const a=s.indexOf(\"var SCROLL_GROUPS = [\"),b=s.indexOf(\"\\n];\",a)+3;"
            + "const g=new Function(s.slice(a,b).replace(\"var SCROLL_GROUPS = \",\"return \"))();"
            + "fs.writeFileSync(\"scripts/config-catalog.json\",JSON.stringify({groups:g.map(x=>"
            + "({id:x.id,title:x.title,keys:x.keys.map(k=>({key:k[0],description:k[1],default:k[2]}))}))},null,2)+\"\\n\")'\n";

        assertThat(groups).as("group count matches the scroll" + hint).hasSize(fromScroll.size());
        for (var g : groups) {
            var id = (String) g.get("id");
            @SuppressWarnings("unchecked")
            var keys = (java.util.List<Map<String, Object>>) g.get("keys");
            var names = keys.stream().map(k -> (String) k.get("key")).toList();
            assertThat(fromScroll).as("group '" + id + "' exists in the scroll" + hint)
                .containsKey(id);
            assertThat(names).as("keys of group '" + id + "' match the scroll" + hint)
                .isEqualTo(fromScroll.get(id));
            for (var k : keys) {
                assertThat((String) k.get("description"))
                    .as("every catalog key carries a description (" + k.get("key") + ")")
                    .isNotBlank();
            }
        }
    }
}
