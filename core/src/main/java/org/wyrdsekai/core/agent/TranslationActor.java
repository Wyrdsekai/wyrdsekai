package org.wyrdsekai.core.agent;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.inference.InferenceRouter;

import java.time.Duration;
import java.util.List;

/**
 * Translation agent (§15.1) — lives in The Lexicon room, handles inference-based
 * translation of narrative prose. Follows CompanionActor pattern.
 *
 * State machine: IDLE → TRANSLATING → IDLE
 * - On TranslateText → assemble prompt → InferenceRouter
 * - On DetectLanguage → detect prompt → InferenceRouter
 * - On InferOk → store in LexiconService translation memory, reply
 * - On InferError → reply with error
 */
public class TranslationActor extends AbstractBehavior<TranslationActor.Command> {

    private static final Logger log = LoggerFactory.getLogger(TranslationActor.class);

    // --- Protocol ---

    public sealed interface Command {}

    /** Request to translate text. */
    public record TranslateText(String text, String sourceLang, String targetLang,
                                TranslationPrompts.TranslationType type,
                                ActorRef<TranslationResult> replyTo) implements Command {}

    /** Request to detect language of text. */
    public record DetectLanguage(String text,
                                 ActorRef<String> replyTo) implements Command {}

    /** Translation result. */
    public record TranslationResult(String original, String translated,
                                    String sourceLang, String targetLang,
                                    double confidence) {}

    // Internal messages
    private record InferenceResponseReceived(
        InferenceRouter.InferResponse response, PendingRequest pending) implements Command {}
    private record VitalityTick() implements Command {}

    private sealed interface PendingRequest {}
    private record PendingTranslation(TranslateText request) implements PendingRequest {}
    private record PendingDetection(DetectLanguage request) implements PendingRequest {}

    // --- Configuration ---

    private static final Duration VITALITY_TICK_INTERVAL = Duration.ofSeconds(1);
    private static final String VITALITY_TICK_KEY = "vitality-tick";

    // --- State ---

    private enum State { IDLE, TRANSLATING }

    private final ActorRef<InferenceRouter.Command> inferenceRouter;
    private final LexiconService lexiconService;
    private final TimerScheduler<Command> timers;

    private State state = State.IDLE;
    private VitalityState vitality;
    private int translationCount = 0;

    // --- Creation ---

    public static Behavior<Command> create(
            ActorRef<InferenceRouter.Command> inferenceRouter,
            LexiconService lexiconService) {
        return Behaviors.setup(context ->
            Behaviors.withTimers(timers ->
                new TranslationActor(context, timers, inferenceRouter, lexiconService)));
    }

    private TranslationActor(ActorContext<Command> context, TimerScheduler<Command> timers,
                             ActorRef<InferenceRouter.Command> inferenceRouter,
                             LexiconService lexiconService) {
        super(context);
        this.timers = timers;
        this.inferenceRouter = inferenceRouter;
        this.lexiconService = lexiconService;

        // Translation agent vitality: high focus, moderate energy, high confidence
        this.vitality = new VitalityState(0.5, 0.8, 0.8, 0.7, 0.0, 0.0, 0.3, 0.8);

        timers.startTimerWithFixedDelay(VITALITY_TICK_KEY,
            new VitalityTick(), VITALITY_TICK_INTERVAL);

        log.info("TranslationActor started");
    }

