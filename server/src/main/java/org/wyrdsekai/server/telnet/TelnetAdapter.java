package org.wyrdsekai.server.telnet;

import org.apache.pekko.actor.typed.ActorSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.RelaySessionTransport;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.InventoryService;
import org.wyrdsekai.core.persistence.InviteService;
import org.wyrdsekai.core.persistence.WardService;
import org.wyrdsekai.server.session.ClientConnectionRegistry;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TCP listener for Telnet/GMCP connections.
 * Uses virtual threads for per-connection handling.
 * Each connection gets a TelnetSession that bridges to the actor system.
 */
public class TelnetAdapter {

    private static final Logger log = LoggerFactory.getLogger(TelnetAdapter.class);

    private volatile ServerSocket serverSocket;
    private volatile ExecutorService executor;
    private volatile boolean running;

    /**
     * Start listening on the given port.
     * Spawns a daemon thread for the accept loop.
     * Each accepted connection runs in a virtual thread.
     */
    private InviteService inviteService; // nullable
    // Cross-zone transit plumbing — optional, set via setTransitContext.
    private volatile String localZoneId;
    private volatile RelaySessionTransport relayTransport;
    private volatile ClientConnectionRegistry connectionRegistry;

    /** Wire cross-zone transit — parity with WS/SSH. */
    public void setTransitContext(String localZoneId,
                                  RelaySessionTransport relayTransport,
                                  ClientConnectionRegistry registry) {
        this.localZoneId = localZoneId;
        this.relayTransport = relayTransport;
        this.connectionRegistry = registry;
    }

    public void start(int port, ActorSystem<?> system,
                      AuthService authService, WardService wardService,
                      InventoryService inventoryService) {
        start(port, "127.0.0.1", system, authService, null, wardService, inventoryService);
    }

    public void start(int port, ActorSystem<?> system,
                      AuthService authService,
                      InviteService inviteService,
                      WardService wardService,
                      InventoryService inventoryService) {
        start(port, "127.0.0.1", system, authService, inviteService, wardService, inventoryService);
    }

    /**
     * Start telnet listener.
     *
     * @param bind bind address; defaults to "127.0.0.1". Operator must
     *             explicitly pass "0.0.0.0" to expose telnet to the LAN.
     *             Telnet is cleartext, so the default is loopback-only.
     */
    public void start(int port, String bind, ActorSystem<?> system,
                      AuthService authService,
                      InviteService inviteService,
                      WardService wardService,
                      InventoryService inventoryService) {
        this.inviteService = inviteService;
        try {
            var bindAddr = (bind == null || bind.isBlank()) ? "127.0.0.1" : bind.trim();
            serverSocket = new ServerSocket(port, 50, InetAddress.getByName(bindAddr));
            running = true;
            executor = Executors.newVirtualThreadPerTaskExecutor();

            var acceptThread = new Thread(
                () -> acceptLoop(system, authService, wardService, inventoryService),
                "telnet-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();

            log.info("Telnet/GMCP adapter listening on {}:{}{}",
                bindAddr, port,
                "0.0.0.0".equals(bindAddr) ? " (EXPOSED — cleartext on LAN)" : "");
        } catch (IOException e) {
            log.error("Failed to start Telnet adapter on port {}: {}", port, e.getMessage());
        }
    }

    public void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
        if (executor != null) {
            executor.shutdown();
        }
        log.info("Telnet adapter stopped");
    }

    private void acceptLoop(ActorSystem<?> system,
                             AuthService authService, WardService wardService,
                             InventoryService inventoryService) {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                log.info("Telnet connection from {}", client.getRemoteSocketAddress());
                var session = new TelnetSession(client, system, authService, inviteService,
                    wardService, inventoryService);
                if (localZoneId != null || relayTransport != null || connectionRegistry != null) {
                    session.setTransitContext(localZoneId, relayTransport, connectionRegistry);
                }
                executor.submit(session);
            } catch (IOException e) {
                if (running) {
                    log.error("Accept error: {}", e.getMessage());
                }
            }
        }
    }
}
