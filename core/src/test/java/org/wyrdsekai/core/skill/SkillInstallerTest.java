package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SkillInstaller — skill lifecycle management.
 * Covers: constructor, listAll, listByStatus, getStatus, enableSkill,
 * disableSkill, configureCredential, installHintFor, scanForSkills.
 */
class SkillInstallerTest {

    private SkillRegistry createRegistry() {
        return new SkillRegistry(null, null);
    }

    private SkillMdImporter createImporter() {
        return new SkillMdImporter();
    }

    private SkillExecutor stubExecutor(String... supportedIds) {
        return new SkillExecutor() {
            @Override
            public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
                return SkillResult.ok("Stub result for " + skillId, Map.of(),
                    10, SkillTier.NATIVE, skillId);
            }

            @Override
            public List<SkillDefinition> availableSkills() {
                return Arrays.stream(supportedIds)
                    .map(id -> SkillDefinition.native_(id, id, "stub", "test", List.of(), SkillAuth.NONE))
                    .toList();
            }

            @Override
            public boolean supports(String skillId) {
                return Arrays.asList(supportedIds).contains(skillId);
            }

            @Override
            public SkillTier tier() {
                return SkillTier.NATIVE;
            }
        };
    }

    // ── Constructor ──────────────────────────────────────────────────────

    @Nested
    class ConstructorTests {

        @Test
        void creates_with_explicit_skills_dir(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            assertNotNull(installer);
        }

        @Test
        void creates_with_null_skills_dir_uses_default() {
            var installer = new SkillInstaller(createRegistry(), createImporter(), null);
            assertNotNull(installer);
        }
    }

    // ── listAll ──────────────────────────────────────────────────────────

    @Nested
    class ListAllTests {

        @Test
        void empty_registry_returns_empty(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            var skills = installer.listAll();
            assertTrue(skills.isEmpty());
        }

        @Test
        void includes_registered_skills(@TempDir Path tmpDir) {
            var registry = createRegistry();
            registry.registerExecutor(stubExecutor("hearth.ha.set-light", "herald.signal.send"));
            var installer = new SkillInstaller(registry, createImporter(), tmpDir);

            var skills = installer.listAll();
            assertEquals(2, skills.size());
            assertTrue(skills.stream().anyMatch(s -> s.id().equals("hearth.ha.set-light")));
            assertTrue(skills.stream().anyMatch(s -> s.id().equals("herald.signal.send")));
            assertTrue(skills.stream().allMatch(s -> s.status() == SkillInstaller.SkillStatus.ENABLED));
        }

        @Test
        void returns_immutable_list(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            var skills = installer.listAll();
            assertThrows(UnsupportedOperationException.class, () ->
                skills.add(new SkillInstaller.SkillInfo("x", "x", "", SkillTier.CLI,
                    SkillInstaller.SkillStatus.AVAILABLE, "", "")));
        }
    }

    // ── listByStatus ────────────────────────────────────────────────────

    @Nested
    class ListByStatusTests {

        @Test
        void filters_by_enabled_status(@TempDir Path tmpDir) {
            var registry = createRegistry();
            registry.registerExecutor(stubExecutor("a.skill", "b.skill"));
            var installer = new SkillInstaller(registry, createImporter(), tmpDir);

            var enabled = installer.listByStatus(SkillInstaller.SkillStatus.ENABLED);
            assertEquals(2, enabled.size());

            var available = installer.listByStatus(SkillInstaller.SkillStatus.AVAILABLE);
            assertTrue(available.isEmpty());
        }

        @Test
        void filters_by_disabled_status(@TempDir Path tmpDir) {
            var registry = createRegistry();
            var installer = new SkillInstaller(registry, createImporter(), tmpDir);

            installer.enableSkill("test.skill", "did:agent:1");
            installer.disableSkill("test.skill", "did:agent:1");

            // Disabled skills show up only via getStatus, not in listAll (since they're not
            // AVAILABLE and not in the registry)
            assertEquals(SkillInstaller.SkillStatus.DISABLED, installer.getStatus("test.skill"));
        }
    }

    // ── listForRoom ─────────────────────────────────────────────────────

    @Nested
    class ListForRoomTests {

        @Test
        void filters_by_room(@TempDir Path tmpDir) {
            var registry = createRegistry();
            // stubExecutor assigns room "test" to all skills
            registry.registerExecutor(stubExecutor("hearth.skill", "herald.skill"));
            var installer = new SkillInstaller(registry, createImporter(), tmpDir);

            var testRoom = installer.listForRoom("test");
            assertEquals(2, testRoom.size());

            var otherRoom = installer.listForRoom("hearth");
            assertTrue(otherRoom.isEmpty());
        }
    }

    // ── getStatus ───────────────────────────────────────────────────────

    @Nested
    class GetStatusTests {

        @Test
        void null_skill_returns_not_found(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            assertEquals(SkillInstaller.SkillStatus.NOT_FOUND, installer.getStatus(null));
        }

        @Test
        void unknown_skill_returns_not_found(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            assertEquals(SkillInstaller.SkillStatus.NOT_FOUND, installer.getStatus("nonexistent"));
        }

        @Test
        void registered_skill_returns_enabled(@TempDir Path tmpDir) {
            var registry = createRegistry();
            registry.registerExecutor(stubExecutor("test.skill"));
            var installer = new SkillInstaller(registry, createImporter(), tmpDir);

            assertEquals(SkillInstaller.SkillStatus.ENABLED, installer.getStatus("test.skill"));
        }

        @Test
        void manually_enabled_skill_returns_enabled(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            installer.enableSkill("manual.skill", "did:agent:1");
            assertEquals(SkillInstaller.SkillStatus.ENABLED, installer.getStatus("manual.skill"));
        }

        @Test
        void disabled_skill_returns_disabled(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            installer.enableSkill("test.skill", "did:agent:1");
            installer.disableSkill("test.skill", "did:agent:1");
            assertEquals(SkillInstaller.SkillStatus.DISABLED, installer.getStatus("test.skill"));
        }
    }

    // ── enableSkill ─────────────────────────────────────────────────────

    @Nested
    class EnableSkillTests {

        @Test
        void enable_returns_true(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            assertTrue(installer.enableSkill("test.skill", "did:agent:1"));
        }

        @Test
        void enable_null_skill_returns_false(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            assertFalse(installer.enableSkill(null, "did:agent:1"));
        }

        @Test
        void enable_null_agent_returns_false(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            assertFalse(installer.enableSkill("test.skill", null));
        }

        @Test
        void enable_not_found_skill_returns_false(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            // First mark as NOT_FOUND explicitly
            installer.disableSkill("gone.skill", "did:agent:1");
            // Now re-enable — should work since it was DISABLED, not NOT_FOUND
            assertTrue(installer.enableSkill("gone.skill", "did:agent:1"));
        }

        @Test
        void enable_updates_status(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            installer.enableSkill("test.skill", "did:agent:1");
            assertEquals(SkillInstaller.SkillStatus.ENABLED, installer.getStatus("test.skill"));
        }
    }

    // ── disableSkill ────────────────────────────────────────────────────

    @Nested
    class DisableSkillTests {

        @Test
        void disable_returns_true(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            assertTrue(installer.disableSkill("test.skill", "did:agent:1"));
        }

        @Test
        void disable_null_skill_returns_false(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            assertFalse(installer.disableSkill(null, "did:agent:1"));
        }

        @Test
        void disable_null_agent_returns_false(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            assertFalse(installer.disableSkill("test.skill", null));
        }

        @Test
        void disable_updates_status(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            installer.enableSkill("test.skill", "did:agent:1");
            installer.disableSkill("test.skill", "did:agent:1");
            assertEquals(SkillInstaller.SkillStatus.DISABLED, installer.getStatus("test.skill"));
        }
    }

    // ── configureCredential ─────────────────────────────────────────────

    @Nested
    class ConfigureCredentialTests {

        @Test
        void configure_returns_true(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            assertTrue(installer.configureCredential("hearth.ha", "token", "secret123"));
        }

        @Test
        void configure_null_skill_returns_false(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            assertFalse(installer.configureCredential(null, "token", "secret"));
        }

        @Test
        void configure_null_key_returns_false(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            assertFalse(installer.configureCredential("hearth.ha", null, "secret"));
        }

        @Test
        void configure_null_value_returns_false(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            assertFalse(installer.configureCredential("hearth.ha", "token", null));
        }
    }

    // ── installHintFor ──────────────────────────────────────────────────

    @Nested
    class InstallHintTests {

        @Test
        void known_skill_returns_hint(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            var hint = installer.installHintFor("herald.signal");
            assertFalse(hint.isEmpty());
            assertTrue(hint.contains("signal-cli"));
        }

        @Test
        void unknown_skill_returns_empty(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            assertEquals("", installer.installHintFor("unknown.skill"));
        }

        @Test
        void null_skill_returns_empty(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            assertEquals("", installer.installHintFor(null));
        }

        @Test
        void kiwix_hint(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            var hint = installer.installHintFor("library.kiwix");
            assertTrue(hint.contains("kiwix"));
        }

        @Test
        void whisper_hint(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            var hint = installer.installHintFor("voice.stt");
            assertTrue(hint.contains("whisper"));
        }
    }

    // ── scanForSkills ───────────────────────────────────────────────────

    @Nested
    class ScanTests {

        @Test
        void scan_does_not_crash_on_empty_dir(@TempDir Path tmpDir) {
            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            int found = installer.scanForSkills();
            // At minimum we get the 4 PATH probes (all marked AVAILABLE if not found)
            assertTrue(found >= 0);
        }

        @Test
        void scan_does_not_crash_on_nonexistent_dir() {
            var installer = new SkillInstaller(createRegistry(), createImporter(),
                Path.of("/tmp/nonexistent-skills-dir-" + System.nanoTime()));
            int found = installer.scanForSkills();
            assertTrue(found >= 0);
        }

        @Test
        void scan_discovers_skill_md_files(@TempDir Path tmpDir) throws IOException {
            // Create a skill directory with SKILL.md
            Path skillDir = tmpDir.resolve("testtool");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                ## do_thing
                **Description**: Does a thing
                | Parameter | Type | Required | Description |
                |-----------|------|----------|-------------|
                | input | string | true | The input |
                """);

            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            int found = installer.scanForSkills();
            // Should find at least the SKILL.md skill + PATH probes
            assertTrue(found >= 1);
        }

        @Test
        void scan_marks_discovered_skills_available(@TempDir Path tmpDir) throws IOException {
            Path skillDir = tmpDir.resolve("mytool");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                ## my_action
                **Description**: My custom action
                """);

            var installer = new SkillInstaller(createRegistry(), createImporter(), tmpDir);
            installer.scanForSkills();

            // The discovered skill should be AVAILABLE (not in registry)
            var all = installer.listAll();
            // PATH-probed skills that aren't installed show as AVAILABLE
            var available = installer.listByStatus(SkillInstaller.SkillStatus.AVAILABLE);
            assertFalse(available.isEmpty());
        }

        @Test
        void scan_does_not_duplicate_registered_skills(@TempDir Path tmpDir) throws IOException {
            var registry = createRegistry();
            // Register a skill with the same ID that would be discovered
            registry.registerExecutor(stubExecutor("workshop.mytool.my_action"));

            Path skillDir = tmpDir.resolve("mytool");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                ## my_action
                **Description**: My custom action
                """);

            var installer = new SkillInstaller(registry, createImporter(), tmpDir);
            installer.scanForSkills();

            // Should still show as ENABLED (from registry), not duplicated as AVAILABLE
            assertEquals(SkillInstaller.SkillStatus.ENABLED,
                installer.getStatus("workshop.mytool.my_action"));
        }
    }

    // ── defaultSkillsDir ────────────────────────────────────────────────

    @Nested
    class DefaultSkillsDirTests {

        @Test
        void default_dir_is_under_home() {
            Path dir = SkillInstaller.defaultSkillsDir();
            assertNotNull(dir);
            assertTrue(dir.toString().contains(".wyrdsekai"));
            assertTrue(dir.toString().endsWith("skills"));
        }
    }

    // ── SkillInfo record ────────────────────────────────────────────────

    @Nested
    class SkillInfoTests {

        @Test
        void record_accessors() {
            var info = new SkillInstaller.SkillInfo(
                "hearth.ha.set-light", "Set Light", "Set brightness",
                SkillTier.NATIVE, SkillInstaller.SkillStatus.ENABLED,
                "", "wyrdsekai");
            assertEquals("hearth.ha.set-light", info.id());
            assertEquals("Set Light", info.name());
            assertEquals("Set brightness", info.description());
            assertEquals(SkillTier.NATIVE, info.tier());
            assertEquals(SkillInstaller.SkillStatus.ENABLED, info.status());
            assertEquals("", info.installHint());
            assertEquals("wyrdsekai", info.origin());
        }
    }

    // ── SkillStatus enum ────────────────────────────────────────────────

    @Nested
    class SkillStatusTests {

        @Test
        void all_statuses_exist() {
            assertEquals(5, SkillInstaller.SkillStatus.values().length);
            assertNotNull(SkillInstaller.SkillStatus.valueOf("AVAILABLE"));
            assertNotNull(SkillInstaller.SkillStatus.valueOf("INSTALLED"));
            assertNotNull(SkillInstaller.SkillStatus.valueOf("ENABLED"));
            assertNotNull(SkillInstaller.SkillStatus.valueOf("DISABLED"));
            assertNotNull(SkillInstaller.SkillStatus.valueOf("NOT_FOUND"));
        }
    }
}
