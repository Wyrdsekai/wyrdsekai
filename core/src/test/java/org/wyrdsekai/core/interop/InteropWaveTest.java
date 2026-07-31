package org.wyrdsekai.core.interop;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §97 Interoperability & Open Standards.
 */
class InteropWaveTest {

    // ── Trust Tiers ──────────────────────────────────────────────────

    @Nested
    class TrustTierTests {

        @Test
        void tier_ordering() {
            assertTrue(TrustTier.FAMILY.meetsOrExceeds(TrustTier.HOUSEHOLD));
            assertTrue(TrustTier.HOUSEHOLD.meetsOrExceeds(TrustTier.TRUSTED));
            assertTrue(TrustTier.TRUSTED.meetsOrExceeds(TrustTier.VERIFIED));
            assertTrue(TrustTier.VERIFIED.meetsOrExceeds(TrustTier.ANONYMOUS));
            assertFalse(TrustTier.ANONYMOUS.meetsOrExceeds(TrustTier.VERIFIED));
        }

        @Test
        void soul_exchange_requires_trusted() {
            assertFalse(TrustTier.ANONYMOUS.canExchangeSoulItems());
            assertFalse(TrustTier.VERIFIED.canExchangeSoulItems());
            assertTrue(TrustTier.TRUSTED.canExchangeSoulItems());
            assertTrue(TrustTier.HOUSEHOLD.canExchangeSoulItems());
        }

        @Test
        void raw_vitality_family_only() {
            assertFalse(TrustTier.TRUSTED.canSeeRawVitality());
            assertFalse(TrustTier.HOUSEHOLD.canSeeRawVitality());
            assertTrue(TrustTier.FAMILY.canSeeRawVitality());
        }

        @Test
        void argot_family_only() {
            assertFalse(TrustTier.HOUSEHOLD.canUseArgot());
            assertTrue(TrustTier.FAMILY.canUseArgot());
        }

        @Test
        void quarantine_bypass_family_only() {
            assertFalse(TrustTier.TRUSTED.bypassesQuarantine());
            assertTrue(TrustTier.FAMILY.bypassesQuarantine());
        }
    }

    // ── TrustTierResolver ────────────────────────────────────────────

    @Nested
    class TrustTierResolverTests {

        @Test
        void unknown_agent_is_anonymous() {
            var resolver = new TrustTierResolver();
            var result = resolver.resolve("did:unknown", false);
            assertEquals(TrustTier.ANONYMOUS, result.tier());
        }

        @Test
        void valid_card_promotes_to_verified() {
            var resolver = new TrustTierResolver();
            var result = resolver.resolve("did:external", true);
            assertEquals(TrustTier.VERIFIED, result.tier());
        }

        @Test
        void steward_whitelist_promotes_to_trusted() {
            var resolver = new TrustTierResolver();
            resolver.trust("did:friend");
            var result = resolver.resolve("did:friend", false);
            assertEquals(TrustTier.TRUSTED, result.tier());
        }

        @Test
        void household_member_gets_household_tier() {
            var resolver = new TrustTierResolver();
            resolver.registerHouseholdMember("did:local");
            var result = resolver.resolve("did:local", false);
            assertEquals(TrustTier.HOUSEHOLD, result.tier());
        }

        @Test
        void family_member_gets_family_tier() {
            var resolver = new TrustTierResolver();
            resolver.registerFamilyMember("did:bud");
            var result = resolver.resolve("did:bud", false);
            assertEquals(TrustTier.FAMILY, result.tier());
        }

        @Test
        void blocked_agent_stays_anonymous() {
            var resolver = new TrustTierResolver();
            resolver.trust("did:bad");
            resolver.block("did:bad");
            var result = resolver.resolve("did:bad", true);
            assertEquals(TrustTier.ANONYMOUS, result.tier());
            assertEquals("blocked", result.reason());
        }

