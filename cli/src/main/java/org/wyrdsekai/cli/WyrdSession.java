package org.wyrdsekai.cli;

import org.wyrdsekai.common.protocol.C2SMessage;

/**
 * the terminal's transport seam. The CLI/TUI render loop and
 * {@link InputHandler} speak this protocol-shaped interface, never a concrete
 * transport, so the same terminal points at either:
 *   - {@link Connection}            — direct WebSocket to a LAN-reachable zone, or
 *   - {@link RelayTunnelConnection} — a tunneled session through the relay to a
 *     NAT'd, relay-only zone (byte-identical world; just carried over the dumb pipe).
 *
 * Mirrors the methods the CLI actually calls on a session; both implementations
 * deliver S2C frames through the {@code Consumer<S2CMessage>} given at construction.
 */
public interface WyrdSession {
    void connect();
    boolean awaitConnected(long timeoutMs);
    void disconnect();
    void prepareClose();
    boolean awaitClosed(long timeoutMs);
    void send(C2SMessage msg);
    String newId();
    void setToken(String token);
    void reconnectWithToken();
}
