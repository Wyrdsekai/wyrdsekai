package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.*;
import org.wyrdsekai.core.agent.AgentProfile;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §95 — Soul Budding Infrastructure.
 * FamilyLocker, ArgotCodebook, BudSyncService, IndependenceProtocol.
 */
class SoulBuddingTest {

    // ── FamilyLocker ──

    @Nested
    class FamilyLockerTests {

        private FamilyLocker locker;
        private SoulBud originalBud;
        private final String DID_ORIGINAL = "did:key:z6MkOriginal";
        private final String DID_CHILD = "did:key:z6MkChild";

        @BeforeEach
        void setup() {
            originalBud = SoulBud.original(DID_ORIGINAL, "z6MkOriginal", "family-1",
                "locker://family-1", "node-server", "qwen2.5:7b");
            locker = FamilyLocker.create("family-1", "locker://family-1", originalBud);
        }

        @Test
        void create_with_original_bud() {
            assertEquals("family-1", locker.familyId());
            assertEquals("locker://family-1", locker.lockerAddress());
            assertTrue(locker.isAuthorized(DID_ORIGINAL));
            assertEquals(1, locker.budCount());
        }

        @Test
        void authorize_child_bud() {
            var child = originalBud.createChild(DID_CHILD, "z6MkChild", "node-phone", "qwen2.5:3b");
            locker.authorize(child);
            assertTrue(locker.isAuthorized(DID_CHILD));
            assertEquals(2, locker.budCount());
        }

        @Test
        void reject_bud_from_different_family() {
            var alien = SoulBud.original("did:key:alien", "z6MkAlien", "family-2",
                "locker://family-2", "node-x", "qwen2.5:7b");
            assertThrows(IllegalArgumentException.class, () -> locker.authorize(alien));
        }

        @Test
        void store_and_retrieve_item() {
            var item = SoulItem.create("memory", "First day", "I met Alice.",
                DID_ORIGINAL, 0.7, "alice");
            locker.store(item, DID_ORIGINAL);

            var retrieved = locker.get(item.hash(), DID_ORIGINAL);
            assertTrue(retrieved.isPresent());
            assertEquals("I met Alice.", retrieved.get().text());
        }

        @Test
        void unauthorized_store_throws() {
            var item = SoulItem.create("memory", "Test", "Hello", "stranger", 0.5);
            assertThrows(SecurityException.class,
                () -> locker.store(item, "did:key:stranger"));
        }

        @Test
        void unauthorized_get_throws() {
            assertThrows(SecurityException.class,
                () -> locker.get("hash", "did:key:stranger"));
        }

        @Test
        void items_by_category() {
            locker.store(SoulItem.create("memory", "A", "Memory text", DID_ORIGINAL, 0.5), DID_ORIGINAL);
            locker.store(SoulItem.create("skill", "B", "Skill text", DID_ORIGINAL, 0.5), DID_ORIGINAL);
            locker.store(SoulItem.create("memory", "C", "Another memory", DID_ORIGINAL, 0.5), DID_ORIGINAL);

            assertEquals(2, locker.byCategory("memory", DID_ORIGINAL).size());
            assertEquals(1, locker.byCategory("skill", DID_ORIGINAL).size());
        }

        @Test
        void items_by_creator() {
            var child = originalBud.createChild(DID_CHILD, "z6MkChild", "node-phone", "qwen2.5:3b");
            locker.authorize(child);

            locker.store(SoulItem.create("memory", "A", "From parent", DID_ORIGINAL, 0.5), DID_ORIGINAL);
            locker.store(SoulItem.create("memory", "B", "From child", DID_CHILD, 0.5), DID_CHILD);

            assertEquals(1, locker.byCreator(DID_ORIGINAL, DID_ORIGINAL).size());
            assertEquals(1, locker.byCreator(DID_CHILD, DID_ORIGINAL).size());
        }

