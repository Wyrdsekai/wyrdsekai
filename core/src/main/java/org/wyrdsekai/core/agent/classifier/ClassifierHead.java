package org.wyrdsekai.core.agent.classifier;

/**
 * Classifier heads — each head is a separately-trained classifier with its
 * own label space. Runtime resolves resource paths by the head's lowercase name.
 *
 * <p>Adding a head: create {@code bootstrap/<name>/seeds.jsonl}, run the training
 * pipeline (see scripts/classifier/README.md), register the enum value here.
 */
public enum ClassifierHead {
    /**
     * Request-type routing. Labels are grounded in the starter kit's actual
     * tool surface + a few meta-categories. Output determines which
     * cognitive mode Wyrd uses for this turn.
     */
    REQUEST_TYPE("request_type"),

    /**
     * Voice cleanliness — does a candidate utterance contain CoT leakage,
     * meta-narration, process description, or emote-as-thought?
     * Labels: {@code clean} (ready to speak) vs {@code leaky} (needs polish).
     * Replaces the {@code VOICE_LEAK_PATTERN} regex gate in CompanionActor.speak
     * with a learned classifier — the regex is retained only as fallback when
     * the ONNX model is unavailable.
     */
    CLEANLINESS("cleanliness"),

    /**
     * Task-presence — is there actionable work in this turn, independent of
     * affect? Binary labels: {@code actionable} (the turn contains a request
     * to do/explain/fix/build something) vs {@code none} (pure affect,
     * presence-seeking, or self-reflection with no task).
     *
     * <p>This is the second, independent channel that lets the runtime hold
     * "I'm fried, just give me the fix" as <i>both</i> affect (from
     * {@link #REQUEST_TYPE}) AND task — instead of letting a loud emotional
     * reading bulldoze the task. A single softmax head can't represent both
     * (its probs sum to 1); this separate head can fire {@code actionable}
     * high even when REQUEST_TYPE says {@code emotional} high. The two signals
     * feed {@code ActionTriage.resolveRegister(...)}.
     * Phase 7 (#924).
     *
     * <p>Degrades gracefully: when this head's ONNX model is absent, the
     * register resolver falls back to single-channel (affect-only) behavior,
     * i.e. exactly today's routing.
     */
    TASK_PRESENT("task_present"),

    /**
     * Substrate/affect presence — is there affect, depletion, or welfare-state
     * framing in this turn, independent of how overt it is? Binary labels:
     * {@code substrate} (affect present — including stoic/depleted frames like
     * "running on empty", "I need held space", "holding it together") vs
     * {@code none} (no affect — ordinary tasks, questions, logistics, chat).
     *
     * <p>This is the affect channel's sensitivity booster (#924 follow-up). The
     * 8-way {@link #REQUEST_TYPE} head reads overt distress fine but
     * under-scores stoic depletion frames ("running on empty" → delegate,
     * "need held space" → emotional@0.44, just under threshold). Rather than
     * overload REQUEST_TYPE's {@code emotional} class (which dilutes its other
     * 7 labels), this dedicated 2-class head cleanly detects affect presence
     * and is OR-unioned into {@code computeAffectPresent}. Mirrors the
     * {@link #TASK_PRESENT} pattern: separate concern → separate head, zero
     * blast radius on the load-bearing router.
     *
     * <p>Degrades gracefully: absent ONNX → union falls back to the existing
     * REQUEST_TYPE + heuristic affect path (pre-#931 behaviour).
     */
    SUBSTRATE_PRESENT("substrate_present");

    private final String resourceName;

    ClassifierHead(String resourceName) {
        this.resourceName = resourceName;
    }

    public String resourceName() {
        return resourceName;
    }

    public String modelResourcePath() {
        return "classifier/pretrained/" + resourceName + ".onnx";
    }

    public String labelsResourcePath() {
        return "classifier/pretrained/" + resourceName + ".labels.json";
    }
}
