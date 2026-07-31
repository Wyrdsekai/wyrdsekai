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
            new ZonePermission("codeplane", "status", ZonePermission.PermissionLevel.ALLOW)
        ));

        assertThat(perms.isAllowed("codeplane", "status")).isTrue();
        assertThat(perms.isAllowed("codeplane", "create")).isFalse();
    }

    @Test void deny_overrides_allow() {
        var perms = new AgentPermissions(List.of(
            new ZonePermission("codeplane", "*", ZonePermission.PermissionLevel.ALLOW),
            new ZonePermission("codeplane", "approve", ZonePermission.PermissionLevel.DENY)
        ));

        assertThat(perms.isAllowed("codeplane", "status")).isTrue();
        assertThat(perms.isAllowed("codeplane", "list")).isTrue();
        assertThat(perms.isAllowed("codeplane", "approve")).isFalse();
    }

    @Test void wildcard_namespace_allows_all() {
        var perms = new AgentPermissions(List.of(
            new ZonePermission("*", "status", ZonePermission.PermissionLevel.ALLOW)
        ));

        assertThat(perms.isAllowed("codeplane", "status")).isTrue();
        assertThat(perms.isAllowed("iot", "status")).isTrue();
        assertThat(perms.isAllowed("codeplane", "create")).isFalse();
    }

    @Test void wildcard_action_allows_all_actions() {
        var perms = new AgentPermissions(List.of(
            new ZonePermission("iot", "*", ZonePermission.PermissionLevel.ALLOW)
        ));

        assertThat(perms.isAllowed("iot", "lights")).isTrue();
        assertThat(perms.isAllowed("iot", "temperature")).isTrue();
        assertThat(perms.isAllowed("codeplane", "status")).isFalse();
    }

    @Test void default_deny_for_unknown_namespace() {
        var perms = new AgentPermissions(List.of(
            new ZonePermission("codeplane", "status", ZonePermission.PermissionLevel.ALLOW)
        ));

        assertThat(perms.isAllowed("unknown", "anything")).isFalse();
    }

    @Test void companion_role_defaults() {
        var perms = AgentPermissions.companion();

        // Companion can read status from any namespace
        assertThat(perms.isAllowed("codeplane", "status")).isTrue();
        assertThat(perms.isAllowed("iot", "list")).isTrue();
        assertThat(perms.isAllowed("engine", "info")).isTrue();

        // But cannot write/approve
        assertThat(perms.isAllowed("codeplane", "create")).isFalse();
        assertThat(perms.isAllowed("codeplane", "approve")).isFalse();
    }

    @Test void new_agent_role_is_read_only() {
        var perms = AgentPermissions.newAgent();

        assertThat(perms.isAllowed("codeplane", "status")).isTrue();
        assertThat(perms.isAllowed("codeplane", "list")).isTrue();
        assertThat(perms.isAllowed("codeplane", "create")).isFalse();
        assertThat(perms.isAllowed("iot", "lights")).isFalse();
    }

    @Test void unrestricted_allows_everything() {
        var perms = AgentPermissions.unrestricted();

        assertThat(perms.isAllowed("codeplane", "approve")).isTrue();
        assertThat(perms.isAllowed("anything", "at_all")).isTrue();
    }

    @Test void null_inputs_return_false() {
        var perms = AgentPermissions.unrestricted();

        assertThat(perms.isAllowed(null, "status")).isFalse();
        assertThat(perms.isAllowed("codeplane", null)).isFalse();
    }

    @Test void with_additional_extends_permissions() {
        var base = AgentPermissions.newAgent();
        assertThat(base.isAllowed("codeplane", "create")).isFalse();

        var extended = base.withAdditional(List.of(
            new ZonePermission("codeplane", "create", ZonePermission.PermissionLevel.ALLOW)
        ));

        assertThat(extended.isAllowed("codeplane", "create")).isTrue();
        // Base read permissions still work
        assertThat(extended.isAllowed("codeplane", "status")).isTrue();
    }

    @Test void zone_permission_matches_wildcard_both() {
        var perm = new ZonePermission("*", "*", ZonePermission.PermissionLevel.ALLOW);
        assertThat(perm.matches("anything", "whatever")).isTrue();
    }

    @Test void zone_permission_matches_exact() {
        var perm = new ZonePermission("codeplane", "create", ZonePermission.PermissionLevel.ALLOW);
        assertThat(perm.matches("codeplane", "create")).isTrue();
        assertThat(perm.matches("codeplane", "delete")).isFalse();
        assertThat(perm.matches("iot", "create")).isFalse();
    }
}
