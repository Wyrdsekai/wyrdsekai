package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for AgentPermissions and ZonePermission (Phase H: Access Rights).
 */
class AgentPermissionsTest {

    @Test void allow_specific_action() {
        var perms = new AgentPermissions(List.of(
            new ZonePermission("codezaiku", "status", ZonePermission.PermissionLevel.ALLOW)
        ));

        assertThat(perms.isAllowed("codezaiku", "status")).isTrue();
        assertThat(perms.isAllowed("codezaiku", "create")).isFalse();
    }

    @Test void deny_overrides_allow() {
        var perms = new AgentPermissions(List.of(
            new ZonePermission("codezaiku", "*", ZonePermission.PermissionLevel.ALLOW),
            new ZonePermission("codezaiku", "approve", ZonePermission.PermissionLevel.DENY)
        ));

        assertThat(perms.isAllowed("codezaiku", "status")).isTrue();
        assertThat(perms.isAllowed("codezaiku", "list")).isTrue();
        assertThat(perms.isAllowed("codezaiku", "approve")).isFalse();
    }

    @Test void wildcard_namespace_allows_all() {
        var perms = new AgentPermissions(List.of(
            new ZonePermission("*", "status", ZonePermission.PermissionLevel.ALLOW)
        ));

        assertThat(perms.isAllowed("codezaiku", "status")).isTrue();
        assertThat(perms.isAllowed("iot", "status")).isTrue();
        assertThat(perms.isAllowed("codezaiku", "create")).isFalse();
    }

    @Test void wildcard_action_allows_all_actions() {
        var perms = new AgentPermissions(List.of(
            new ZonePermission("iot", "*", ZonePermission.PermissionLevel.ALLOW)
        ));

        assertThat(perms.isAllowed("iot", "lights")).isTrue();
        assertThat(perms.isAllowed("iot", "temperature")).isTrue();
        assertThat(perms.isAllowed("codezaiku", "status")).isFalse();
    }

    @Test void default_deny_for_unknown_namespace() {
        var perms = new AgentPermissions(List.of(
            new ZonePermission("codezaiku", "status", ZonePermission.PermissionLevel.ALLOW)
        ));

        assertThat(perms.isAllowed("unknown", "anything")).isFalse();
    }

    @Test void companion_role_defaults() {
        var perms = AgentPermissions.companion();

        // Companion can read status from any namespace
        assertThat(perms.isAllowed("codezaiku", "status")).isTrue();
        assertThat(perms.isAllowed("iot", "list")).isTrue();
        assertThat(perms.isAllowed("engine", "info")).isTrue();

        // But cannot write/approve
        assertThat(perms.isAllowed("codezaiku", "create")).isFalse();
        assertThat(perms.isAllowed("codezaiku", "approve")).isFalse();
    }

    @Test void new_agent_role_is_read_only() {
        var perms = AgentPermissions.newAgent();

        assertThat(perms.isAllowed("codezaiku", "status")).isTrue();
        assertThat(perms.isAllowed("codezaiku", "list")).isTrue();
        assertThat(perms.isAllowed("codezaiku", "create")).isFalse();
        assertThat(perms.isAllowed("iot", "lights")).isFalse();
    }

    @Test void unrestricted_allows_everything() {
        var perms = AgentPermissions.unrestricted();

        assertThat(perms.isAllowed("codezaiku", "approve")).isTrue();
        assertThat(perms.isAllowed("anything", "at_all")).isTrue();
    }

    @Test void null_inputs_return_false() {
        var perms = AgentPermissions.unrestricted();

        assertThat(perms.isAllowed(null, "status")).isFalse();
        assertThat(perms.isAllowed("codezaiku", null)).isFalse();
    }

    @Test void with_additional_extends_permissions() {
        var base = AgentPermissions.newAgent();
        assertThat(base.isAllowed("codezaiku", "create")).isFalse();

        var extended = base.withAdditional(List.of(
            new ZonePermission("codezaiku", "create", ZonePermission.PermissionLevel.ALLOW)
        ));

        assertThat(extended.isAllowed("codezaiku", "create")).isTrue();
        // Base read permissions still work
        assertThat(extended.isAllowed("codezaiku", "status")).isTrue();
    }

    @Test void zone_permission_matches_wildcard_both() {
        var perm = new ZonePermission("*", "*", ZonePermission.PermissionLevel.ALLOW);
        assertThat(perm.matches("anything", "whatever")).isTrue();
    }

    @Test void zone_permission_matches_exact() {
        var perm = new ZonePermission("codezaiku", "create", ZonePermission.PermissionLevel.ALLOW);
        assertThat(perm.matches("codezaiku", "create")).isTrue();
        assertThat(perm.matches("codezaiku", "delete")).isFalse();
        assertThat(perm.matches("iot", "create")).isFalse();
    }
}
