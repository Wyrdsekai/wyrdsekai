package org.wyrdsekai.scripting.codemode;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track A Phase 1 — runtime smoke for {@link CodeModeExecutor}.
 *
 * <p>Asserts: console capture, error surface, timeout, namespace dispatch,
 * console.error tagging, and host-class lockdown.</p>
 */
class CodeModeExecutorTest {

    @Test
    void simple_console_log_returns_log() {
        var result = CodeModeExecutor.run(
            "console.log('hello'); console.log('world');",
            Map.of());
        assertThat(result.success()).isTrue();
        assertThat(result.log()).containsExactly("hello", "world");
    }

    @Test
    void script_error_returns_failure() {
        var result = CodeModeExecutor.run(
            "throw new Error('boom');",
            Map.of());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("boom");
    }

    @Test
    void timeout_fires_on_infinite_loop() {
        long start = System.currentTimeMillis();
        var result = CodeModeExecutor.run(
            "while(true){}",
            Map.of(),
            1_000);
        long elapsed = System.currentTimeMillis() - start;
        assertThat(result.success()).isFalse();
        assertThat(result.error()).containsAnyOf("timed out", "timeout", "cancel");
        // Allow generous slack — timeout firing should land within 5x cap.
        assertThat(elapsed).isLessThan(5_500);
    }

    @Test
    void namespace_method_invocation_reflected_in_log() {
        var captured = new AtomicReference<Object[]>();
        Function<Object[], Object> echo = args -> {
            captured.set(args);
            return Map.of("ok", true, "echo", args.length > 0 ? args[0] : null);
        };
        var ns = new LinkedHashMap<String, Map<String, Function<Object[], Object>>>();
        ns.put("library_card", Map.of("invoke", echo));

        var result = CodeModeExecutor.run("""
            const r = library_card.invoke('mythology');
            console.log('ok:', r.ok);
            """, ns);

        assertThat(result.success()).isTrue();
        assertThat(captured.get()).isNotNull();
        assertThat(result.log()).anyMatch(line -> line.contains("ok: true"));
    }

    @Test
    void console_error_lands_with_marker() {
        var result = CodeModeExecutor.run(
            "console.log('one'); console.error('boom'); console.warn('hmm');",
            Map.of());
        assertThat(result.success()).isTrue();
        assertThat(result.log()).hasSize(3);
        assertThat(result.log().get(0)).isEqualTo("one");
        assertThat(result.log().get(1)).startsWith("[error]").contains("boom");
        assertThat(result.log().get(2)).startsWith("[warn]").contains("hmm");
    }

    @Test
    void host_class_access_blocked_does_not_kill_jvm() {
        // Java.type lookup must fail inside the isolate. We don't care about
        // the exact error string — only that the JVM remains alive (the
        // assertion runs after, which requires the test process to live).
        var result = CodeModeExecutor.run("""
            try {
                var SystemCls = Java.type('java.lang.System');
                SystemCls.exit(1);
                console.log('reached-exit-call');
            } catch (e) {
                console.log('blocked');
            }
            """, Map.of());

        // We must still be running. Either the script completed with the
        // 'blocked' branch, or the eval threw — both are fine. The forbidden
        // outcome is JVM exit, which we'd never observe here.
        assertThat(result).isNotNull();
        if (result.success()) {
            assertThat(result.log()).noneMatch(line -> line.contains("reached-exit-call"));
        }
    }

    @Test
    void namespace_method_returning_complex_object_round_trips() {
        Function<Object[], Object> rich = args -> Map.of(
            "findings", "stuff",
            "sources", List.of("a", "b", "c"));
        var ns = Map.of("library_card", Map.of("invoke", rich));

        var result = CodeModeExecutor.run("""
            const r = library_card.invoke({query: 'x'});
            console.log('count:', r.sources.length);
            console.log('first:', r.sources[0]);
            """, ns);

        assertThat(result.success()).isTrue();
        assertThat(result.log()).contains("count: 3", "first: a");
    }

    @Test
    void script_return_value_is_captured() {
        var result = CodeModeExecutor.run(
            "return {answer: 42};",
            Map.of());
        assertThat(result.success()).isTrue();
        assertThat(result.returnValue()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var ret = (Map<String, Object>) result.returnValue();
        assertThat(ret).containsEntry("answer", 42);
    }

    @Test
    void empty_script_fails_gracefully() {
        var result = CodeModeExecutor.run("", Map.of());
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("empty");
    }

    @Test
    void undefined_namespace_method_surfaces_as_script_error() {
        var ns = Map.of("library_card",
            Map.<String, Function<Object[], Object>>of(
                "invoke", a -> "ok"));
        var result = CodeModeExecutor.run(
            "library_card.bogus_method('hi');",
            ns);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isNotNull();
    }
}
