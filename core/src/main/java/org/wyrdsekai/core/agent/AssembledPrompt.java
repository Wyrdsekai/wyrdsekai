package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.inference.InferenceClient.ChatMessage;

import java.util.List;

/**
 * a prompt + its target backend, bound at
 * assembly time. Carrying the {@code backendId} on the prompt itself
 * makes wrong-backend pairing a type-level concern: the inference
 * dispatcher can verify the prompt's intended backend matches the
 * resolved backend before sending, instead of silently truncating.
 *
 * <p>Background (the bug F15 prevents): tier-aware prompt assembly
 * was wired into {@code CompanionActor}'s main turn dispatch
 * (#493 — voice-tier prompts go to a slim ~2K-token sandwich for
 * the 4B voice backend's 4K window) but other actors that build
 * prompts ({@code ChiefEngineerActor}, {@code WardenActor},
 * {@code CompanionActor.onGreetPlayer}) called the full assembler
 * unconditionally. If any of those routes to the voice backend by
 * config change or capability redirect, the 5K-token full prompt
 * silently overflows the 4K window — the bug only surfaces in
 * production logs ("request (4759 tokens) exceeds context (4096)").
 *
 * <p>Discipline: every assembler call returns an {@link AssembledPrompt}
 * with an explicit {@code backendId}. Callers that pass it to inference
 * must keep that ID intact. The dispatcher logs a WARN when the
 * resolved backend doesn't match.
 *
 * <p>{@code backendId} values used today:
 * <ul>
 *   <li>{@code "cap:quick"} — voice tier (4B model + voice LoRA, 4K ctx).
 *       Use {@link PromptAssembler#assembleForVoice}.</li>
 *   <li>{@code "cap:full"} — heavy tier (9B reasoning, 16K+ ctx).
 *       Use {@link PromptAssembler#assembleForFull}. This is the
 *       default for actors that don't have triage-driven routing.</li>
 * </ul>
 */
public record AssembledPrompt(
    String backendId,
    List<ChatMessage> messages,
    int approxTokens
) {

    public static final String BACKEND_VOICE = "cap:quick";
    public static final String BACKEND_FULL  = "cap:full";

    public AssembledPrompt {
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("AssembledPrompt requires a backendId — "
                + "every prompt must declare which backend it was built for. F15.");
        }
        if (messages == null) messages = List.of();
    }

    /**
     * Returns true if this prompt is safe to dispatch to the given backend
     * label. Safe = labels match exactly. Mismatch = caller routed wrong;
     * truncation likely; dispatcher should WARN or refuse.
     */
    public boolean matches(String resolvedBackendId) {
        if (resolvedBackendId == null) {
            // No explicit backend hint = default routing; treat as compatible
            // with the heavy tier (the historical default). Voice prompts must
            // always carry an explicit cap:quick hint.
            return BACKEND_FULL.equals(backendId);
        }
        return backendId.equals(resolvedBackendId);
    }

    /** Approximate token count from chat-message char totals (4 chars ≈ 1 token). */
    public static int estimateTokens(List<ChatMessage> messages) {
        int chars = 0;
        for (var m : messages) {
            if (m.content() != null) chars += m.content().length();
        }
        return chars / 4;
    }
}
