package org.wyrdsekai.core.governance;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Atomic task claim protocol (§4.3).
 * Manages blackboard tasks: posting, claiming (first-claim-wins), completion.
 * Thread-safe via ConcurrentHashMap + synchronized claim operations.
 */
public class TaskClaimProtocol {

    private final Map<String, BlackboardTask> tasks = new ConcurrentHashMap<>();
    private int nextId = 1;

    /** Post a new task to the blackboard. */
    public BlackboardTask post(String description, String roomId, String postedBy) {
        var id = "task-" + nextId++;
        var task = BlackboardTask.create(id, description, roomId, postedBy);
        tasks.put(id, task);
        return task;
    }

    /** Attempt to claim a task (atomic, first-claim-wins). */
    public synchronized Optional<BlackboardTask> claim(String taskId, String agentId) {
        var task = tasks.get(taskId);
        if (task == null) return Optional.empty();
        var claimed = task.claim(agentId);
        claimed.ifPresent(t -> tasks.put(taskId, t));
        return claimed;
    }

    /** Mark a task as complete. */
    public synchronized Optional<BlackboardTask> complete(String taskId, String agentId) {
        var task = tasks.get(taskId);
        if (task == null) return Optional.empty();
        var completed = task.complete(agentId);
        completed.ifPresent(t -> tasks.put(taskId, t));
        return completed;
    }

    /** Cancel a task. */
    public synchronized Optional<BlackboardTask> cancel(String taskId, String entityId) {
        var task = tasks.get(taskId);
        if (task == null) return Optional.empty();
        var cancelled = task.cancel(entityId);
        cancelled.ifPresent(t -> tasks.put(taskId, t));
        return cancelled;
    }

    /** Get open tasks for a room. */
    public List<BlackboardTask> openTasks(String roomId) {
        return tasks.values().stream()
            .filter(t -> t.roomId().equals(roomId) && t.isOpen())
            .sorted(Comparator.comparingDouble(BlackboardTask::relevanceScore).reversed())
            .toList();
    }

    /** Get all tasks for a room. */
    public List<BlackboardTask> tasksForRoom(String roomId) {
        return tasks.values().stream()
            .filter(t -> t.roomId().equals(roomId))
            .sorted(Comparator.comparing(BlackboardTask::createdAt).reversed())
            .toList();
    }

    /** Get a task by ID. */
    public Optional<BlackboardTask> get(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /** Total task count. */
    public int taskCount() {
        return tasks.size();
    }

    /** Open task count across all rooms. */
    public int openTaskCount() {
        return (int) tasks.values().stream().filter(BlackboardTask::isOpen).count();
    }
}