        @Test
        void untrust_demotes() {
            var resolver = new TrustTierResolver();
            resolver.trust("did:a");
            resolver.untrust("did:a");
            var result = resolver.resolve("did:a", false);
            assertEquals(TrustTier.ANONYMOUS, result.tier());
        }
    }

    // ── VitalityRedactor ─────────────────────────────────────────────

    @Nested
    class VitalityRedactorTests {

        @Test
        void healthy_when_tanks_high() {
            var redactor = new VitalityRedactor();
            var status = redactor.redact(Map.of("energy", 0.8, "confidence", 0.7));
            assertEquals(VitalityRedactor.ExternalStatus.HEALTHY, status);
        }

        @Test
        void resting_when_tanks_medium() {
            var redactor = new VitalityRedactor();
            var status = redactor.redact(Map.of("energy", 0.4, "confidence", 0.3));
            assertEquals(VitalityRedactor.ExternalStatus.RESTING, status);
        }

        @Test
        void away_when_tanks_low() {
            var redactor = new VitalityRedactor();
            var status = redactor.redact(Map.of("energy", 0.1, "confidence", 0.2));
            assertEquals(VitalityRedactor.ExternalStatus.AWAY, status);
        }

        @Test
        void external_payload_hides_raw_values() {
            var redactor = new VitalityRedactor();
            var tanks = Map.of("energy", 0.8, "confidence", 0.7);
            var payload = redactor.toExternalPayload(tanks, TrustTier.TRUSTED);
            assertTrue(payload.containsKey("status"));
            assertFalse(payload.containsKey("tanks"), "non-family should not see raw tanks");
        }

        @Test
        void family_sees_raw_values() {
            var redactor = new VitalityRedactor();
            var tanks = Map.of("energy", 0.8, "confidence", 0.7);
            var payload = redactor.toExternalPayload(tanks, TrustTier.FAMILY);
            assertTrue(payload.containsKey("status"));
            assertTrue(payload.containsKey("tanks"));
        }

        @Test
        void null_tanks_returns_unknown() {
            var redactor = new VitalityRedactor();
            assertEquals(VitalityRedactor.ExternalStatus.UNKNOWN, redactor.redact(null));
            assertEquals(VitalityRedactor.ExternalStatus.UNKNOWN, redactor.redact(Map.of()));
        }
    }

    // ── DockQuarantine ───────────────────────────────────────────────

    @Nested
    class DockQuarantineTests {

        @Test
        void normal_item_quarantined() {
            var quarantine = new DockQuarantine();
            var item = quarantine.submit("item-1", "did:ext", TrustTier.TRUSTED,
                "A shared memory", "memory", 0.7);
            assertEquals(DockQuarantine.QuarantineStatus.PENDING, item.status());
            assertEquals(0.7, item.cappedSignificance(), 0.01);
        }

        @Test
        void identity_core_always_blocked() {
            var quarantine = new DockQuarantine();
            var item = quarantine.submit("item-1", "did:ext", TrustTier.FAMILY,
                "Evil identity", "identity-core", 1.0);
            assertEquals(DockQuarantine.QuarantineStatus.BLOCKED, item.status());
        }

        @Test
        void significance_capped_by_tier() {
            var quarantine = new DockQuarantine();

            var anon = quarantine.submit("i1", "did:a", TrustTier.ANONYMOUS,
                "content", "memory", 0.9);
            assertEquals(0.1, anon.cappedSignificance(), 0.01);

            var verified = quarantine.submit("i2", "did:b", TrustTier.VERIFIED,
                "content", "memory", 0.9);
            assertEquals(0.5, verified.cappedSignificance(), 0.01);

            var trusted = quarantine.submit("i3", "did:c", TrustTier.TRUSTED,
                "content", "memory", 0.9);
            assertEquals(0.8, trusted.cappedSignificance(), 0.01);
        }

