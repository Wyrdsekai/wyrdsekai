package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The person's request must actually reach the tool.
 *
 * <p>It did not. The scripted-tool dispatcher built its {@code query} param from
 * {@code pendingTrigger} — which is <b>already null</b> by the time a ReAct tool call dispatches
 * (the loop clears it when it starts). So the ternary fell to {@code ""} and every scripted item
 * was handed an <b>empty</b> {@code query}.
 *
 * <p>Measured on home-server, 2026-07-14, in the calculator's own log line:
 * <pre>
 *   Executing scripted tool item 'Calculator' … with params:
 *     {expression=std([12.0, 47.0, 8.0, 93.0, 21.0, 66.0, 5]), query=, agentDid=…}
 *                                                              ^^^^^^ empty
 * </pre>
 *
 * <p>This is a quiet, wide bug. The dispatcher's own comment says the user's request "is the most
 * reliable source for query/topic params" — and then supplied nothing. The calculator's whole
 * recovery path ("if the model's expression is unreadable, read the PERSON's question instead")
 * could never fire, because the person's question was never there. Any item leaning on
 * {@code query} was silently degraded the same way.
 *
 * <p>{@code lastReactTrigger} is the surviving handle — it is exactly what the rest of the actor
 * falls back to, and what the 2026-07-08 item-build fix landed for this same reason. The dispatcher
 * simply never adopted it.
 *
 * <p>Guarded structurally: the dispatcher must not read {@code pendingTrigger} alone.
 */
class UserRequestReachesTheToolTest {

    private static final Path ACTOR =
        Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    /**
     * Scoped to the DISPATCHER. Reading {@code pendingTrigger} where the ReAct loop is first set up
     * is correct — it is still alive there, and is handed straight to {@code reactRequester}. It is
     * only at tool-DISPATCH time, later, that it has been cleared.
     */
    @Test
    void theDispatcherDoesNotBuildTheUserRequestFromPendingTriggerAlone() throws IOException {
        var src = Files.readString(ACTOR);
        var start = src.indexOf("private boolean tryDispatchScriptedToolCall");
        assertTrue(start > 0, "dispatcher not found — did it get renamed?");
        var window = src.substring(start, Math.min(src.length(), start + 8000));

        assertTrue(!window.contains("var userRequest = pendingTrigger != null ? pendingTrigger.text() : \"\";"),
            "the scripted-tool dispatcher is reading pendingTrigger ALONE — it is null by the time "
                + "a ReAct tool call dispatches, so every item gets query=\"\" and the person's "
                + "request never reaches the tool. Fall back to reactRequester / lastReactTrigger.");

        assertTrue(window.contains("lastReactTrigger"),
            "the dispatcher must fall back to lastReactTrigger — it is the only trigger still alive "
                + "at ReAct dispatch time");
    }

    /**
     * ...but only what a PERSON said. Own-time turns fabricate a {@code Said} with
     * {@code entityId = "system"} carrying the autonomy PROMPT as its text. Injecting that as
     * {@code query} piped our own scaffold into every tool: on home-server the calculator was asked to
     * evaluate <em>"(own time) A seeking pull moves in you right now. No one else is here…"</em>,
     * and the journal wrote that same prompt down as though it were her reflection.
     *
     * <p>Internal scaffold must never cross into tool params. Nobody asked for it, and an item
     * that treats it as the request will confidently do the wrong thing with it.
     */
    @Test
    void theSystemsOwnPromptIsNotTreatedAsAUserRequest() throws IOException {
        var src = Files.readString(ACTOR);
        var start = src.indexOf("private boolean tryDispatchScriptedToolCall");
        var window = src.substring(start, Math.min(src.length(), start + 8000));

        assertTrue(window.contains("isHumanRequest("),
            "the dispatcher must gate the injected query on isHumanRequest() — otherwise an "
                + "own-time turn hands the tool our own autonomy prompt as 'the user's request'");

        assertTrue(src.contains("private boolean isHumanRequest("),
            "isHumanRequest must exist");
        var guard = src.substring(src.indexOf("private boolean isHumanRequest("));
        guard = guard.substring(0, Math.min(guard.length(), 700));
        assertTrue(guard.contains("\"system\""),
            "isHumanRequest must reject the synthetic system speaker — that is the autonomy prompt");
    }
}
