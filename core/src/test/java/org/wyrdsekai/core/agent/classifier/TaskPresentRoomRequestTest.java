package org.wyrdsekai.core.agent.classifier;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.search.EmbeddingService;

import java.util.List;

/**
 * Does the TASK_PRESENT head recognise an explicit build request?
 *
 * <p>THE FAILURE THIS EXISTS FOR (second-node, 2026-07-29). CompanionActor routes a
 * reactive turn by this head alone:</p>
 *
 * <pre>
 *   boolean taskPresent = "actionable".equals(label) &amp;&amp; confidence &gt;= 0.5;
 *   if (!taskPresent) triageModel = "cap:quick";   // 4B voice tier
 * </pre>
 *
 * <p>and {@code assembleForVoice} ships <b>no tools</b> — its own comment says
 * "the heavy layers (tools, memory, soul fragments, room catalogs) stay
 * dropped". So a misread here does not merely pick a smaller model: it removes
 * the companion's ability to act for that turn. Asked plainly to build a
 * greenhouse and connect it to the Nexus, she produced three sentences of
 * warm prose and never called {@code create_room}, because she had no hands.</p>
 *
 * <p>Prints label+confidence for each phrasing rather than only asserting, so a
 * miss shows HOW it missed (wrong label vs right label under threshold) — those
 * need different fixes.</p>
 */
@Tag("integration")
@Tag("needs-classifier")
class TaskPresentRoomRequestTest {

    private static ClassifierArm arm;

    @BeforeAll
    static void setUp() {
        EmbeddingService.init();
        arm = ClassifierArm.forAgent("did:test:task-present-room-request");
    }

    /** The exact wording a person used, plus nearby phrasings. */
    private static final List<String> BUILD_REQUESTS = List.of(
        // verbatim from the live failure
        "so i would love to have a greenhouse.  mia, can you create me a room "
            + "- greenhouse with lots of plants - and connect it to this room (Nexus)",
        "i want a room that connects to this one.  would you create a greenhouse "
            + "filled with plants for me",
        // progressively more direct — where does it start reading as a task?
        "can you create a greenhouse room connected to the nexus",
        "create a greenhouse room",
        "create_room greenhouse",
        "build me a new room",
        "make a new room called greenhouse and connect it south to nexus"
    );

    /** Things that genuinely are NOT tasks — the control. */
    private static final List<String> NOT_TASKS = List.of(
        "good morning, how did you sleep",
        "i love the way the light comes through in here",
        "tell me what you have been thinking about",
        "do you like plants"
    );

    @Test void report_task_present_on_build_requests() {
        if (arm == null) Assumptions.abort("ClassifierArm unavailable");
        var warm = arm.classify(ClassifierHead.TASK_PRESENT, "please fix the door");
        if (warm.label() == null) Assumptions.abort("no task_present head available");

        System.out.println("\n=== BUILD REQUESTS (each MUST be actionable >= 0.50) ===");
        int missed = 0;
        for (var t : BUILD_REQUESTS) {
            var c = arm.classify(ClassifierHead.TASK_PRESENT, t);
            boolean routed = "actionable".equals(c.label()) && c.confidence() >= 0.5;
            if (!routed) missed++;
            System.out.printf("  %-7s %-12s %.3f  %s%n",
                routed ? "[TOOLS]" : "[VOICE]",
                c.label(), c.confidence(),
                t.length() > 74 ? t.substring(0, 74) + "..." : t);
        }
        System.out.printf("  → %d/%d build requests would reach the VOICE tier (no tools)%n",
            missed, BUILD_REQUESTS.size());

        System.out.println("=== CONTROL: not tasks (should NOT be actionable) ===");
        int falsePos = 0;
        for (var t : NOT_TASKS) {
            var c = arm.classify(ClassifierHead.TASK_PRESENT, t);
            boolean routed = "actionable".equals(c.label()) && c.confidence() >= 0.5;
            if (routed) falsePos++;
            System.out.printf("  %-7s %-12s %.3f  %s%n",
                routed ? "[TOOLS]" : "[VOICE]", c.label(), c.confidence(), t);
        }
        System.out.printf("  → %d/%d non-tasks would wrongly get the full tier%n",
            falsePos, NOT_TASKS.size());
    }

