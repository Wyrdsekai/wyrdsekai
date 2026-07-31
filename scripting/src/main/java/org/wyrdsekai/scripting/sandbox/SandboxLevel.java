package org.wyrdsekai.scripting.sandbox;

/**
 * Graduated sandbox levels for GraalJS script execution.
 * Each level adds capabilities on top of the previous one.
 *
 * <ul>
 *   <li>ROOM_SCRIPT — Room behavior only: world API, no Java interop</li>
 *   <li>SKILL_BASIC — + HTTP client, JSON, HTML parsing, crypto</li>
 *   <li>SKILL_DATA — + JDBC (SQLite), file I/O within workspace</li>
 *   <li>SKILL_SERVER — + HTTP server, network listen (future)</li>
 *   <li>SKILL_FULL — + unrestricted Java interop (steward-approved)</li>
 * </ul>
 */
public enum SandboxLevel {
    /** Room behavior only — world API, no Java interop. */
    ROOM_SCRIPT,

    /** + HTTP client, JSON, HTML parsing, crypto. */
    SKILL_BASIC,

    /** + JDBC (SQLite), CSV, file I/O within workspace. */
    SKILL_DATA,

    /** + HTTP server, network listen (future). */
    SKILL_SERVER,

    /** + unrestricted Java interop (steward-approved). */
    SKILL_FULL;

    /** Check if this level includes a given capability level. */
    public boolean includes(SandboxLevel other) {
        return this.ordinal() >= other.ordinal();
    }
}
