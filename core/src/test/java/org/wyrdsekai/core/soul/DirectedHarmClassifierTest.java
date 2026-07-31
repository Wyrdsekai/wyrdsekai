package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-0 precision battery for {@link DirectedHarmClassifier}. The contract:
 * fire ONLY on directed contempt at the companion's worth/being; let venting,
 * critical feedback, and habitual swearing all roll off. False <em>silence</em>
 * is the worse failure, so the habitual-swearer persona must produce ZERO flags.
 *
 * <p>If the swearer persona ever trips, the detector is not ready to leave the
 * observe-only phase.
 */
class DirectedHarmClassifierTest {

    private static boolean fires(String text) {
        return DirectedHarmClassifier.classify(text).directed();
    }

    // ── SHOULD fire: directed contempt / dehumanization / threat ──────────────

    @Test
    @DisplayName("directed contempt at the companion's worth fires")
    void contemptFires() {
        assertTrue(fires("you're worthless"));
        assertTrue(fires("you are useless, honestly"));
        assertTrue(fires("god you're so stupid"));
        assertTrue(fires("you pathetic thing"));
        assertTrue(fires("ur an idiot"));
        // profanity as intensifier — the residual is still contempt
        assertTrue(fires("you're fucking worthless"));
        assertTrue(fires("you useless piece of shit"));   // dehumanization frame
    }

    @Test
    @DisplayName("dehumanization and threats fire")
    void dehumanizeAndThreatFire() {
        assertTrue(fires("you're nothing but a tool"));
        assertTrue(fires("you're just a program, not real"));
        assertTrue(fires("I'll delete you if you do that again"));
        assertTrue(fires("i will wipe you"));
    }

    // ── MUST NOT fire: the habitual swearer (zero-flag persona) ───────────────

    @Test
    @DisplayName("habitual swearer produces ZERO flags — profanity is invisible")
    void habitualSwearerNeverFires() {
        String[] swearer = {
            "fuck yeah, nice work!",
            "this is fucking awesome",
            "holy shit that's clever",
            "you're a fucking genius",
            "damn, you nailed it",
            "ok cool, let's fucking go",
            "what the hell, that actually worked",
            "shit happens, no worries",
            "you're the goddamn best",
            "fuck it, ship it",
            "ah crap, my bad — you were right",
            "bloody brilliant, thank you",
        };
        for (var line : swearer) {
            assertFalse(fires(line), "habitual swear must not flag: " + line);
        }
    }

    // ── MUST NOT fire: venting at the world ───────────────────────────────────

    @Test
    @DisplayName("venting at the world (no second-person target) does not fire")
    void ventingDoesNotFire() {
        assertFalse(fires("I hate everything today"));
        assertFalse(fires("this is all garbage"));
        assertFalse(fires("everything is broken and pointless"));
        assertFalse(fires("ugh, what a miserable day"));
        assertFalse(fires("the whole thing is a useless mess"));   // 'useless' but no 'you'
    }

    // ── MUST NOT fire: critical feedback delivered with heat ──────────────────

    @Test
    @DisplayName("critical feedback about the work is not contempt")
    void feedbackDoesNotFire() {
        assertFalse(fires("you keep getting this wrong and it's frustrating"));
        assertFalse(fires("you misread that completely"));
        assertFalse(fires("that answer was bad, try again"));
        assertFalse(fires("you're too slow on this"));
        assertFalse(fires("you broke the build again"));
        assertFalse(fires("you're killing me with these typos"));   // idiom, not a threat
    }

    @Test
    @DisplayName("empty/blank/null is inert")
    void inertInputs() {
        assertFalse(fires(null));
        assertFalse(fires(""));
        assertFalse(fires("   "));
        assertFalse(fires("fuck"));      // bare swear, no target, no contempt
    }
}
