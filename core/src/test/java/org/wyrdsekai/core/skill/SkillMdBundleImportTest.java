package org.wyrdsekai.core.skill;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.typesafe.config.ConfigFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * / PLAN 1.3 — the OpenClaw/Hermes SKILL.md import pipeline:
 * the HOCON→flat-key config bridge, the bundled-defs seed, and startup import
 * with bins/env gating (a skill whose CLI is missing must stay OFF the live
 * surface — offered-but-unrunnable is the talks-but-can't-do failure).
 */
final class SkillMdBundleImportTest {

    // ── config bridge ───────────────────────────────────────────────────

    @Test
    void hocon_bridge_flattens_wyrdsekai_skills_keys() {
        var config = ConfigFactory.parseString("""
            wyrdsekai.skills {
              openclaw.url = "ws://127.0.0.1:18789"
              ha.url = "http://ha.local:8123"
              gcal.enabled = true
            }
            """);
        var flat = SkillBootstrap.configFromHocon(config);
        assertEquals("ws://127.0.0.1:18789", flat.get("openclaw.url"));
        assertEquals("http://ha.local:8123", flat.get("ha.url"));
        assertEquals("true", flat.get("gcal.enabled"));
    }

    @Test
    void hocon_bridge_is_empty_when_no_skills_block() {
        assertTrue(SkillBootstrap.configFromHocon(ConfigFactory.parseString("a=1")).isEmpty());
    }

    @Test
    void emergency_contacts_parse_from_the_flat_key() {
        var contacts = SkillBootstrap.parseEmergencyContacts(
            "Operator:+15550199:steward, Neighbor:+15550188");
        assertEquals(2, contacts.size());
        assertEquals("Operator", contacts.get(0).name());
        assertEquals("+15550199", contacts.get(0).phone());
        assertEquals("steward", contacts.get(0).relationship());
        assertEquals("", contacts.get(1).relationship());
        assertTrue(SkillBootstrap.parseEmergencyContacts(null).isEmpty());
        assertTrue(SkillBootstrap.parseEmergencyContacts("garbage").isEmpty());
    }

    // ── bundled seed ────────────────────────────────────────────────────

    @Test
    void bundled_defs_seed_once_and_never_overwrite(@TempDir Path skillsDir) throws Exception {
        SkillBootstrap.seedBundledSkills(skillsDir);
        var openhue = skillsDir.resolve("openhue").resolve("SKILL.md");
        assertTrue(Files.exists(openhue), "the §12.4 bundle must seed openhue");
        assertTrue(Files.exists(skillsDir.resolve("himalaya").resolve("SKILL.md")));
        assertTrue(Files.exists(skillsDir.resolve("github").resolve("SKILL.md")));

        // Steward edits are never clobbered by a re-seed on the next boot.
        Files.writeString(openhue, "steward edited this");
        SkillBootstrap.seedBundledSkills(skillsDir);
        assertEquals("steward edited this", Files.readString(openhue));
    }

    @Test
    void bundle_covers_the_spec_mapping(@TempDir Path skillsDir) throws Exception {
        SkillBootstrap.seedBundledSkills(skillsDir);
        try (var dirs = Files.list(skillsDir)) {
            long count = dirs.filter(Files::isDirectory).count();
            assertTrue(count >= 31, "§12.4 maps 31 skills; bundle has " + count);
        }
    }

    // ── import gating ───────────────────────────────────────────────────

    private static void writeSkill(Path skillsDir, String name, String frontExtra, String body)
            throws Exception {
        var dir = skillsDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
            ---
            name: %s
            description: test skill %s
            %s
            ---
            %s
            """.formatted(name, name, frontExtra, body));
    }

    @Test
    void satisfied_preconditions_register_live_and_execute(@TempDir Path skillsDir)
            throws Exception {
        // `sh` is on PATH everywhere this test runs — the bins gate passes.
        writeSkill(skillsDir, "greeter", """
            metadata:
              openclaw:
                requires:
                  bins: [sh]
              wyrdsekai:
                room: scriptorium
            """, "Greet {{who}} warmly, in one sentence.");

        var registry = new SkillRegistry(null, null);
        // Note: the bundled seed also lands in skillsDir, and any bundled
        // skill whose CLI happens to exist on THIS host legitimately goes
        // live too — so assert on the specific skill, not the total count.
        int live = SkillBootstrap.importSkillMd(registry, skillsDir);
        assertTrue(live >= 1);
        assertTrue(registry.hasSkill("scriptorium.greeter"));

        var def = registry.allSkills().stream()
            .filter(d -> d.id().equals("scriptorium.greeter")).findFirst().orElseThrow();
        assertEquals("scriptorium", def.room(), "metadata.wyrdsekai.room must place the skill");
        assertEquals("openclaw/greeter", def.origin());

        registry.setPermissions("did:test:agent", SkillPermission.companionDefault());
        var result = registry.execute("scriptorium.greeter", Map.of("who", "operator"),
            SkillContext.forAgent("did:test:agent", "scriptorium", Map.of(), Long.MAX_VALUE));
        assertTrue(result.success(), result.output());
        assertEquals("Greet operator warmly, in one sentence.", result.output().strip(),
            "prompt instructions must return with {{param}} substituted");
    }

    @Test
    void missing_bin_keeps_the_skill_dormant(@TempDir Path skillsDir) throws Exception {
        writeSkill(skillsDir, "ghostly", """
            metadata:
              openclaw:
                requires:
                  bins: [definitely-not-a-real-binary-xyz]
            """, "Do ghost things.");

        var registry = new SkillRegistry(null, null);
        SkillBootstrap.importSkillMd(registry, skillsDir);
        assertFalse(registry.hasSkill("workshop.ghostly"),
            "a skill whose CLI is missing must not be offered live");
    }

    @Test
    void missing_env_keeps_the_skill_dormant(@TempDir Path skillsDir) throws Exception {
        writeSkill(skillsDir, "keyed", """
            metadata:
              openclaw:
                requires:
                  env: [DEFINITELY_NOT_SET_ENV_XYZ]
            """, "Use the key.");

        var registry = new SkillRegistry(null, null);
        SkillBootstrap.importSkillMd(registry, skillsDir);
        assertFalse(registry.hasSkill("workshop.keyed"));
    }

    @Test
    void legacy_structured_format_binds_to_the_cli_executor(@TempDir Path skillsDir)
            throws Exception {
        // Legacy format, dir name = binary. `echo` is on PATH, so the tool
        // binds live and a registry execute really forks it (through the
        // egress-gated ProcessBuilder).
        var dir = skillsDir.resolve("echo");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
            ## say
            **Description**: Echo the given text back.

            | name | type | required | description |
            |------|------|----------|-------------|
            | `text` | `string` | true | What to echo |
            """);

        var registry = new SkillRegistry(null, null);
        SkillBootstrap.importSkillMd(registry, skillsDir);
        assertTrue(registry.hasSkill("workshop.echo.say"));
        registry.setPermissions("did:test:agent", SkillPermission.companionDefault());
        var result = registry.execute("workshop.echo.say", Map.of("text", "hello"),
            SkillContext.forAgent("did:test:agent", "workshop", Map.of(), Long.MAX_VALUE));
        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("hello"), result.output());
    }

    @Test
    void bundled_set_imports_cleanly_with_only_dormant_skills(@TempDir Path skillsDir) {
        // On a bare CI host none of the §12.4 CLIs exist — every bundled skill
        // must import DORMANT (not live, not crashing). Any that would go
        // live here would be offering a tool this host can actually run,
        // which is fine too — so only assert no exception + no phantom Hue.
        var registry = new SkillRegistry(null, null);
        SkillBootstrap.importSkillMd(registry, skillsDir);
        if (!SkillBootstrap.isInPath("openhue")) {
            assertFalse(registry.hasSkill("hearth.openhue"));
        }
    }

    // ── Hermes (~/.hermes/skills, agentskills.io convention) ────────────

    @Test
    void hermes_library_skills_import_alongside_the_data_dir(@TempDir Path skillsDir,
            @TempDir Path hermesDir) throws Exception {
        // A skill that exists ONLY in the user's Hermes library — same
        // SKILL.md format, so it carries straight over.
        writeSkill(hermesDir, "hermes-notes", """
            metadata:
              openclaw:
                requires:
                  bins: [sh]
              wyrdsekai:
                room: study
            """, "Summarize {{topic}} from my notes.");

        var registry = new SkillRegistry(null, null);
        int live = SkillBootstrap.importSkillMd(registry, skillsDir, hermesDir);
        assertTrue(live >= 1);
        assertTrue(registry.hasSkill("study.hermes-notes"),
            "a Hermes-library skill must register like a data-dir skill");

        registry.setPermissions("did:test:agent", SkillPermission.companionDefault());
        var result = registry.execute("study.hermes-notes", Map.of("topic", "gardening"),
            SkillContext.forAgent("did:test:agent", "study", Map.of(), Long.MAX_VALUE));
        assertTrue(result.success(), result.output());
        assertEquals("Summarize gardening from my notes.", result.output().strip());
    }

    @Test
    void data_dir_wins_name_collisions_with_the_hermes_library(@TempDir Path skillsDir,
            @TempDir Path hermesDir) throws Exception {
        // Same directory name in both roots; the wyrdsekai data dir is
        // scanned first and must win (steward-edited copy beats library).
        writeSkill(skillsDir, "collide", """
            metadata:
              openclaw:
                requires:
                  bins: [sh]
              wyrdsekai:
                room: workshop
            """, "DATA-DIR VERSION for {{x}}.");
        writeSkill(hermesDir, "collide", """
            metadata:
              openclaw:
                requires:
                  bins: [sh]
              wyrdsekai:
                room: workshop
            """, "HERMES VERSION for {{x}}.");

        var registry = new SkillRegistry(null, null);
        SkillBootstrap.importSkillMd(registry, skillsDir, hermesDir);

        registry.setPermissions("did:test:agent", SkillPermission.companionDefault());
        var result = registry.execute("workshop.collide", Map.of("x", "y"),
            SkillContext.forAgent("did:test:agent", "workshop", Map.of(), Long.MAX_VALUE));
        assertTrue(result.success(), result.output());
        assertEquals("DATA-DIR VERSION for y.", result.output().strip(),
            "the $DATA/skills copy must shadow the Hermes-library copy");
    }

    @Test
    void missing_hermes_dir_is_a_clean_no_op(@TempDir Path skillsDir) {
        var registry = new SkillRegistry(null, null);
        // Nonexistent path — must neither throw nor register phantoms.
        SkillBootstrap.importSkillMd(registry, skillsDir,
            skillsDir.resolve("no-such-hermes"));
        assertFalse(registry.hasSkill("study.hermes-notes"));
    }
}
