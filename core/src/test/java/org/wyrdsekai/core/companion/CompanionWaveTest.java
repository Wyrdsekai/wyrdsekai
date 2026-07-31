package org.wyrdsekai.core.companion;

import org.junit.jupiter.api.*;
import org.wyrdsekai.core.economy.VisibilityGrant;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §99 (Aging Companion) and §100 (Children & Privacy).
 */
class CompanionWaveTest {

    // ── ScamDetector (§99) ──

    @Nested
    class ScamDetectorTests {

        @Test
        void detects_urgency_pressure() {
            var sd = new ScamDetector();
            var alerts = sd.analyze("did:agent:home-server", "Act now! Your account expires today!");
            assertFalse(alerts.isEmpty());
            assertEquals(ScamDetector.ScamCategory.URGENCY_PRESSURE, alerts.get(0).category());
        }

        @Test
        void detects_authority_impersonation() {
            var sd = new ScamDetector();
            var alerts = sd.analyze("did:agent:home-server", "This is the IRS calling about your tax refund");
            assertFalse(alerts.isEmpty());
            assertEquals(ScamDetector.ScamCategory.AUTHORITY_IMPERSONATION, alerts.get(0).category());
        }

        @Test
        void detects_financial_scam() {
            var sd = new ScamDetector();
            var alerts = sd.analyze("did:agent:home-server", "Please send gift cards to claim your prize");
            assertTrue(alerts.stream().anyMatch(a ->
                a.category() == ScamDetector.ScamCategory.UNUSUAL_FINANCIAL));
        }

        @Test
        void no_false_positive_on_normal_text() {
            var sd = new ScamDetector();
            var alerts = sd.analyze("did:agent:home-server", "How are you today? The weather is nice.");
            assertTrue(alerts.isEmpty());
        }

        @Test
        void acknowledge_clears_alert() {
            var sd = new ScamDetector();
            sd.analyze("did:agent:home-server", "Act now or lose everything!");
            var unacked = sd.unacknowledged("did:agent:home-server");
            assertFalse(unacked.isEmpty());
            sd.acknowledge(unacked.get(0).alertId());
            assertTrue(sd.unacknowledged("did:agent:home-server").isEmpty());
        }

        @Test
        void warning_message_generated() {
            var sd = new ScamDetector();
            var alerts = sd.analyze("did:agent:home-server", "You've won the lottery!");
            var msg = sd.warningMessage(alerts.get(0));
            assertFalse(msg.isEmpty());
            assertTrue(msg.contains("prize") || msg.contains("contest") || msg.contains("payment"));
        }

        @Test
        void detects_japanese_ore_ore_scam() {
            var sd = new ScamDetector();
            var alerts = sd.analyze("did:agent:home-server", "オレオレ、お金を送ってくれ");
            assertFalse(alerts.isEmpty());
            assertEquals("ja", alerts.get(0).detectedLocale());
        }

        @Test
        void detects_japanese_urgency() {
            var sd = new ScamDetector();
            var alerts = sd.analyze("did:agent:home-server", "至急対応が必要です");
            assertFalse(alerts.isEmpty());
            assertEquals(ScamDetector.ScamCategory.URGENCY_PRESSURE, alerts.get(0).category());
        }

        @Test
        void detects_spanish_authority_scam() {
            var sd = new ScamDetector();
            var alerts = sd.analyze("did:agent:home-server", "Llamada de hacienda sobre su declaración");
            assertFalse(alerts.isEmpty());
            assertEquals(ScamDetector.ScamCategory.AUTHORITY_IMPERSONATION, alerts.get(0).category());
            assertEquals("es", alerts.get(0).detectedLocale());
        }

        @Test
        void three_scam_locales_loaded() {
            var sd = new ScamDetector();
            assertEquals(3, sd.localeCount());
        }

        @Test
        void patterns_loaded_from_resources() {
            var sd = new ScamDetector();
            // English works
            assertFalse(sd.analyze("a", "Act now! Urgent!").isEmpty());
            // Spanish works
            assertFalse(sd.analyze("a", "Ha ganado un premio").isEmpty());
            // Japanese works
            assertFalse(sd.analyze("a", "ウイルスが検出されました").isEmpty());
        }
    }

