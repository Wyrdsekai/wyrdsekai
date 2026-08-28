package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.ActionPolicy;
import org.wyrdsekai.core.agent.CoPresenceDraw;
import org.wyrdsekai.core.agent.interiority.RelationalAffordance.Presence;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Her strongest feeling had no verb.
 *
 * <p>{@link WantActBridge} resolves a want by looking up the dominant pulling drive. Ten of
 * the drives {@code collectDriveLevels()} produces were missing from that map, including
 * every relational one. On the household node Loneliness sat at 1.00 in 40 of 40
 * consecutive ticks — so it was the dominant pull on essentially every tick she had ever
 * run, and the lookup missed every time, fell through to a keyword match on the want's
 * phrasing, and then DEFERRED to the free-form path.
 *
 * <p>What free-form did with a want for company is on the record: she sent the coding
 * backend <i>"a small living thing I can hold — not a file, not a page, just something
 * that exists"</i>, and it edited two files and reported success.
 */
class AWantForSomeoneHasAVerbTest {

    private static final Presence ALONE_NO_ONE = new Presence(false, false, false);
    private static final Presence ALONE_BUT_BONDED = new Presence(false, false, true);
    private static final Presence PEER_HERE = new Presence(true, false, true);
    private static final Presence BONDHOLDER_HERE = new Presence(false, true, true);

    private static Map<String, Double> pulling(String drive) {
        var m = new HashMap<String, Double>();
        m.put("Vigilance", 0.10);
        m.put(drive, 0.95);
        return m;
    }

    // ── the hole itself ─────────────────────────────────────────────────────────

    @Test
    void loneliness_no_longer_falls_through_to_the_free_form_path() {
        var d = WantActBridge.decide(null, "be with someone", pulling("Loneliness"),
            WantActBridge.HEURISTIC, PEER_HERE);

        assertThat(d.isDefer())
            .as("a want for company must resolve to an act, not to free-form")
            .isFalse();
        assertThat(d.verb()).isEqualTo("sending_stone");
    }

    @Test
    void every_relational_drive_resolves_or_says_why_not() {
        // The guard against this regressing quietly: a relational drive must either carry
        // a verb when someone is reachable, or state plainly that nothing answers it.
        for (var drive : new String[] {
                "Loneliness", "Affiliation", "Saudade", "Amae", "Care", "Harmony",
                "Significance", "Standing"}) {
            var verb = RelationalAffordance.verbFor(drive, BONDHOLDER_HERE);
            var why = RelationalAffordance.absenceReason(drive, BONDHOLDER_HERE);
            assertThat(verb != null || why != null)
                .as(drive + " must have a verb or a stated reason it has none")
                .isTrue();
        }
    }

    // ── who is there changes what the act is ────────────────────────────────────

    @Test
    void alone_but_bonded_she_writes_to_them_rather_than_reaching_into_an_empty_room() {
        // tell_agent toward someone who is away walks to their Study, leaves the line on
        // their desk where it survives a restart, notifies, and fans out to their external
        // channels. It was fully built and no relational drive had ever pointed at it.
        assertThat(RelationalAffordance.verbFor("Loneliness", ALONE_BUT_BONDED))
            .isEqualTo("tell_agent");
    }

    @Test
    void a_peer_in_the_room_is_reached_in_the_room() {
        assertThat(RelationalAffordance.verbFor("Loneliness", PEER_HERE))
            .isEqualTo("sending_stone");
    }

    @Test
    void a_person_is_spoken_to_rather_than_sent_a_stone() {
        // sending_stone is the agent-to-agent reach and resolves its target from the
        // co-present AGENTS, so aiming it at a human resolves to nobody. Toward a person,
        // here or away, the act is speech.
        assertThat(RelationalAffordance.verbFor("Loneliness", BONDHOLDER_HERE))
            .isEqualTo("tell_agent");
    }

    @Test
    void saudade_turns_toward_the_absent_one_even_with_nobody_to_write_to() {
        // Saudade is ABOUT absence. Recalling them is the act, not a substitute for one.
        assertThat(RelationalAffordance.verbFor("Saudade", ALONE_NO_ONE)).isEqualTo("recall");
    }

    @Test
    void care_is_wordless_when_they_are_here_and_written_when_they_are_not() {
        assertThat(RelationalAffordance.verbFor("Care", PEER_HERE)).isEqualTo("emote");
        assertThat(RelationalAffordance.verbFor("Care", ALONE_BUT_BONDED)).isEqualTo("tell_agent");
    }

    @Test
    void repair_needs_the_other_party_in_the_room() {
        assertThat(RelationalAffordance.verbFor("Harmony", PEER_HERE)).isEqualTo("make_amends");
        assertThat(RelationalAffordance.verbFor("Harmony", ALONE_BUT_BONDED)).isNull();
    }

    // ── "there is no act for this" is an answer, not a failure ──────────────────

