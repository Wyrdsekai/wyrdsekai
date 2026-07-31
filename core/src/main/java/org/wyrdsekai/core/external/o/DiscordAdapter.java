package org.wyrdsekai.core.external.o;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Discord outbound. Bot API client.
 *
 * <p>Methods: {@code send_message, dm, list_channels, react, edit}.</p>
 *
 * <p>Credentials slot: {@code discord.bot_token}. The token is sent as
 * {@code Authorization: Bot <token>} per Discord's REST conventions.</p>
 */
public final class DiscordAdapter implements ExternalAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiscordAdapter.class);

    static final String DEFAULT_BASE_URL = "https://discord.com/api/v10/";

    private final Function<String, Optional<String>> credentials;
    private final SlackAdapter.HttpInvoker http;
    private final String baseUrl;

    public DiscordAdapter() {
        this(slot -> CredentialResolver.get().resolve(slot),
            new SlackAdapter.RealHttpInvoker(), DEFAULT_BASE_URL);
    }

    DiscordAdapter(Function<String, Optional<String>> credentials,
                   SlackAdapter.HttpInvoker http, String baseUrl) {
        this.credentials = credentials;
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    @Override public String namespace() { return "discord"; }

    @Override public Set<String> capabilities() {
        return Set.of("send_message", "send", "dm", "list_channels", "react", "edit");
    }

    @Override public String credentialSlot() { return "discord.bot_token"; }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var token = credentials.apply("discord.bot_token");
        if (token.isEmpty()) {
            return AdapterResponse.fail("credentials_missing",
                "discord.bot_token not in Safe", false);
        }
        var args = req.args();
        return switch (req.method()) {
            case "send_message", "send" -> sendMessage(args, token.get());
            case "dm" -> dm(args, token.get());
            case "list_channels" -> listChannels(args, token.get());
            case "react" -> react(args, token.get());
            case "edit" -> edit(args, token.get());
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse sendMessage(Map<String, Object> args, String token) {
        var channel = AdapterHttp.str(args, "channel");
        var content = AdapterHttp.str(args, "message");
        if (content == null) content = AdapterHttp.str(args, "content");
        if (channel == null) {
            return AdapterResponse.fail("invalid_argument", "'channel' is required", false);
        }
        if (content == null) content = "";
        var payload = new LinkedHashMap<String, Object>();
        payload.put("content", content);
        var opts = AdapterHttp.asMap(args.get("opts"));
        if (opts.containsKey("embeds")) payload.put("embeds", opts.get("embeds"));
        if (opts.containsKey("replyTo")) {
            payload.put("message_reference", Map.of("message_id", opts.get("replyTo")));
        }
        return invokeDiscordPost("channels/" + channel + "/messages", payload, token);
    }

    private AdapterResponse dm(Map<String, Object> args, String token) {
        var userId = AdapterHttp.str(args, "userId");
        var content = AdapterHttp.str(args, "message");
        if (content == null) content = AdapterHttp.str(args, "content");
        if (userId == null) {
            return AdapterResponse.fail("invalid_argument", "'userId' is required", false);
        }
        // Discord requires opening a DM channel first via /users/@me/channels.
        try {
            var body = AdapterHttp.MAPPER.writeValueAsString(Map.of("recipient_id", userId));
            var open = http.postJson(baseUrl + "users/@me/channels", body,
                Map.of("Authorization", "Bot " + token));
            if (open.statusCode() != 200 && open.statusCode() != 201) {
                return AdapterResponse.fail("dm_open_failed",
                    "could not open DM channel: HTTP " + open.statusCode(), true);
            }
            var channel = AdapterHttp.MAPPER.readTree(open.body()).path("id").asText();
            if (channel.isBlank()) {
                return AdapterResponse.fail("dm_open_failed",
                    "no channel id in DM response", false);
            }
            var newArgs = new LinkedHashMap<String, Object>(args);
            newArgs.put("channel", channel);
            return sendMessage(newArgs, token);
        } catch (Exception e) {
            return AdapterResponse.fail("discord_error", e.getMessage(), true);
        }
    }

    private AdapterResponse listChannels(Map<String, Object> args, String token) {
        var guild = AdapterHttp.str(args, "guild");
        if (guild == null) {
            return AdapterResponse.fail("invalid_argument", "'guild' is required", false);
        }
        try {
            var resp = http.get(baseUrl + "guilds/" + guild + "/channels",
                Map.of("Authorization", "Bot " + token));
            return AdapterHttp.fromHttp(resp);
        } catch (Exception e) {
            return AdapterResponse.fail("discord_error", e.getMessage(), true);
        }
    }

    private AdapterResponse react(Map<String, Object> args, String token) {
        var channel = AdapterHttp.str(args, "channel");
        var msg = AdapterHttp.str(args, "messageId");
        var emoji = AdapterHttp.str(args, "emoji");
        if (channel == null || msg == null || emoji == null) {
            return AdapterResponse.fail("invalid_argument",
                "'channel', 'messageId', 'emoji' are required", false);
        }
        var enc = URLEncoder.encode(emoji, StandardCharsets.UTF_8);
        try {
            var url = baseUrl + "channels/" + channel + "/messages/" + msg
                + "/reactions/" + enc + "/@me";
            var b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bot " + token)
                .timeout(AdapterHttp.DEFAULT_TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
            var resp = AdapterHttp.client().send(b,
                HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 204) return AdapterResponse.ok(Map.of("ok", true));
            return AdapterHttp.fromHttp(resp);
        } catch (Exception e) {
            return AdapterResponse.fail("discord_error", e.getMessage(), true);
        }
    }

    private AdapterResponse edit(Map<String, Object> args, String token) {
        var channel = AdapterHttp.str(args, "channel");
        var msg = AdapterHttp.str(args, "messageId");
        var content = AdapterHttp.str(args, "newContent");
        if (channel == null || msg == null || content == null) {
            return AdapterResponse.fail("invalid_argument",
                "'channel', 'messageId', 'newContent' are required", false);
        }
        try {
            var body = AdapterHttp.MAPPER.writeValueAsString(Map.of("content", content));
            var url = baseUrl + "channels/" + channel + "/messages/" + msg;
            var b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bot " + token)
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(AdapterHttp.DEFAULT_TIMEOUT)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
            var resp = AdapterHttp.client().send(b,
                HttpResponse.BodyHandlers.ofString());
            return AdapterHttp.fromHttp(resp);
        } catch (Exception e) {
            return AdapterResponse.fail("discord_error", e.getMessage(), true);
        }
    }

    private AdapterResponse invokeDiscordPost(String path, Map<String, Object> payload,
                                                String token) {
        try {
            var body = AdapterHttp.MAPPER.writeValueAsString(payload);
            var resp = http.postJson(baseUrl + path, body,
                Map.of("Authorization", "Bot " + token));
            return AdapterHttp.fromHttp(resp);
        } catch (Exception e) {
            log.warn("discord {} failed: {}", path, e.getMessage());
            return AdapterResponse.fail("discord_error", e.getMessage(), true);
        }
    }
}
