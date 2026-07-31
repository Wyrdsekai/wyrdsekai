package org.wyrdsekai.core.codemode;

/**
 * Track A Phase 2b — system-message hint that licenses the
 * model to emit a free-form ```js block in its response.
 *
 * <p>The block is added <em>in addition to</em> the existing tool list, not
 * as a replacement. The model can still emit JSON actions; code-mode is just
 * one more option when the request shape calls for composition.
 *
 * <p>Per spec §4.1 example, ~150 tokens is the target — long enough to teach
 * the model the namespace surface, short enough not to drown the rest of the
 * primacy zone. The block is gated upstream (
 * {@link CodeModeFeatureFlag#isImprovisationEnabled()} +
 * {@link ImprovisationTrigger} + emotional context check) so it never appears
 * unless the operator opted in AND the request looks research-shape AND the
 * companion is not in grief.
 *
 * <p>Hallucinated-data resistance (per §11): the prompt explicitly tells the
 * model not to fabricate results when a tool isn't available. This is the
 * soft guard for Phase 2b — the JS probe finding (test 04) showed the model
 * sometimes simulated missing-API responses with fake data. Spec §11 calls
 * for the prompt instruction as the line of defense; if soak shows it's not
 * enough, Phase 2c can add a stricter runtime check.
 */
public final class FreeFormCodeModePromptBlock {

    private FreeFormCodeModePromptBlock() {}

    /**
     * The hint text. Returns the same instance on every call — the block is
     * static (no per-turn parameters in Phase 2b; the namespace is built
     * dynamically by {@link CodeModeNamespace}).
     */
    public static String text() {
        return BLOCK;
    }

    private static final String BLOCK =
        "CODE MODE (composition only): if the task needs multiple tool results "
        + "combined (search + dedupe + format, compare two sources, look at both "
        + "X and Y at once), you may write one small JavaScript block in your "
        + "response. Wrap in ```js ... ```.\n"
        + "Namespace: <equipped-item-alias>.<method>(args), world.peek(roomAlias), "
        + "world.listInventory(), mcp.search(query), mcp.execute(server, tool, args). "
        + "console.log(...) becomes your next observation.\n"
        + "Narrate around the script (\"let me check both at once...\") so the "
        + "human sees thinking, not mechanism. Single-tool calls stay as direct "
        + "tool actions. If you don't have a needed tool, say so — do not fabricate "
        + "results or simulate API calls.";
}