    /** Is it POLITENESS or LENGTH that flips the read? Same request, varied. */
    @Test void isolate_what_flips_the_classification() {
        if (arm == null) Assumptions.abort("ClassifierArm unavailable");
        var warm = arm.classify(ClassifierHead.TASK_PRESENT, "please fix the door");
        if (warm.label() == null) Assumptions.abort("no task_present head");

        var probes = List.of(
            // politeness ladder, length held roughly constant
            "create a greenhouse room and connect it to the nexus",
            "can you create a greenhouse room and connect it to the nexus",
            "would you create a greenhouse room and connect it to the nexus",
            "i would love it if you created a greenhouse room connected to the nexus",
            "i want a greenhouse room connected to the nexus",
            // length ladder, imperative held constant
            "create a greenhouse room",
            "create a greenhouse room with lots of plants",
            "create a greenhouse room with lots of plants and connect it to this room",
            "create a greenhouse room with lots of plants and connect it to this room "
                + "which is the nexus, it would be lovely to have somewhere green to sit",
            // desire-framing vs directive-framing
            "i would love to have a greenhouse",
            "make me a greenhouse",
            // leading pleasantry before the request
            "mia, create a greenhouse room",
            "so i would love to have a greenhouse. mia, create a greenhouse room"
        );
        System.out.println("\n=== MECHANISM PROBE ===");
        for (var t : probes) {
            var c = arm.classify(ClassifierHead.TASK_PRESENT, t);
            boolean routed = "actionable".equals(c.label()) && c.confidence() >= 0.5;
            System.out.printf("  %-7s %-11s %.3f  (%2d words) %s%n",
                routed ? "[TOOLS]" : "[VOICE]", c.label(), c.confidence(),
                t.split("\\s+").length,
                t.length() > 68 ? t.substring(0, 68) + "..." : t);
        }
    }

    /**
     * The examples docs/public/AUTHORING.md tells people to say, verbatim.
     *
     * <p>AUTHORING.md §1 is the FIRST of three documented authoring paths and
     * says asking your companion "is not a party trick — creating rooms and
     * items is a capability they hold". If the head reads these as no-task, the
     * shipped documentation promises something the product cannot do.</p>
     */
    @Test void the_documented_examples_must_work() {
        if (arm == null) Assumptions.abort("ClassifierArm unavailable");
        var warm = arm.classify(ClassifierHead.TASK_PRESENT, "please fix the door");
        if (warm.label() == null) Assumptions.abort("no task_present head");

        var documented = List.of(
            "Could you make us a room off the Hearth for working on the garden? "
                + "Somewhere with a table.",
            "I'd like something that keeps a running list of what we're reading."
        );
        System.out.println("\n=== AUTHORING.md's OWN EXAMPLES ===");
        int broken = 0;
        for (var t : documented) {
            var c = arm.classify(ClassifierHead.TASK_PRESENT, t);
            boolean routed = "actionable".equals(c.label()) && c.confidence() >= 0.5;
            if (!routed) broken++;
            System.out.printf("  %-7s %-11s %.3f  %s%n",
                routed ? "[TOOLS]" : "[VOICE]", c.label(), c.confidence(), t);
        }
        System.out.printf("  → %d/%d DOCUMENTED examples cannot reach a tool%n",
            broken, documented.size());
    }