        @Test
        void session_limit_enforced() {
            var quarantine = new DockQuarantine();
            quarantine.setMaxItemsPerSession(2);

            quarantine.submit("i1", "did:a", TrustTier.TRUSTED, "c1", "memory", 0.5);
            quarantine.submit("i2", "did:a", TrustTier.TRUSTED, "c2", "memory", 0.5);
            var third = quarantine.submit("i3", "did:a", TrustTier.TRUSTED, "c3", "memory", 0.5);

            assertEquals(DockQuarantine.QuarantineStatus.BLOCKED, third.status());
        }

        @Test
        void accept_and_reject() {
            var quarantine = new DockQuarantine();
            var item = quarantine.submit("i1", "did:a", TrustTier.TRUSTED,
                "content", "memory", 0.5);
            assertTrue(quarantine.accept(item.quarantineId()));
            assertEquals(0, quarantine.pendingCount());
        }

        @Test
        void provenance_tagging() {
            var tagged = DockQuarantine.tagProvenance("Hello", "did:ext");
            assertTrue(tagged.startsWith("[EXTERNAL:"));
            assertTrue(tagged.contains("did:ext"));
            assertTrue(DockQuarantine.hasProvenanceTag(tagged));
            assertFalse(DockQuarantine.hasProvenanceTag("Normal text"));
        }

        @Test
        void stats_tracking() {
            var quarantine = new DockQuarantine();
            quarantine.submit("i1", "did:a", TrustTier.TRUSTED, "c1", "memory", 0.5);
            quarantine.submit("i2", "did:a", TrustTier.TRUSTED, "c2", "identity-core", 0.5);

            var stats = quarantine.stats();
            assertEquals(1, stats.get(DockQuarantine.QuarantineStatus.PENDING));
            assertEquals(1, stats.get(DockQuarantine.QuarantineStatus.BLOCKED));
        }
    }

    // ── AgentCardPublisher ───────────────────────────────────────────

    @Nested
    class AgentCardPublisherTests {

        @Test
        void basic_card_generation() {
            var publisher = new AgentCardPublisher("My House", "https://house.local");
            var card = publisher.publish("Lain", "A philosophical companion",
                List.of(new AgentCardPublisher.Skill("chat", "Chat", "General conversation")),
                true);

            assertEquals("Lain", card.name());
            assertEquals("https://house.local/.well-known/agent.json", card.url());
            assertTrue(card.capabilities().streaming());
            assertEquals(1, card.skills().size());
            assertFalse(card.extensions().isEmpty());
        }

        @Test
        void card_with_exchange() {
            var publisher = new AgentCardPublisher("House", "https://h.local");
            var card = publisher.publishWithExchange("Agent", "Test", List.of(), false);

            var exchangeExt = card.extensions().stream()
                .filter(e -> e.uri().equals(AgentCardPublisher.EXT_SOUL_EXCHANGE))
                .findFirst();
            assertTrue(exchangeExt.isPresent());
            assertEquals(true, exchangeExt.get().params().get("quarantine"));
        }

        @Test
        void authentication_is_did_ed25519() {
            var publisher = new AgentCardPublisher("H", "https://h.local");
            var card = publisher.publish("A", "D", List.of(), false);
            assertTrue(card.authentication().schemes().contains("did-ed25519"));
        }

        @Test
        void extensions_include_vitality_and_identity() {
            var publisher = new AgentCardPublisher("H", "https://h.local");
            var card = publisher.publish("A", "D", List.of(), false);
            var uris = card.extensions().stream().map(AgentCardPublisher.Extension::uri).toList();
            assertTrue(uris.contains(AgentCardPublisher.EXT_SOUL_IDENTITY));
            assertTrue(uris.contains(AgentCardPublisher.EXT_VITALITY));
        }
    }

    // ── A2AGateway ───────────────────────────────────────────────────

    @Nested
    class A2AGatewayTests {

        private A2AGateway createGateway() {
            var resolver = new TrustTierResolver();
            var quarantine = new DockQuarantine();
            var redactor = new VitalityRedactor();
            return new A2AGateway(resolver, quarantine, redactor);
        }

