package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.security.DenialCatalog;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F13: hook stdout → structured Denial parsing. Uses inline shell
 * commands rather than tempfile scripts so the test doesn't depend on
 * filesystem permission semantics — the runner already invokes commands
 * via {@code sh -c} so any shell-parseable string works.
 */
class ActionHookRunnerDenialTest {

    private static final ActionHookRunner.HookPayload PAYLOAD =
        new ActionHookRunner.HookPayload(
            "PRE_ACTION", "agent-1", "Wyrd", "delegate",
            Map.of("target", "task-x"), "room-1", 1);

    @Test
    void textHookFallsBackToCatalogTemplate() {
        var runner = new ActionHookRunner(Map.of(
            ActionHookRunner.HookEvent.PRE_ACTION,
            List.of("cat >/dev/null; echo 'No, I refuse.'; exit 2")));

        var result = runner.run(ActionHookRunner.HookEvent.PRE_ACTION, PAYLOAD);
        assertFalse(result.allowed());
        assertEquals(2, result.exitCode());
        assertNotNull(result.denial());
        assertEquals(DenialCatalog.CODE_HOOK_DENIED, result.denial().code());
        assertTrue(result.denial().reason().contains("No, I refuse."),
            "text-fallback path uses raw stdout as the reason");
    }

    @Test
    void jsonHookEmitsStructuredDenial() {
        var json = "{\"deny\":true,"
            + "\"code\":\"fabricated_credential\","
            + "\"reason\":\"That endpoint mints credentials; the steward must do it.\","
            + "\"remediation\":\"Ask Masumi.\","
            + "\"cliHint\":{\"command\":\"wyrd relay register <url>\"},"
            + "\"inWorldResolution\":{\"action\":\"request_access\","
            + "\"source\":\"wyrd:relay/relay-node\",\"scope\":\"use\","
            + "\"reason\":\"Need a token to join the household.\"}}";
        // Single-quote-wrap the JSON for echo; no special chars inside need escaping.
        var runner = new ActionHookRunner(Map.of(
            ActionHookRunner.HookEvent.PRE_ACTION,
            List.of("cat >/dev/null; echo '" + json + "'; exit 2")));
        var result = runner.run(ActionHookRunner.HookEvent.PRE_ACTION, PAYLOAD);

        assertFalse(result.allowed());
        var d = result.denial();
        assertNotNull(d);
        assertEquals("fabricated_credential", d.code());
        assertEquals("Ask Masumi.", d.remediation());
        assertNotNull(d.cliHint());
        assertEquals("wyrd relay register <url>", d.cliHint().get("command"));
        assertNotNull(d.inWorldResolution());
        assertEquals("request_access", d.inWorldResolution().action());
        assertEquals("wyrd:relay/relay-node", d.inWorldResolution().source());
        assertEquals("use", d.inWorldResolution().scope());
    }

    @Test
    void malformedJsonFallsBackToText() {
        // starts with '{' but is not valid JSON — should fall back to text-template
        var runner = new ActionHookRunner(Map.of(
            ActionHookRunner.HookEvent.PRE_ACTION,
            List.of("cat >/dev/null; echo '{ this is not really json'; exit 2")));

        var result = runner.run(ActionHookRunner.HookEvent.PRE_ACTION, PAYLOAD);
        assertFalse(result.allowed());
        assertNotNull(result.denial());
        assertEquals(DenialCatalog.CODE_HOOK_DENIED, result.denial().code(),
            "malformed JSON degrades to plain hook_denied, doesn't crash");
    }

    @Test
    void allowingHookProducesNoDenial() {
        var runner = new ActionHookRunner(Map.of(
            ActionHookRunner.HookEvent.PRE_ACTION, List.of("cat >/dev/null; exit 0")));
        var result = runner.run(ActionHookRunner.HookEvent.PRE_ACTION, PAYLOAD);
        assertTrue(result.allowed());
        assertNull(result.denial());
    }
}
