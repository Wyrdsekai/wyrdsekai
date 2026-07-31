package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ContextAccessManager} — per-agent context permission management.
 */
class ContextAccessManagerTest {

    private ContextAccessManager mgr;

    @BeforeEach
    void setUp() {
        ContextAccessManager.init();
        mgr = ContextAccessManager.get();
    }

    @AfterEach
    void tearDown() {
        ContextAccessManager.reset();
    }

    @Test
    void grant_and_check_access() {
        mgr.grant("agent-ma", "active_window", "vscode,terminal", "did:key:operator");

        assertThat(mgr.isGranted("agent-ma", "active_window")).isTrue();
        assertThat(mgr.isGranted("agent-ma", "calendar")).isFalse();
    }

    @Test
    void deny_with_30_day_cooldown() {
        mgr.deny("agent-ma", "active_window", "did:key:operator");

        assertThat(mgr.isGranted("agent-ma", "active_window")).isFalse();
        assertThat(mgr.canAskFor("agent-ma", "active_window")).isFalse();
    }

    @Test
    void canAskFor_returns_false_within_cooldown() {
        mgr.deny("agent-ma", "email_subjects", "did:key:operator");

        assertThat(mgr.canAskFor("agent-ma", "email_subjects")).isFalse();
    }

    @Test
    void canAskFor_returns_true_when_no_denial_exists() {
        assertThat(mgr.canAskFor("agent-ma", "calendar")).isTrue();
    }

    @Test
    void revoke_removes_permission() {
        mgr.grant("agent-ma", "active_window", "all", "did:key:operator");
        assertThat(mgr.isGranted("agent-ma", "active_window")).isTrue();

        mgr.revoke("agent-ma", "active_window");
        assertThat(mgr.isGranted("agent-ma", "active_window")).isFalse();
    }

    @Test
    void list_permissions_for_agent() {
        mgr.grant("agent-ma", "active_window", "vscode", "did:key:operator");
        mgr.grant("agent-ma", "calendar", "all", "did:key:operator");
        mgr.deny("agent-ma", "email_subjects", "did:key:operator");

        var perms = mgr.listPermissions("agent-ma");
        assertThat(perms).hasSize(3);
        assertThat(perms.stream().filter(ContextPermission::granted).count()).isEqualTo(2);
        assertThat(perms.stream().filter(p -> !p.granted()).count()).isEqualTo(1);
    }

    @Test
    void scope_checking_specific_apps() {
        mgr.grant("agent-ma", "active_window", "vscode,terminal", "did:key:operator");

        var scope = mgr.getScope("agent-ma", "active_window");
        assertThat(scope).isPresent();
        assertThat(scope.get()).isEqualTo("vscode,terminal");
    }

    @Test
    void scope_returns_empty_when_not_granted() {
        assertThat(mgr.getScope("agent-ma", "active_window")).isEmpty();
    }

    @Test
    void buildContext_formatting() {
        mgr.grant("agent-ma", "active_window", "vscode,terminal", "did:key:operator");
        mgr.deny("agent-ma", "email_subjects", "did:key:operator");

        String ctx = mgr.buildContext("agent-ma");
        assertThat(ctx).isNotNull();
        assertThat(ctx).contains("## Context Access");
        assertThat(ctx).contains("active_window");
        assertThat(ctx).contains("scope: vscode,terminal");
        assertThat(ctx).contains("email_subjects");
        assertThat(ctx).contains("denied");
    }

    @Test
    void buildContext_returns_null_when_no_permissions() {
        assertThat(mgr.buildContext("agent-nonexistent")).isNull();
    }

    @Test
    void multiple_agents_independent() {
        mgr.grant("agent-ma", "active_window", "all", "did:key:operator");
        mgr.deny("agent-chief", "active_window", "did:key:operator");

        assertThat(mgr.isGranted("agent-ma", "active_window")).isTrue();
        assertThat(mgr.isGranted("agent-chief", "active_window")).isFalse();
    }

    @Test
    void grant_replaces_previous_denial() {
        mgr.deny("agent-ma", "calendar", "did:key:operator");
        assertThat(mgr.isGranted("agent-ma", "calendar")).isFalse();

        mgr.grant("agent-ma", "calendar", "all", "did:key:operator");
        assertThat(mgr.isGranted("agent-ma", "calendar")).isTrue();
        // Only one entry after replacement
        assertThat(mgr.listPermissions("agent-ma")).hasSize(1);
    }
}
