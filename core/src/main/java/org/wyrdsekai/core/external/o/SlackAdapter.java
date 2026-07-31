package org.wyrdsekai.core.external.o;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Slack outbound. Web API client.
 *
 * <p>Methods: {@code post_message, dm, list_channels, react}. The base URL is
 * pinned to {@code slack.com/api/} — Slack does not allow a configurable host
 * for the bot-token web API.</p>
 *
 * <p>Credentials slot: {@code slack.bot_token} (xoxb-…).</p>
 */
public final class SlackAdapter implements ExternalAdapter {

    private static final Logger log = LoggerFactory.getLogger(SlackAdapter.class);

    static final String DEFAULT_BASE_URL = "https://slack.com/api/";

    private final Function<String, Optional<String>> credentials;
    private final HttpInvoker http;
    private final String baseUrl;

    public SlackAdapter() {
        this(slot -> CredentialResolver.get().resolve(slot),
            new RealHttpInvoker(), DEFAULT_BASE_URL);
    }

    SlackAdapter(Function<String, Optional<String>> credentials,
                 HttpInvoker http, String baseUrl) {
        this.credentials = credentials;
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    @Override public String namespace() { return "slack"; }

    @Override public Set<String> capabilities() {
        return Set.of("post_message", "dm", "list_channels", "react", "search", "upload_file");
    }

    @Override public String credentialSlot() { return "slack.bot_token"; }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var token = credentials.apply("slack.bot_token");
        if (token.isEmpty()) {
            return AdapterResponse.fail("credentials_missing",
                "slack.bot_token not in Safe", false);
        }
        var args = req.args();
        return switch (req.method()) {
            case "post_message" -> postMessage(args, token.get());
            case "dm" -> dm(args, token.get());
            case "list_channels" -> listChannels(args, token.get());
            case "react" -> react(args, token.get());
            case "search" -> search(args, token.get());
            case "upload_file" -> uploadFile(args, token.get());
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse postMessage(Map<String, Object> args, String token) {
        var channel = AdapterHttp.str(args, "channel");
        var text = AdapterHttp.str(args, "text");
        if (channel == null || channel.isBlank()) {
            return AdapterResponse.fail("invalid_argument", "'channel' is required", false);
        }
        if (text == null) text = "";
        var payload = new LinkedHashMap<String, Object>();
        payload.put("channel", channel);
        payload.put("text", text);
        var opts = AdapterHttp.asMap(args.get("opts"));
        if (opts.containsKey("threadTs")) payload.put("thread_ts", opts.get("threadTs"));
        if (opts.containsKey("blocks")) payload.put("blocks", opts.get("blocks"));
        return invokeSlack("chat.postMessage", payload, token);
    }

    private AdapterResponse dm(Map<String, Object> args, String token) {
        var userId = AdapterHttp.str(args, "userId");
        var text = AdapterHttp.str(args, "text");
        if (userId == null) {
            return AdapterResponse.fail("invalid_argument", "'userId' is required", false);
        }
        // Slack: chat.postMessage with channel=<userId> opens a DM channel automatically.
        var payload = new LinkedHashMap<String, Object>();
        payload.put("channel", userId);
        payload.put("text", text == null ? "" : text);
        return invokeSlack("chat.postMessage", payload, token);
    }

    private AdapterResponse listChannels(Map<String, Object> args, String token) {
        var opts = AdapterHttp.asMap(args.get("opts"));
        var sb = new StringBuilder("conversations.list");
        if (opts.containsKey("limit")) sb.append("?limit=").append(opts.get("limit"));
        return invokeSlackGet(sb.toString(), token);
    }

    private AdapterResponse react(Map<String, Object> args, String token) {
        var channel = AdapterHttp.str(args, "channel");
        var ts = AdapterHttp.str(args, "ts");
        var emoji = AdapterHttp.str(args, "emoji");
        if (channel == null || ts == null || emoji == null) {
            return AdapterResponse.fail("invalid_argument",
                "'channel', 'ts', 'emoji' are required", false);
        }
        var payload = Map.<String, Object>of(
            "channel", channel, "timestamp", ts, "name", emoji);
        return invokeSlack("reactions.add", payload, token);
    }

    private AdapterResponse search(Map<String, Object> args, String token) {
        var q = AdapterHttp.str(args, "query");
        if (q == null) {
            return AdapterResponse.fail("invalid_argument", "'query' is required", false);
        }
        return invokeSlackGet(
            "search.messages?query=" + URLEncoder.encode(q,
                StandardCharsets.UTF_8),
            token);
    }

    private AdapterResponse uploadFile(Map<String, Object> args, String token) {
        // Minimal stub — full upload requires multipart/form-data; surface a
        // structured error so items can detect it.
        return AdapterResponse.fail("not_implemented",
            "slack.upload_file requires multipart support — coming next phase", false);
    }

    private AdapterResponse invokeSlack(String method, Map<String, Object> payload,
                                          String token) {
        try {
            var body = AdapterHttp.MAPPER.writeValueAsString(payload);
            var resp = http.postJson(baseUrl + method, body, Map.of(
                "Authorization", "Bearer " + token));
            return mapSlackResponse(resp);
        } catch (Exception e) {
            log.warn("slack {} failed: {}", method, e.getMessage());
            return AdapterResponse.fail("slack_error", e.getMessage(), true);
        }
    }

    private AdapterResponse invokeSlackGet(String pathAndQuery, String token) {
        try {
            var resp = http.get(baseUrl + pathAndQuery, Map.of(
                "Authorization", "Bearer " + token));
            return mapSlackResponse(resp);
        } catch (Exception e) {
            return AdapterResponse.fail("slack_error", e.getMessage(), true);
        }
    }

    /** Slack returns 200 + {ok:false, error:...} on logical failure; map both. */
    private static AdapterResponse mapSlackResponse(HttpResponse<String> resp) {
        if (resp.statusCode() != 200) {
            return AdapterHttp.fromHttp(resp);
        }
        try {
            var json = AdapterHttp.MAPPER.readTree(resp.body());
            if (!json.path("ok").asBoolean(false)) {
                var err = json.path("error").asText("unknown");
                var retryable = "rate_limited".equals(err) || "internal_error".equals(err);
                return AdapterResponse.fail("slack_" + err, err, retryable);
            }
            return AdapterResponse.ok(AdapterHttp.MAPPER.convertValue(json, Map.class));
        } catch (Exception e) {
            return AdapterResponse.fail("parse_error", e.getMessage(), false);
        }
    }

    /** Test seam — tests inject a fake. */
    interface HttpInvoker {
        HttpResponse<String> postJson(String url, String body,
                                                      Map<String, String> headers) throws Exception;
        HttpResponse<String> get(String url,
                                                 Map<String, String> headers) throws Exception;
    }

    static final class RealHttpInvoker implements HttpInvoker {
        @Override
        public HttpResponse<String> postJson(String url, String body,
                                                            Map<String, String> headers)
                throws Exception {
            return AdapterHttp.postJson(url, body, headers);
        }
        @Override
        public HttpResponse<String> get(String url,
                                                       Map<String, String> headers)
                throws Exception {
            return AdapterHttp.get(url, headers);
        }
    }
}
