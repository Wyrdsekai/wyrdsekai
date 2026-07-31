package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arc 3 — peer-bond auto-formation E2E.
 *
 * <p>Drives the full happy-path:</p>
 * <ol>
 *   <li>Companion observes {@code SUGGESTION_THRESHOLD} significant
 *       interactions with peer X (recorded via
 *       {@link PeerInteractionRegistry#noteInteraction}).</li>
 *   <li>{@link ChronicleService#detectAll} runs.</li>
 *   <li>The combined findings include a
 *       {@link DoomLoopDetector.Severity#INFO} entry keyed
 *       {@code peer_bond_suggest:<peerDid>}.</li>
 * </ol>
 *
 * <p>Also covers the suppression path:</p>
 * <ul>
 *   <li>If the same peer is already in {@code markBonded}, no suggestion
 *       fires (proves the bond-accept → registry-mark path closes the
 *       loop and the chronicle won't keep nagging).</li>
 * </ul>
 *
 * <p>Tier-1 by design — wires the real {@link ChronicleService} via
 * {@link TickLogReader} on a temp file, the real
 * {@link PeerInteractionRegistry} singleton, and the real
 * {@link PeerBondSuggestionDetector}. No mocking. The registry is reset
 * between tests so the singleton doesn't leak.</p>
 */
class PeerBondFormationE2ETest {

    private static final String SELF = "did:wyrd:companion-a";
    private static final String PEER = "did:wyrd:companion-b";

    @BeforeEach
    void cleanRegistry() {
        PeerInteractionRegistry.get().clearAllForTests();
    }

    @AfterEach
    void cleanRegistryAfter() {
        PeerInteractionRegistry.get().clearAllForTests();
    }

    @Test
    void thresholdInteractionsSurfaceSuggestionInChronicle(@TempDir Path tmp) throws Exception {
        var registry = PeerInteractionRegistry.get();
        // Drive past the threshold inside the window.
        var now = Instant.now();
        for (int i = 0; i < PeerBondSuggestionDetector.SUGGESTION_THRESHOLD + 2; i++) {
            registry.noteInteraction(SELF, PEER, now.minusSeconds(60L * i));
        }
        // Sanity: snapshot reads back what we wrote.
        var counts = registry.countsByPeerInWindow(SELF,
            PeerBondSuggestionDetector.WINDOW_DAYS);
        assertThat(counts).containsKey(PEER);
        assertThat(counts.get(PEER))
            .isGreaterThanOrEqualTo(PeerBondSuggestionDetector.SUGGESTION_THRESHOLD);

        // Stand up a real ChronicleService against an empty log file (no
        // doom / psychosis / resilience entries — keeps the assertion
        // narrow to the peer-bond pathway).
        var logFile = tmp.resolve("activity.jsonl");
        Files.write(logFile, new byte[0]);
        var service = new ChronicleService(new TickLogReader(logFile));

        var findings = service.detectAll(SELF, "companion-a", null, Set.of());

        var suggestion = findings.stream()
            .filter(f -> ("peer_bond_suggest:" + PEER).equals(f.key()))
            .findFirst();
        assertThat(suggestion).as("expected peer_bond_suggest finding for " + PEER)
            .isPresent();
        var f = suggestion.get();
        assertThat(f.severity()).isEqualTo(DoomLoopDetector.Severity.INFO);
        assertThat(f.message()).contains(PEER);
        assertThat(f.message().toLowerCase()).contains("propose a peer bond?");
        assertThat(f.message().toLowerCase()).contains("not a flag");
    }

    @Test
    void markedBondedSuppressesSuggestion(@TempDir Path tmp) throws Exception {
        var registry = PeerInteractionRegistry.get();
        var now = Instant.now();
        for (int i = 0; i < PeerBondSuggestionDetector.SUGGESTION_THRESHOLD + 5; i++) {
            registry.noteInteraction(SELF, PEER, now.minusSeconds(60L * i));
        }
        // Simulating handleAcceptPeerBond's wire — once the bond is active,
        // the chronicle should stop suggesting.
        registry.markBonded(SELF, PEER);

        var logFile = tmp.resolve("activity.jsonl");
        Files.write(logFile, new byte[0]);
        var service = new ChronicleService(new TickLogReader(logFile));

        var findings = service.detectAll(SELF, "companion-a", null, Set.of());

        var suggestion = findings.stream()
            .filter(f -> ("peer_bond_suggest:" + PEER).equals(f.key()))
            .findFirst();
        assertThat(suggestion)
            .as("already-bonded peer should not generate a suggestion")
            .isEmpty();
    }

    @Test
    void subThresholdDoesNotSurface(@TempDir Path tmp) throws Exception {
        var registry = PeerInteractionRegistry.get();
        var now = Instant.now();
        // One below threshold.
        for (int i = 0; i < PeerBondSuggestionDetector.SUGGESTION_THRESHOLD - 1; i++) {
            registry.noteInteraction(SELF, PEER, now.minusSeconds(60L * i));
        }

        var logFile = tmp.resolve("activity.jsonl");
        Files.write(logFile, new byte[0]);
        var service = new ChronicleService(new TickLogReader(logFile));

        var findings = service.detectAll(SELF, "companion-a", null, Set.of());
        var suggestion = findings.stream()
            .filter(f -> ("peer_bond_suggest:" + PEER).equals(f.key()))
            .findFirst();
        assertThat(suggestion).isEmpty();
    }

    @Test
    void interactionsOutsideWindowDoNotCount(@TempDir Path tmp) throws Exception {
        var registry = PeerInteractionRegistry.get();
        // All interactions older than the window — should drop out of count.
        var ancient = Instant.now()
            .minusSeconds((PeerBondSuggestionDetector.WINDOW_DAYS + 1) * 24L * 3600L);
        for (int i = 0; i < PeerBondSuggestionDetector.SUGGESTION_THRESHOLD + 10; i++) {
            registry.noteInteraction(SELF, PEER, ancient.minusSeconds(60L * i));
        }

        var counts = registry.countsByPeerInWindow(SELF,
            PeerBondSuggestionDetector.WINDOW_DAYS);
        assertThat(counts).doesNotContainKey(PEER);

        var logFile = tmp.resolve("activity.jsonl");
        Files.write(logFile, new byte[0]);
        var service = new ChronicleService(new TickLogReader(logFile));

        var findings = service.detectAll(SELF, "companion-a", null, Set.of());
        var suggestion = findings.stream()
            .filter(f -> ("peer_bond_suggest:" + PEER).equals(f.key()))
            .findFirst();
        assertThat(suggestion).isEmpty();
    }
}
