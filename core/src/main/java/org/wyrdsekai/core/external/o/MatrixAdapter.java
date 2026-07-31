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
import java.util.UUID;
import java.util.function.Function;

/**
 * Matrix outbound via the homeserver
 * Client-Server API. Mirrors the patterns already in
 * {@code MatrixChannel} but is invoked per-call (no persistent session)
 * because items are one-shot users of the surface.
 *
 * <p>Methods: {@code send, invite, join}.</p>
 *
 * <p>Credentials slot: {@code matrix.access_token} packed as
 * {@code <homeserver_url>|<access_token>} so a single Safe entry carries
 * both fields.</p>
 */
public final class MatrixAdapter implements ExternalAdapter {

    private static final Logger log = LoggerFactory.getLogger(MatrixAdapter.class);

    private final Function<String, Optional<String>> credentials;
    private final SlackAdapter.HttpInvoker http;

    public MatrixAdapter() {
        this(slot -> CredentialResolver.get().resolve(slot),
            new SlackAdapter.RealHttpInvoker());
    }

    MatrixAdapter(Function<String, Optional<String>> credentials,
                  SlackAdapter.HttpInvoker http) {
        this.credentials = credentials;
        this.http = http;
    }

    @Override public String namespace() { return "matrix"; }

    @Override public Set<String> capabilities() {
        return Set.of("send", "invite", "join");
    }

    @Override public String credentialSlot() { return "matrix.access_token"; }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var raw = credentials.apply("matrix.access_token");
        if (raw.isEmpty()) {
            return AdapterResponse.fail("credentials_missing",
                "matrix.access_token not in Safe (expected '<homeserver>|<token>')",
                false);
        }
        var creds = parseCreds(raw.get());
        if (creds == null) {
            return AdapterResponse.fail("credentials_invalid",
                "matrix.access_token must be 'homeserver|token'", false);
        }
        var args = req.args();
        return switch (req.method()) {
            case "send" -> send(args, creds);
            case "invite" -> invite(args, creds);
            case "join" -> join(args, creds);
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse send(Map<String, Object> args, MatrixCreds creds) {
        var roomId = AdapterHttp.str(args, "roomId");
        var text = AdapterHttp.str(args, "text");
        if (roomId == null) {
            return AdapterResponse.fail("invalid_argument", "'roomId' is required", false);
        }
        if (text == null) text = "";
        var opts = AdapterHttp.asMap(args.get("opts"));
        var payload = new LinkedHashMap<String, Object>();
        payload.put("msgtype", "m.text");
        payload.put("body", text);
        if (opts.containsKey("htmlBody")) {
            payload.put("format", "org.matrix.custom.html");
            payload.put("formatted_body", opts.get("htmlBody"));
        }
        var txnId = "wyrd-" + UUID.randomUUID();
        var url = creds.homeserverUrl() + "/_matrix/client/v3/rooms/"
            + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
            + "/send/m.room.message/" + txnId;
        try {
            var body = AdapterHttp.MAPPER.writeValueAsString(payload);
            var b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + creds.accessToken())
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(AdapterHttp.DEFAULT_TIMEOUT)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
            var resp = AdapterHttp.client().send(b,
                HttpResponse.BodyHandlers.ofString());
            return AdapterHttp.fromHttp(resp);
        } catch (Exception e) {
            log.warn("matrix send failed: {}", e.getMessage());
            return AdapterResponse.fail("matrix_error", e.getMessage(), true);
        }
    }

    private AdapterResponse invite(Map<String, Object> args, MatrixCreds creds) {
        var roomId = AdapterHttp.str(args, "roomId");
        var userId = AdapterHttp.str(args, "userId");
        if (roomId == null || userId == null) {
            return AdapterResponse.fail("invalid_argument",
                "'roomId' and 'userId' are required", false);
        }
        try {
            var body = AdapterHttp.MAPPER.writeValueAsString(Map.of("user_id", userId));
            var url = creds.homeserverUrl() + "/_matrix/client/v3/rooms/"
                + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
                + "/invite";
            var resp = http.postJson(url, body, Map.of(
                "Authorization", "Bearer " + creds.accessToken()));
            return AdapterHttp.fromHttp(resp);
        } catch (Exception e) {
            return AdapterResponse.fail("matrix_error", e.getMessage(), true);
        }
    }

    private AdapterResponse join(Map<String, Object> args, MatrixCreds creds) {
        var roomIdOrAlias = AdapterHttp.str(args, "roomIdOrAlias");
        if (roomIdOrAlias == null) {
            return AdapterResponse.fail("invalid_argument",
                "'roomIdOrAlias' is required", false);
        }
        try {
            var url = creds.homeserverUrl() + "/_matrix/client/v3/join/"
                + URLEncoder.encode(roomIdOrAlias,
                    StandardCharsets.UTF_8);
            var resp = http.postJson(url, "{}", Map.of(
                "Authorization", "Bearer " + creds.accessToken()));
            return AdapterHttp.fromHttp(resp);
        } catch (Exception e) {
            return AdapterResponse.fail("matrix_error", e.getMessage(), true);
        }
    }

    private static MatrixCreds parseCreds(String raw) {
        var idx = raw.indexOf('|');
        if (idx <= 0 || idx == raw.length() - 1) return null;
        var hs = raw.substring(0, idx);
        var token = raw.substring(idx + 1);
        if (hs.endsWith("/")) hs = hs.substring(0, hs.length() - 1);
        return new MatrixCreds(hs, token);
    }

    record MatrixCreds(String homeserverUrl, String accessToken) {}
}