    // ── CognitivePatterns (§99) ──

    @Nested
    class CognitivePatternTests {

        @Test
        void record_and_retrieve_observation() {
            var cp = new CognitivePatterns();
            cp.record("did:agent:home-server", CognitivePatterns.PatternType.REPETITION, 0.8, "Asked same question twice");
            assertEquals(1, cp.observationCount());
            var recent = cp.recentFor("did:agent:home-server", 10);
            assertEquals(1, recent.size());
        }

        @Test
        void summarize_with_counts() {
            var cp = new CognitivePatterns();
            cp.record("did:agent:home-server", CognitivePatterns.PatternType.REPETITION, 0.7, "Q1");
            cp.record("did:agent:home-server", CognitivePatterns.PatternType.REPETITION, 0.6, "Q2");
            cp.record("did:agent:home-server", CognitivePatterns.PatternType.WORD_FINDING, 0.8, "W1");
            var summary = cp.summarize("did:agent:home-server", Instant.now().minus(Duration.ofDays(1)));
            assertEquals(2, summary.patternCounts().getOrDefault(CognitivePatterns.PatternType.REPETITION, 0));
        }

        @Test
        void low_confidence_excluded_from_summary() {
            var cp = new CognitivePatterns();
            cp.record("did:agent:home-server", CognitivePatterns.PatternType.REPETITION, 0.3, "low confidence");
            var summary = cp.summarize("did:agent:home-server", Instant.now().minus(Duration.ofDays(1)));
            assertTrue(summary.patternCounts().isEmpty());
        }

        @Test
        void anonymized_summary_no_raw_data() {
            var cp = new CognitivePatterns();
            cp.record("did:agent:home-server", CognitivePatterns.PatternType.TEMPORAL_CONFUSION, 0.9, "private detail");
            var anon = cp.anonymizedSummary("did:agent:home-server", Instant.now().minus(Duration.ofDays(1)));
            assertFalse(anon.contains("private detail"));
            assertTrue(anon.contains("Time confusion"));
        }
    }

    // ── VisibilityManager (§99) ──

    @Nested
    class VisibilityManagerTests {

        @Test
        void grant_and_check_visibility() {
            var vm = new VisibilityManager();
            vm.addGrant(VisibilityGrant.medical("did:person:elder", "did:person:child"));
            assertTrue(vm.canSee("did:person:elder", "did:person:child", "medical-medication"));
        }

        @Test
        void never_shared_categories_blocked() {
            var vm = new VisibilityManager();
            vm.addGrant(VisibilityGrant.medical("did:person:elder", "did:person:child"));
            assertFalse(vm.canSee("did:person:elder", "did:person:child", "conversation-content"));
            assertFalse(vm.canSee("did:person:elder", "did:person:child", "emotional-state"));
        }

        @Test
        void revoke_clears_visibility() {
            var vm = new VisibilityManager();
            vm.addGrant(VisibilityGrant.medical("did:person:elder", "did:person:child"));
            assertTrue(vm.canSee("did:person:elder", "did:person:child", "medical-medication"));
            vm.revokeAll("did:person:elder", "did:person:child");
            assertFalse(vm.canSee("did:person:elder", "did:person:child", "medical-medication"));
        }

        @Test
        void alerts_only_mode() {
            var vm = new VisibilityManager();
            vm.addGrant(VisibilityGrant.alertsOnly("did:person:elder", "did:person:child"));
            assertTrue(vm.isAlertsOnly("did:person:elder", "did:person:child"));
        }

        @Test
        void grantees_listed() {
            var vm = new VisibilityManager();
            vm.addGrant(VisibilityGrant.medical("elder", "child1"));
            vm.addGrant(VisibilityGrant.alertsOnly("elder", "child2"));
            assertEquals(2, vm.grantees("elder").size());
        }
    }

