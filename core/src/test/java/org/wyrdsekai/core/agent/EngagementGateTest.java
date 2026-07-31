package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Entity;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.soul.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EngagementGateTest {

    private static AgentProfile testProfile() {
        return new AgentProfile("TestAgent", "test-agent", "agent",
            "Test", "System prompt", 4096, 256, 0.7, "did:key:test");
    }

    private static RoomSnapshot snapshotWithEntities(Entity... entities) {
        return new RoomSnapshot("test-room", "Test Room", "A test room.", "test",
            List.of(), List.of(entities), List.of(), List.of());
    }

    @Nested
    class NameDetection {
        @Test
        void detectsNameAsWholeWord() {
            assertTrue(EngagementGate.mentionsName("Hey Kai, what do you think?", "Kai"));
            assertTrue(EngagementGate.mentionsName("Ma, are you there?", "Ma"));
            assertTrue(EngagementGate.mentionsName("What about Sora?", "Sora"));
        }

        @Test
        void rejectsNameAsSubstring() {
            assertFalse(EngagementGate.mentionsName("The kaiser was great", "Kai"));
            assertFalse(EngagementGate.mentionsName("I made a map", "Ma"));
            assertFalse(EngagementGate.mentionsName("The sonora desert", "Sora"));
        }

        @Test
        void caseInsensitive() {
            assertTrue(EngagementGate.mentionsName("hey KAI", "Kai"));
            assertTrue(EngagementGate.mentionsName("hey kai", "Kai"));
        }
    }

    @Nested
    class QuestionDetection {
        @Test
        void detectsQuestionMark() {
            assertTrue(EngagementGate.isQuestion("What do you think?"));
        }

        @Test
        void detectsQuestionWords() {
            assertTrue(EngagementGate.isQuestion("How does that work"));
            assertTrue(EngagementGate.isQuestion("Why is the sky blue"));
            assertTrue(EngagementGate.isQuestion("Can you help me"));
        }

        @Test
        void rejectsStatements() {
            assertFalse(EngagementGate.isQuestion("The system looks good."));
            assertFalse(EngagementGate.isQuestion("I finished the build."));
        }
    }

    @Nested
    class EngagementDecision {
        @Test
        void playerSpeechAlwaysEngages() {
            var said = new WorldEvent.Said("room", Instant.now(), "player-1", "Player", "Hello");
            var snapshot = snapshotWithEntities(
                new Entity("test-agent", "TestAgent", "agent", ""),
                new Entity("player-1", "Player", "player", ""));

            var decision = EngagementGate.evaluate(
                said, testProfile(), null, VitalityState.initial(),
                snapshot, new ConversationTracker(), Instant.MIN);

            assertEquals(EngagementGate.Decision.ENGAGE, decision);
        }

        @Test
        void agentSpeechWithoutNameMentionObserves() {
            var said = new WorldEvent.Said("room", Instant.now(), "other-agent", "Other", "Status looks good");
            var snapshot = snapshotWithEntities(
                new Entity("test-agent", "TestAgent", "agent", ""),
                new Entity("other-agent", "Other", "agent", ""));

            var decision = EngagementGate.evaluate(
                said, testProfile(), null, VitalityState.initial(),
                snapshot, new ConversationTracker(), Instant.MIN);

            assertEquals(EngagementGate.Decision.OBSERVE, decision);
        }

        @Test
        void agentSpeechWithNameMentionEngages() {
            var said = new WorldEvent.Said("room", Instant.now(), "other-agent", "Other",
                "TestAgent, what do you think?");
            var snapshot = snapshotWithEntities(
                new Entity("test-agent", "TestAgent", "agent", ""),
                new Entity("other-agent", "Other", "agent", ""));

            var decision = EngagementGate.evaluate(
                said, testProfile(), null, VitalityState.initial(),
                snapshot, new ConversationTracker(), Instant.MIN);

            assertEquals(EngagementGate.Decision.ENGAGE, decision);
        }
    }

    @Nested
    class GreetingProtocol {
        @Test
        void playerEntryTriggersGreeting() {
            var snapshot = snapshotWithEntities(
                new Entity("test-agent", "TestAgent", "agent", ""));

            assertTrue(EngagementGate.shouldGreet(
                "player-1", "player", testProfile(), null, snapshot));
        }

        @Test
        void agentEntryNoGreeting() {
            var snapshot = snapshotWithEntities(
                new Entity("test-agent", "TestAgent", "agent", ""),
                new Entity("other-agent", "Other", "agent", ""));

            assertFalse(EngagementGate.shouldGreet(
                "other-agent", "agent", testProfile(), null, snapshot));
        }
    }
}
