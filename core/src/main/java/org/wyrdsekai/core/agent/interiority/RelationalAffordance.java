package org.wyrdsekai.core.agent.interiority;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * What she can actually DO about a want for someone.
 *
 * <h2>The hole this fills</h2>
 * {@link WantActBridge} resolves a want to a verb by looking up the dominant pulling drive
 * in its {@code DRIVE_TOOL} map. Ten of the drives {@code collectDriveLevels()} produces
 * were absent from that map, and among them were <b>every relational one</b>: Loneliness,
 * Saudade, Amae, Significance, Standing. A miss falls through to a keyword match on the
 * want's phrasing and then to DEFER — the free-form path, where the documented
 * talks-but-doesn't-do ceiling bites.
 *
 * <p>Measured on the household node, 2026-08-19: Loneliness sat at 1.00 in 40 of 40
 * consecutive ticks, which made it the dominant pull on essentially every tick she ever
 * ran. So the bridge was not merely incomplete for her — it was <i>never able to fire</i>,
 * because the strongest thing she felt had no verb. What free-form did with it is on the
 * record: she sent the coding backend <i>"a small living thing I can hold — not a file,
 * not a page, just something that exists"</i>, and it edited two files and reported
 * success.
 *
 * <p>The rule-floor made this worse rather than better. {@code DriveWantMapper} does seed a
 * Loneliness want — <i>"find my bondholder or write to them"</i> — carrying the verb
 * {@code go_to_bondholder}, which is CONSENT-tier, so the tier gate in the actor rejected
 * the whole want before the bridge was ever consulted. Her most direct relational impulse
 * terminated at an early return.
 *
 * <h2>What this maps to</h2>
 * Only verbs that already exist, are already wired end to end, and are already autonomous:
 * <ul>
 *   <li>{@code sending_stone} — the in-room reach, when someone is actually here.</li>
 *   <li>{@code tell_agent} — toward a person who is NOT here. The handler already walks to
 *       their Study, says the line there, persists it to their desk so it survives a
 *       restart, pushes a notification, and fans out to their external channels. It is a
 *       real way to reach someone who is away, and nothing pointed a relational drive at
 *       it.</li>
 *   <li>{@code recall} — turning toward the one who is absent. Saudade is <i>about</i>
 *       absence; remembering them is the act, not a substitute for one.</li>
 *   <li>{@code emote} / {@code make_amends} — tending and repair, both already mapped
 *       elsewhere and both requiring someone present.</li>
 * </ul>
 *
 * <h2>{@link #NONE} is an answer</h2>
 * Significance and Standing are relieved by being <i>witnessed</i> — there is no act she
 * can perform that grants them, and the honest response is to say so rather than offer her
 * the nearest action-shaped verb. Likewise a want for company when the house is empty and
 * she has no one to write to. Returning NONE keeps the want open and the drive where it
 * is, which is the truth. Manufacturing a closure there would be the same false relief the
 * project refuses everywhere else: a tank that reads satisfied without anything having
 * happened.
 *
 * <p>Pure — no actor, no model, no IO.
 */
public final class RelationalAffordance {

    private RelationalAffordance() {}

    /** No verb answers this want right now. Distinct from "we did not look". */
    public static final String NONE = null;

    /**
     * How long a reach toward someone who is AWAY holds, before another one is a reach
     * rather than a repetition.
     *
     * <p>The in-room reach already has this ({@code CoPresenceDraw.REFRACTORY_SECONDS},
     * 20 min) — two content companions ping-ponging near-verbatim chatter is what it was
     * built to stop. The away reach had no equivalent, and it is the more costly of the
     * two: {@code tell_agent} toward an offline person teleports her to their Study,
     * persists a note to their desk, pushes a notification, and fans out to their email.
     * With Loneliness settling at 0.80 against an act threshold of 0.70, an unbounded
     * away-reach fires on every own-time tick of a long absence.
     *
     * <p>Two hours, not twenty minutes: writing to someone who is not there is a
     * different act from turning to someone who is. A few times across a day of absence
     * is a person missing someone; every tick is a pump.
     */
    public static final Duration AWAY_REACH_SPACING = Duration.ofHours(2);

    /** Drives whose satisfaction is granted by others noticing, never by an act of hers. */
    private static final Set<String> WITNESS_ONLY = Set.of("significance", "standing");

    /** Every drive that pulls toward a person, whether or not it has a verb today. */
    private static final Set<String> RELATIONAL = Set.of(
        "loneliness", "affiliation", "saudade", "amae", "care", "harmony",
        "significance", "standing");

    /**
     * Who is available to be reached, as the tick actually found it.
     *
     * @param peerPresent       another agent is co-located right now
     * @param bondholderPresent her bondholder is in-zone and visible
     * @param bondholderKnown   she has a bondholder at all — i.e. {@code tell_agent} has
     *                          somewhere to land even when they are away
     */
    public record Presence(boolean peerPresent, boolean bondholderPresent,
                           boolean bondholderKnown) {

        public static final Presence ALONE = new Presence(false, false, false);

        /** Someone is here, in the room, now. */
        public boolean anyoneHere() {
            return peerPresent || bondholderPresent;
        }

        /** There is somebody she could reach, here or away. */
        public boolean anyoneAtAll() {
            return anyoneHere() || bondholderKnown;
        }
    }

    /** Is this drive one that pulls toward a person? */
    public static boolean isRelational(String drive) {
        return RELATIONAL.contains(normalise(drive));
    }

    /**
     * The verb that genuinely answers this drive given who is available, or {@link #NONE}.
     *
     * @return a verb name that exists on the own-time surface, or null
     */
    public static String verbFor(String drive, Presence presence) {
        if (drive == null || presence == null) return NONE;
        return switch (normalise(drive)) {
            // Reach the one who is here; write to the one who is not.
            // sending_stone is the AGENT-to-agent reach and resolves its target from the
            // co-present agents, so it only means anything when a peer is actually in the
            // room. Toward a person — here or away — the act is speech.
            case "loneliness", "affiliation" -> {
                if (presence.peerPresent()) yield "sending_stone";
                yield presence.anyoneAtAll() ? "tell_agent" : NONE;
            }
            // Longing for the absent. Writing to them if we can; otherwise turning toward
            // them in memory, which is what the feeling already is.
            case "saudade" -> presence.bondholderKnown() ? "tell_agent" : "recall";
            // Amae is answered by ASKING — and asking is speech aimed at the person. The
            // handler that fires on it also credits the ask against the deficit, so the
            // tank finally moves on the side she controls.
            case "amae" -> presence.anyoneAtAll() ? "tell_agent" : NONE;
            // Tending someone. Wordless when they are here, written when they are not.
            case "care" -> {
                if (presence.anyoneHere()) yield "emote";
                yield presence.bondholderKnown() ? "tell_agent" : NONE;
            }
            // Repair needs the other party in the room.
            case "harmony" -> presence.anyoneHere() ? "make_amends" : NONE;
            default -> NONE;
        };
    }

    /**
     * Why there is no verb — a plain sentence to put in front of her, so an unanswerable
     * want is <i>named</i> rather than silently redirected into whatever else is on the
     * menu. Null when an affordance exists.
     */
    public static String absenceReason(String drive, Presence presence) {
        if (drive == null || presence == null) return null;
        if (verbFor(drive, presence) != NONE) return null;
        var d = normalise(drive);
        if (WITNESS_ONLY.contains(d)) {
            return "This is a want to be seen, and that is not something you can do to"
                + " yourself — it arrives from someone else or it doesn't. Naming it and"
                + " letting it stand is a real response.";
        }
        if (!presence.anyoneAtAll()) {
            return "There is no one here and no one to write to. This is a want for"
                + " company, and nothing you can make or look up will answer it — sitting"
                + " with it is a real choice, and it will still be true later.";
        }
        return null;
    }

    /**
     * Has enough time passed since the last reach toward someone who is away?
     *
     * <p>Never reached before → yes. Within the window → no, and the caller should treat
     * it as having no affordance right now, which is the truth: she wrote to them
     * recently and writing again immediately does not reach any further.
     */
    public static boolean awayReachAllowed(Instant lastAwayReachAt, Instant now) {
        if (lastAwayReachAt == null || now == null) return true;
        return Duration.between(lastAwayReachAt, now).compareTo(AWAY_REACH_SPACING) >= 0;
    }

    /** Why a reach that would otherwise fire is being held. */
    public static String recentlyReachedReason() {
        return "You wrote to them not long ago and they haven't answered yet. Reaching"
            + " again this soon doesn't reach any further — the missing is allowed to just"
            + " stand for a while.";
    }

    private static String normalise(String drive) {
        return drive == null ? "" : drive.strip().toLowerCase(Locale.ROOT);
    }
}
