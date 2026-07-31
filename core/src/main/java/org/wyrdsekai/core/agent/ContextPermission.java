package org.wyrdsekai.core.agent;

import java.time.Duration;
import java.time.Instant;

/**
 * A permission record for agent access to a context source.
 * Sources include: "active_window", "calendar", "location", "voice", "email_subjects", "files".
 *
 * <p>Permissions follow the lifecycle: agent requests → human grants or denies →
 * permission stored → agent remembers the decision → behavior adjusts.</p>
 *
 * <p>Denials carry a 30-day cooldown: the agent will not re-ask for the same
 * source within 30 days of a denial.</p>
 *
 * @param source    Context source identifier (e.g. "active_window", "calendar", "location", "voice")
 * @param scope     Granularity within the source (e.g. app names "vscode,terminal", calendar IDs, voice mode)
 * @param granted   true if access was granted, false if denied
 * @param decidedAt When the decision was made
 * @param decidedBy Human's DID who made the decision
 * @param expiresAt When the permission expires, or null for permanent (until revoked)
 */
public record ContextPermission(
    String source,
    String scope,
    boolean granted,
    Instant decidedAt,
    String decidedBy,
    Instant expiresAt
) {
    /** Check if this permission has expired. Never-expiring permissions return false. */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /** Check if this is a denial that is still within cooldown (30 days). */
    public boolean isInCooldown() {
        if (granted) return false;
        return decidedAt != null && Instant.now().isBefore(decidedAt.plus(Duration.ofDays(30)));
    }
}