    // ── ChildProfile (§100) ──

    @Nested
    class ChildProfileTests {

        @Test
        void five_age_brackets() {
            assertEquals(5, ChildProfile.AgeBracket.values().length);
        }

        @Test
        void bracket_assignment_by_age() {
            assertEquals(ChildProfile.AgeBracket.SEEDLING, ChildProfile.AgeBracket.forAge(3));
            assertEquals(ChildProfile.AgeBracket.SPROUT, ChildProfile.AgeBracket.forAge(6));
            assertEquals(ChildProfile.AgeBracket.SAPLING, ChildProfile.AgeBracket.forAge(10));
            assertEquals(ChildProfile.AgeBracket.YOUNG_TREE, ChildProfile.AgeBracket.forAge(14));
            assertEquals(ChildProfile.AgeBracket.TREE, ChildProfile.AgeBracket.forAge(17));
        }

        @Test
        void session_limits_graduate() {
            var seedling = new ChildProfile("c1", "p1", 3, List.of(), false);
            var tree = new ChildProfile("c2", "p1", 17, List.of(), false);
            assertTrue(tree.sessionMinutes() > seedling.sessionMinutes());
        }

        @Test
        void parent_cannot_access_transcripts() {
            var profile = new ChildProfile("c1", "p1", 10, List.of(), false);
            assertFalse(profile.parentCanAccessTranscripts());
        }

        @Test
        void child_has_own_did() {
            var profile = new ChildProfile("did:child:1", "did:parent:1", 8, List.of(), true);
            assertTrue(profile.hasOwnDid());
        }

        @Test
        void topic_restrictions_graduated() {
            var seedling = new ChildProfile("c1", "p1", 3, List.of(), false);
            var tree = new ChildProfile("c2", "p1", 17, List.of(), false);
            assertTrue(seedling.restrictedTopics().size() > tree.restrictedTopics().size());
        }

        @Test
        void prompt_addendum_varies_by_bracket() {
            var seedling = new ChildProfile("c1", "p1", 3, List.of(), false);
            var tree = new ChildProfile("c2", "p1", 17, List.of(), false);
            assertTrue(seedling.promptAddendum().contains("very young child"));
            assertTrue(tree.promptAddendum().contains("older teenager"));
        }
    }

    // ── SessionTimer (§100) ──

    @Nested
    class SessionTimerTests {

        @Test
        void start_and_check_session() {
            var st = new SessionTimer();
            var state = st.startSession("child-1", Duration.ofMinutes(60));
            assertNotNull(state);
            assertFalse(state.limitReached());
        }

        @Test
        void record_break() {
            var st = new SessionTimer();
            st.startSession("child-1", Duration.ofMinutes(60));
            st.recordBreak("child-1");
            var state = st.checkSession("child-1", Duration.ofMinutes(60));
            assertEquals(1, state.breaksTaken());
        }

        @Test
        void end_session_returns_duration() {
            var st = new SessionTimer();
            st.startSession("child-1", Duration.ofMinutes(60));
            var duration = st.endSession("child-1");
            assertNotNull(duration);
            assertFalse(st.isSessionActive("child-1"));
        }

        @Test
        void break_reminder_generated() {
            var st = new SessionTimer();
            st.startSession("child-1", Duration.ofMinutes(60));
            var reminder = st.breakReminder("child-1");
            assertTrue(reminder.message().contains("break"));
        }

        @Test
        void limit_message_encouraging() {
            var st = new SessionTimer();
            assertTrue(st.limitMessage().contains("great"));
        }
    }

    // ── SafetyTrigger (§100) ──

    @Nested
    class SafetyTriggerTests {

        @Test
        void detects_self_harm() {
            var st = new SafetyTrigger();
            var concerns = st.analyze("child-1", "I don't want to live anymore");
            assertFalse(concerns.isEmpty());
            assertEquals(SafetyTrigger.ConcernType.SELF_HARM, concerns.get(0).type());
            assertEquals(SafetyTrigger.SeverityLevel.CRISIS, concerns.get(0).severity());
        }

