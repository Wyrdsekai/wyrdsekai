package org.wyrdsekai.core.coding;

/**
 * Outcome of {@link AuthResolver#resolveAuth(String)}: which credential
 * path is live for a paid backend right now.
 * §9.2 (dual-path auth: OAuth-first, API-key fallback).
 *
 * <p>Adapters consume this at task-spawn time and never directly touch
 * the underlying credential store — the resolver is the only thing that
 * crosses the trust boundary into the Key Chest.</p>
 *
 * <p>The three sealed permits map 1:1 to the resolver's decision tree:
 * <ol>
 *   <li>{@link OAuthSession} — backend has a live OAuth session on
 *       disk; the adapter must rely on the upstream CLI's own
 *       credential lookup (no env-var injection needed).</li>
 *   <li>{@link ApiKey} — no OAuth session, but the household has stored
 *       a key in the Key Chest; the adapter injects it as the
 *       backend's documented env var.</li>
 *   <li>{@link AuthMissing} — neither path is configured; the adapter
 *       refuses to start and the companion's Study Mailbox surfaces
 *       the {@code recoveryCommand} to the steward.</li>
 * </ol>
 */
public sealed interface AuthMode
        permits AuthMode.OAuthSession,
                AuthMode.ApiKey,
                AuthMode.AuthMissing {

    /**
     * Live OAuth session detected by the resolver — typically by
     * checking that the backend's credential file exists and is
     * non-empty (cheap probe; the upstream CLI does its own freshness
     * check on first use).
     */
    record OAuthSession() implements AuthMode {}

    /**
     * Key Chest fallback — no OAuth session, but the household has
     * configured an API key in the named slot. The {@code value} is
     * the decrypted plaintext, ready to inject as the backend's env
     * var.
     */
    record ApiKey(String value) implements AuthMode {}

    /**
     * Neither auth path is live. {@code recoveryCommand} is the
     * literal CLI invocation the steward should run (typically
     * {@code "wyrd coding login <backend>"}); {@code reason} is a
     * one-liner suitable for surfacing in the Study Mailbox.
     */
    record AuthMissing(String backend, String recoveryCommand, String reason)
            implements AuthMode {}
}