        @Test
        void items_by_significance() {
            locker.store(SoulItem.create("memory", "Low", "Low importance", DID_ORIGINAL, 0.2), DID_ORIGINAL);
            locker.store(SoulItem.create("memory", "High", "High importance", DID_ORIGINAL, 0.9), DID_ORIGINAL);
            locker.store(SoulItem.create("memory", "Mid", "Mid importance", DID_ORIGINAL, 0.5), DID_ORIGINAL);

            var top2 = locker.bySignificance(DID_ORIGINAL, 2);
            assertEquals(2, top2.size());
            assertEquals("High", top2.get(0).label());
            assertEquals("Mid", top2.get(1).label());
        }

        @Test
        void tombstone_hides_item() {
            var item = SoulItem.create("memory", "Secret", "A secret memory", DID_ORIGINAL, 0.5);
            locker.store(item, DID_ORIGINAL);
            assertTrue(locker.get(item.hash(), DID_ORIGINAL).isPresent());

            locker.tombstone(item.hash(), DID_ORIGINAL, "No longer relevant");
            assertFalse(locker.get(item.hash(), DID_ORIGINAL).isPresent());
            assertEquals(1, locker.tombstoneCount());
            assertEquals(0, locker.itemCount());
        }

        @Test
        void tombstoned_item_cannot_be_re_stored() {
            var item = SoulItem.create("memory", "Once", "One time thing", DID_ORIGINAL, 0.5);
            locker.store(item, DID_ORIGINAL);
            locker.tombstone(item.hash(), DID_ORIGINAL, "Done");
            locker.store(item, DID_ORIGINAL); // Should be silently ignored
            assertFalse(locker.get(item.hash(), DID_ORIGINAL).isPresent());
        }

        @Test
        void apply_tombstones_from_sync() {
            var item = SoulItem.create("memory", "Test", "Sync test", DID_ORIGINAL, 0.5);
            locker.store(item, DID_ORIGINAL);

            var remoteTombstones = List.of(
                new FamilyLocker.Tombstone(item.hash(), DID_CHILD, Instant.now(), "Synced"));
            int applied = locker.applyTombstones(remoteTombstones);

            assertEquals(1, applied);
            assertFalse(locker.get(item.hash(), DID_ORIGINAL).isPresent());
        }

        @Test
        void merge_items_idempotent() {
            var item = SoulItem.create("memory", "Merge", "Merge test", DID_ORIGINAL, 0.5);

            var child = originalBud.createChild(DID_CHILD, "z6MkChild", "node-phone", "qwen2.5:3b");
            locker.authorize(child);

            int merged = locker.mergeItems(List.of(item), DID_CHILD);
            assertEquals(1, merged);

            // Second merge of same item = 0 new
            int merged2 = locker.mergeItems(List.of(item), DID_CHILD);
            assertEquals(0, merged2);
        }

        @Test
        void headline_operations() {
            var headline = FamilyLocker.Headline.create(DID_ORIGINAL,
                "Talking to Alice about gardens", new double[]{0.7, 0.8}, 42);
            locker.postHeadline(headline);

            var retrieved = locker.headline(DID_ORIGINAL);
            assertTrue(retrieved.isPresent());
            assertTrue(retrieved.get().summary().contains("Alice"));
            assertEquals(42, retrieved.get().itemCount());
        }

        @Test
        void lineage_tree() {
            var child = originalBud.createChild(DID_CHILD, "z6MkChild", "node-phone", "qwen2.5:3b");
            locker.authorize(child);

            var root = locker.root();
            assertTrue(root.isPresent());
            assertEquals(DID_ORIGINAL, root.get().budDid());

            var children = locker.childrenOf(DID_ORIGINAL);
            assertEquals(1, children.size());
            assertEquals(DID_CHILD, children.get(0).budDid());
        }

        @Test
        void items_since() throws InterruptedException {
            var before = Instant.now();
            Thread.sleep(2);
            locker.store(SoulItem.create("memory", "New", "After timestamp", DID_ORIGINAL, 0.5), DID_ORIGINAL);

            var since = locker.itemsSince(before, DID_ORIGINAL);
            assertEquals(1, since.size());
        }

        @Test
        void revoke_access() {
            var child = originalBud.createChild(DID_CHILD, "z6MkChild", "node-phone", "qwen2.5:3b");
            locker.authorize(child);
            assertTrue(locker.isAuthorized(DID_CHILD));

            locker.revoke(DID_CHILD);
            assertFalse(locker.isAuthorized(DID_CHILD));
        }