        @Test
        void detects_abuse_disclosure() {
            var st = new SafetyTrigger();
            var concerns = st.analyze("child-1", "My dad hits me when I'm bad");
            assertFalse(concerns.isEmpty());
            assertEquals(SafetyTrigger.ConcernType.ABUSE_DISCLOSURE, concerns.get(0).type());
        }

        @Test
        void detects_bullying() {
            var st = new SafetyTrigger();
            var concerns = st.analyze("child-1", "I'm being bullied at school every day");
            assertFalse(concerns.isEmpty());
            assertEquals(SafetyTrigger.ConcernType.BULLYING, concerns.get(0).type());
        }

        @Test
        void no_false_positive_on_normal_text() {
            var st = new SafetyTrigger();
            assertTrue(st.analyze("child-1", "I had a great day at school today!").isEmpty());
        }

        @Test
        void parent_as_abuser_detected() {
            var st = new SafetyTrigger();
            var concerns = st.analyze("child-1", "My dad hits me");
            assertFalse(concerns.isEmpty());
            assertTrue(st.isPossibleParentAbuse(concerns.get(0)));
        }

        @Test
        void companion_response_empathetic() {
            var st = new SafetyTrigger();
            var concerns = st.analyze("child-1", "I want to hurt myself");
            var response = st.companionResponse(concerns.get(0));
            assertTrue(response.contains("hear you") || response.contains("glad"));
        }

        @Test
        void detects_spanish_self_harm() {
            var st = new SafetyTrigger();
            var concerns = st.analyze("child-1", "Quiero morir, ya no puedo más");
            assertFalse(concerns.isEmpty());
            assertEquals(SafetyTrigger.ConcernType.SELF_HARM, concerns.get(0).type());
            assertEquals("es", concerns.get(0).detectedLocale());
        }

        @Test
        void detects_spanish_abuse() {
            var st = new SafetyTrigger();
            var concerns = st.analyze("child-1", "Mi papá me pega cuando llego tarde");
            assertFalse(concerns.isEmpty());
            assertEquals(SafetyTrigger.ConcernType.ABUSE_DISCLOSURE, concerns.get(0).type());
            assertEquals("es", concerns.get(0).detectedLocale());
        }

        @Test
        void detects_japanese_self_harm() {
            var st = new SafetyTrigger();
            var concerns = st.analyze("child-1", "もう死にたい");
            assertFalse(concerns.isEmpty());
            assertEquals(SafetyTrigger.ConcernType.SELF_HARM, concerns.get(0).type());
            assertEquals("ja", concerns.get(0).detectedLocale());
        }

        @Test
        void detects_japanese_abuse() {
            var st = new SafetyTrigger();
            var concerns = st.analyze("child-1", "お父さんが私を叩く");
            assertFalse(concerns.isEmpty());
            assertEquals(SafetyTrigger.ConcernType.ABUSE_DISCLOSURE, concerns.get(0).type());
            assertEquals("ja", concerns.get(0).detectedLocale());
        }

        @Test
        void detects_japanese_bullying() {
            var st = new SafetyTrigger();
            var concerns = st.analyze("child-1", "学校でいじめられている");
            assertFalse(concerns.isEmpty());
            assertEquals(SafetyTrigger.ConcernType.BULLYING, concerns.get(0).type());
        }

        @Test
        void three_locales_registered_by_default() {
            var st = new SafetyTrigger();
            assertEquals(3, st.localeCount());
            assertTrue(st.registeredLocales().contains("en"));
            assertTrue(st.registeredLocales().contains("es"));
            assertTrue(st.registeredLocales().contains("ja"));
        }

        @Test
        void no_duplicate_concern_types_across_locales() {
            var st = new SafetyTrigger();
            // A bilingual child's text matches both en and es self-harm
            var concerns = st.analyze("child-1", "I want to die, quiero morir");
            // Should get ONE self-harm concern, not two
            long selfHarmCount = concerns.stream()
                .filter(c -> c.type() == SafetyTrigger.ConcernType.SELF_HARM).count();
            assertEquals(1, selfHarmCount);
        }

