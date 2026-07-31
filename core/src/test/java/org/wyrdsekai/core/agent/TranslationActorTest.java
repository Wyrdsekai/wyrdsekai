package org.wyrdsekai.core.agent;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.*;
import org.wyrdsekai.core.inference.InferenceRouter;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TranslationActorTest {

    private static ActorTestKit testKit;
    private LexiconService lexiconService;

    @BeforeAll static void setupKit() {
        testKit = ActorTestKit.create();
    }

    @AfterAll static void tearDownKit() {
        testKit.shutdownTestKit();
    }

    @BeforeEach void setUp() {
        lexiconService = new LexiconService();
    }

    // ── Translation Tests ──

    @Test void translate_text_routes_to_inference() {
        var inferProbe = testKit.createTestProbe(InferenceRouter.Command.class);
        var resultProbe = testKit.createTestProbe(TranslationActor.TranslationResult.class);

        var actor = testKit.spawn(TranslationActor.create(inferProbe.ref(), lexiconService));
        actor.tell(new TranslationActor.TranslateText(
            "Hello world", "en", "es",
            TranslationPrompts.TranslationType.PROSE, resultProbe.ref()));

        var request = inferProbe.expectMessageClass(InferenceRouter.InferRequest.class);
        assertThat(request.systemPrompt()).contains("es");
        assertThat(request.userMessage()).isEqualTo("Hello world");
    }

    @Test void translate_text_returns_result_on_success() {
        var inferProbe = testKit.createTestProbe(InferenceRouter.Command.class);
        var resultProbe = testKit.createTestProbe(TranslationActor.TranslationResult.class);

        var actor = testKit.spawn(TranslationActor.create(inferProbe.ref(), lexiconService));
        actor.tell(new TranslationActor.TranslateText(
            "Hello", "en", "es",
            TranslationPrompts.TranslationType.HINT, resultProbe.ref()));

        var request = inferProbe.expectMessageClass(InferenceRouter.InferRequest.class);
        // Simulate inference response
        request.replyTo().tell(new InferenceRouter.InferOk(
            request.requestId(), "Hola", 10, 5));

        var result = resultProbe.expectMessageClass(TranslationActor.TranslationResult.class);
        assertThat(result.original()).isEqualTo("Hello");
        assertThat(result.translated()).isEqualTo("Hola");
        assertThat(result.sourceLang()).isEqualTo("en");
        assertThat(result.targetLang()).isEqualTo("es");
        assertThat(result.confidence()).isGreaterThan(0.0);
    }

    @Test void translate_text_stores_in_translation_memory() {
        var inferProbe = testKit.createTestProbe(InferenceRouter.Command.class);
        var resultProbe = testKit.createTestProbe(TranslationActor.TranslationResult.class);

        var actor = testKit.spawn(TranslationActor.create(inferProbe.ref(), lexiconService));
        actor.tell(new TranslationActor.TranslateText(
            "Goodbye", "en", "ja",
            TranslationPrompts.TranslationType.PROSE, resultProbe.ref()));

        var request = inferProbe.expectMessageClass(InferenceRouter.InferRequest.class);
        request.replyTo().tell(new InferenceRouter.InferOk(
            request.requestId(), "\u3055\u3088\u3046\u306a\u3089", 10, 5));

        resultProbe.expectMessageClass(TranslationActor.TranslationResult.class);

        // Verify translation memory
        var cached = lexiconService.getTranslation("goodbye", "ja");
        assertThat(cached).isPresent();
        assertThat(cached.get()).isEqualTo("\u3055\u3088\u3046\u306a\u3089");
    }

    @Test void translate_text_uses_cache_on_second_request() {
        var inferProbe = testKit.createTestProbe(InferenceRouter.Command.class);
        var resultProbe = testKit.createTestProbe(TranslationActor.TranslationResult.class);

        // Pre-populate translation memory
        lexiconService.registerTranslation("hello", "es", "Hola", "translator");

        var actor = testKit.spawn(TranslationActor.create(inferProbe.ref(), lexiconService));
        actor.tell(new TranslationActor.TranslateText(
            "hello", "en", "es",
            TranslationPrompts.TranslationType.HINT, resultProbe.ref()));

        // Should NOT route to inference (cache hit)
        inferProbe.expectNoMessage(Duration.ofMillis(200));

        var result = resultProbe.expectMessageClass(TranslationActor.TranslationResult.class);
        assertThat(result.translated()).isEqualTo("Hola");
        assertThat(result.confidence()).isEqualTo(1.0);
    }

    @Test void translate_text_returns_original_on_error() {
        var inferProbe = testKit.createTestProbe(InferenceRouter.Command.class);
        var resultProbe = testKit.createTestProbe(TranslationActor.TranslationResult.class);

        var actor = testKit.spawn(TranslationActor.create(inferProbe.ref(), lexiconService));
        actor.tell(new TranslationActor.TranslateText(
            "Test", "en", "de",
            TranslationPrompts.TranslationType.COMMAND, resultProbe.ref()));

        var request = inferProbe.expectMessageClass(InferenceRouter.InferRequest.class);
        request.replyTo().tell(new InferenceRouter.InferError(
            request.requestId(), "Backend unavailable"));

        var result = resultProbe.expectMessageClass(TranslationActor.TranslationResult.class);
        assertThat(result.translated()).isEqualTo("Test"); // returns original
        assertThat(result.confidence()).isEqualTo(0.0);
    }

    // ── Language Detection Tests ──

    @Test void detect_language_routes_to_inference() {
        var inferProbe = testKit.createTestProbe(InferenceRouter.Command.class);
        var langProbe = testKit.createTestProbe(String.class);

        var actor = testKit.spawn(TranslationActor.create(inferProbe.ref(), lexiconService));
        actor.tell(new TranslationActor.DetectLanguage("Bonjour le monde", langProbe.ref()));

        // Detection routes via ToolInferRequest capability "quick" (4B voice
        // backend) — same tuning CompanionActor used before W5 rerouted it here.
        var request = inferProbe.expectMessageClass(InferenceRouter.ToolInferRequest.class);
        assertThat(request.systemPrompt()).contains("language");
        assertThat(request.capability()).isEqualTo("quick");
    }

    @Test void detect_language_returns_tag_on_success() {
        var inferProbe = testKit.createTestProbe(InferenceRouter.Command.class);
        var langProbe = testKit.createTestProbe(String.class);

        var actor = testKit.spawn(TranslationActor.create(inferProbe.ref(), lexiconService));
        actor.tell(new TranslationActor.DetectLanguage("Hola mundo", langProbe.ref()));

        var request = inferProbe.expectMessageClass(InferenceRouter.ToolInferRequest.class);
        request.replyTo().tell(new InferenceRouter.InferOk(
            request.requestId(), "es", 5, 2));

        assertThat(langProbe.expectMessageClass(String.class)).isEqualTo("es");
    }

    @Test void detect_language_returns_unknown_on_error() {
        var inferProbe = testKit.createTestProbe(InferenceRouter.Command.class);
        var langProbe = testKit.createTestProbe(String.class);

        var actor = testKit.spawn(TranslationActor.create(inferProbe.ref(), lexiconService));
        actor.tell(new TranslationActor.DetectLanguage("???", langProbe.ref()));

        var request = inferProbe.expectMessageClass(InferenceRouter.ToolInferRequest.class);
        request.replyTo().tell(new InferenceRouter.InferError(
            request.requestId(), "Failed"));

        assertThat(langProbe.expectMessageClass(String.class)).isEqualTo("unknown");
    }

    // ── Prompt Template Tests ──

    @Test void translation_prompts_prose_has_correct_temperature() {
        assertThat(TranslationPrompts.temperature(TranslationPrompts.TranslationType.PROSE))
            .isEqualTo(0.7);
        assertThat(TranslationPrompts.temperature(TranslationPrompts.TranslationType.COMMAND))
            .isEqualTo(0.2);
        assertThat(TranslationPrompts.temperature(TranslationPrompts.TranslationType.DETECT))
            .isEqualTo(0.1);
        assertThat(TranslationPrompts.temperature(TranslationPrompts.TranslationType.HINT))
            .isEqualTo(0.3);
    }

    @Test void translation_prompts_system_prompt_formats() {
        var prompt = TranslationPrompts.systemPrompt(
            TranslationPrompts.TranslationType.PROSE, "English", "Spanish");
        assertThat(prompt).contains("English").contains("Spanish").contains("MUD");
    }

    @Test void translation_prompts_locale_context() {
        var ctx = TranslationPrompts.localeContext("Spanish", "es", 42);
        assertThat(ctx).contains("Spanish").contains("es").contains("42");
    }
}