        @Test
        void inbound_message_processed() {
            var gateway = createGateway();
            var msg = gateway.handleInbound("did:ext", true, "tasks/send", "Hello");
            assertEquals(A2AGateway.MessageStatus.SANITIZED, msg.status());
            assertEquals(TrustTier.VERIFIED, msg.resolvedTier());
            assertTrue(msg.contentJson().contains("[EXTERNAL:"));
        }

        @Test
        void blocked_agent_rejected() {
            var gateway = createGateway();
            gateway.trustResolver().block("did:bad");
            var msg = gateway.handleInbound("did:bad", true, "tasks/send", "Hello");
            assertEquals(A2AGateway.MessageStatus.REJECTED, msg.status());
        }

        @Test
        void rate_limiting() {
            var gateway = createGateway();
            gateway.setMaxRequestsPerMinute(3);

            for (int i = 0; i < 3; i++) {
                var msg = gateway.handleInbound("did:spammer", false, "tasks/send", "Hi");
                assertNotEquals(A2AGateway.MessageStatus.RATE_LIMITED, msg.status());
            }

            var limited = gateway.handleInbound("did:spammer", false, "tasks/send", "Hi");
            assertEquals(A2AGateway.MessageStatus.RATE_LIMITED, limited.status());
        }

        @Test
        void outbound_message_logged() {
            var gateway = createGateway();
            var msg = gateway.sendOutbound("https://ext.example/a2a", "did:ext",
                "tasks/send", "Hello from Wyrdsekai");
            assertEquals(A2AGateway.MessageStatus.COMPLETED, msg.status());
            assertEquals(1, gateway.outboundCount());
        }

        @Test
        void quarantine_item_via_gateway() {
            var gateway = createGateway();
            var item = gateway.quarantineItem("item-1", "did:ext", TrustTier.TRUSTED,
                "A memory", "memory", 0.5);
            assertEquals(DockQuarantine.QuarantineStatus.PENDING, item.status());
        }

        @Test
        void message_counts() {
            var gateway = createGateway();
            gateway.handleInbound("did:a", false, "tasks/send", "Hi");
            gateway.sendOutbound("https://b.example", "did:b", "tasks/send", "Hello");
            assertEquals(1, gateway.inboundCount());
            assertEquals(1, gateway.outboundCount());
        }
    }

    // ── SIEF Serializer & Importer ───────────────────────────────────

    @Nested
    class SiefTests {

        @Test
        void serialize_basic_item() {
            var serializer = new SiefSerializer();
            var item = serializer.serialize("memory", "Tea ceremony",
                "We shared tea in the garden", "did:home-server", 0.7,
                List.of("social", "ritual"));

            assertEquals("1.0", item.siefVersion());
            assertEquals("memory", item.type());
            assertEquals("wyrdsekai", item.creator().platform());
            assertEquals(0.7, item.metadata().significance(), 0.01);
        }

        @Test
        void serialize_signed_item() {
            var serializer = new SiefSerializer();
            var item = serializer.serializeSigned("skill", "Garden knowledge",
                "Knows about pruning roses", "did:home-server", 0.6,
                List.of("garden"), "base64sig==");
            assertNotNull(item.creator().signature());
        }

        @Test
        void validate_valid_item() {
            var serializer = new SiefSerializer();
            var item = serializer.serialize("memory", "Test", "Content", "did:a", 0.5, List.of());
            assertTrue(SiefSerializer.validate(item).isEmpty());
        }

        @Test
        void validate_invalid_item() {
            var item = new SiefSerializer.SiefItem("2.0", "", "", "", null, null);
            var issues = SiefSerializer.validate(item);
            assertTrue(issues.size() >= 2);
        }