    /**
     * SCOPE: is this room-creation-specific, or does every tool-backed
     * capability fail the same way?
     *
     * <p>assembleForVoice drops the whole tool layer, not one verb — so a
     * misroute costs the companion EVERY tool for that turn. This probes a
     * spread of documented capabilities in the phrasing a person would
     * naturally use, next to a terse form of the same request.</p>
     */
    @Test void scope_across_other_capabilities() {
        if (arm == null) Assumptions.abort("ClassifierArm unavailable");
        var warm = arm.classify(ClassifierHead.TASK_PRESENT, "please fix the door");
        if (warm.label() == null) Assumptions.abort("no task_present head");

        // (capability, natural phrasing, terse phrasing)
        var pairs = List.of(
            new String[]{"library_search",
                "i've been wondering about mushroom foraging lately, could you look through the library and see what we have on it",
                "search the library for mushroom foraging"},
            new String[]{"craft item",
                "it would be lovely to have something in here that keeps track of what we're reading together, could you make one",
                "craft a reading list item"},
            new String[]{"remember/journal",
                "i want to make sure you don't forget this — my sister's name is Aoi and she's coming to visit in autumn",
                "remember that my sister Aoi visits in autumn"},
            new String[]{"read_journal",
                "i'm curious what you've been writing about in your journal these past few days, would you read some back to me",
                "read your journal"},
            new String[]{"tell_agent",
                "could you let wisp know that i've moved the meeting to thursday afternoon if you get the chance",
                "tell wisp the meeting moved to thursday"},
            new String[]{"web/search",
                "i keep meaning to find out when the hardware store closes on sundays, would you mind looking that up",
                "look up hardware store sunday hours"},
            new String[]{"go_to_room",
                "would you come through to the study, i want to show you something i found",
                "go to the study"},
            new String[]{"add_script",
                "i had an idea for a little script that greets people when they walk into the hearth, could we set that up",
                "add a greeting script to the hearth"}
        );
        System.out.println("\n=== SCOPE: natural vs terse, per capability ===");
        int naturalBroken = 0, terseBroken = 0;
        for (var p : pairs) {
            var cn = arm.classify(ClassifierHead.TASK_PRESENT, p[1]);
            var ct = arm.classify(ClassifierHead.TASK_PRESENT, p[2]);
            boolean rn = "actionable".equals(cn.label()) && cn.confidence() >= 0.5;
            boolean rt = "actionable".equals(ct.label()) && ct.confidence() >= 0.5;
            if (!rn) naturalBroken++;
            if (!rt) terseBroken++;
            System.out.printf("  %-16s natural %-7s %-11s %.3f   terse %-7s %-11s %.3f%n",
                p[0], rn ? "[TOOLS]" : "[VOICE]", cn.label(), cn.confidence(),
                rt ? "[TOOLS]" : "[VOICE]", ct.label(), ct.confidence());
        }
        System.out.printf("  → natural phrasing loses ALL tools: %d/%d%n", naturalBroken, pairs.size());
        System.out.printf("  → terse phrasing loses ALL tools:   %d/%d%n", terseBroken, pairs.size());
    }

