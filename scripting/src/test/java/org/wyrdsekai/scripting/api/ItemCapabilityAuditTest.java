package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ItemCapabilityAuditTest {

    private static ItemManifest declaring(String... caps) {
        return new ItemManifest("audited", "1.0.0", "d", "did:wyrd:test", List.of(caps), Map.of(),
            "low", List.of(), List.of(), List.of(), List.of(), null, null, null, "1.0", null);
    }

    @Test
    void the_runtime_says_which_capability_a_call_needs() {
        assertThat(ItemCapabilityAudit.requiredFor("library", "add")).containsExactly("library.add");
        assertThat(ItemCapabilityAudit.requiredFor("household", "setRole"))
            .containsExactly("household.set_role");
        assertThat(ItemCapabilityAudit.requiredFor("web", "search")).as("Tier 1").isEmpty();
        assertThat(ItemCapabilityAudit.requiredFor("openweather", "forecast"))
            .as("an adapter namespace").containsExactly("openweather.forecast");
    }

    @Test
    void an_undeclared_call_is_named_with_its_fix() {
        var script = """
            function invoke(p) {
              var hits = world.web.search(p.args);
              world.library.add(hits[0].title, {});
              return { text: world.llm.summarize(hits[0].title, 'short') };
            }
            """;
        var problems = ItemCapabilityAudit.undeclared(script, declaring("library.add"));
        assertThat(problems).singleElement().asString()
            .contains("world.llm.summarize").contains("'llm.summarize'")
            .contains("exports.manifest.capabilities")
            .doesNotContain("library.add").doesNotContain("web.search");
        assertThat(ItemCapabilityAudit.undeclared(script, declaring("library.add", "llm.summarize")))
            .isEmpty();
    }
}
