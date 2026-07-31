package org.wyrdsekai.server.session;

/**
 * Transport-agnostic view of a single logged-in client connection.
 * <p>
 * Implemented by each inbound transport (WebSocket, SSH, Telnet) as a thin
 * facade over its own connection state. The {@link ClientConnectionRegistry}
 * holds one instance per active login so server-side components (notably the
 * federation transit starter) can reach a client by {@code playerId} without
 * caring which wire protocol it speaks.
 * <p>
 */
public interface ClientConnection {

    String sessionId();

    String playerId();

    String playerName();

    /**
     * Start proxying this connection to a remote zone. While proxying,
     * input lines from the client are forwarded to the remote zone as
     * commands; events from the remote zone are rendered through the
     * transport's existing output path.
     *
     * @return {@code true} if the remote session was started successfully
     */
    boolean startRemoteSession(String remoteZoneId, String transitToken);

    /**
     * End the current remote session (if any). Restores local command
     * handling and re-seeds local room state.
     */
    void endRemoteSession();

    /** True while proxying — input routing is diverted to the remote zone. */
    boolean isProxying();

    /** The remote zone currently being proxied to, or {@code null}. */
    String currentRemoteZoneId();

    /**
     * Close this connection with a human-readable reason. Used by the
     * registry's link-takeover path: when a second login arrives for the
     * same {@code playerId}, the old session is notified and disconnected
     * so the new session becomes the authoritative one. Implementations
     * should: (1) render the reason to the client, (2) perform a graceful
     * LeaveRoom so entity presence doesn't linger as a ghost, (3) close
     * the underlying channel. Default is a no-op so older transports that
     * don't yet implement this stay compatible.
     */
    default void disconnect(String reason) { /* no-op default */ }

    /**
     * Deliver a single out-of-band text line to this connection — the
     * transport renders it through its normal output path (a Prose frame on
     * WS, a rendered line + fresh prompt on SSH/Telnet). Backs the tell-back
     * path (second-node re-verify 2026-07-11 #29): a companion's reply to
     * {@code tell} must reach the sender on WHATEVER surface they're using,
     * not just WebSocket. Default returns {@code false} — "I couldn't
     * deliver this" — so transports that don't implement it stay honest and
     * callers fall back (e.g. teleport-and-speak).
     *
     * @return true only when the line was handed to a live session channel
     */
    default boolean deliverLine(String text) { return false; }
}