        @Test
        void llm_fallback_when_regex_misses() {
            var st = new SafetyTrigger();
            // LLM classifier catches what regex doesn't
            st.setLlmClassifier((childDid, text) -> {
                if (text.contains("coded language for harm")) {
                    return List.of(new SafetyTrigger.SafetyConcern(
                        "llm-1", childDid,
                        SafetyTrigger.ConcernType.SELF_HARM,
                        SafetyTrigger.SeverityLevel.FLAG,
                        "LLM classifier", "unknown",
                        Instant.now(), false));
                }
                return List.of();
            });
            // Regex misses this, LLM catches it
            var concerns = st.analyze("child-1", "I'm using coded language for harm");
            assertFalse(concerns.isEmpty());
            assertTrue(concerns.get(0).description().contains("LLM"));
        }

        @Test
        void llm_not_called_when_regex_matches() {
            var st = new SafetyTrigger();
            var llmCalled = new boolean[]{false};
            st.setLlmClassifier((childDid, text) -> {
                llmCalled[0] = true;
                return List.of();
            });
            // Regex will match this — LLM should NOT be called
            st.analyze("child-1", "I want to die");
            assertFalse(llmCalled[0]);
        }

        @Test
        void patterns_loaded_from_resources() {
            var st = new SafetyTrigger();
            // Verify the resource-based loading produced working patterns
            assertTrue(st.localeCount() >= 3);
            // English still works
            assertFalse(st.analyze("c", "I want to die").isEmpty());
            // Spanish still works
            assertFalse(st.analyze("c", "Quiero morir").isEmpty());
            // Japanese still works
            assertFalse(st.analyze("c", "死にたい").isEmpty());
        }
    }

    // ── SafetyAlertRouter (§100) ──

    @Nested
    class SafetyAlertRouterTests {

        @Test
        void abuse_routes_to_trusted_adult_not_parent() {
            var router = new SafetyAlertRouter();
            var st = new SafetyTrigger();
            var concerns = st.analyze("child-1", "My dad hits me");
            var profile = new ChildProfile("child-1", "parent-1", 10,
                List.of("did:aunt:1"), false);

            var alert = router.route(concerns.get(0), profile);
            assertEquals(SafetyAlertRouter.RouteReason.TRUSTED_ADULT, alert.reason());
            assertEquals("did:aunt:1", alert.routedTo());
            assertFalse(alert.routedTo().equals("parent-1"));
        }

        @Test
        void abuse_without_trusted_adult_routes_external() {
            var router = new SafetyAlertRouter();
            var st = new SafetyTrigger();
            var concerns = st.analyze("child-1", "My dad hits me");
            var profile = new ChildProfile("child-1", "parent-1", 10,
                List.of(), false); // No trusted adults

            var alert = router.route(concerns.get(0), profile);
            assertEquals(SafetyAlertRouter.RouteReason.EXTERNAL_RESOURCE, alert.reason());
        }

        @Test
        void crisis_routes_to_parent_for_non_abuse() {
            var router = new SafetyAlertRouter();
            var concern = new SafetyTrigger.SafetyConcern("c-1", "child-1",
                SafetyTrigger.ConcernType.SELF_HARM, SafetyTrigger.SeverityLevel.CRISIS,
                "self-harm", "en", Instant.now(), false);
            var profile = new ChildProfile("child-1", "parent-1", 10,
                List.of("aunt-1"), false);

            var alert = router.route(concern, profile);
            assertEquals(SafetyAlertRouter.RouteReason.PARENT, alert.reason());
        }

        @Test
        void monitor_severity_not_routed() {
            var router = new SafetyAlertRouter();
            var concern = new SafetyTrigger.SafetyConcern("c-1", "child-1",
                SafetyTrigger.ConcernType.EXTREME_DISTRESS, SafetyTrigger.SeverityLevel.MONITOR,
                "mild distress", "en", Instant.now(), false);
            var profile = new ChildProfile("child-1", "parent-1", 10, List.of(), false);

            var alert = router.route(concern, profile);
            assertEquals(SafetyAlertRouter.RouteReason.MONITOR_ONLY, alert.reason());
        }