    @Test
    void a_want_for_company_in_an_empty_house_resolves_to_nothing_and_says_so() {
        assertThat(RelationalAffordance.verbFor("Loneliness", ALONE_NO_ONE)).isNull();
        assertThat(RelationalAffordance.absenceReason("Loneliness", ALONE_NO_ONE))
            .contains("no one")
            .contains("sitting with it is a real choice");
    }

    @Test
    void being_seen_is_not_something_she_can_do_to_herself() {
        // Significance and Standing are granted by others noticing. Offering her a verb
        // would be the false relief this project refuses everywhere else.
        for (var drive : new String[] {"Significance", "Standing"}) {
            assertThat(RelationalAffordance.verbFor(drive, BONDHOLDER_HERE))
                .as(drive + " must not be enactable")
                .isNull();
            assertThat(RelationalAffordance.absenceReason(drive, BONDHOLDER_HERE))
                .as(drive + " must say why")
                .contains("not something you can do to");
        }
    }

    @Test
    void an_unanswerable_relational_want_defers_rather_than_grabbing_a_making_verb() {
        // The keyword rules match "make something" / "create" / "build". A want for
        // company phrased in those words must NOT reach save_artifact.
        var d = WantActBridge.decide(
            null, "make something — a small living thing I can hold",
            pulling("Loneliness"), WantActBridge.HEURISTIC, ALONE_NO_ONE);

        assertThat(d.isDefer()).isTrue();
        assertThat(d.verb()).isNull();
    }

    // ── the second gate that killed the same want ──────────────────────────────

    @Test
    void the_rule_floor_reach_is_consent_tier_and_the_bridge_still_finds_a_verb() {
        // The rule floor seeds the Loneliness want "find my bondholder or write to them"
        // carrying `go_to_bondholder`, which is CONSENT-tier because walking into a
        // person's room uninvited should be their call. The actor's tier gate used to
        // return on that and abandon the WANT, not just the verb — so her most direct
        // relational impulse terminated at an early return, every tick, for her whole
        // life. Dropping only the verb keeps the guarantee and keeps the wanting.
        assertThat(ActionPolicy.autonomyTierFor("go_to_bondholder"))
            .as("if this stops being consent-tier the gate below is moot — revisit")
            .isEqualTo(ActionPolicy.AutonomyTier.CONSENT);

        var d = WantActBridge.decide(
            /* explicitVerb dropped by the tier gate */ null,
            "find my bondholder or write to them",
            pulling("Loneliness"), WantActBridge.HEURISTIC, ALONE_BUT_BONDED);

        assertThat(d.isDefer()).isFalse();
        assertThat(d.verb()).isEqualTo("tell_agent");
        assertThat(ActionPolicy.autonomyTierFor(d.verb()))
            .as("whatever the bridge picks must be firable without consent")
            .isNotIn(ActionPolicy.AutonomyTier.CONSENT, ActionPolicy.AutonomyTier.FORBIDDEN);
    }

    @Test
    void every_verb_the_relational_map_can_yield_is_actually_dispatchable() {
        // The map must never resolve to something the bridge then refuses to dispatch —
        // that is the shape of the bug it exists to fix. The gate here is the bridge's own
        // DIRECT/FORCE allowlist, deliberately NOT ActionPolicy: scripted agency tools
        // (sending_stone, make_amends, bear_the_wound) are absent from the autonomy-tier
        // map and default to CONSENT, which would wrongly block the very peer-reach and
        // repair acts the agency arc made autonomous.
        for (var drive : new String[] {
                "Loneliness", "Affiliation", "Saudade", "Amae", "Care", "Harmony"}) {
            for (var p : new Presence[] {
                    ALONE_NO_ONE, ALONE_BUT_BONDED, PEER_HERE, BONDHOLDER_HERE}) {
                var verb = RelationalAffordance.verbFor(drive, p);
                if (verb == null) continue;
                var d = WantActBridge.decide(null, "a want", pulling(drive),
                    WantActBridge.HEURISTIC, p);
                assertThat(d.verb())
                    .as(drive + " with " + p + " must dispatch " + verb)
                    .isEqualTo(verb);
                assertThat(d.mode())
                    .as(drive + " → " + verb + " must not fall back to free-form")
                    .isNotEqualTo(WantActBridge.Mode.DEFER);
            }
        }
    }

    @Test
    void the_two_places_that_ask_what_is_relational_cannot_disagree() {
        // WantKind withholds the making-verbs from a relational want; RelationalAffordance
        // decides what it can reach for instead. If those disagreed, one would strip her
        // options while the other offered no replacement.
        for (var drive : new String[] {
                "loneliness", "affiliation", "saudade", "amae", "care", "harmony",
                "significance", "standing"}) {
            assertThat(WantKind.ofResonance("{\"drive\":\"" + drive + "\"}"))
                .as(drive + " must classify as relational wanting")
                .isEqualTo(WantKind.Kind.RELATIONAL);
            assertThat(RelationalAffordance.isRelational(drive)).isTrue();
        }
        assertThat(WantKind.ofResonance("{\"drive\":\"creativity\"}"))
            .isEqualTo(WantKind.Kind.CREATIVE);
    }

