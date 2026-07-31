package org.wyrdsekai.core.external.o;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * WhatsApp outbound. Wraps the bundled
 * {@code whatsmeow} Go sidecar that already powers
 * {@code WhatsAppChannel}.
 *
 * <p>Methods: {@code send_message, send_media}.</p>
 *
 * <p>Credentials slot: {@code whatsapp.session} packed as
 * {@code <sidecar_url>} (the URL of the local whatsmeow sidecar HTTP API).
 * Auth is established at sidecar pairing time, not per-call.</p>
 */
public final class WhatsAppAdapter implements ExternalAdapter {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppAdapter.class);

    private final Function<String, Optional<String>> credentials;
    private final SlackAdapter.HttpInvoker http;

    public WhatsAppAdapter() {
        this(slot -> CredentialResolver.get().resolve(slot),
            new SlackAdapter.RealHttpInvoker());
    }

    WhatsAppAdapter(Function<String, Optional<String>> credentials,
                    SlackAdapter.HttpInvoker http) {
        this.credentials = credentials;
        this.http = http;
    }

    @Override public String namespace() { return "whatsapp"; }

    @Override public Set<String> capabilities() {
        return Set.of("send_message", "send", "send_media");
    }

    @Override public String credentialSlot() { return "whatsapp.session"; }

    @Override
    public AdapterResponse invoke(AdapterRequest req) {
        var url = credentials.apply("whatsapp.session");
        if (url.isEmpty()) {
            return AdapterResponse.fail("credentials_missing",
                "whatsapp.session not in Safe (expected sidecar URL)", false);
        }
        var sidecarUrl = url.get();
        if (sidecarUrl.endsWith("/")) sidecarUrl = sidecarUrl.substring(0, sidecarUrl.length() - 1);
        var args = req.args();
        return switch (req.method()) {
            case "send_message", "send" -> sendMessage(args, sidecarUrl);
            case "send_media" -> sendMedia(args, sidecarUrl);
            default -> AdapterResponse.fail("unknown_method", req.method(), false);
        };
    }

    private AdapterResponse sendMessage(Map<String, Object> args, String sidecarUrl) {
        var jid = AdapterHttp.str(args, "jid");
        var text = AdapterHttp.str(args, "text");
        if (text == null) text = AdapterHttp.str(args, "message");
        if (jid == null) {
            return AdapterResponse.fail("invalid_argument", "'jid' is required", false);
        }
        if (text == null) text = "";
        var payload = new LinkedHashMap<String, Object>();
        payload.put("recipient", jid);
        payload.put("body", text);
        try {
            var body = AdapterHttp.MAPPER.writeValueAsString(payload);
            var resp = http.postJson(sidecarUrl + "/send", body, Map.of());
            return AdapterHttp.fromHttp(resp);
        } catch (Exception e) {
            log.warn("whatsapp send failed: {}", e.getMessage());
            return AdapterResponse.fail("whatsapp_error", e.getMessage(), true);
        }
    }

    private AdapterResponse sendMedia(Map<String, Object> args, String sidecarUrl) {
        var jid = AdapterHttp.str(args, "jid");
        var mediaPath = AdapterHttp.str(args, "mediaPath");
        if (jid == null || mediaPath == null) {
            return AdapterResponse.fail("invalid_argument",
                "'jid' and 'mediaPath' are required", false);
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("recipient", jid);
        payload.put("mediaPath", mediaPath);
        var opts = AdapterHttp.asMap(args.get("opts"));
        if (opts.containsKey("caption")) payload.put("caption", opts.get("caption"));
        try {
            var body = AdapterHttp.MAPPER.writeValueAsString(payload);
            var resp = http.postJson(sidecarUrl + "/send_media", body, Map.of());
            return AdapterHttp.fromHttp(resp);
        } catch (Exception e) {
            return AdapterResponse.fail("whatsapp_error", e.getMessage(), true);
        }
    }
}
