package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.agent.PromptAssembler;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The turn after her own library read returned her own lines.
 *
 * <p>Household node, 2026-09-23 04:57 and 07:21: the judgment turn after a library result came back
 * as about 3,000 characters, her last eight lines with the answer in them twice. The result reaches
 * her as a "[Tool completed]" line filed under her own id, so the prompt built for that turn ended
 * on her own words as assistant turns; the router put its synthetic user turn at position 1, the
 * client merged the trailing assistant turns into one, and llama-server (prefill_assistant on by
 * default) continued that message and returned it as the reply.</p>
 */
class TheLineSheAnswersIsNotHersToContinueTest {

    private static final AgentProfile MIA = new AgentProfile(
        "mia", "companion-mia", "agent", "A companion", "You are mia.", 4096, 512, 0.7);

    private static WorldEvent.Said said(String who, String name, String text, int secondsAgo) {
        return new WorldEvent.Said("hearth", Instant.parse("2026-09-23T11:21:00Z").minusSeconds(secondsAgo),
            who, name, text);
    }

    /** What the client sends: the router's llama-server shaping, then the client's role merge. */
    private static List<InferenceClient.ChatMessage> wire(List<InferenceClient.ChatMessage> assembled) {
        var shaped = InferenceRouter.ensureUserTurn(InferenceRouter.endOnATurnToAnswer(assembled));
        return InferenceClient.sanitizeMessageRoles(shaped);
    }

    @Test
    void her_own_tools_result_is_the_turn_she_answers() {
        var hot = new ArrayList<WorldEvent.Said>();
        for (int i = 8; i >= 2; i--) hot.add(said("companion-mia", "mia", "a line of mine, number " + i, i * 60));
        var trigger = said("companion-mia", "mia",
            "[Tool completed] Rectified flow straightens the paths between noise and data.\n"
                + "[Share the substance with the user in your own words — never repeat this "
                + "bracketed status text aloud.]", 0);
        hot.add(trigger);

        var sent = wire(PromptAssembler.assemble(MIA, null, hot, trigger));

        var last = sent.getLast();
        assertThat(last.role()).as("a turn to answer, not her words to continue").isEqualTo("user");
        assertThat(last.content()).startsWith("[Tool completed] Rectified flow");
        assertThat(sent.stream().filter(m -> "assistant".equals(m.role()))
                .anyMatch(m -> m.content().contains("[Tool completed]")))
            .as("the result line is not one of her own assistant turns").isFalse();
        assertThat(sent.stream().anyMatch(m -> "assistant".equals(m.role())
                && m.content().contains("a line of mine, number 2")))
            .as("her earlier lines stay hers").isTrue();
    }

    @Test
    void a_persons_line_is_answered_as_before() {
        var hot = new ArrayList<WorldEvent.Said>();
        hot.add(said("companion-mia", "mia", "a line of mine", 120));
        var trigger = said("person-sam", "sam", "what did you find?", 0);
        hot.add(trigger);
        var sent = wire(PromptAssembler.assemble(MIA, null, hot, trigger));
        assertThat(sent.getLast().role()).isEqualTo("user");
        assertThat(sent.getLast().content()).isEqualTo("sam says: what did you find?");
    }

    @Test
    void a_trailing_assistant_message_is_never_sent_to_be_continued() {
        var in = List.of(
            new InferenceClient.ChatMessage("system", "You are mia."),
            new InferenceClient.ChatMessage("user", "hello"),
            new InferenceClient.ChatMessage("assistant", "my own line"));
        var out = InferenceRouter.endOnATurnToAnswer(in);
        assertThat(out).hasSize(4);
        assertThat(out.getLast().role()).isEqualTo("user");
        assertThat(out.getLast().content()).isEqualTo(InferenceRouter.SYNTHETIC_USER_TURN);
        assertThat(out.subList(0, 3)).isEqualTo(in);
    }

    @Test
    void a_tool_call_or_a_tool_result_at_the_end_is_left_alone() {
        var call = new InferenceClient.ChatMessage("assistant", null,
            List.of(new InferenceClient.ToolCall("c1", "function",
                new InferenceClient.ToolCallFunction("library_card", "{\"query\":\"flow\"}"))), null);
        var withCall = List.of(new InferenceClient.ChatMessage("user", "look it up"), call);
        assertThat(InferenceRouter.endOnATurnToAnswer(withCall)).isSameAs(withCall);
        var withResult = List.of(new InferenceClient.ChatMessage("user", "look it up"),
            new InferenceClient.ChatMessage("tool", "1. Flow Matching (Lipman 2022)"));
        assertThat(InferenceRouter.endOnATurnToAnswer(withResult)).isSameAs(withResult);
        assertThat(InferenceRouter.endOnATurnToAnswer(null)).isNull();
        assertThat(InferenceRouter.endOnATurnToAnswer(List.of())).isEmpty();
    }

    @Test
    void the_llama_server_shaping_and_its_retry_both_end_on_a_turn_to_answer() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/inference/InferenceRouter.java";
        var p = Files.exists(Paths.get("..", rel)) ? Paths.get("..", rel) : Paths.get(rel);
        var src = Files.readString(p);
        int backstop = src.indexOf("messages = endOnATurnToAnswer(messages);");
        int ensure = src.indexOf("messages = ensureUserTurn(messages);");
        assertThat(backstop).as("before ensureUserTurn, which then finds its turn").isGreaterThan(0).isLessThan(ensure);
        assertThat(src).contains("ensureUserTurn(endOnATurnToAnswer(compacted.messages()))");
    }
}
