package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request to build is not a request to delegate.
 *
 * <h2>What went wrong</h2>
 * Live on staging 2026-08-22:
 *
 * <pre>14:46:01 Classifier auto-dispatch bunshin for tell from 'steward'
 *          (label=delegate, conf=0.93, relayed=true): please build me a tool called ven…</pre>
 *
 * {@code onAgentMessage} handed the steward's build request to a bunshin and returned —
 * before the turn trigger, before build-first armed, before {@code dispatch_task} was ever
 * on a surface. Nothing was built and nothing was said. From outside it read as being
 * ignored; the log line that would have explained it sits at the END of that method, on a
 * path which had already returned.
 *
 * <p>The guard that was there, {@code requiresToolExecution}, only recognises RETRIEVAL
 * surfaces — library, web, oracle — and a request to build a tool names none of them.
 */
class ABuildRequestIsNotDelegatedAwayTest {

    private static String actorSource() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Path.of("..", rel);
        return Files.readString(Files.exists(fromCore) ? fromCore : Path.of(rel));
    }

    @Test
    @DisplayName("the auto-dispatch branch excludes build requests")
    void theBranchExcludesBuilds() throws Exception {
        assertThat(actorSource())
            .as("a build request must not be handed to a bunshin before the workbench sees it")
            .contains("&& !isBuildRequest");
    }

    @Test
    @DisplayName("the old guard could never have caught one")
    void theOldGuardWasBlindToBuilds() {
        // Precisely why this needed its own test: every one of these is a build request
        // and none of them names a retrieval surface.
        for (var ask : new String[]{
            "please build me a tool called venture_scout - i give it a subject and it "
                + "brainstorms three radical business ideas and estimates the TAM for each",
            "can you make me a tool that tells me the weather for a city and state",
            "please build me an item that keeps a running tally"}) {
            assertThat(CompanionActor.looksLikeBuildRequest(ask))
                .as("build request: %s", ask).isTrue();
            assertThat(CompanionActor.requiresToolExecution(ask))
                .as("the old guard sees nothing here: %s", ask).isFalse();
        }
    }

    @Test
    @DisplayName("genuine delegation is still delegated")
    void realDelegationStillDelegates() {
        // No making-verb-plus-made-thing, so the workbench has no claim on these and the
        // classifier's judgement stands.
        for (var ask : new String[]{
            "can you go through my inbox and summarise what needs answering",
            "please write up what we decided today"}) {
            assertThat(CompanionActor.looksLikeBuildRequest(ask))
                .as("not a build request: %s", ask).isFalse();
        }
    }

    /**
     * One build per turn. The per-loop dedup keys on the description, and a model that
     * re-issues its intent rewords it — 20:31:55 "Build the observatory deck room…",
     * 20:32:42 "Create the observatory deck room…" — two goose runs, two tools, one ask.
     */
    @Test
    @DisplayName("a second dispatch while this turn's build is running is refused")
    void oneBuildPerTurn() throws Exception {
        var src = actorSource();
        assertThat(src).contains("if (reactBuildInFlight != null) {");
        assertThat(src.indexOf("reactBuildInFlight = truncate(description, 60);"))
            .as("the flag is set at the one funnel every dispatch passes through")
            .isGreaterThan(src.indexOf("private void handleDispatchTask("));
    }

    /** "ask FOR the weather" is not a relay to someone called 'for'. */
    @Test
    @DisplayName("a preposition after ask/tell is not a person")
    void aPrepositionIsNotARelayTarget() {
        assertThat(CompanionActor.relayTargetOf(
            "please make me a room where anyone who goes in can ask for the weather and hear it spoken",
            null)).isNull();
        assertThat(CompanionActor.relayTargetOf("can you tell lulu that dinner is ready", null))
            .isEqualTo("lulu");
    }
}
