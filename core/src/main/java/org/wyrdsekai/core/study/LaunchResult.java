package org.wyrdsekai.core.study;

/**
 * Result of a desktop app launch attempt.
 *
 * @param success Whether the launch succeeded
 * @param message Narration text for the room
 * @param pid     OS process ID (for tracking, 0 if unknown)
 */
public record LaunchResult(
    boolean success,
    String message,
    long pid
) {
    public static LaunchResult ok(String message, long pid) {
        return new LaunchResult(true, message, pid);
    }

    public static LaunchResult ok(String message) {
        return new LaunchResult(true, message, 0);
    }

    public static LaunchResult fail(String message) {
        return new LaunchResult(false, message, 0);
    }
}
