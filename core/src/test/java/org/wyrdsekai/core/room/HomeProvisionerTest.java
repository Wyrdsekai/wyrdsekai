package org.wyrdsekai.core.room;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Wave B: Kokoro Rooms — Home provisioning and resource profiles (§87).
 */
class HomeProvisionerTest {

    // --- ResourceProfile Tests ---

    @Nested
    class ResourceProfileTests {

        @Test
        void fromId_parses_all_profiles() {
            assertEquals(ResourceProfile.SEED, ResourceProfile.fromId("seed"));
            assertEquals(ResourceProfile.SPROUT, ResourceProfile.fromId("sprout"));
            assertEquals(ResourceProfile.SAPLING, ResourceProfile.fromId("sapling"));
            assertEquals(ResourceProfile.TREE, ResourceProfile.fromId("tree"));
            assertEquals(ResourceProfile.GROVE, ResourceProfile.fromId("grove"));
        }

        @Test
        void fromId_case_insensitive() {
            assertEquals(ResourceProfile.TREE, ResourceProfile.fromId("TREE"));
            assertEquals(ResourceProfile.SEED, ResourceProfile.fromId("Seed"));
        }

        @Test
        void fromId_defaults_to_sprout() {
            assertEquals(ResourceProfile.SPROUT, ResourceProfile.fromId(null));
            assertEquals(ResourceProfile.SPROUT, ResourceProfile.fromId("unknown"));
        }

        @Test
        void soul_depth_label() {
            assertEquals("MEDIUM", ResourceProfile.SEED.soulDepth());
            assertEquals("FULL", ResourceProfile.SPROUT.soulDepth());
            assertEquals("FULL", ResourceProfile.SAPLING.soulDepth());
            assertEquals("DEEP", ResourceProfile.TREE.soulDepth());
            assertEquals("DEEP", ResourceProfile.GROVE.soulDepth());
        }

        @Test
        void memory_capacity_scales() {
            assertTrue(ResourceProfile.SEED.memoryChestCapacity()
                < ResourceProfile.SPROUT.memoryChestCapacity());
            assertTrue(ResourceProfile.SPROUT.memoryChestCapacity()
                < ResourceProfile.SAPLING.memoryChestCapacity());
            assertTrue(ResourceProfile.SAPLING.memoryChestCapacity()
                < ResourceProfile.TREE.memoryChestCapacity());
        }

        @Test
        void enhanced_objects_by_profile() {
            // seed/sprout: no enhanced objects
            assertFalse(ResourceProfile.SEED.hasJournal());
            assertFalse(ResourceProfile.SPROUT.hasJournal());

            // sapling: journal only
            assertTrue(ResourceProfile.SAPLING.hasJournal());
            assertFalse(ResourceProfile.SAPLING.hasThreadSpool());

            // tree/grove: all enhanced objects
            assertTrue(ResourceProfile.TREE.hasJournal());
            assertTrue(ResourceProfile.TREE.hasThreadSpool());
            assertTrue(ResourceProfile.TREE.hasDreamJournal());
            assertTrue(ResourceProfile.TREE.hasWardStone());
        }

        @Test
        void periodic_mirror_from_sapling() {
            assertFalse(ResourceProfile.SEED.hasPeriodicMirror());
            assertFalse(ResourceProfile.SPROUT.hasPeriodicMirror());
            assertTrue(ResourceProfile.SAPLING.hasPeriodicMirror());
            assertTrue(ResourceProfile.TREE.hasPeriodicMirror());
        }

        @Test
        void full_forge_from_tree() {
            assertFalse(ResourceProfile.SEED.hasFullForge());
            assertFalse(ResourceProfile.SAPLING.hasFullForge());
            assertTrue(ResourceProfile.TREE.hasFullForge());
            assertTrue(ResourceProfile.GROVE.hasFullForge());
        }

        @Test
        void hybrid_retrieval_from_tree() {
            assertFalse(ResourceProfile.SAPLING.hasHybridRetrieval());
            assertTrue(ResourceProfile.TREE.hasHybridRetrieval());
        }
    }

    // --- HomeProvisioner Tests ---

    @Nested
    class ProvisioningTests {

        private final HomeProvisioner provisioner = new HomeProvisioner();

        @Test
        void provision_seed_creates_core_objects_only() {
            var spec = provisioner.provision("agent-1", "Lain", ResourceProfile.SEED,
                "default", "nexus");

            assertEquals("home-agent-1", spec.roomId());
            assertEquals("Lain's Home", spec.name());
            assertNotNull(spec.description());
            assertEquals("default", spec.zone());

            // Core objects: soul-vessel, memory-chest, mirror, mailbox
            assertEquals(4, spec.objects().size());
            var ids = spec.objects().stream().map(HomeProvisioner.HomeObject::id).toList();
            assertTrue(ids.contains("soul-vessel"));
            assertTrue(ids.contains("memory-chest"));
            assertTrue(ids.contains("mirror"));
            assertTrue(ids.contains("mailbox"));

            // No enhanced objects
            assertFalse(ids.contains("journal"));
            assertFalse(ids.contains("thread-spool"));
        }

