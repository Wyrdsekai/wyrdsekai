package org.wyrdsekai.core.agent;

import java.time.Duration;

import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.wyrdsekai.core.inference.InferenceRouter;

/**
 * #35 test support — "the 4B is always the voice". Every substantive
 * {@code speak(...)} now runs a one-shot 4B voice pass, which fires an extra
 * {@link InferenceRouter.ChatRequest} (requestId prefixed {@code "polish-"})
 * at the inference router BEFORE the line reaches the room. Probe-driven
 * integration tests that fetch a specific "next" inference request would
 * otherwise grab that voice-pass request and desync.
 *
 * <p>{@link #nextChatRequest} transparently drains voice-pass requests: each
 * {@code polish-} request is answered by echoing its draft (the last user
 * message) straight back, so the guard sees the draft VERBATIM and the room
 * receives the line immediately — no 3s timeout, no lost fact — and the method
 * returns the first NON-polish request, which is the one the test actually
 * cares about (the reactive turn, a tool call, a spawned familiar's request,
 * a preflight scorer call, …).</p>
 */
final class VoicePassTestSupport {

    private VoicePassTestSupport() {}

    /** Prefix stamped on every voice-polish request id (see CompanionActor.polishVoiceAsync). */
    static final String POLISH_PREFIX = "polish-";

    /**
     * Return the next {@link InferenceRouter.ChatRequest} that is NOT a voice
     * pass, echo-answering (and thereby draining) any voice-pass requests seen
     * along the way so the actor's speech is delivered promptly.
     */
    static InferenceRouter.ChatRequest nextChatRequest(
            TestProbe<InferenceRouter.Command> probe, Duration timeout) {
        while (true) {
            var req = probe.expectMessageClass(
                InferenceRouter.ChatRequest.class, timeout);
            if (isVoicePass(req)) {
                echoDraft(req);
                continue;
            }
            return req;
        }
    }

    /**
     * Return the next {@link InferenceRouter.ToolInferRequest}, draining any
     * voice-pass ChatRequests seen first. Used where a tool inference (e.g.
     * think_deeply) is expected right after a spoken prose line.
     */
    static InferenceRouter.ToolInferRequest nextToolInferRequest(
            TestProbe<InferenceRouter.Command> probe, Duration timeout) {
        while (true) {
            var cmd = probe.expectMessageClass(
                InferenceRouter.Command.class, timeout);
            if (cmd instanceof InferenceRouter.ChatRequest cr && isVoicePass(cr)) {
                echoDraft(cr);
                continue;
            }
            return (InferenceRouter.ToolInferRequest) cmd;
        }
    }

    /** True when the request is a #35 voice-polish pass. */
    static boolean isVoicePass(InferenceRouter.ChatRequest req) {
        return req.requestId() != null && req.requestId().startsWith(POLISH_PREFIX);
    }

    /** Answer a voice-pass request by echoing its draft back unchanged. */
    static void echoDraft(InferenceRouter.ChatRequest req) {
        var msgs = req.messages();
        var draft = (msgs == null || msgs.isEmpty())
            ? "" : msgs.get(msgs.size() - 1).content();
        req.replyTo().tell(new InferenceRouter.InferOk(
            req.requestId(), draft, 1, 1));
    }
}