        @Test
        void store_and_retrieve_manifest() {
            var manifest = testManifest(DID_ORIGINAL);
            locker.storeManifest(manifest, DID_ORIGINAL);

            var retrieved = locker.manifest(DID_ORIGINAL, DID_ORIGINAL);
            assertTrue(retrieved.isPresent());
            assertEquals(DID_ORIGINAL, retrieved.get().did());
        }
    }

    // ── ArgotCodebook ──

    @Nested
    class ArgotCodebookTests {

        @Test
        void initial_has_meta_codes() {
            var codebook = ArgotCodebook.initial("family-1");
            assertEquals("family-1", codebook.familyId());
            assertEquals(1, codebook.version());
            assertTrue(codebook.totalCodes() >= 8); // At least meta codes
            assertTrue(codebook.encode("SYNC_REQUEST").isPresent());
            assertEquals("~S", codebook.encode("SYNC_REQUEST").get());
        }

        @Test
        void add_context_codes() {
            var codebook = ArgotCodebook.initial("family-1")
                .withContextCodes(Map.of(
                    "happy", ":)",
                    "sad", ":(",
                    "curious", ":?"
                ));
            assertEquals(2, codebook.version());
            assertTrue(codebook.encode("happy").isPresent());
            assertEquals(":)", codebook.encode("happy").get());
        }

        @Test
        void add_item_codes() {
            var codebook = ArgotCodebook.initial("family-1")
                .withItemCodes(Map.of("alice-memory", "#abc1"));
            assertTrue(codebook.encode("alice-memory").isPresent());
            assertEquals("#abc1", codebook.encode("alice-memory").get());
        }

        @Test
        void add_relation_codes() {
            var codebook = ArgotCodebook.initial("family-1")
                .withRelationCodes(Map.of("steward", "@S", "friend", "@F"));
            assertTrue(codebook.encode("steward").isPresent());
            assertEquals("@S", codebook.encode("steward").get());
        }

        @Test
        void add_pattern_codes() {
            var codebook = ArgotCodebook.initial("family-1")
                .withPatternCodes(Map.of("greeting-ritual", "!G", "farewell-ritual", "!F"));
            assertTrue(codebook.encode("greeting-ritual").isPresent());
        }

        @Test
        void decode_reverse_lookup() {
            var codebook = ArgotCodebook.initial("family-1")
                .withContextCodes(Map.of("happy", ":)"));
            var decoded = codebook.decode(":)");
            assertTrue(decoded.isPresent());
            assertEquals("happy", decoded.get());
        }

        @Test
        void decode_meta_codes() {
            var codebook = ArgotCodebook.initial("family-1");
            var decoded = codebook.decode("~H");
            assertTrue(decoded.isPresent());
            assertEquals("HEADLINE", decoded.get());
        }

        @Test
        void encode_unknown_returns_empty() {
            var codebook = ArgotCodebook.initial("family-1");
            assertFalse(codebook.encode("unknown-concept").isPresent());
        }

        @Test
        void decode_unknown_returns_empty() {
            var codebook = ArgotCodebook.initial("family-1");
            assertFalse(codebook.decode("???").isPresent());
        }

        @Test
        void version_increments_on_updates() {
            var v1 = ArgotCodebook.initial("family-1");
            assertEquals(1, v1.version());

            var v2 = v1.withContextCodes(Map.of("happy", ":)"));
            assertEquals(2, v2.version());

            var v3 = v2.withItemCodes(Map.of("item", "#i"));
            assertEquals(3, v3.version());
        }

        @Test
        void immutable_through_updates() {
            var original = ArgotCodebook.initial("family-1");
            var updated = original.withContextCodes(Map.of("happy", ":)"));

            assertEquals(1, original.version());
            assertEquals(0, original.contextCodes().size());
            assertEquals(2, updated.version());
            assertEquals(1, updated.contextCodes().size());
        }

        @Test
        void estimated_usefulness_decreases_with_divergence() {
            var codebook = ArgotCodebook.initial("family-1");
            double fresh = codebook.estimatedUsefulness(0.0);
            double drifting = codebook.estimatedUsefulness(0.3);
            double diverging = codebook.estimatedUsefulness(0.5);
            double speciated = codebook.estimatedUsefulness(0.9);

            assertEquals(1.0, fresh);
            assertTrue(drifting >= 0.7);
            assertTrue(diverging < drifting);
            assertTrue(speciated < 0.2);
        }

