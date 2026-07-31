package org.wyrdsekai.core.coding;

import java.util.UUID;

/**
 * Side-channel metadata that ties an in-room codex/artifact
 * {@link org.wyrdsekai.common.model.RoomObject} back to the backend
 * task + artifact that produced it.
 *
 * <p>. {@link CodingTaskItemBridge}
 * stamps this on placement; {@link
 * org.wyrdsekai.core.room.RoomActor#onUseObject} consults it so a
 * player typing {@code use codex-abc12345 examine} reaches the same
 * router that serves player WebSocket commands and agent
 * {@code zone_command} actions.</p>
 *
 * @param roomObjectId    the {@code id} of the placed {@link
 *                        org.wyrdsekai.common.model.RoomObject}
 *                        (e.g. {@code "codex-abc12345"})
 * @param backend         the backend's stable namespace name
 *                        ({@code "openhands"}, {@code "opencode"},
 *                        {@code "codeplane"}, …)
 * @param taskId          UUID-string of the originating task (the
 *                        backend's primary handle)
 * @param artifactId      UUID of this specific artifact
 * @param kind            {@code "codex"} or {@code "artifact"} — drives
 *                        the default verb used when the player types
 *                        {@code use <id>} with no verb
 * @param scriptedItemId  When non-null, the {@code manifest.name} of a
 *                        scripted item registered with {@link
 *                        org.wyrdsekai.core.item.ScriptedItemLoader}
 *                        for this artifact's GraalJS source. Tells
 *                        {@code RoomActor.dispatchCodingItemUse} to
 *                        route through the items-as-tools script engine
 *                        instead of {@code LocalCommandRouter}. Null
 *                        when the agent didn't produce a parseable
 *                        manifest, in which case the legacy router
 *                        path still applies as a fallback.
 */
public record CodingItemMetadata(
        String roomObjectId,
        String backend,
        String taskId,
        UUID artifactId,
        String kind,
        String scriptedItemId) {

    /** Backward-compatible constructor — no scripted item registered. */
    public CodingItemMetadata(String roomObjectId, String backend, String taskId,
                              UUID artifactId, String kind) {
        this(roomObjectId, backend, taskId, artifactId, kind, null);
    }

    /** True for built-output items (run / test / deploy verbs apply). */
    public boolean isArtifact() { return "artifact".equalsIgnoreCase(kind); }

    /** True for source/workspace items (examine / log / diff verbs apply). */
    public boolean isCodex() { return "codex".equalsIgnoreCase(kind); }

    /** True iff a scripted item was registered alongside placement. */
    public boolean hasScriptedItem() {
        return scriptedItemId != null && !scriptedItemId.isBlank();
    }

    /**
     * Default verb when the player types {@code use <id>} with no
     * action — examine for codex (file list), examine for artifact
     * (build status). Run is opt-in via explicit {@code use <id> run}.
     */
    public String defaultVerb() { return "examine"; }
}