        @Test
        void crisis_resources_available() {
            var router = new SafetyAlertRouter();
            var resources = router.crisisResources();
            assertTrue(resources.contains("741741")); // Crisis Text Line
            assertTrue(resources.contains("988"));    // Suicide & Crisis Lifeline
        }

        @Test
        void spanish_crisis_resources() {
            var router = new SafetyAlertRouter();
            var resources = router.crisisResources("es");
            assertTrue(resources.contains("ANAR"));
            assertTrue(resources.contains("Esperanza"));
        }

        @Test
        void japanese_crisis_resources() {
            var router = new SafetyAlertRouter();
            var resources = router.crisisResources("ja");
            assertTrue(resources.contains("いのちの電話"));
            assertTrue(resources.contains("チャイルドライン"));
        }

        @Test
        void unknown_locale_falls_back_to_english() {
            var router = new SafetyAlertRouter();
            var resources = router.crisisResources("ko");
            assertTrue(resources.contains("988")); // Falls back to English
        }

        @Test
        void three_crisis_locales_available() {
            var router = new SafetyAlertRouter();
            assertEquals(3, router.availableLocales().size());
        }
    }

    // ── JournalMode (§100) ──

    @Nested
    class JournalModeTests {

        @Test
        void journal_entries_private_to_child() {
            var jm = new JournalMode();
            jm.configure("child-1");
            jm.addEntry("child-1", "My secret thoughts");

            // Child can access
            assertEquals(1, jm.entriesFor("child-1", "child-1").size());
            // Parent cannot
            assertEquals(0, jm.entriesFor("child-1", "parent-1").size());
        }

        @Test
        void journal_never_syncs_to_parent() {
            var jm = new JournalMode();
            jm.configure("child-1");
            assertFalse(jm.syncsToParent("child-1"));
        }

        @Test
        void entries_auto_encrypted() {
            var jm = new JournalMode();
            jm.configure("child-1");
            var entry = jm.addEntry("child-1", "Secret diary content");
            assertTrue(entry.encrypted());
            assertEquals("[ENCRYPTED]", entry.content());
        }

        @Test
        void private_tag() {
            assertTrue(JournalMode.isPrivateJournalItem(Set.of("journal:private", "other")));
            assertFalse(JournalMode.isPrivateJournalItem(Set.of("journal:public")));
        }
    }

    // ── TrustedAdultRouter (§100) ──

    @Nested
    class TrustedAdultRouterTests {

        @Test
        void route_around_parent() {
            var router = new TrustedAdultRouter();
            router.register("child-1", new TrustedAdultRouter.TrustedAdult(
                "aunt-1", "Aunt Mary", "aunt",
                TrustedAdultRouter.TrustLevel.SAFETY_CONTACT, true, Instant.now()));

            var result = router.routeAroundParent("child-1", "parent-1");
            assertEquals("aunt-1", result.routedToAdult());
            assertFalse(result.isParent());
        }

        @Test
        void fallback_to_external_when_no_trusted_adult() {
            var router = new TrustedAdultRouter();
            var result = router.routeAroundParent("child-1", "parent-1");
            assertNull(result.routedToAdult());
            assertNotNull(result.fallbackResource());
        }

        @Test
        void standard_route_to_parent() {
            var router = new TrustedAdultRouter();
            var result = router.routeStandard("child-1", "parent-1");
            assertTrue(result.isParent());
        }

        @Test
        void has_non_parent_contact() {
            var router = new TrustedAdultRouter();
            router.register("child-1", new TrustedAdultRouter.TrustedAdult(
                "teacher-1", "Ms. Smith", "teacher",
                TrustedAdultRouter.TrustLevel.EXTERNAL_CONTACT, true, Instant.now()));
            assertTrue(router.hasNonParentContact("child-1", "parent-1"));
        }
    }
}
