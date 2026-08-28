package org.wyrdsekai.core.coding.acp;

/**
 * ACP v1 method names, in one table.
 *
 * <p>Deliberately a table and not string literals scattered through the
 * dispatcher: ACP v2 renames and removes methods (authenticate →
 * auth/login, session/load gone), and the negotiated-version design means
 * a v2 dialect later is an additive second table, not a rewrite. This was
 * the "cheap now, expensive to retrofit" advice in the CodeZaiku
 * integration letter (2026-08-15), taken.</p>
 *
 * <p>Source of truth: agent-client-protocol {@code schema/v1/schema.json}
 * ("The current stable ACP protocol version is 1" — README). Goose and
 * opencode speak v1; Goose's own fixture sends {@code protocolVersion: 1}.</p>
 */
public final class AcpMethods {

    private AcpMethods() {}

    /** The protocol version this client speaks and offers at initialize. */
    public static final int PROTOCOL_VERSION = 1;

    // client → agent requests
    public static final String INITIALIZE = "initialize";
    public static final String SESSION_NEW = "session/new";
    public static final String SESSION_PROMPT = "session/prompt";

    // client → agent notifications
    public static final String SESSION_CANCEL = "session/cancel";

    // agent → client notifications
    public static final String SESSION_UPDATE = "session/update";

    // agent → client requests
    public static final String SESSION_REQUEST_PERMISSION = "session/request_permission";
    public static final String FS_READ_TEXT_FILE = "fs/read_text_file";
    public static final String FS_WRITE_TEXT_FILE = "fs/write_text_file";
}
