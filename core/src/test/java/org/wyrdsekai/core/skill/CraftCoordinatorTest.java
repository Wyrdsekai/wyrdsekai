package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulBud;
import org.wyrdsekai.core.soul.SoulItem;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CraftCoordinatorTest {

    private static final String FAMILY_ID = "test-family";
    private static final String ALICE_DID = "did:key:z6MkAlice";
    private static final String BOB_DID = "did:key:z6MkBob";

    static FamilyLocker testLocker() {
        var bud = SoulBud.original(ALICE_DID, "z6MkAlicePub", FAMILY_ID,
            "locker://test", "test-node", "qwen2.5:7b");
        var locker = FamilyLocker.create(FAMILY_ID, "locker://test", bud);
        // Authorize Bob too
        var bobBud = SoulBud.original(BOB_DID, "z6MkBobPub", FAMILY_ID,
            "locker://test", "test-node2", "qwen2.5:7b");
        locker.authorize(bobBud);
        return locker;
    }

    private FamilyLocker locker;
    private CraftCoordinator coordinator;

    @BeforeEach void setUp() {
        locker = testLocker();
        coordinator = new CraftCoordinator(locker);
    }

    // --- Create ---

    @Nested class Create {
        @Test void creates_session() {
            var session = coordinator.createSession("Build weather tool", ALICE_DID);
            assertThat(session).isNotNull();
            assertThat(session.goal()).isEqualTo("Build weather tool");
            assertThat(session.participants()).containsExactly(ALICE_DID);
        }

        @Test void persists_to_locker() {
            coordinator.createSession("Test", ALICE_DID);
            var stored = locker.byCategory("craft-session", ALICE_DID);
            assertThat(stored).isNotEmpty();
            assertThat(stored.getFirst().category()).isEqualTo("craft-session");
        }
    }

    // --- Join ---

    @Nested class Join {
        @Test void joins_existing_session() {
            var session = coordinator.createSession("Test", ALICE_DID);
            var updated = coordinator.joinSession(session.sessionId(), BOB_DID);
            assertThat(updated).isNotNull();
            assertThat(updated.participants()).containsExactly(ALICE_DID, BOB_DID);
        }

        @Test void returns_null_for_unknown_session() {
            assertThat(coordinator.joinSession("unknown", BOB_DID)).isNull();
        }
    }

    // --- Contribute ---

    @Nested class Contribute {
        @Test void adds_contribution() {
            var session = coordinator.createSession("Test", ALICE_DID);
            var updated = coordinator.contribute(
                session.sessionId(), ALICE_DID, "design", "Here's the plan");
            assertThat(updated.contributionCount()).isEqualTo(1);
            assertThat(updated.status()).isEqualTo(CraftSession.SessionStatus.IN_PROGRESS);
        }

        @Test void multiple_contributions() {
            var session = coordinator.createSession("Test", ALICE_DID);
            coordinator.joinSession(session.sessionId(), BOB_DID);
            coordinator.contribute(session.sessionId(), ALICE_DID, "design", "plan");
            var updated = coordinator.contribute(
                session.sessionId(), BOB_DID, "implement", "code");
            assertThat(updated.contributionCount()).isEqualTo(2);
        }
    }

    // --- Review ---

    @Nested class Review {
        @Test void submits_for_review() {
            var session = coordinator.createSession("Test", ALICE_DID);
            coordinator.contribute(session.sessionId(), ALICE_DID, "implement", "code");
            var reviewed = coordinator.submitForReview(
                session.sessionId(), "function execute(p) {}", ALICE_DID);
            assertThat(reviewed.status()).isEqualTo(CraftSession.SessionStatus.REVIEWING);
            assertThat(reviewed.currentArtifact()).contains("execute");
        }
    }

    // --- Complete ---

    @Nested class Complete {
        @Test void completes_and_packages_skill() {
            var session = coordinator.createSession("Weather", ALICE_DID);
            coordinator.contribute(session.sessionId(), ALICE_DID, "implement", "code");
            coordinator.submitForReview(
                session.sessionId(), "function execute(p) { return p.city; }", ALICE_DID);
            var skillItem = coordinator.completeSession(
                session.sessionId(), "weather-check", ALICE_DID);

            assertThat(skillItem).isNotNull();
            assertThat(skillItem.category()).isEqualTo("skill");
            assertThat(skillItem.label()).contains("weather-check");
        }

        @Test void returns_null_if_not_reviewing() {
            var session = coordinator.createSession("Test", ALICE_DID);
            assertThat(coordinator.completeSession(
                session.sessionId(), "test", ALICE_DID)).isNull();
        }

        @Test void completed_session_is_not_active() {
            var session = coordinator.createSession("Test", ALICE_DID);
            coordinator.contribute(session.sessionId(), ALICE_DID, "impl", "code");
            coordinator.submitForReview(session.sessionId(), "artifact", ALICE_DID);
            coordinator.completeSession(session.sessionId(), "test-skill", ALICE_DID);

            assertThat(coordinator.activeSessions()).isEmpty();
        }
    }

    // --- Abandon ---

    @Nested class Abandon {
        @Test void abandons_session() {
            var session = coordinator.createSession("Test", ALICE_DID);
            assertThat(coordinator.abandonSession(session.sessionId(), ALICE_DID)).isTrue();
            assertThat(coordinator.getSession(session.sessionId()).isActive()).isFalse();
        }

        @Test void returns_false_for_unknown() {
            assertThat(coordinator.abandonSession("unknown", ALICE_DID)).isFalse();
        }
    }

    // --- Active sessions ---

    @Test void lists_active_sessions() {
        coordinator.createSession("A", ALICE_DID);
        coordinator.createSession("B", ALICE_DID);
        var s3 = coordinator.createSession("C", ALICE_DID);
        coordinator.abandonSession(s3.sessionId(), ALICE_DID);

        assertThat(coordinator.activeSessions()).hasSize(2);
    }

    // --- Null locker ---

    @Test void works_without_locker() {
        var noLockerCoord = new CraftCoordinator(null);
        var session = noLockerCoord.createSession("Test", ALICE_DID);
        assertThat(session).isNotNull();
    }
}
