package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A detour out of the turn pipeline must rejoin UPSTREAM of the gates it
 * detoured around.
 *
 * <h2>What went wrong</h2>
 * Battery cpB5 (2026-08-23 17:26): "please build a working weather tool and put
 * it in the room weather-attic-1325…" arrived three minutes after the previous
 * tell, so the senderContext carried the "[You are mid-conversation…]" hint.
 * The hint's em dash is non-ASCII; {@code shouldDetectLanguage} sniffed the
 * WHOLE envelope and sent the turn through the language-detect hop, which
 * returns early from {@code onProcessEvents} — before the turn is pinned and
 * before build-first/library-first arm. On detecting EN it rejoined at
 * {@code runIdentityInference()}, downstream of all of it. Build-first never
 * armed, the loop ran 25 tools wide, and she built a second room instead of
 * the tool. The translation and vision hops had the same downstream rejoin —
 * JA/ES turns lost the forces on EVERY message.
 */
class ADetourRejoinsUpstreamOfTheGateTest {

    private static String actorSource() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Path.of("..", rel);
        return Files.readString(Files.exists(fromCore) ? fromCore : Path.of(rel));
    }

    @Test
    @DisplayName("every detour rejoin pins and arms before running inference")
    void everyRejoinPinsFirst() throws Exception {
        var src = actorSource();
        // onProcessEvents (straight line) + detect-EN + translate + vision.
        long calls = src.lines()
            .filter(l -> l.trim().equals("pinTurnAndArmFirstDoors();"))
            .count();
        assertThat(calls)
            .as("straight line, detect hop, translate hop, and vision hop all pin+arm")
            .isGreaterThanOrEqualTo(4);
        // Each rejoin must pin BEFORE it infers.
        for (var marker : new String[] {
                "// Detected EN (or detect failed) — proceed normally.",
                "// Set as pendingTrigger and proceed exactly as the post-vision branch",
                "// Ensure the original trigger is still active"}) {
            var at = src.indexOf(marker);
            assertThat(at).as("marker present: " + marker).isGreaterThan(0);
            var window = src.substring(at, Math.min(src.length(), at + 900));
            var pin = window.indexOf("pinTurnAndArmFirstDoors();");
            var infer = window.indexOf("runIdentityInference();");
            assertThat(pin).as("rejoin after '" + marker + "' pins").isGreaterThan(-1);
            assertThat(pin)
                .as("rejoin after '" + marker + "' pins BEFORE inferring")
                .isLessThan(infer);
        }
    }

    @Test
    @DisplayName("the language sniff reads the person's words, not our envelope")
    void sniffReadsTheUtterance() throws Exception {
        var src = actorSource();
        var body = src.substring(src.indexOf("private boolean shouldDetectLanguage"));
        body = body.substring(0, body.indexOf("\n    }"));
        assertThat(body)
            .as("shouldDetectLanguage sniffs the extracted user utterance — the "
                + "envelope's own em dashes must not trip the hop")
            .contains("extractUserTellContent");
    }

    @Test
    @DisplayName("stripActorWrappers returns the bare ask from a full mid-conversation envelope")
    void stripReturnsTheBareAsk() {
        var ask = "please build a working weather tool and put it in the room "
            + "weather-attic-1325 so anyone in the weather attic can ask for a "
            + "city and state and hear the weather";
        var ctx = "[message from steward: " + ask + "]"
            + "\n[You are mid-conversation with steward (last heard from 111s ago). "
            + "Continue naturally — do NOT greet or re-introduce yourself.]"
            + "\n[When done, REPLY using: {\"action\": \"tell_agent\", \"target\": "
            + "\"steward\", \"message\": \"<your findings>\"}]";
        assertThat(CompanionActor.stripActorWrappers(ctx)).isEqualTo(ask);
        // The single-line form still round-trips.
        assertThat(CompanionActor.stripActorWrappers("[message from steward: " + ask + "]"))
            .isEqualTo(ask);
        // A payload containing brackets survives.
        assertThat(CompanionActor.stripActorWrappers("[message from steward: use array[0]]"))
            .isEqualTo("use array[0]");
        // Unwrapped text passes through untouched.
        assertThat(CompanionActor.stripActorWrappers(ask)).isEqualTo(ask);
    }

    @Test
    @DisplayName("the cpB5 phrasing arms build-first once stripped")
    void theCpB5PhrasingArms() {
        var ask = "please build a working weather tool and put it in the room "
            + "weather-attic-1325 so anyone in the weather attic can ask for a "
            + "city and state and hear the weather";
        assertThat(CompanionActor.looksLikeBuildRequest(ask)).isTrue();
        assertThat(CompanionActor.looksLikeFactQuestion(ask)).isFalse();
    }
}