    // --- Message handling ---

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(TranslateText.class, this::onTranslateText)
            .onMessage(DetectLanguage.class, this::onDetectLanguage)
            .onMessage(InferenceResponseReceived.class, this::onInferenceResponse)
            .onMessage(VitalityTick.class, this::onVitalityTick)
            .build();
    }

    private Behavior<Command> onTranslateText(TranslateText cmd) {
        // Check translation memory first
        var cached = lexiconService.getTranslation(cmd.text(), cmd.targetLang());
        if (cached.isPresent()) {
            log.debug("Translation cache hit: {} -> {}", cmd.text(), cmd.targetLang());
            cmd.replyTo().tell(new TranslationResult(
                cmd.text(), cached.get(), cmd.sourceLang(), cmd.targetLang(), 1.0));
            return this;
        }

        // Route to inference
        var systemPrompt = TranslationPrompts.systemPrompt(
            cmd.type(), cmd.sourceLang(), cmd.targetLang());
        var temp = TranslationPrompts.temperature(cmd.type());
        var maxTokens = TranslationPrompts.maxTokens(cmd.type());

        var adapter = getContext().messageAdapter(
            InferenceRouter.InferResponse.class,
            resp -> new InferenceResponseReceived(resp, new PendingTranslation(cmd)));

        inferenceRouter.tell(new InferenceRouter.InferRequest(
            "translate-" + translationCount++, null,
            systemPrompt, cmd.text(), maxTokens, temp, adapter));

        state = State.TRANSLATING;
        vitality = new VitalityState(
                vitality.contextBudget() - 0.05, vitality.confidence(),
                vitality.energy() - 0.1, vitality.alignment(),
                vitality.errorPressure(), vitality.momentum() + 0.1,
                vitality.rapport(), vitality.focus() + 0.05).clamped();
        return this;
    }

    private Behavior<Command> onDetectLanguage(DetectLanguage cmd) {
        var adapter = getContext().messageAdapter(
            InferenceRouter.InferResponse.class,
            resp -> new InferenceResponseReceived(resp, new PendingDetection(cmd)));

        // Route via capability "quick" (4B voice backend, ~220ms) — matches the
        // tuning CompanionActor used when it self-rolled this call, so wiring
        // detection through this actor costs no latency (W5, 2026-07-11).
        inferenceRouter.tell(new InferenceRouter.ToolInferRequest(
            "detect-" + translationCount++,
            "translator",
            "quick",
            null,
            TranslationPrompts.systemPrompt(
                TranslationPrompts.TranslationType.DETECT, "", ""),
            cmd.text(),
            TranslationPrompts.maxTokens(TranslationPrompts.TranslationType.DETECT),
            adapter));

        state = State.TRANSLATING;
        return this;
    }

    private Behavior<Command> onInferenceResponse(InferenceResponseReceived msg) {
        state = State.IDLE;

        switch (msg.response()) {
            case InferenceRouter.InferOk ok -> {
                switch (msg.pending()) {
                    case PendingTranslation pt -> {
                        var translated = ok.content().strip();
                        var cmd = pt.request();

                        // Store in translation memory
                        lexiconService.registerTranslation(
                            cmd.text(), cmd.targetLang(), translated, "translator");

                        vitality = vitality.withConfidence(vitality.confidence() + 0.05)
                            .withAlignment(vitality.alignment() + 0.1)
                            .withErrorPressure(vitality.errorPressure() - 0.1);
                        log.debug("Translation complete: {} ({} -> {})",
                            cmd.text().substring(0, Math.min(30, cmd.text().length())),
                            cmd.sourceLang(), cmd.targetLang());

                        cmd.replyTo().tell(new TranslationResult(
                            cmd.text(), translated,
                            cmd.sourceLang(), cmd.targetLang(), 0.9));
                    }
                    case PendingDetection pd -> {
                        var detected = ok.content().strip().toLowerCase();
                        pd.request().replyTo().tell(detected);
                    }
                }
            }
            case InferenceRouter.InferError err -> {
                vitality = vitality.withConfidence(vitality.confidence() - 0.1)
                    .withAlignment(vitality.alignment() - 0.1)
                    .withErrorPressure(vitality.errorPressure() + 0.2);
                log.warn("Translation inference failed: {}", err.error());

                switch (msg.pending()) {
                    case PendingTranslation pt -> pt.request().replyTo().tell(
                        new TranslationResult(
                            pt.request().text(), pt.request().text(),
                            pt.request().sourceLang(), pt.request().targetLang(), 0.0));
                    case PendingDetection pd -> pd.request().replyTo().tell("unknown");
                }
            }
        }
        return this;
    }

    private Behavior<Command> onVitalityTick(VitalityTick tick) {
        vitality = vitality.tick();
        return this;
    }

    // --- Accessors (for testing) ---

    public VitalityState getVitality() { return vitality; }
    public State getState() { return state; }
    public int getTranslationCount() { return translationCount; }
}
