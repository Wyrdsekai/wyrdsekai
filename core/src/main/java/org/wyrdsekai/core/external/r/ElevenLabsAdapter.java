package org.wyrdsekai.core.external.r;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ElevenLabs TTS + voice cloning.
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code tts(text, voiceId, opts?)} — POST /v1/text-to-speech/&lt;voice&gt;,
 *       returns {@code {audioB64, format:"audio/mpeg"}}.</li>
 *   <li>{@code voice_clone(name, samples)} — high-impact action; install warning required.
 *       Samples are passed as data URIs (base64) — adapter forwards them as a
 *       multipart-style JSON envelope; for now returns a structured stub since
 *       multipart upload from a JS-supplied {@code samples} array needs the
 *       upload pipeline (Phase R+1).</li>
 * </ul>
 */
public final class ElevenLabsAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "elevenlabs";

    private static final String DEFAULT_BASE = "https://api.elevenlabs.io";

    private final HttpAdapterSupport http;
    private final String baseUrl;

    public ElevenLabsAdapter() { this(new HttpAdapterSupport(), DEFAULT_BASE); }

    public ElevenLabsAdapter(HttpAdapterSupport http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl == null ? DEFAULT_BASE : baseUrl;
    }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() { return Set.of("tts", "voice_clone"); }

    @Override public String credentialSlot() { return "elevenlabs.api_key"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "tts" -> tts(request);
            case "voice_clone" -> voiceClone(request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse tts(AdapterRequest request) {
        var args = request.args();
        var text = (String) args.get("text");
        if (text == null || text.isBlank()) return http.missingArg("text");
        var voiceId = (String) args.get("voiceId");
        if (voiceId == null || voiceId.isBlank()) return http.missingArg("voiceId");
        var key = http.resolveCredential(credentialSlot());
        if (key.isEmpty()) return http.missingCredential(credentialSlot());
        var body = new LinkedHashMap<String, Object>();
        body.put("text", text);
        body.put("model_id", args.getOrDefault("model_id", "eleven_multilingual_v2"));
        var opts = args.get("opts");
        if (opts instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                if (e.getKey() instanceof String k) body.put(k, e.getValue());
            }
        }
        var req = http.reqBuilder(URI.create(baseUrl + "/v1/text-to-speech/" + voiceId))
            .header("xi-api-key", key.get())
            .header("content-type", "application/json")
            .header("accept", "audio/mpeg")
            .POST(HttpRequest.BodyPublishers.ofString(http.jsonBody(body)))
            .build();
        // The body is binary; we re-execute with byte handler instead of the string helper.
        try {
            var resp = http.client().send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 400) {
                return AdapterResponse.fail("upstream_error",
                    "HTTP " + resp.statusCode(), resp.statusCode() >= 500);
            }
            var bytes = resp.body();
            if (bytes != null && bytes.length > HttpAdapterSupport.MAX_RESPONSE_BYTES) {
                return AdapterResponse.fail("response_too_large",
                    "audio response exceeded 10MB cap", false);
            }
            var b64 = bytes == null ? "" : Base64.getEncoder().encodeToString(bytes);
            return AdapterResponse.ok(Map.of(
                "audioB64", b64,
                "format", "audio/mpeg"
            ));
        } catch (Exception e) {
            return AdapterResponse.fail("network_error", e.getMessage(), true);
        }
    }

    private AdapterResponse voiceClone(AdapterRequest request) {
        var args = request.args();
        var name = (String) args.get("name");
        if (name == null || name.isBlank()) return http.missingArg("name");
        var samples = args.get("samples");
        if (!(samples instanceof List<?> list) || list.isEmpty()) return http.missingArg("samples");
        // Voice cloning requires multipart upload of audio files. The samples
        // arrive as Object lists from the JS side; the multipart pipeline that
        // takes binary uploads from item scripts is not yet wired.
        return http.notYetWired(NAMESPACE,
            "voice_clone requires multipart upload pipeline (deferred to Phase R+1)");
    }
}