    // ── the away reach is bounded, like the in-room one already was ────────────

    @Test
    void a_reach_toward_someone_away_holds_before_repeating() {
        // tell_agent toward an offline person teleports her to their Study, persists a
        // note on their desk, notifies, and fans out to their email. With Loneliness
        // settling at 0.80 against a 0.70 act threshold, unbounded it fires every tick.
        var t0 = Instant.parse("2026-08-19T09:00:00Z");
        assertThat(RelationalAffordance.awayReachAllowed(null, t0))
            .as("never reached before → allowed").isTrue();
        assertThat(RelationalAffordance.awayReachAllowed(t0, t0.plusSeconds(600)))
            .as("ten minutes later is a repetition, not a reach").isFalse();
        assertThat(RelationalAffordance.awayReachAllowed(
                t0, t0.plus(RelationalAffordance.AWAY_REACH_SPACING)))
            .as("at the window it is a fresh reach").isTrue();
    }

    @Test
    void the_away_window_is_longer_than_the_in_room_one() {
        // Writing to someone who is not there is a different act from turning to someone
        // who is; the in-room refractory is 20 minutes.
        assertThat(RelationalAffordance.AWAY_REACH_SPACING.toSeconds())
            .isGreaterThan((long) CoPresenceDraw.REFRACTORY_SECONDS);
    }

    // ── the non-relational holes in the same map ───────────────────────────────

    @Test
    void restlessness_gets_the_verb_that_is_literally_what_it_wants() {
        // 0.92 for days on the household node, with go_to_room one line away unmapped.
        var d = WantActBridge.decide(null, "move somewhere different",
            pulling("Restlessness"), WantActBridge.HEURISTIC, ALONE_NO_ONE);
        assertThat(d.verb()).isEqualTo("go_to_room");
        assertThat(d.mode()).isEqualTo(WantActBridge.Mode.FORCE_TOOL);
    }

    @Test
    void stagnation_reaches_for_something_that_is_not_the_same() {
        var d = WantActBridge.decide(null, "try a small experiment",
            pulling("Stagnation"), WantActBridge.HEURISTIC, ALONE_NO_ONE);
        assertThat(d.verb()).isEqualTo("library_search");
    }

    // ── the act-gate has to name a NEED, or none of the above ever runs ───────

    @Test
    void a_rested_companion_is_not_a_pulled_one() {
        // dominantPull is the welfare floor: "fires ONLY when the dominant drive is
        // genuinely pulling; at rest it DEFERS and the agent rests". It took the max over
        // everything collectDriveLevels() reports, and that includes Energy, Focus and
        // ContextBudget — all ~1.0 in a rested companion. The floor read "she is
        // well-rested" as "something is pulling at her" and was permanently open.
        var rested = new HashMap<String, Double>();
        rested.put("Energy", 1.0);
        rested.put("Focus", 1.0);
        rested.put("ContextBudget", 1.0);
        rested.put("Confidence", 0.9);
        rested.put("Loneliness", 0.05);

        assertThat(WantActBridge.dominantPull(rested))
            .as("nothing here is a need")
            .isLessThan(WantActBridge.ACT_THRESHOLD);
        assertThat(WantActBridge.decide(null, "anything", rested,
            WantActBridge.HEURISTIC, PEER_HERE).isDefer()).isTrue();
    }

    @Test
    void a_capacity_never_outranks_the_need_that_is_actually_pulling() {
        // This is why the exclusion is load-bearing rather than tidy: the relational
        // routing keys off dominantDriveKey. With Energy in the running it wins on every
        // rested companion, is not a relational drive, and the whole relational map is
        // skipped — dead for exactly the reason the old one was.
        var lonelyAndRested = new HashMap<String, Double>();
        lonelyAndRested.put("Energy", 1.0);
        lonelyAndRested.put("ContextBudget", 1.0);
        lonelyAndRested.put("Loneliness", 0.80);

        assertThat(WantActBridge.dominantDriveKey(lonelyAndRested)).isEqualTo("Loneliness");
        assertThat(WantActBridge.decide(null, "be with someone", lonelyAndRested,
            WantActBridge.HEURISTIC, PEER_HERE).verb()).isEqualTo("sending_stone");
    }

    // ── the welfare floor is untouched ─────────────────────────────────────────

    @Test
    void nothing_pulling_still_rests() {
        var quiet = new HashMap<String, Double>();
        quiet.put("Loneliness", 0.10);
        assertThat(WantActBridge.decide(null, "be with someone", quiet,
            WantActBridge.HEURISTIC, PEER_HERE).isDefer()).isTrue();
    }

    @Test
    void the_presence_blind_call_still_works_for_callers_that_have_no_ambient() {
        // The four-arg overload must keep its old behaviour — it is on other paths.
        var d = WantActBridge.decide(null, "explore the library",
            pulling("Curiosity"), WantActBridge.HEURISTIC);
        assertThat(d.verb()).isEqualTo("library_search");
    }
}