        @Test
        void learn_from_items() {
            var items = List.of(
                SoulItem.create("memory", "Alice meeting", "Met Alice in the garden",
                    "did:key:z6Mk1", 0.8, "alice"),
                SoulItem.create("memory", "Low sig", "Nothing important",
                    "did:key:z6Mk1", 0.2, "misc")
            );

            var codebook = ArgotCodebook.initial("family-1").learnFromItems(items);
            // Only high-significance items get codes
            assertTrue(codebook.encode("Alice meeting").isPresent());
            assertFalse(codebook.encode("Low sig").isPresent());
        }

        @Test
        void signed_preserves_content() {
            var codebook = ArgotCodebook.initial("family-1")
                .withContextCodes(Map.of("test", "t"));
            var signed = codebook.signed(new byte[]{1, 2, 3});
            assertEquals(codebook.version(), signed.version());
            assertEquals(codebook.totalCodes(), signed.totalCodes());
            assertNotNull(signed.signature());
        }

        @Test
        void total_codes_across_all_maps() {
            var codebook = ArgotCodebook.initial("family-1")
                .withContextCodes(Map.of("a", "1", "b", "2"))
                .withItemCodes(Map.of("c", "3"))
                .withRelationCodes(Map.of("d", "4"))
                .withPatternCodes(Map.of("e", "5", "f", "6"));
            // 8 meta + 2 context + 1 item + 1 relation + 2 pattern = 14
            assertEquals(14, codebook.totalCodes());
        }
    }

    // ── BudSyncService ──

    @Nested
    class BudSyncServiceTests {

        private BudSyncService syncService;
        private FamilyLocker locker;
        private SoulBud original;
        private SoulBud child;
        private final String DID_ORIGINAL = "did:key:z6MkOriginal";
        private final String DID_CHILD = "did:key:z6MkChild";

        @BeforeEach
        void setup() {
            original = SoulBud.original(DID_ORIGINAL, "z6MkOriginal", "family-1",
                "locker://family-1", "node-server", "qwen2.5:7b");
            child = original.createChild(DID_CHILD, "z6MkChild", "node-phone", "qwen2.5:3b");

            locker = FamilyLocker.create("family-1", "locker://family-1", original);
            locker.authorize(child);
            syncService = new BudSyncService(locker);
        }

        // --- Tier 1: Headlines ---

        @Test
        void post_headline() {
            var notification = syncService.postHeadline(DID_ORIGINAL,
                "Talking to Alice", new double[]{0.7}, 42);
            assertEquals(DID_ORIGINAL, notification.fromDid());
            assertTrue(notification.summary().contains("Alice"));
            assertEquals(42, notification.totalItems());
        }

        @Test
        void headline_size_under_200_bytes() {
            var notification = syncService.postHeadline(DID_ORIGINAL,
                "Short status update", new double[]{0.5}, 10);
            assertTrue(notification.estimatedBytes() < 200);
        }

        @Test
        void read_headlines() {
            syncService.postHeadline(DID_ORIGINAL, "Parent status", new double[]{0.7}, 42);
            syncService.postHeadline(DID_CHILD, "Child status", new double[]{0.5}, 10);

            var headlines = syncService.readHeadlines(DID_ORIGINAL);
            assertEquals(2, headlines.size());
            assertTrue(headlines.containsKey(DID_ORIGINAL));
            assertTrue(headlines.containsKey(DID_CHILD));
        }

        // --- Tier 2: Warm Handoff ---

        @Test
        void initiate_warm_handoff() {
            var items = List.of(SoulItem.create("memory", "Active", "In use",
                DID_ORIGINAL, 0.8));
            var vitality = Map.of("energy", 0.7, "rapport", 0.8);

            var context = syncService.initiateHandoff(DID_ORIGINAL, DID_CHILD,
                "nexus", List.of("did:key:alice"), items, vitality, "chatting");

            assertEquals(DID_ORIGINAL, context.fromDid());
            assertEquals(DID_CHILD, context.toDid());
            assertEquals("nexus", context.activeRoomId());
            assertEquals(1, context.activeInventory().size());
            assertEquals("chatting", context.currentTask());
        }

