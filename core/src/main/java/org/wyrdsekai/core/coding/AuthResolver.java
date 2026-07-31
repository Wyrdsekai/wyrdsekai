package org.wyrdsekai.core.coding;

/**
 * Resolves the live auth path for a paid coding backend.
 *
 * <p>, every paid-tier adapter
 * (Claude SDK, Codex, Gemini, Cline, Continue, Goose, Devin,
 * OpenHands LLM) calls {@link #resolveAuth(String)} at task-spawn
 * time. The resolver tries OAuth first (if the backend supports it),
 * falls back to the household Key Chest, and surfaces an
 * {@link AuthMode.AuthMissing} when neither is live.</p>
 *
 * <p>This is the only abstraction that crosses the trust boundary
 * into the Key Chest from the coding subsystem — adapters never read
 * keys directly.</p>
 */
public interface AuthResolver {

    /**
     * Resolve the live auth path for {@code backendName} (matches the
     * canonical name in the manifest, e.g. {@code "claude-sdk"},
     * {@code "codex"}). Implementations must return non-null; an
     * unknown backend (no manifest entry) is reported as
     * {@link AuthMode.AuthMissing} with a reason naming the missing
     * entry.
     */
    AuthMode resolveAuth(String backendName);
}
