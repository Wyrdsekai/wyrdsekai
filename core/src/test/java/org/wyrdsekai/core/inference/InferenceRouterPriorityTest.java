package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;

import java.util.PriorityQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * verify the router classifies requests into the
 * correct priority band by requestId prefix.
 *
 * <p>Unit-level: the queue ordering under load is implicit in Java's
 * {@link java.util.PriorityQueue} semantics on the int returned here. We
 * assert the mapping, not the queue implementation.</p>
 */
class InferenceRouterPriorityTest {

    @Test
    void bunshin_prefix_gets_bunshin_priority() {
        assertThat(InferenceRouter.classifyPriority("bunshin-abc-t0"))
            .isEqualTo(InferenceRouter.PRIORITY_BUNSHIN);
    }

    @Test
    void familiar_prefix_gets_familiar_priority() {
        assertThat(InferenceRouter.classifyPriority("familiar-xyz-t5"))
            .isEqualTo(InferenceRouter.PRIORITY_FAMILIAR);
    }

    @Test
    void tool_and_item_prefixes_get_tool_priority() {
        assertThat(InferenceRouter.classifyPriority("tool-invoke-1"))
            .isEqualTo(InferenceRouter.PRIORITY_TOOL);
        assertThat(InferenceRouter.classifyPriority("item-lib-summarize"))
            .isEqualTo(InferenceRouter.PRIORITY_TOOL);
    }

    @Test
    void unknown_prefix_defaults_to_human() {
        assertThat(InferenceRouter.classifyPriority("companion-wyrd-t1"))
            .isEqualTo(InferenceRouter.PRIORITY_HUMAN);
        assertThat(InferenceRouter.classifyPriority("req-123"))
            .isEqualTo(InferenceRouter.PRIORITY_HUMAN);
        assertThat(InferenceRouter.classifyPriority(""))
            .isEqualTo(InferenceRouter.PRIORITY_HUMAN);
    }

    @Test
    void null_request_id_defaults_to_human() {
        assertThat(InferenceRouter.classifyPriority(null))
            .isEqualTo(InferenceRouter.PRIORITY_HUMAN);
    }

    @Test
    void priority_ordering_is_primary_tool_bunshin_familiar_ambient() {
        // lower value wins
        assertThat(InferenceRouter.PRIORITY_HUMAN)
            .isLessThan(InferenceRouter.PRIORITY_TOOL);
        assertThat(InferenceRouter.PRIORITY_TOOL)
            .isLessThan(InferenceRouter.PRIORITY_BUNSHIN);
        assertThat(InferenceRouter.PRIORITY_BUNSHIN)
            .isLessThan(InferenceRouter.PRIORITY_FAMILIAR);
        assertThat(InferenceRouter.PRIORITY_FAMILIAR)
            .isLessThan(InferenceRouter.PRIORITY_AMBIENT);
    }

    @Test
    void priority_queue_drains_in_spec_order() {
        // Verifies that a PriorityQueue with these int priorities actually
        // orders a batch of mixed requests the way the spec requires.
        var queue = new PriorityQueue<Integer>();
        queue.add(InferenceRouter.PRIORITY_FAMILIAR);
        queue.add(InferenceRouter.PRIORITY_HUMAN);
        queue.add(InferenceRouter.PRIORITY_BUNSHIN);
        queue.add(InferenceRouter.PRIORITY_TOOL);
        queue.add(InferenceRouter.PRIORITY_AMBIENT);

        // Drain and verify primary → tool → bunshin → familiar → ambient
        assertThat(queue.poll()).isEqualTo(InferenceRouter.PRIORITY_HUMAN);
        assertThat(queue.poll()).isEqualTo(InferenceRouter.PRIORITY_TOOL);
        assertThat(queue.poll()).isEqualTo(InferenceRouter.PRIORITY_BUNSHIN);
        assertThat(queue.poll()).isEqualTo(InferenceRouter.PRIORITY_FAMILIAR);
        assertThat(queue.poll()).isEqualTo(InferenceRouter.PRIORITY_AMBIENT);
    }
}
