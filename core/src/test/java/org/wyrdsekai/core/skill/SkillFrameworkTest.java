package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.safety.McpAuditLog;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Skill Framework.
 * Covers: records, enums, SkillDefinition, SkillParam, SkillResult,
 * SkillContext, SkillAuth, SkillPermission, SkillRegistry.
 */
class SkillFrameworkTest {

    // ── SkillDefinition ─────────────────────────────────────────────────

    @Nested
    class SkillDefinitionTests {

        @Test
        void create_native_skill() {
            var skill = SkillDefinition.native_("hearth.ha.set-light", "Set Light",
                "Set brightness of a light", "hearth",
                List.of(SkillParam.required("entity_id", "string", "Light entity")),
                SkillAuth.apiKey("ha_token"));

            assertEquals("hearth.ha.set-light", skill.id());
            assertEquals("hearth", skill.room());
            assertEquals(SkillTier.NATIVE, skill.tier());
            assertEquals("wyrdsekai", skill.origin());
            assertEquals("Apache-2.0", skill.license());
            assertTrue(skill.requiresAuth());
            assertTrue(skill.schedulable());
        }

        @Test
        void room_prefix_extraction() {
            var skill = SkillDefinition.native_("hearth.ha.set-light", "Set Light",
                "desc", "hearth", List.of(), SkillAuth.NONE);
            assertEquals("hearth", skill.roomPrefix());
        }

        @Test
        void null_id_throws() {
            assertThrows(IllegalArgumentException.class, () ->
                new SkillDefinition(null, "n", "d", "r", SkillTier.NATIVE,
                    "o", "l", List.of(), SkillAuth.NONE, SkillLocality.ANY, false));
        }

        @Test
        void blank_name_throws() {
            assertThrows(IllegalArgumentException.class, () ->
                new SkillDefinition("id", "  ", "d", "r", SkillTier.NATIVE,
                    "o", "l", List.of(), SkillAuth.NONE, SkillLocality.ANY, false));
        }

        @Test
        void defaults_applied_for_nulls() {
            var skill = new SkillDefinition("test.skill", "Test", "desc",
                "room", null, null, "MIT", null, SkillAuth.NONE, null, false);
            assertEquals(SkillTier.NATIVE, skill.tier());
            assertEquals("wyrdsekai", skill.origin());
            assertEquals(List.of(), skill.params());
            assertEquals(SkillLocality.ANY, skill.locality());
        }

        @Test
        void no_auth_skill() {
            var skill = SkillDefinition.native_("lib.search", "Search", "desc",
                "library", List.of(), SkillAuth.NONE);
            assertFalse(skill.requiresAuth());
        }
    }

    // ── SkillParam ──────────────────────────────────────────────────────

    @Nested
    class SkillParamTests {

        @Test
        void required_factory() {
            var p = SkillParam.required("query", "string", "Search query");
            assertEquals("query", p.name());
            assertTrue(p.required());
            assertEquals("string", p.type());
        }

        @Test
        void optional_factory() {
            var p = SkillParam.optional("limit", "number", "Max results");
            assertFalse(p.required());
        }

        @Test
        void enum_factory() {
            var p = SkillParam.enum_("mode", "Operation mode", true,
                List.of("on", "off", "toggle"));
            assertEquals("enum", p.type());
            assertEquals(List.of("on", "off", "toggle"), p.enumValues());
            assertTrue(p.required());
        }

        @Test
        void null_name_throws() {
            assertThrows(IllegalArgumentException.class, () ->
                SkillParam.required(null, "string", "desc"));
        }

        @Test
        void null_type_defaults_to_string() {
            var p = new SkillParam("test", null, "desc", false, null);
            assertEquals("string", p.type());
            assertEquals(List.of(), p.enumValues());
        }
    }

    // ── SkillResult ─────────────────────────────────────────────────────

    @Nested
    class SkillResultTests {

        @Test
        void ok_result() {
            var result = SkillResult.ok("Light set to 80%", Map.of("brightness", 80),
                150, SkillTier.NATIVE, "hearth.ha.set-light");
            assertTrue(result.success());
            assertEquals("Light set to 80%", result.output());
            assertNull(result.cost());
        }

        @Test
        void ok_result_with_cost() {
            var result = SkillResult.ok("Done", Map.of(), 200, SkillTier.CLI,
                "scrying.search", 0.01);
            assertTrue(result.success());
            assertEquals(0.01, result.cost());
        }

        @Test
        void error_result() {
            var result = SkillResult.error("Timeout", 5000, SkillTier.NATIVE, "hearth.ha.set-light");
            assertFalse(result.success());
            assertEquals("Timeout", result.output());
        }