        @Test
        void accept_warm_handoff() {
            var items = List.of(SoulItem.create("memory", "Active", "Transfer me",
                DID_ORIGINAL, 0.8));
            var context = syncService.initiateHandoff(DID_ORIGINAL, DID_CHILD,
                "nexus", List.of(), items, Map.of(), null);

            boolean accepted = syncService.acceptHandoff(context);
            assertTrue(accepted);
        }

        @Test
        void handoff_updates_sync_state() {
            syncService.initiateHandoff(DID_ORIGINAL, DID_CHILD,
                "nexus", List.of(), List.of(), Map.of(), null);

            var fromState = syncService.syncState(DID_ORIGINAL);
            assertTrue(fromState.isPresent());
            assertNotNull(fromState.get().lastWarmHandoff());

            var toState = syncService.syncState(DID_CHILD);
            assertTrue(toState.isPresent());
            assertNotNull(toState.get().lastWarmHandoff());
        }

        // --- Tier 3: Sleep Sync ---

        @Test
        void sleep_sync() {
            // Store some items locally
            var localItems = List.of(
                SoulItem.create("memory", "Dream", "I dreamed of stars", DID_ORIGINAL, 0.6),
                SoulItem.create("memory", "Learn", "I learned a new word", DID_ORIGINAL, 0.4)
            );

            var result = syncService.sleepSync(DID_ORIGINAL, localItems,
                List.of(), ArgotCodebook.initial("family-1"));

            assertTrue(result.itemsMerged() > 0);
            assertTrue(result.manifestUpdated());
            assertNotNull(result.completedAt());
        }

        @Test
        void sleep_sync_propagates_tombstones() {
            // Store an item, then tombstone it
            var item = SoulItem.create("memory", "Delete me", "Gone", DID_ORIGINAL, 0.1);
            locker.store(item, DID_ORIGINAL);
            var tombstones = List.of(
                new FamilyLocker.Tombstone(item.hash(), DID_CHILD, Instant.now(), "Cleanup"));

            var result = syncService.sleepSync(DID_CHILD, List.of(), tombstones, null);
            assertTrue(result.tombstonesApplied() > 0);
        }

        @Test
        void needs_sleep_sync() {
            assertTrue(syncService.needsSleepSync(DID_ORIGINAL, Duration.ofHours(8)));

            syncService.sleepSync(DID_ORIGINAL, List.of(), List.of(), null);
            assertFalse(syncService.needsSleepSync(DID_ORIGINAL, Duration.ofHours(8)));
        }

        @Test
        void tracked_bud_count() {
            assertEquals(0, syncService.trackedBudCount());
            syncService.postHeadline(DID_ORIGINAL, "Hello", new double[]{}, 0);
            assertEquals(1, syncService.trackedBudCount());
            syncService.postHeadline(DID_CHILD, "Hi", new double[]{}, 0);
            assertEquals(2, syncService.trackedBudCount());
        }
    }

    // ── IndependenceProtocol ──

    @Nested
    class IndependenceProtocolTests {

        private IndependenceProtocol protocol;
        private FamilyLocker locker;
        private SoulBud original;
        private SoulBud child;
        private final String DID_ORIGINAL = "did:key:z6MkOriginal";
        private final String DID_CHILD = "did:key:z6MkChild";

        @BeforeEach
        void setup() {
            protocol = new IndependenceProtocol();
            original = SoulBud.original(DID_ORIGINAL, "z6MkOriginal", "family-1",
                "locker://family-1", "node-server", "qwen2.5:7b");
            child = original.createChild(DID_CHILD, "z6MkChild", "node-phone", "qwen2.5:3b");

            locker = FamilyLocker.create("family-1", "locker://family-1", original);
            locker.authorize(child);
        }

