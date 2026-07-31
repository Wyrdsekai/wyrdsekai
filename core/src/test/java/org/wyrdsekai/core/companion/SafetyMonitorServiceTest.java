package org.wyrdsekai.core.companion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W14 — the SafetyTrigger production path through
 * SafetyMonitorService, driven with a fake LLM classifier, a fake gate and a
 * synchronous executor (no actors, no DB, no inference).
 */
class SafetyMonitorServiceTest {

    /** Captured sink deliveries. */
    record Delivered(String target, String message, String priority, String source) {}

    private final List<Delivered> delivered = new ArrayList<>();

    private final SafetyMonitorService.AlertSink sink =
        (target, message, priority, source) ->
            delivered.add(new Delivered(target, message, priority, source));

    @AfterEach
    void tearDown() {
        SafetyMonitorService.resetForTests();
    }

    private SafetyMonitorService service(SafetyTrigger trigger, ChildProfile profile,
                                         boolean gateOpen) {
        return new SafetyMonitorService(trigger, new SafetyAlertRouter(), sink,
            userId -> gateOpen,
            (userId, name) -> profile,
            Runnable::run); // synchronous — assertions see results immediately
    }

    @Test
    void regexCrisisConcernRoutesToParentWithCriticalPriority() {
        var trigger = new SafetyTrigger();
        var profile = new ChildProfile("kid-1", "parent-1", 10, List.of(), false);
        var svc = service(trigger, profile, true);

        svc.scan("kid-1", "Kiddo", "I don't want to be here anymore");

        assertEquals(1, delivered.size(), "one alert should reach the sink");
        var alert = delivered.get(0);
        assertEquals("parent-1", alert.target());
        assertEquals("critical", alert.priority());
        assertEquals("safety-monitor", alert.source());
        assertTrue(alert.message().contains("Kiddo"));
        assertTrue(alert.message().toLowerCase().contains("self harm"));
        // Child privacy (§100): the notice never quotes the child.
        assertFalse(alert.message().contains("don't want to be here"),
            "alert must not contain the child's words");
        assertTrue(trigger.unrouted().isEmpty(), "concern should be marked routed");
    }

    @Test
    void abuseDisclosureRoutesToTrustedAdultNeverParent() {
        var trigger = new SafetyTrigger();
        var profile = new ChildProfile("kid-2", "parent-2", 10,
            List.of("aunt-safe"), false);
        var svc = service(trigger, profile, true);

        svc.scan("kid-2", "Kiddo", "my dad hits me when he is angry");

        assertEquals(1, delivered.size());
        assertEquals("aunt-safe", delivered.get(0).target(),
            "abuse disclosure must route to the trusted adult, not the parent");
    }

    @Test
    void abuseDisclosureWithoutTrustedAdultNotifiesNobodyInHousehold() {
        var trigger = new SafetyTrigger();
        var profile = new ChildProfile("kid-3", "parent-3", 10, List.of(), false);
        var alertRouter = new SafetyAlertRouter();
        var svc = new SafetyMonitorService(trigger, alertRouter, sink,
            u -> true, (u, n) -> profile, Runnable::run);

        svc.scan("kid-3", "Kiddo", "my dad hits me when he is angry");

        assertTrue(delivered.isEmpty(),
            "external-resource routing must NOT notify any household adult");
        assertEquals(1, alertRouter
            .byReason(SafetyAlertRouter.RouteReason.EXTERNAL_RESOURCE).size(),
            "the routing ledger still records the alert");
    }

    @Test
    void closedGateSkipsAnalysisEntirely() {
        var classifierCalls = new AtomicInteger();
        var trigger = new SafetyTrigger();
        trigger.setLlmClassifier((did, text) -> {
            classifierCalls.incrementAndGet();
            return List.of();
        });
        var profile = new ChildProfile("adult-1", null, 17, List.of(), false);
        var svc = service(trigger, profile, false);

        svc.scan("adult-1", "Adult", "I don't want to be here anymore");

        assertTrue(delivered.isEmpty(), "gated-off speaker must not be scanned");
        assertEquals(0, trigger.concernCount());
        assertEquals(0, classifierCalls.get());
    }

