package org.wyrdsekai.core.codezaiku;

/**
 * Commands for interacting with Codex and Artifact items.
 * These route through the zone bridge to CodeZaiku for execution.
 *
 * <p>Agents emit these via {@code codex_action} in their LLM output.
 * Players use {@code use codex <operation>} in the MUD.</p>
 */
public sealed interface CodexCommand {

    /** Examine a file in the codex (or list files if file is null). */
    record Examine(String codexId, String file) implements CodexCommand {}

    /** Git commit with a message. */
    record Commit(String codexId, String message) implements CodexCommand {}

    /** Git push to remote repository. */
    record Push(String codexId) implements CodexCommand {}

    /** Create or switch git branch. */
    record Branch(String codexId, String branchName) implements CodexCommand {}

    /** Git diff against a ref (or working tree if ref is null). */
    record Diff(String codexId, String ref) implements CodexCommand {}

    /** Trigger a build — produces an Artifact item. */
    record Build(String codexId) implements CodexCommand {}

    /** Deploy an artifact to a target (room, node, container, remote, MCP, skill). */
    record Deploy(String artifactId, String target) implements CodexCommand {}

    /** Delete the item (workspace or artifact) and remove from the room. */
    record Destroy(String itemId) implements CodexCommand {}
}
