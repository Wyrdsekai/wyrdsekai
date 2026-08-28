package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A companion should not decline something she can actually do.
 *
 * <p>Asked on 2026-08-19 for "a tool that queries the library and speaks a story out loud
 * to the room", she declined — honestly and with care: <i>"That's something I won't be
 * able to give, and it matters enough for me not to pretend otherwise — none of my things
 * carry their own voices yet."</i> Declining beats the alternative she gave earlier the
 * same evening, which was to hand over an unrelated possession and say "Here it is".
 *
 * <p>But the claim was false. {@code ItemWorldApiProvider} exposes
 * {@code agentSpeak(String)}, {@code agentTell}, {@code roomEmit} and
 * {@code roomBroadcastBodyLanguage} — scripted items can talk. She inferred otherwise
 * from the crafting tool's own description, whose primary guidance listed the script APIs
 * as "world.library, world.web, world.oracle, world.llm" and omitted world.agent. The
 * speak call appeared only at the tail of the fourth parameter, inside the largest tool
 * schema on her surface.
 *
 * <p>She was reasoning correctly from an incomplete description of herself.
 */
class SheKnowsHerItemsCanSpeakTest {

    private static String scriptParamDescription() {
        return ToolItemStarterKit.craftFromTemplate().params().stream()
            .filter(p -> "script".equals(p.name()))
            .findFirst().orElseThrow().description();
    }

    private static String craftDescription() {
        return ToolItemStarterKit.craftFromTemplate().description();
    }

    @Test
    void the_primary_guidance_says_items_can_speak() {
        assertThat(craftDescription())
            .as("she reads this to decide what is possible — speaking must be in it")
            .contains("world.agent");
    }

    @Test
    void the_capability_she_was_asked_for_is_named_in_plain_words() {
        // Not just an API in a list: the thing she was asked to build should be findable
        // by what it DOES, the way the other needs are ("needs to STORE info", etc).
        assertThat(craftDescription().toLowerCase())
            .contains("speak")
            .contains("aloud");
    }

    @Test
    void the_script_parameter_teaches_a_pattern_not_one_recipe() {
        // Naming the APIs was not enough: asked again, she crafted the closest template
        // and left `script` empty, so the item was a plain journal with generated
        // boilerplate and no behaviour. Picking a template is a far easier generation
        // task than writing a chained script, so the description has to show the shape.
        //
        // But the shape must be a PATTERN, not the one thing that was asked for. An
        // example that solves the exact request teaches her to build that request.
        var script = scriptParamDescription();
        assertThat(script)
            .as("the distinction that matters: object versus behaviour")
            .containsIgnoringCase("REQUIRED");
        assertThat(script)
            .as("slots, so any capability can fill them")
            .containsIgnoringCase("gather")
            .containsIgnoringCase("transform")
            .containsIgnoringCase("emit");
    }

    @Test
    void it_asks_for_the_body_only_because_the_runtime_wraps_it() {
        // handleCraftFromTemplate does:
        //     "function invoke(params) {\n" + customScript + "\n}"
        // so a script that declares its own invoke() wraps into a NESTED declaration.
        // That parses cleanly, passes the validation gate, and returns undefined — an
        // item that does nothing, with no error anywhere. An earlier draft of this very
        // description told her to write the function signature herself.
        var script = scriptParamDescription();
        assertThat(script)
            .as("say plainly that the wrapper is provided")
            .containsIgnoringCase("BODY ONLY");
        assertThat(script)
            .as("and the shape shown must not include the signature line")
            .doesNotContain("function invoke(params) {\n");
    }

    @Test
    void no_slot_is_mandatory_so_simple_items_stay_simple() {
        // Guards the opposite overfit: an item that only speaks, or only computes, must
        // not read as malformed against a three-stage template.
        assertThat(scriptParamDescription())
            .containsIgnoringCase("may be skipped");
    }

    @Test
    void the_capabilities_it_offers_are_alternatives_not_a_fixed_chain() {
        var script = scriptParamDescription();
        assertThat(script).contains("world.library.search").contains("world.web.search");
        assertThat(script)
            .as("more than one way to gather means it is a menu, not a recipe")
            .contains("|");
    }

    @Test
    void the_speak_api_it_advertises_actually_exists() {
        // Guards the opposite failure: promising her a capability that is not there
        // would be worse than the omission this fixes.
        var methods = org.wyrdsekai.scripting.api.ItemWorldApiProvider.class.getMethods();
        assertThat(java.util.Arrays.stream(methods).map(java.lang.reflect.Method::getName))
            .contains("agentSpeak");
    }
}
