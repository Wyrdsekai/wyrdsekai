package org.wyrdsekai.core.agent;

/**
 * Bidirectional conversation channel (Telegram, Discord, Slack, LINE, Keybase).
 *
 * <p>Extends {@link AlertChannel} with a listener that routes incoming messages
 * from the external platform to the companion via {@link AgentEventStream}.
 * The companion responds through the same channel — the bridge is invisible
 * to the user. They're just chatting with their companion on Telegram/etc.</p>
 */
public interface ConversationChannel extends AlertChannel {

    /**
     * Start listening for incoming messages on this channel.
     * Routes messages to the companion via AgentEventStream.publishAgentMessage().
     * Resolves the companion dynamically via EntityRegistry.findByName() at message time.
     *
     * @param companionName the companion name to look up (e.g., "Wyrd")
     */
    void startListener(String companionName);

    /** Stop the listener on shutdown. */
    void stopListener();

    /** Whether the listener is currently active. */
    boolean isListening();
}
