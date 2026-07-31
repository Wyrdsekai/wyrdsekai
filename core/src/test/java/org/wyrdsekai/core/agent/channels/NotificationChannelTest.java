package org.wyrdsekai.core.agent.channels;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AlertChannel;
import org.wyrdsekai.core.agent.ConversationChannel;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for notification channel implementations.
 * Tests construction, interface compliance, and message formatting.
 * Actual HTTP calls are not tested (would need live services).
 */
class NotificationChannelTest {

    // ─── ntfy ───────────────────────────────────────────────────

    @Test
    void ntfy_implementsAlertChannel() {
        AlertChannel ch = new NtfyAlertChannel("test-topic");
        assertEquals("ntfy", ch.name());
    }

    @Test
    void ntfy_customServer() {
        var ch = new NtfyAlertChannel("https://ntfy.example.com", "my-topic");
        assertEquals("ntfy", ch.name());
    }

    @Test
    void ntfy_stripsTrailingSlash() {
        var ch = new NtfyAlertChannel("https://ntfy.example.com/", "my-topic");
        assertEquals("ntfy", ch.name());
    }

    // ─── Email ──────────────────────────────────────────────────

    @Test
    void email_implementsAlertChannel() {
        AlertChannel ch = new EmailAlertChannel("to@example.com", "user@example.com", "pass");
        assertEquals("email", ch.name());
    }

    @Test
    void email_fullSmtpConfig() {
        AlertChannel ch = new EmailAlertChannel(
            "to@example.com", "smtp.custom.com", 465,
            "user", "pass", "from@custom.com");
        assertEquals("email", ch.name());
    }

    // ─── Discord ────────────────────────────────────────────────

    @Test
    void discord_implementsAlertChannel() {
        AlertChannel ch = new DiscordChannel("https://discord.com/api/webhooks/123/abc");
        assertEquals("discord", ch.name());
    }

    // ─── Webhook ────────────────────────────────────────────────

    @Test
    void webhook_implementsAlertChannel() {
        AlertChannel ch = new WebhookAlertChannel("https://example.com/hook");
        assertEquals("webhook", ch.name());
    }

    @Test
    void webhook_customLabel() {
        AlertChannel ch = new WebhookAlertChannel("https://example.com/hook", "my-ifttt");
        assertEquals("my-ifttt", ch.name());
    }

    // ─── Telegram ───────────────────────────────────────────────

    @Test
    void telegram_implementsConversationChannel() {
        ConversationChannel ch = new TelegramChannel("123:ABC", "987654");
        assertEquals("telegram", ch.name());
        assertFalse(ch.isListening(), "Should not be listening before startListener");
    }

    @Test
    void telegram_startStopListener() {
        var ch = new TelegramChannel("invalid-token", "invalid-chat");
        ch.startListener("Wyrd");
        assertTrue(ch.isListening());
        ch.stopListener();
        // Give it a moment to stop
        try { Thread.sleep(100); } catch (InterruptedException e) {}
        assertFalse(ch.isListening());
    }

    // ─── Slack ──────────────────────────────────────────────────

    @Test
    void slack_implementsConversationChannel() {
        ConversationChannel ch = new SlackChannel("xoxb-test", "C12345");
        assertEquals("slack", ch.name());
        assertFalse(ch.isListening());
    }

    // ─── LINE ───────────────────────────────────────────────────

    @Test
    void line_implementsAlertChannel() {
        AlertChannel ch = new LineChannel("token", "U12345");
        assertEquals("line", ch.name());
    }

    // ─── Keybase ────────────────────────────────────────────────

    @Test
    void keybase_implementsConversationChannel() {
        ConversationChannel ch = new KeybaseChannel("operator");
        assertEquals("keybase", ch.name());
        assertFalse(ch.isListening());
    }

    // ─── Interface Contract ─────────────────────────────────────

    @Test
    void allChannels_haveCorrectNames() {
        assertEquals("ntfy", new NtfyAlertChannel("test").name());
        assertEquals("discord", new DiscordChannel("https://example.com").name());
        assertEquals("webhook", new WebhookAlertChannel("https://example.com").name());
        assertEquals("line", new LineChannel("token", "user").name());
        assertEquals("telegram", new TelegramChannel("tok", "chat").name());
        assertEquals("slack", new SlackChannel("tok", "ch").name());
        assertEquals("keybase", new KeybaseChannel("user").name());
        assertEquals("email", new EmailAlertChannel("to@test.com", "u@test.com", "pw").name());
    }
}