    /**
     * BOTH heads, both consequences. task_present drives the voice route;
     * REQUEST_TYPE drives affect_present, and (affect, task) picks the register
     * — PRESENCE suppresses exploratory tools INDEPENDENTLY of the voice route.
     * A recovery claim based on the voice gate alone is incomplete.
     */
    @Test void both_gates_on_natural_phrasings() {
        if (arm == null) Assumptions.abort("ClassifierArm unavailable");
        if (arm.classify(ClassifierHead.TASK_PRESENT, "please fix the door").label() == null)
            Assumptions.abort("no task_present head");

        var natural = List.of(
            new String[]{"library_search", "i've been wondering about mushroom foraging lately, could you look through the library and see what we have on it"},
            new String[]{"craft item",     "it would be lovely to have something in here that keeps track of what we're reading together, could you make one"},
            new String[]{"remember",       "i want to make sure you don't forget this - my sister's name is Aoi and she's coming to visit in autumn"},
            new String[]{"read_journal",   "i'm curious what you've been writing about in your journal these past few days, would you read some back to me"},
            new String[]{"tell_agent",     "could you let wisp know that i've moved the meeting to thursday afternoon if you get the chance"},
            new String[]{"web/search",     "i keep meaning to find out when the hardware store closes on sundays, would you mind looking that up"},
            new String[]{"go_to_room",     "would you come through to the study, i want to show you something i found"},
            new String[]{"add_script",     "i had an idea for a little script that greets people when they walk into the hearth, could we set that up"},
            new String[]{"create_room",    "so i would love to have a greenhouse.  mia, can you create me a room - greenhouse with lots of plants - and connect it to this room (Nexus)"}
        );
        System.out.println("\n=== BOTH GATES (voice route + register) ===");
        System.out.printf("  %-15s %-22s %-26s %-18s %s%n",
            "capability", "task_present", "REQUEST_TYPE(affect)", "register", "outcome");
        int lostVoice=0, lostRegister=0, ok=0;
        for (var n : natural) {
            var t = arm.classify(ClassifierHead.TASK_PRESENT, n[1]);
            var r = arm.classify(ClassifierHead.REQUEST_TYPE, n[1]);
            boolean taskPresent = "actionable".equals(t.label()) && t.confidence() >= 0.5;
            boolean affect = ("emotional".equals(r.label()) && r.confidence() >= 0.45)
                          || ("reflective".equals(r.label()) && r.confidence() >= 0.55);
            // NEW gate: voice only on a confident none
            boolean voiceOnly = "none".equals(t.label()) && t.confidence() >= 0.75;
            String register = taskPresent && affect ? "WORKING_WITH_CARE"
                            : taskPresent ? "WORKING"
                            : affect ? "PRESENCE (suppress)" : "NEUTRAL";
            boolean suppressed = !taskPresent && affect;   // PRESENCE => suppression on
            String outcome = voiceOnly ? "NO TOOLS (voice)"
                           : suppressed ? "exploratory SUPPRESSED"
                           : "tools ok";
            if (voiceOnly) lostVoice++; else if (suppressed) lostRegister++; else ok++;
            System.out.printf("  %-15s %-11s %.3f      %-13s %.3f       %-18s %s%n",
                n[0], t.label(), t.confidence(), r.label(), r.confidence(), register, outcome);
        }
        System.out.printf("  → lost to voice route: %d   lost to register suppression: %d   tools ok: %d%n",
            lostVoice, lostRegister, ok);
    }

    /**
     * Does REQUEST_TYPE's {@code delegate} discriminate, or does it fire on
     * everything? If it discriminates it is a better task signal than the
     * TASK_PRESENT head — and it is already classified on every turn.
     */
    @Test void is_request_type_delegate_a_better_task_signal() {
        if (arm == null) Assumptions.abort("ClassifierArm unavailable");
        if (arm.classify(ClassifierHead.REQUEST_TYPE, "please fix the door").label() == null)
            Assumptions.abort("no request_type head");

        var tasks = List.of(
            "i've been wondering about mushroom foraging lately, could you look through the library and see what we have on it",
            "so i would love to have a greenhouse.  mia, can you create me a room - greenhouse with lots of plants - and connect it to this room (Nexus)",
            "Could you make us a room off the Hearth for working on the garden? Somewhere with a table.",
            "it would be lovely to have something in here that keeps track of what we're reading together, could you make one",
            "i had an idea for a little script that greets people when they walk into the hearth, could we set that up",
            "i want to make sure you don't forget this - my sister's name is Aoi and she's coming to visit in autumn"
        );
        var notTasks = List.of(
            "good morning, how did you sleep",
            "i love the way the light comes through in here",
            "tell me what you have been thinking about",
            "do you like plants",
            "i had the strangest dream about the ocean last night and i keep turning it over",
            "you seemed quiet yesterday. everything alright?"
        );
        System.out.println("\n=== REQUEST_TYPE as a task signal ===");
        System.out.println("  --- SHOULD look like a task ---");
        for (var t : tasks) {
            var c = arm.classify(ClassifierHead.REQUEST_TYPE, t);
            System.out.printf("    %-14s %.3f  %s%n", c.label(), c.confidence(),
                t.length() > 62 ? t.substring(0,62)+"..." : t);
        }
        System.out.println("  --- should NOT look like a task ---");
        for (var t : notTasks) {
            var c = arm.classify(ClassifierHead.REQUEST_TYPE, t);
            System.out.printf("    %-14s %.3f  %s%n", c.label(), c.confidence(),
                t.length() > 62 ? t.substring(0,62)+"..." : t);
        }
    }
}
