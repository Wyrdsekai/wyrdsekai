package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.SoulItem;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers — cross-agent tool copy. Preserves original
 * creator, annotates copy lineage via tags, content-addressed hash stays
 * the same (content unchanged).
 */
class ToolTransferTest {

    private static final String WYRD = "did:wyrd:zA:wyrd";
    private static final String EMBER = "did:wyrd:zA:ember";

    @Test
    void copy_preserves_creator_and_content() {
        var tool = SoulItem.create("skill", "researcher",
            "function research() { return 'found'; }",
            WYRD, 0.7, "tag1", "tag2");
        var copy = ToolTransfer.copy(tool, WYRD, FormTransfer.Intent.TEACHING, "for Ember");

        assertEquals(WYRD, copy.creatorDid(),
            "original creator preserved (§7.4)");
        assertEquals(tool.hash(), copy.hash(),
            "content-addressed hash unchanged (same text → same hash)");
        assertEquals(tool.text(), copy.text());
        assertEquals(tool.category(), copy.category());
    }

    @Test
    void copy_extends_tags_with_copy_marker() {
        var tool = SoulItem.create("skill", "researcher",
            "code", WYRD, 0.5, "original-tag");
        var copy = ToolTransfer.copy(tool, WYRD, FormTransfer.Intent.GIFT, null);

        var tags = Arrays.asList(copy.tags());
        assertTrue(tags.contains("original-tag"));
        assertTrue(tags.stream().anyMatch(t -> t.startsWith("copied-from:" + WYRD)));
    }

    @Test
    void copy_depth_accumulates_across_hops() {
        var tool = SoulItem.create("skill", "shared", "code", WYRD, 0.5);
        assertEquals(0, ToolTransfer.copyDepth(tool));

        var firstCopy = ToolTransfer.copy(tool, WYRD, FormTransfer.Intent.GIFT, null);
        assertEquals(1, ToolTransfer.copyDepth(firstCopy));

        var secondCopy = ToolTransfer.copy(firstCopy, EMBER, FormTransfer.Intent.GIFT, null);
        assertEquals(2, ToolTransfer.copyDepth(secondCopy));
    }

    @Test
    void gift_convenience_uses_gift_intent() {
        var tool = SoulItem.create("skill", "a", "x", WYRD, 0.5);
        var copy = ToolTransfer.gift(tool, WYRD);
        assertTrue(Arrays.stream(copy.tags())
            .anyMatch(t -> t.contains("GIFT")));
    }

    @Test
    void copy_rejects_null_inputs() {
        var tool = SoulItem.create("skill", "a", "x", WYRD, 0.5);
        assertThrows(IllegalArgumentException.class,
            () -> ToolTransfer.copy(null, WYRD, FormTransfer.Intent.GIFT, null));
        assertThrows(IllegalArgumentException.class,
            () -> ToolTransfer.copy(tool, "", FormTransfer.Intent.GIFT, null));
    }

    @Test
    void foreign_tool_inbox_delivers_and_drains() {
        ForeignToolInbox.resetForTests();
        var inbox = ForeignToolInbox.get();
        var tool = SoulItem.create("skill", "shared", "code", WYRD, 0.5);
        var copy = ToolTransfer.copy(tool, WYRD, FormTransfer.Intent.TEACHING, null);

        inbox.deliver(new ForeignToolInbox.PendingTool(
            copy, WYRD, EMBER, FormTransfer.Intent.TEACHING, null, null));
        assertEquals(1, inbox.pendingCount(EMBER));

        var drained = inbox.drain(EMBER);
        assertEquals(1, drained.size());
        assertEquals(copy.hash(), drained.get(0).item().hash());
        assertEquals(0, inbox.pendingCount(EMBER),
            "drain is destructive");
    }
}
