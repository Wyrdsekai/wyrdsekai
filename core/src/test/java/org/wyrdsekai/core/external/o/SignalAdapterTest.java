package org.wyrdsekai.core.external.o;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SignalAdapterTest {

    private FakeCreds creds;
    private FakeRunner runner;

    @BeforeEach
    void setup() {
        creds = new FakeCreds();
        runner = new FakeRunner();
    }

    private SignalAdapter adapter() {
        return new SignalAdapter(creds, runner);
    }

    @Test
    void namespace_caps() {
        var a = adapter();
        assertEquals("signal", a.namespace());
        assertEquals("signal.account", a.credentialSlot());
        assertTrue(a.capabilities().contains("send"));
        assertTrue(a.capabilities().contains("send_group"));
    }

    @Test
    void send_happy_path() {
        creds.put("signal.account", "+15551234");
        runner.nextResponse = AdapterResponse.ok(Map.of("exitCode", 0, "stdout", ""));
        var resp = adapter().invoke(AdapterRequest.of("signal", "send",
            Map.of("recipient", "+15559999", "text", "hi")));
        assertTrue(resp.success());
        assertEquals(List.of("signal-cli", "-u", "+15551234", "send",
            "-m", "hi", "+15559999"), runner.lastCommand);
    }

    @Test
    void send_group_uses_g_flag() {
        creds.put("signal.account", "+15551234");
        runner.nextResponse = AdapterResponse.ok(Map.of("exitCode", 0));
        var resp = adapter().invoke(AdapterRequest.of("signal", "send_group",
            Map.of("groupId", "GROUP1", "text", "hello team")));
        assertTrue(resp.success());
        assertTrue(runner.lastCommand.contains("-g"));
        assertTrue(runner.lastCommand.contains("GROUP1"));
    }

    @Test
    void send_missing_recipient() {
        creds.put("signal.account", "+15551234");
        var resp = adapter().invoke(AdapterRequest.of("signal", "send",
            Map.of("text", "hi")));
        assertFalse(resp.success());
        assertEquals("invalid_argument", resp.error().code());
    }

    @Test
    void send_missing_creds() {
        var resp = adapter().invoke(AdapterRequest.of("signal", "send",
            Map.of("recipient", "+15559999", "text", "hi")));
        assertFalse(resp.success());
        assertEquals("credentials_missing", resp.error().code());
    }

    @Test
    void process_failure_propagated() {
        creds.put("signal.account", "+15551234");
        runner.nextResponse = AdapterResponse.fail("process_failed",
            "exit=2: invalid recipient", false);
        var resp = adapter().invoke(AdapterRequest.of("signal", "send",
            Map.of("recipient", "garbage", "text", "hi")));
        assertFalse(resp.success());
        assertEquals("process_failed", resp.error().code());
    }

    @Test
    void process_timeout_marked_retryable() {
        creds.put("signal.account", "+15551234");
        runner.nextResponse = AdapterResponse.fail("process_timeout",
            "did not exit", true);
        var resp = adapter().invoke(AdapterRequest.of("signal", "send",
            Map.of("recipient", "+15551111", "text", "hi")));
        assertFalse(resp.success());
        assertTrue(resp.error().retryable());
    }

    @Test
    void unknown_method() {
        creds.put("signal.account", "+15551234");
        var resp = adapter().invoke(AdapterRequest.of("signal", "delete_account",
            Map.of()));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void send_blank_text_allowed() {
        creds.put("signal.account", "+15551234");
        runner.nextResponse = AdapterResponse.ok(Map.of("exitCode", 0));
        var resp = adapter().invoke(AdapterRequest.of("signal", "send",
            Map.of("recipient", "+15559999")));
        assertTrue(resp.success());
        assertTrue(runner.lastCommand.contains(""));
    }

    static final class FakeCreds implements Function<String, Optional<String>> {
        private final Map<String, String> values = new HashMap<>();
        void put(String k, String v) { values.put(k, v); }
        @Override public Optional<String> apply(String s) {
            return Optional.ofNullable(values.get(s));
        }
    }

    static final class FakeRunner implements SignalAdapter.ProcessRunner {
        AdapterResponse nextResponse = AdapterResponse.ok(Map.of());
        List<String> lastCommand;
        Duration lastTimeout;

        @Override
        public AdapterResponse run(List<String> command, Duration timeout) {
            this.lastCommand = command;
            this.lastTimeout = timeout;
            return nextResponse;
        }
    }
}