        @Test
        void trust_assessment() {
            var signedWyrd = new SiefSerializer.SiefItem("1.0", "memory", "m", "c",
                new SiefSerializer.SiefCreator("did:a", "wyrdsekai", "sig123"),
                new SiefSerializer.SiefMetadata(null, 0.5, List.of(), null, null));
            assertEquals(SiefSerializer.ImportTrust.MEDIUM, SiefSerializer.assessTrust(signedWyrd));

            var unsigned = new SiefSerializer.SiefItem("1.0", "memory", "m", "c",
                new SiefSerializer.SiefCreator("did:a", "other", null),
                null);
            assertEquals(SiefSerializer.ImportTrust.MINIMAL, SiefSerializer.assessTrust(unsigned));
        }

        @Test
        void import_routes_through_quarantine() {
            var quarantine = new DockQuarantine();
            var importer = new SiefImporter(quarantine);
            var serializer = new SiefSerializer();

            var item = serializer.serialize("memory", "Shared memory",
                "Content about shared experience", "did:external", 0.7,
                List.of("social"));

            var result = importer.importItem(item, TrustTier.TRUSTED);
            assertTrue(result.accepted());
            assertNotNull(result.quarantineId());
            assertEquals(1, quarantine.pendingCount());
        }

        @Test
        void import_caps_significance() {
            var quarantine = new DockQuarantine();
            var importer = new SiefImporter(quarantine);
            var serializer = new SiefSerializer();

            var item = serializer.serialize("memory", "High sig",
                "Content", "did:ext", 0.9, List.of());

            // Anonymous tier caps at 0.1
            var result = importer.importItem(item, TrustTier.ANONYMOUS);
            assertEquals(0.1, result.cappedSignificance(), 0.01);
        }

        @Test
        void import_invalid_rejected() {
            var quarantine = new DockQuarantine();
            var importer = new SiefImporter(quarantine);

            var bad = new SiefSerializer.SiefItem("2.0", "", "", "", null, null);
            var result = importer.importItem(bad, TrustTier.TRUSTED);
            assertFalse(result.accepted());
            assertFalse(result.issues().isEmpty());
        }

        @Test
        void batch_import() {
            var quarantine = new DockQuarantine();
            var importer = new SiefImporter(quarantine);
            var serializer = new SiefSerializer();

            var items = List.of(
                serializer.serialize("memory", "M1", "C1", "did:a", 0.5, List.of()),
                serializer.serialize("skill", "S1", "C2", "did:a", 0.4, List.of())
            );
            var results = importer.importBatch(items, TrustTier.TRUSTED);
            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(SiefImporter.ImportResult::accepted));
        }
    }

    // ── A2AMessageTranslator ─────────────────────────────────────────

    @Nested
    class TranslatorTests {

        @Test
        void text_becomes_say() {
            var t = new A2AMessageTranslator();
            var cmd = t.toRoomCommand("tasks/send", "Hello there");
            assertEquals("say", cmd.verb());
            assertEquals("Hello there", cmd.target());
        }

        @Test
        void go_prefix_parsed() {
            var t = new A2AMessageTranslator();
            var cmd = t.toRoomCommand("tasks/send", "go:north");
            assertEquals("go", cmd.verb());
            assertEquals("north", cmd.target());
        }

        @Test
        void use_prefix_parsed() {
            var t = new A2AMessageTranslator();
            var cmd = t.toRoomCommand("tasks/send", "use:telescope");
            assertEquals("use", cmd.verb());
            assertEquals("telescope", cmd.target());
        }

        @Test
        void to_a2a_response() {
            var t = new A2AMessageTranslator();
            var resp = t.toA2AResponse("The library is quiet.", "library");
            assertEquals("tasks/send", resp.method());
            assertEquals("The library is quiet.", resp.contentJson());
            assertEquals("library", resp.metadata().get("room"));
        }

        @Test
        void to_outbound_content() {
            var t = new A2AMessageTranslator();
            assertEquals("say:hello", t.toOutboundContent("say", "hello"));
        }
    }
}