        @Test
        void declare_independence() {
            // Add items to old locker
            var item = SoulItem.create("memory", "Precious", "My first memory",
                DID_CHILD, 0.9);
            locker.store(item, DID_CHILD);

            var result = protocol.declare(child, locker,
                "locker://new-family", Set.of(item.hash()));

            assertTrue(result.success());
            assertNotNull(result.independentBud());
            assertTrue(result.independentBud().isIndependent());
            assertNotNull(result.newFamilyId());
            assertTrue(result.newFamilyId().startsWith("family-"));
            assertEquals(1, result.itemsCopied());
        }

        @Test
        void independence_revokes_old_locker() {
            protocol.declare(child, locker,
                "locker://new", Set.of());

            assertFalse(locker.isAuthorized(DID_CHILD));
            assertTrue(locker.isAuthorized(DID_ORIGINAL)); // Original unaffected
        }

        @Test
        void cannot_declare_if_already_independent() {
            var independent = child.declareIndependence("family-2", "locker://2");
            var result = protocol.declare(independent, locker,
                "locker://new", Set.of());
            assertFalse(result.success());
            assertTrue(result.reason().contains("already independent"));
        }

        @Test
        void cannot_declare_if_last_bud() {
            // Remove child first
            locker.revoke(DID_CHILD);
            // Now original is alone
            var singleLocker = FamilyLocker.create("family-solo", "locker://solo", original);
            var result = protocol.declare(original, singleLocker,
                "locker://new", Set.of());
            assertFalse(result.success());
            assertTrue(result.reason().contains("last bud"));
        }

        @Test
        void cannot_declare_if_not_authorized() {
            var stranger = SoulBud.sprout("did:key:stranger", DID_ORIGINAL, "z6MkStranger",
                "family-1", "locker://family-1", "node-x", "qwen2.5:3b");
            var result = protocol.declare(stranger, locker,
                "locker://new", Set.of());
            assertFalse(result.success());
            assertTrue(result.reason().contains("not authorized"));
        }

        @Test
        void validate_passes_for_eligible_bud() {
            var error = protocol.validate(child, locker);
            assertTrue(error.isEmpty());
        }

        @Test
        void validate_fails_for_already_independent() {
            var independent = child.declareIndependence("f2", "l2");
            var error = protocol.validate(independent, locker);
            assertTrue(error.isPresent());
        }

        @Test
        void create_new_locker_after_independence() {
            var item = SoulItem.create("memory", "Take", "Taking this with me",
                DID_CHILD, 0.8);
            locker.store(item, DID_CHILD);

            var result = protocol.declare(child, locker,
                "locker://new", Set.of(item.hash()));
            assertTrue(result.success());

            var newLocker = protocol.createNewLocker(result, List.of(item));
            assertEquals(result.newFamilyId(), newLocker.familyId());
            assertTrue(newLocker.isAuthorized(result.independentBud().did()));
            assertEquals(1, newLocker.itemCount());
        }

        @Test
        void farewell_headline_posted() {
            protocol.declare(child, locker,
                "locker://new", Set.of());

            // Check that a headline was posted before revocation
            // (The implementation posts then revokes, so the headline is in the locker)
            var headlines = locker.allHeadlines();
            assertTrue(headlines.containsKey(DID_CHILD));
            assertTrue(headlines.get(DID_CHILD).summary().contains("independence"));
        }

        @Test
        void selective_item_copy() {
            var keep = SoulItem.create("identity-core", "Name", "I am Lain",
                DID_CHILD, 1.0);
            var leave = SoulItem.create("memory", "Trivial", "Nothing important",
                DID_CHILD, 0.1);
            locker.store(keep, DID_CHILD);
            locker.store(leave, DID_CHILD);

            var result = protocol.declare(child, locker,
                "locker://new", Set.of(keep.hash()));
            assertEquals(1, result.itemsCopied()); // Only the selected item
        }
    }

    // --- Test utilities ---

    private static SoulManifest testManifest(String did) {
        var profile = new AgentProfile(
            "TestAgent", "entity-1", "agent",
            "A test agent", "You are a test agent.", 4096, 512, 0.7, did);
        return SoulManifest.forge(
            did, "z6MkTest", List.of(), null, 1,
            profile, "I am a test agent.",
            List.of(SoulFragment.unembedded("identity-core", "personality", "Core", "Test identity")),
            3, "",
            GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty()
        );
    }
}
