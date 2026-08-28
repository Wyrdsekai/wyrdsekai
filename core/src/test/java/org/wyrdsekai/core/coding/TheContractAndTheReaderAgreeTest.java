package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.item.CarriedItemUse;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.core.item.ItemScriptResponse;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The field the preamble tells an author to fill must be a field we read.
 *
 * <h2>Why this test exists at all</h2>
 * The items-as-tools preamble is the contract every coding backend is handed, and its
 * FILE SHAPE block ends with {@code return &#123; ok: true, summary: "..." &#125;}.
 * {@code ItemScriptResponse} — the one place a result becomes words a person reads —
 * looked only at {@code response}, {@code text} and {@code error}.
 *
 * <p>So the two halves of one contract disagreed, silently and in the worst direction:
 * an item that followed the instructions exactly produced a correct answer, and every
 * surface printed "You use the &lt;item&gt;." instead. Live 2026-08-21, on a tool the
 * steward had just watched get built and handed to him.
 *
 * <p>That is the third time in three days that two pieces of code answered the same
 * question differently — the entrypoint check versus the runtime, the hand-off's index
 * versus the description, and now the author's contract versus the reader. A comment
 * saying "keep these in step" is not a mechanism. This is.
 */
class TheContractAndTheReaderAgreeTest {

    /** The `return { ok: true, summary: "..." }` line in the FILE SHAPE block. */
    private static final Pattern RETURN_SHAPE =
        Pattern.compile("return\\s*\\{[^}]*?\\b(\\w+)\\s*:\\s*\"\\.\\.\\.\"");

    @Test
    void every_field_the_preamble_teaches_is_a_field_we_read() {
        var m = RETURN_SHAPE.matcher(OpenHandsBackend.itemsAsToolsPreamble(ItemCapabilitySet.craftedDefault()));
        assertThat(m.find())
            .as("the preamble must still show authors what to return")
            .isTrue();
        do {
            assertThat(ItemScriptResponse.TEXT_FIELDS)
                .as("preamble teaches `%s` — ItemScriptResponse must read it, or the "
                    + "item's answer is discarded on every surface", m.group(1))
                .contains(m.group(1));
        } while (m.find());
    }

    /** The CWD variant is a derivation of the same string; it must not diverge here. */
    @Test
    void the_cwd_variant_teaches_the_same_return_shape() {
        var m = RETURN_SHAPE.matcher(OpenHandsBackend.itemsAsToolsPreambleCwd(ItemCapabilitySet.craftedDefault()));
        assertThat(m.find()).isTrue();
        assertThat(ItemScriptResponse.TEXT_FIELDS).contains(m.group(1));
    }

    /**
     * And the args spelling, for the same reason: the preamble promises
     * {@code params.args}, and the carried-item path did not set it — so goose's
     * perfectly correct {@code if (typeof params.args !== "string")} guard rejected
     * every use.
     */
    @Test
    void the_preamble_promises_params_args_and_we_deliver_it() {
        assertThat(OpenHandsBackend.itemsAsToolsPreamble(ItemCapabilitySet.craftedDefault())).contains("params.args");
        assertThat(CarriedItemUse.params("alice", "x"))
            .containsKey("args");
    }
}
