package org.wyrdsekai.core.agent;

import java.util.concurrent.CompletableFuture;

/**
 * One-way notification channel (email, phone push, ntfy, webhook).
 * Sends a message and a deep link back to Wyrdsekai. Fire-and-forget.
 */
public interface AlertChannel {

    /** Channel name for logging and config (e.g., "email", "ntfy"). */
    String name();

    /**
     * Send a notification.
     *
     * @param message   the notification text
     * @param priority  "ambient", "normal", or "critical"
     * @param fromAgent name of the companion sending the notification
     * @param deepLink  URL or command to open Wyrdsekai (e.g., "ssh mac-node -p 7022")
     * @return true if sent successfully
     */
    CompletableFuture<Boolean> send(String message, String priority,
                                     String fromAgent, String deepLink);
}
