package org.wyrdsekai.core.external.o;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Telegram Bot API outbound.
 *
 * <p>Methods: {@code send_message, send_photo, edit_message}.</p>
 *
 * <p>Credentials slot: {@code telegram.bot_token}. Token is embedded in the
 * URL path per Telegram convention: {@code https://api.telegram.org/bot<token>/method}.</p>
 */
public final class TelegramAdapter implements ExternalAdapter {

    private static final Logger log = LoggerFactory.getLogger(TelegramAdapter.class);

    static final String DEFAULT_BASE_URL = "https://api.telegram.org/";

    private final Function<String, Optional<String>> credentials;
    private final SlackAdapter.HttpInvoker http;
    private final String baseUrl;

    public TelegramAdapter() {
        this(slot -> CredentialResolver.get().resolve(slot),
            new SlackAdapter.RealHttpInvoker(), DEFAULT_BASE_URL);
    }

    TelegramAdapter(Function<String, Optional<String>> credentials,
                    SlackAdapter.HttpInvoker http, String baseUrl) {
        this.credentials = credentials;
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    @Override public String namespace() { return "telegram"; }

    @Override public Set<String> capabilities() {
        return Set.of("send_message", "send", "send_photo", "edit_message");
    }

    @Override public String credentialSlot() { return "telegram.bot_token"; }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var token = credentials.apply("telegram.bot_token");
        if (token.isEmpty()) {
            return AdapterResponse.fail("credentials_missing",
                "telegram.bot_token not in Safe", false);
        }
        var args = req.args();
        return switch (req.method()) {
            case "send_message", "send" -> sendMessage(args, token.get());
            case "send_photo" -> sendPhoto(args, token.get());
            case "edit_message" -> editMessage(args, token.get());
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse sendMessage(Map<String, Object> args, String token) {
        var chatId = AdapterHttp.str(args, "chatId");
        var text = AdapterHttp.str(args, "text");
        if (chatId == null) {
            return AdapterResponse.fail("invalid_argument", "'chatId' is required", false);
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("chat_id", chatId);
        payload.put("text", text == null ? "" : text);
        var opts = AdapterHttp.asMap(args.get("opts"));
        if (opts.containsKey("parseMode")) payload.put("parse_mode", opts.get("parseMode"));
        if (opts.containsKey("replyToMessageId"))
            payload.put("reply_to_message_id", opts.get("replyToMessageId"));
        return invokeTelegram("sendMessage", payload, token);
    }

    private AdapterResponse sendPhoto(Map<String, Object> args, String token) {
        var chatId = AdapterHttp.str(args, "chatId");
        var photo = AdapterHttp.str(args, "photo");
        if (chatId == null || photo == null) {
            return AdapterResponse.fail("invalid_argument",
                "'chatId' and 'photo' (URL or file_id) are required", false);
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("chat_id", chatId);
        payload.put("photo", photo);
        var opts = AdapterHttp.asMap(args.get("opts"));
        if (opts.containsKey("caption")) payload.put("caption", opts.get("caption"));
        return invokeTelegram("sendPhoto", payload, token);
    }

    private AdapterResponse editMessage(Map<String, Object> args, String token) {
        var chatId = AdapterHttp.str(args, "chatId");
        var msgId = AdapterHttp.str(args, "messageId");
        var text = AdapterHttp.str(args, "text");
        if (chatId == null || msgId == null || text == null) {
            return AdapterResponse.fail("invalid_argument",
                "'chatId', 'messageId', 'text' are required", false);
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("chat_id", chatId);
        payload.put("message_id", msgId);
        payload.put("text", text);
        return invokeTelegram("editMessageText", payload, token);
    }

    private AdapterResponse invokeTelegram(String method, Map<String, Object> payload,
                                             String token) {
        try {
            var url = baseUrl + "bot" + token + "/" + method;
            var body = AdapterHttp.MAPPER.writeValueAsString(payload);
            var resp = http.postJson(url, body, Map.of());
            return mapTelegramResponse(resp);
        } catch (Exception e) {
            log.warn("telegram {} failed: {}", method, e.getMessage());
            return AdapterResponse.fail("telegram_error", e.getMessage(), true);
        }
    }

    /** Telegram returns 200 + {ok:true,result:{...}} or 4xx + {ok:false,description:...}. */
    private static AdapterResponse mapTelegramResponse(HttpResponse<String> resp) {
        try {
            var json = AdapterHttp.MAPPER.readTree(resp.body() == null ? "{}" : resp.body());
            if (resp.statusCode() == 200 && json.path("ok").asBoolean(false)) {
                return AdapterResponse.ok(AdapterHttp.MAPPER.convertValue(
                    json.path("result"), Map.class));
            }
            var desc = json.path("description").asText("HTTP " + resp.statusCode());
            var retryable = resp.statusCode() == 429 || resp.statusCode() >= 500;
            return AdapterResponse.fail("telegram_" + resp.statusCode(), desc, retryable);
        } catch (Exception e) {
            return AdapterResponse.fail("parse_error", e.getMessage(), false);
        }
    }
}