        @Test
        void denied_result() {
            var result = SkillResult.denied("Not authorized", "vault.payment.send");
            assertFalse(result.success());
            assertNull(result.executorTier());
        }

        @Test
        void unavailable_result() {
            var result = SkillResult.unavailable("nonexistent.skill");
            assertFalse(result.success());
            assertTrue(result.output().contains("nonexistent.skill"));
        }
    }

    // ── SkillContext ────────────────────────────────────────────────────

    @Nested
    class SkillContextTests {

        @Test
        void agent_context() {
            var ctx = SkillContext.forAgent("did:agent:123", "hearth",
                Map.of("ha_token", "secret"), 1000);
            assertEquals("did:agent:123", ctx.agentDid());
            assertEquals("hearth", ctx.roomId());
            assertFalse(ctx.isHumanSession());
            assertEquals(1000, ctx.budgetRemaining());
        }

        @Test
        void human_context() {
            var ctx = SkillContext.forHuman("user:alice", "study",
                Map.of(), true);
            assertTrue(ctx.isHumanSession());
            assertTrue(ctx.isLocalSession());
            assertEquals(Long.MAX_VALUE, ctx.budgetRemaining());
        }

        @Test
        void null_agent_did_throws() {
            assertThrows(IllegalArgumentException.class, () ->
                SkillContext.forAgent(null, "room", Map.of(), 100));
        }

        @Test
        void null_credentials_defaulted() {
            var ctx = new SkillContext("did:x", "room", null, 100, 30_000,
                false, false, null);
            assertNotNull(ctx.credentials());
            assertTrue(ctx.credentials().isEmpty());
        }

        @Test
        void negative_timeout_defaulted() {
            var ctx = new SkillContext("did:x", "room", Map.of(), 100, -1,
                false, false, null);
            assertEquals(30_000, ctx.timeoutMs());
        }
    }

    // ── SkillAuth ───────────────────────────────────────────────────────

    @Nested
    class SkillAuthTests {

        @Test
        void none_auth() {
            assertNull(SkillAuth.NONE.credentialKey());
            assertEquals(AuthType.NONE, SkillAuth.NONE.type());
        }

        @Test
        void api_key_auth() {
            var auth = SkillAuth.apiKey("ha_token");
            assertEquals("ha_token", auth.credentialKey());
            assertEquals(AuthType.API_KEY, auth.type());
        }

        @Test
        void oauth_auth() {
            var auth = SkillAuth.oauth("google_oauth");
            assertEquals(AuthType.OAUTH_DEVICE_FLOW, auth.type());
        }

        @Test
        void local_bridge_auth() {
            var auth = SkillAuth.localBridge("signal_bridge");
            assertEquals(AuthType.LOCAL_BRIDGE, auth.type());
        }
    }

    // ── SkillPermission ─────────────────────────────────────────────────

    @Nested
    class SkillPermissionTests {

        @Test
        void allow_all() {
            var perm = SkillPermission.allowAll();
            assertTrue(perm.isAllowed("any.skill.here"));
            assertTrue(perm.isAllowed("something.else"));
        }

        @Test
        void deny_all() {
            var perm = SkillPermission.denyAll();
            assertFalse(perm.isAllowed("any.skill.here"));
        }

        @Test
        void glob_matching() {
            var perm = SkillPermission.allowAll();
            perm.deny("vault.*");
            perm.allow("vault.balance.check");

            assertTrue(perm.isAllowed("hearth.ha.set-light"));
            assertFalse(perm.isAllowed("vault.payment.send"));
            assertTrue(perm.isAllowed("vault.balance.check"),
                "Explicit allow should override glob deny");
        }

        @Test
        void glob_pattern_matches_prefix() {
            var perm = SkillPermission.denyAll();
            perm.allow("hearth.*");

            assertTrue(perm.isAllowed("hearth.ha.set-light"));
            assertTrue(perm.isAllowed("hearth.ha.get-state"));
            assertFalse(perm.isAllowed("scrying.search"));
        }

        @Test
        void explicit_deny_overrides_glob_allow() {
            var perm = SkillPermission.denyAll();
            perm.allow("hearth.*");
            perm.deny("hearth.ha.dangerous-skill");

            assertTrue(perm.isAllowed("hearth.ha.set-light"));
            assertFalse(perm.isAllowed("hearth.ha.dangerous-skill"));
        }
    }

    // ── SkillRegistry ───────────────────────────────────────────────────

    @Nested
    class SkillRegistryTests {

