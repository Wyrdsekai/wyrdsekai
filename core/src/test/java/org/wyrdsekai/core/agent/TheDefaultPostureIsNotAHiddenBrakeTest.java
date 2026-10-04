package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.BondNaming;
import org.wyrdsekai.core.soul.BondholderPosture;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bondholder's posture is "a stated limit, not a hidden brake". It was a hidden brake: the
 * default, BOUNDED, removed web search and reading a page from every own-time turn (6,547 turns in a
 * week on the household node, whose own Searxng was configured), and nothing in the product could
 * change a bond's posture: {@code Bond.withPosture} had no caller. operator, 2026-10-03: "im really
 * concerned we are preventing them from learning".
 */
class TheDefaultPostureIsNotAHiddenBrakeTest {

    @AfterEach
    void tearDown() {
        ActionPolicy.installWebSearchCost(null);
    }

    @Test
    void under_the_default_the_households_own_search_is_hers_and_a_paid_one_is_not() {
        ActionPolicy.installWebSearchCost(() -> false);   // a Searxng on the node, or the keyless fallback
        for (var verb : List.of("web_search", "searching_glass", "read_content")) {
            assertThat(ActionPolicy.posturePermits(verb, BondholderPosture.BOUNDED)).as(verb).isTrue();
            assertThat(ActionPolicy.posturePermits(verb, BondholderPosture.MINIMAL)).as(verb + " under MINIMAL").isFalse();
            assertThat(ActionPolicy.posturePermits(verb, BondholderPosture.SUSPENDED)).isFalse();
        }
        assertThat(ActionPolicy.posturePermits("query_oracle", BondholderPosture.BOUNDED)).as("an outside service").isFalse();
        ActionPolicy.installWebSearchCost(() -> true);    // Brave, Tavily, SerpAPI
        assertThat(ActionPolicy.posturePermits("web_search", BondholderPosture.BOUNDED)).isFalse();
        assertThat(ActionPolicy.posturePermits("web_search", BondholderPosture.GENEROUS)).isTrue();
        ActionPolicy.installWebSearchCost(null);
        assertThat(ActionPolicy.posturePermits("web_search", BondholderPosture.BOUNDED)).as("not known: held").isFalse();
        assertThat(ActionPolicy.posturePermits("read_book", BondholderPosture.BOUNDED)).as("reading is never a cloud resource").isTrue();
        assertThat(ActionPolicy.posturePermits("library_search", BondholderPosture.MINIMAL)).isTrue();
    }

    @Test
    void the_bondholder_has_a_verb_to_set_it() throws Exception {
        assertThat(BondNaming.isCommand("bond posture mia")).isTrue();
        assertThat(BondNaming.isCommand("bond posture mia generous")).isTrue();
        assertThat(BondNaming.isCommand("bond posture")).as("that is speech").isFalse();
        for (var lang : List.of("en", "es", "ja")) {
            var text = ScriptMessageCatalog.forLang(lang);
            for (var key : List.of("bond.posture.usage", "bond.posture.is", "bond.posture.set", "bond.posture.not_yours",
                    "bond.posture.generous", "bond.posture.bounded", "bond.posture.minimal", "bond.posture.suspended")) {
                assertThat(text.get(key)).as(lang + " " + key).isNotEqualTo(key);
            }
            assertThat(text.get("bond.usage")).contains("bond posture");
        }
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var from = Path.of("..", rel);
        var ca = Files.readString(Files.exists(from) ? from : Path.of(rel));
        assertThat(ca).contains(".onMessage(BondPosture.class, this::onBondPosture)");
        assertThat(ca).contains("var updated = mine.withPosture(msg.wanted());");
        assertThat(ca).contains("persistBond(updated);");
    }
}