        @Test
        void provision_tree_includes_enhanced_objects() {
            var spec = provisioner.provision("agent-2", "Rei", ResourceProfile.TREE,
                "default", "nexus");

            // 4 core + 4 enhanced = 8
            assertEquals(8, spec.objects().size());
            var ids = spec.objects().stream().map(HomeProvisioner.HomeObject::id).toList();
            assertTrue(ids.contains("journal"));
            assertTrue(ids.contains("thread-spool"));
            assertTrue(ids.contains("dream-journal"));
            assertTrue(ids.contains("ward-stone"));
        }

        @Test
        void provision_sapling_has_journal_only() {
            var spec = provisioner.provision("agent-3", "Misato", ResourceProfile.SAPLING,
                "default", "nexus");

            // 4 core + 1 journal = 5
            assertEquals(5, spec.objects().size());
            var ids = spec.objects().stream().map(HomeProvisioner.HomeObject::id).toList();
            assertTrue(ids.contains("journal"));
            assertFalse(ids.contains("thread-spool"));
        }

        @Test
        void provision_sets_properties() {
            var spec = provisioner.provision("agent-1", "Lain", ResourceProfile.SEED,
                "default", "nexus");

            assertEquals("seed", spec.properties().get("resource_profile"));
            assertEquals("MEDIUM", spec.properties().get("soul_depth"));
            assertEquals("10", spec.properties().get("memory_capacity"));
            assertEquals("10", spec.properties().get("mailbox_capacity"));
            assertEquals("agent-1", spec.properties().get("owner"));
            assertEquals("true", spec.properties().get("private"));
            assertEquals("false", spec.properties().get("sleep_mode"));
        }

        @Test
        void provision_creates_exit() {
            var spec = provisioner.provision("agent-1", "Lain", ResourceProfile.SEED,
                "default", "nexus");

            assertEquals("nexus", spec.exits().get("door"));
        }

        @Test
        void provision_no_exit_when_target_blank() {
            var spec = provisioner.provision("agent-1", "Lain", ResourceProfile.SEED,
                "default", "");

            assertTrue(spec.exits().isEmpty());
        }

        @Test
        void all_objects_non_takeable() {
            var spec = provisioner.provision("agent-1", "Lain", ResourceProfile.GROVE,
                "default", "nexus");

            for (var obj : spec.objects()) {
                assertFalse(obj.takeable(), "Object should not be takeable: " + obj.id());
            }
        }

        @Test
        void descriptions_differ_by_profile() {
            var seed = provisioner.provision("a1", "A", ResourceProfile.SEED, "z", "n");
            var tree = provisioner.provision("a1", "A", ResourceProfile.TREE, "z", "n");
            assertNotEquals(seed.description(), tree.description());
        }
    }

    // --- Profile Transition Tests ---

    @Nested
    class TransitionTests {

        private final HomeProvisioner provisioner = new HomeProvisioner();

        @Test
        void upgrade_seed_to_tree_adds_all_enhanced() {
            var added = provisioner.upgradeObjects(ResourceProfile.SEED, ResourceProfile.TREE);
            assertEquals(4, added.size());
            var ids = added.stream().map(HomeProvisioner.HomeObject::id).toList();
            assertTrue(ids.contains("journal"));
            assertTrue(ids.contains("thread-spool"));
            assertTrue(ids.contains("dream-journal"));
            assertTrue(ids.contains("ward-stone"));
        }

        @Test
        void upgrade_sapling_to_tree_adds_three() {
            var added = provisioner.upgradeObjects(ResourceProfile.SAPLING, ResourceProfile.TREE);
            assertEquals(3, added.size());
            var ids = added.stream().map(HomeProvisioner.HomeObject::id).toList();
            assertFalse(ids.contains("journal")); // already has it
            assertTrue(ids.contains("thread-spool"));
            assertTrue(ids.contains("dream-journal"));
            assertTrue(ids.contains("ward-stone"));
        }

        @Test
        void upgrade_same_profile_adds_nothing() {
            var added = provisioner.upgradeObjects(ResourceProfile.TREE, ResourceProfile.TREE);
            assertTrue(added.isEmpty());
        }

        @Test
        void downgrade_tree_to_seed_marks_all_dormant() {
            var dormant = provisioner.dormantObjects(ResourceProfile.TREE, ResourceProfile.SEED);
            assertEquals(4, dormant.size());
            assertTrue(dormant.contains("journal"));
            assertTrue(dormant.contains("thread-spool"));
            assertTrue(dormant.contains("dream-journal"));
            assertTrue(dormant.contains("ward-stone"));
        }

        @Test
        void downgrade_tree_to_sapling_marks_three_dormant() {
            var dormant = provisioner.dormantObjects(ResourceProfile.TREE, ResourceProfile.SAPLING);
            assertEquals(3, dormant.size());
            assertFalse(dormant.contains("journal")); // sapling keeps journal
        }

        @Test
        void no_downgrade_when_same() {
            var dormant = provisioner.dormantObjects(ResourceProfile.SEED, ResourceProfile.SEED);
            assertTrue(dormant.isEmpty());
        }
    }
}
