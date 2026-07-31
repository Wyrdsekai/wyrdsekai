package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SubagentActorTest {

    @Test
    void request_convenience_constructor() {
        var req = new SubagentActor.SubagentRequest("parent1", "search for books", "some context");
        assertEquals("parent1", req.parentId());
        assertEquals("search for books", req.task());
        assertEquals("some context", req.context());
        assertEquals(500, req.maxTokens());
        assertEquals(Duration.ofMinutes(2), req.timeout());
        assertNotNull(req.taskId());
    }

    @Test
    void result_success_factory() {
        var result = SubagentActor.SubagentResult.success("task1", "Found 3 books");
        assertTrue(result.success());
        assertEquals("task1", result.taskId());
        assertEquals("Found 3 books", result.summary());
        assertNull(result.error());
    }

    @Test
    void result_failure_factory() {
        var result = SubagentActor.SubagentResult.failure("task1", "timeout");
        assertFalse(result.success());
        assertEquals("task1", result.taskId());
        assertNull(result.summary());
        assertEquals("timeout", result.error());
    }

    @Test
    void full_request_constructor() {
        var req = new SubagentActor.SubagentRequest(
            "task-123", "parent2", "analyze data", "context data",
            1000, Duration.ofSeconds(30));
        assertEquals("task-123", req.taskId());
        assertEquals("parent2", req.parentId());
        assertEquals(1000, req.maxTokens());
        assertEquals(Duration.ofSeconds(30), req.timeout());
    }
}