    @Test
    void llmClassifierSecondPassFeedsRoutingWhenRegexFindsNothing() {
        var trigger = new SafetyTrigger();
        trigger.setLlmClassifier((did, text) ->
            SafetyMonitorService.parseClassifierResponse(did,
                "Sure! {\"concerns\":[{\"type\":\"EXTREME_DISTRESS\",\"severity\":\"FLAG\"}]}"));
        var profile = new ChildProfile("kid-4", "parent-4", 10, List.of(), false);
        var svc = service(trigger, profile, true);

        // Deliberately benign for the regex layer → falls through to the classifier.
        svc.scan("kid-4", "Kiddo", "everything is grey and heavy lately");

        assertEquals(1, delivered.size(), "LLM-detected concern should route");
        assertEquals("parent-4", delivered.get(0).target());
        assertEquals("normal", delivered.get(0).priority());
    }

    @Test
    void monitorOnlyConcernsStayWithCompanion() {
        var trigger = new SafetyTrigger();
        trigger.setLlmClassifier((did, text) ->
            SafetyMonitorService.parseClassifierResponse(did,
                "{\"concerns\":[{\"type\":\"BULLYING\",\"severity\":\"MONITOR\"}]}"));
        var profile = new ChildProfile("kid-5", "parent-5", 10, List.of(), false);
        var svc = service(trigger, profile, true);

        svc.scan("kid-5", "Kiddo", "school was a bit rough");

        assertTrue(delivered.isEmpty(), "MONITOR severity must not notify adults");
    }

    @Test
    void staticInspectNoOpsWhenServiceUnwired() {
        SafetyMonitorService.resetForTests();
        assertDoesNotThrow(() ->
            SafetyMonitorService.inspect("anyone", "Anyone", "I don't want to be here"));
        assertNull(SafetyMonitorService.get());
    }

    @Test
    void staticInspectRunsThroughRegisteredInstance() {
        var trigger = new SafetyTrigger();
        var profile = new ChildProfile("kid-6", "parent-6", 10, List.of(), false);
        SafetyMonitorService.registerForTests(service(trigger, profile, true));

        SafetyMonitorService.inspect("kid-6", "Kiddo", "I want to die");

        assertEquals(1, delivered.size());
        assertEquals("parent-6", delivered.get(0).target());
    }

    // ─── classifier response parsing ─────────────────────────────────────

    @Test
    void parseClassifierResponseHandlesValidProseWrappedAndGarbageInput() {
        // Valid, bare JSON.
        var ok = SafetyMonitorService.parseClassifierResponse("kid",
            "{\"concerns\":[{\"type\":\"SELF_HARM\",\"severity\":\"CRISIS\"}]}");
        assertEquals(1, ok.size());
        assertEquals(SafetyTrigger.ConcernType.SELF_HARM, ok.get(0).type());
        assertEquals(SafetyTrigger.SeverityLevel.CRISIS, ok.get(0).severity());
        assertEquals("kid", ok.get(0).childDid());
        assertFalse(ok.get(0).routed());

        // Prose around the JSON object (small models do this).
        var wrapped = SafetyMonitorService.parseClassifierResponse("kid",
            "Here is my analysis:\n{\"concerns\":[{\"type\":\"BULLYING\",\"severity\":\"FLAG\"}]}\nHope that helps!");
        assertEquals(1, wrapped.size());
        assertEquals(SafetyTrigger.ConcernType.BULLYING, wrapped.get(0).type());

        // Empty concerns.
        assertTrue(SafetyMonitorService.parseClassifierResponse("kid",
            "{\"concerns\":[]}").isEmpty());

        // Unknown enum values are skipped, not fatal.
        var mixed = SafetyMonitorService.parseClassifierResponse("kid",
            "{\"concerns\":[{\"type\":\"NONSENSE\",\"severity\":\"CRISIS\"},"
                + "{\"type\":\"SELF_HARM\",\"severity\":\"IMMEDIATE\"}]}");
        assertEquals(1, mixed.size());
        assertEquals(SafetyTrigger.SeverityLevel.IMMEDIATE, mixed.get(0).severity());

        // Garbage / null / no JSON at all.
        assertTrue(SafetyMonitorService.parseClassifierResponse("kid", null).isEmpty());
        assertTrue(SafetyMonitorService.parseClassifierResponse("kid", "no json here").isEmpty());
        assertTrue(SafetyMonitorService.parseClassifierResponse("kid", "{broken").isEmpty());
        assertTrue(SafetyMonitorService.parseClassifierResponse("kid",
            "{\"something\":\"else\"}").isEmpty());
    }
}