        private SkillRegistry createRegistry() {
            // Null sanitizer/auditLog for unit tests (both are null-safe)
            return new SkillRegistry(null, null);
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

        @Test
        void execute_with_permission() {
            var reg = createRegistry();
            reg.registerExecutor(stubExecutor("test.skill"));
            reg.setPermissions("did:agent:1", SkillPermission.allowAll());

            var ctx = SkillContext.forAgent("did:agent:1", "room", Map.of(), 1000);
            var result = reg.execute("test.skill", Map.of(), ctx);

            assertTrue(result.success());
            assertTrue(result.output().contains("test.skill"));
        }

        @Test
        void execute_denied_without_permission() {
            var reg = createRegistry();
            reg.registerExecutor(stubExecutor("test.skill"));

            var ctx = SkillContext.forAgent("did:agent:1", "room", Map.of(), 1000);
            var result = reg.execute("test.skill", Map.of(), ctx);

            assertFalse(result.success());
            assertTrue(result.output().contains("Permission denied"));
        }

        @Test
        void execute_denied_when_budget_zero() {
            var reg = createRegistry();
            reg.registerExecutor(stubExecutor("test.skill"));
            reg.setPermissions("did:agent:1", SkillPermission.allowAll());

            var ctx = SkillContext.forAgent("did:agent:1", "room", Map.of(), 0);
            var result = reg.execute("test.skill", Map.of(), ctx);

            assertFalse(result.success());
            assertTrue(result.output().contains("Budget"));
        }

        @Test
        void unavailable_skill() {
            var reg = createRegistry();
            reg.setPermissions("did:agent:1", SkillPermission.allowAll());

            var ctx = SkillContext.forAgent("did:agent:1", "room", Map.of(), 1000);
            var result = reg.execute("nonexistent.skill", Map.of(), ctx);

            assertFalse(result.success());
            assertTrue(result.output().contains("not available"));
        }

        @Test
        void all_skills_aggregates_executors() {
            var reg = createRegistry();
            reg.registerExecutor(stubExecutor("a.skill", "b.skill"));
            reg.registerExecutor(stubExecutor("c.skill"));

            assertEquals(3, reg.allSkills().size());
        }

        @Test
        void skills_for_agent_respects_permissions() {
            var reg = createRegistry();
            reg.registerExecutor(stubExecutor("a.skill", "b.skill", "c.skill"));

            var perm = SkillPermission.denyAll();
            perm.allow("a.*");
            perm.allow("b.*");
            reg.setPermissions("did:agent:1", perm);

            var skills = reg.skillsForAgent("did:agent:1");
            assertEquals(2, skills.size());
        }

        @Test
        void has_skill() {
            var reg = createRegistry();
            reg.registerExecutor(stubExecutor("test.skill"));

            assertTrue(reg.hasSkill("test.skill"));
            assertFalse(reg.hasSkill("nonexistent.skill"));
        }

        @Test
        void executor_exception_returns_error() {
            var reg = createRegistry();
            reg.registerExecutor(new SkillExecutor() {
                @Override
                public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
                    throw new RuntimeException("Boom!");
                }

                @Override
                public List<SkillDefinition> availableSkills() {
                    return List.of(SkillDefinition.native_("boom", "boom", "d", "r", List.of(), SkillAuth.NONE));
                }

                @Override
                public boolean supports(String skillId) { return "boom".equals(skillId); }

                @Override
                public SkillTier tier() { return SkillTier.NATIVE; }
            });
            reg.setPermissions("did:a", SkillPermission.allowAll());
            var ctx = SkillContext.forAgent("did:a", "r", Map.of(), 1000);
            var result = reg.execute("boom", Map.of(), ctx);

            assertFalse(result.success());
            assertTrue(result.output().contains("Boom!"));
        }
    }

    // ── Enums ───────────────────────────────────────────────────────────

    @Nested
    class EnumTests {

        @Test
        void skill_tiers() {
            assertEquals(5, SkillTier.values().length);
            assertNotNull(SkillTier.valueOf("NATIVE"));
            assertNotNull(SkillTier.valueOf("WORKBENCH"));
            assertNotNull(SkillTier.valueOf("CLI"));
            assertNotNull(SkillTier.valueOf("OPENCLAW"));
            assertNotNull(SkillTier.valueOf("PROMPT"));
        }

        @Test
        void skill_localities() {
            assertEquals(4, SkillLocality.values().length);
            assertNotNull(SkillLocality.valueOf("LOCAL"));
            assertNotNull(SkillLocality.valueOf("PHONE"));
            assertNotNull(SkillLocality.valueOf("ANY"));
            assertNotNull(SkillLocality.valueOf("BETWEEN"));
        }

        @Test
        void auth_types() {
            assertEquals(4, AuthType.values().length);
        }
    }
}
