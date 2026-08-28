package org.wyrdsekai.core.coding;

/**
 * Cost/policy tier for a {@link CodingTaskBackend}.
 *
 * <p>Used by the selection policy and {@link org.wyrdsekai.core.protection.ActionPolicy}
 * to decide whether a task is allowed against the agent's current budget. Per
 * </p>
 *
 * <ul>
 *   <li>{@link #LOCAL_FREE} — runs on local hardware against a local model,
 *       no per-token cost (e.g. Aider against the household Qwen).</li>
 *   <li>{@link #LOCAL_HEAVY} — runs locally but consumes nontrivial host
 *       resources (RAM, disk, wallclock) — e.g. CodeZaiku long-running
 *       boards, OpenHands Docker sandboxes.</li>
 *   <li>{@link #CLOUD_PAID} — outbound API call against a paid provider
 *       (Claude, OpenAI, Gemini). Always cost-policy gated.</li>
 * </ul>
 */
public enum BackendTier {
    LOCAL_FREE,
    LOCAL_HEAVY,
    CLOUD_PAID
}
