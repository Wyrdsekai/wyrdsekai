package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CancellationTokenTest {

    @Test
    void new_token_not_cancelled() {
        var token = new CancellationToken();
        assertFalse(token.isCancelled());
        assertNull(token.reason());
    }

    @Test
    void cancel_sets_state_and_reason() {
        var token = new CancellationToken();
        token.cancel("new human input");
        assertTrue(token.isCancelled());
        assertEquals("new human input", token.reason());
    }

    @Test
    void double_cancel_keeps_first_reason() {
        var token = new CancellationToken();
        token.cancel("first reason");
        token.cancel("second reason");
        assertTrue(token.isCancelled());
        assertEquals("first reason", token.reason());
    }

    @Test
    void child_cancelled_when_parent_cancelled() {
        var parent = new CancellationToken();
        var child = parent.child();

        assertFalse(child.isCancelled());
        parent.cancel("parent cancelled");
        assertTrue(child.isCancelled());
        assertTrue(child.reason().contains("parent"));
    }

    @Test
    void child_of_already_cancelled_parent_starts_cancelled() {
        var parent = new CancellationToken();
        parent.cancel("already done");
        var child = parent.child();
        assertTrue(child.isCancelled());
    }

    @Test
    void grandchild_receives_cancel() {
        var grandparent = new CancellationToken();
        var parent = grandparent.child();
        var child = parent.child();

        assertFalse(child.isCancelled());
        grandparent.cancel("top-level abort");
        assertTrue(parent.isCancelled());
        assertTrue(child.isCancelled());
    }

    @Test
    void none_token_starts_not_cancelled() {
        var token = CancellationToken.NONE;
        assertFalse(token.isCancelled());
    }

    @Test
    void created_at_is_set() {
        var token = new CancellationToken();
        assertNotNull(token.createdAt());
    }
}
