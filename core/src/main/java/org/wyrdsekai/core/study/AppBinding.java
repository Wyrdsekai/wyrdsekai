package org.wyrdsekai.core.study;

/**
 * Mapping from a MUD-friendly alias to a desktop application.
 *
 * @param alias       User-facing name ("notes", "editor", "browser")
 * @param command     Actual binary or app name ("obsidian", "code", "firefox")
 * @param description Human-readable description
 */
public record AppBinding(
    String alias,
    String command,
    String description
) {
    public AppBinding {
        if (alias == null || alias.isBlank()) throw new IllegalArgumentException("Alias required");
        if (command == null || command.isBlank()) throw new IllegalArgumentException("Command required");
        if (description == null) description = command;
    }
}
