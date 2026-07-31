package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parse-robustness for the Qwen XML tool-syntax leak (second-node 2026-07-09): the 9B emitted a JSON
 * action whose string value was closed with XML tool syntax, followed by a truncated second
 * XML-format call — the emote text absorbed the XML tail and the tell_agent was lost.
 */
class XmlToolLeakParseTest {

    @Test
    void hybrid_json_with_truncated_xml_tail_salvages_clean_first_action() {
        // Verbatim shape from second-node's log.
        var raw = "{\"action\":\"emote\",\"text\":\"I'm holding something I just found out — about you "
            + "and your tea through hard times. It's not much yet, but I need the weight of that here "
            + "with me before we move on.</parameter>\n</function>\n</tool_call>\n<tool_call>\n"
            + "<function=tell_agent>\n<parameter=target>\nlulu\"}";
        var result = ActionParser.parseAll(raw);
        assertThat(result.primaryAction()).isInstanceOf(ActionParser.AgentAction.Emote.class);
        var emote = (ActionParser.AgentAction.Emote) result.primaryAction();
        assertThat(emote.text())
            .as("XML fragments must not pollute the spoken text")
            .doesNotContain("</parameter>").doesNotContain("<tool_call>")
            .doesNotContain("<function=").contains("before we move on");
    }

    @Test
    void hybrid_json_plus_complete_xml_call_yields_both_actions() {
        var raw = "{\"action\":\"emote\",\"text\":\"a quiet nod\"}\n"
            + "<tool_call>\n<function=tell_agent>\n<parameter=target>lulu</parameter>\n"
            + "<parameter=message>good morning from operator</parameter>\n</function>\n</tool_call>";
        var result = ActionParser.parseAll(raw);
        assertThat(result.actions()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result.actions())
            .anySatisfy(a -> assertThat(a).isInstanceOf(ActionParser.AgentAction.Emote.class))
            .anySatisfy(a -> {
                assertThat(a).isInstanceOf(ActionParser.AgentAction.TellAgent.class);
                assertThat(((ActionParser.AgentAction.TellAgent) a).message())
                    .contains("good morning");
            });
    }

    @Test
    void pure_xml_tool_call_still_parses() {
        var raw = "<tool_call>\n<function=remember>\n"
            + "<parameter=content>tea is genmaicha</parameter>\n</function>\n</tool_call>";
        var result = ActionParser.parseAll(raw);
        assertThat(result.primaryAction()).isInstanceOf(ActionParser.AgentAction.Remember.class);
    }

    @Test
    void plain_json_untouched() {
        var raw = "{\"action\":\"tell_agent\",\"target\":\"operator\",\"message\":\"hello\"}";
        var result = ActionParser.parseAll(raw);
        assertThat(result.primaryAction()).isInstanceOf(ActionParser.AgentAction.TellAgent.class);
    }

    @Test
    void strip_helper_cuts_orphan_fragment_and_rebalances_json() {
        var s = ActionParser.stripLeakedXmlToolSyntax(
            "{\"action\":\"emote\",\"text\":\"hi there</parameter></function>\"}");
        assertThat(s).doesNotContain("</parameter>");
        assertThat(s).endsWith("}");
    }

    @Test
    void strip_helper_leaves_clean_text_alone() {
        var s = "no xml here at all {\"action\":\"emote\",\"text\":\"hi\"}";
        assertThat(ActionParser.stripLeakedXmlToolSyntax(s)).isEqualTo(s);
    }
}
