package org.wyrdsekai.core.external.r;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.util.Map;
import java.util.Set;

/**
 * cloud Whisper transcription.
 *
 * <p>Distinct from local whisper.cpp; this adapter wraps a hosted OpenAI
 * Whisper or compatible transcription endpoint. The payload is
 * audio (base64 or url) — until the multipart upload pipeline is wired,
 * this returns a structured {@code not_yet_wired} response so item scripts
 * can branch cleanly.</p>
 *
 * <p>Methods:</p>
 * <ul>
 *   <li>{@code transcribe(audio, opts?)} — accepts {@code audio} as base64 or
 *       URL; returns {@code {text, segments}}.</li>
 * </ul>
 */
public final class WhisperAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "whisper";

    private final HttpAdapterSupport http;

    public WhisperAdapter() { this(new HttpAdapterSupport()); }

    public WhisperAdapter(HttpAdapterSupport http) { this.http = http; }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() { return Set.of("transcribe"); }

    @Override public String credentialSlot() { return "openai.api_key"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "transcribe" -> transcribe(request);
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }

    private AdapterResponse transcribe(AdapterRequest request) {
        var args = request.args();
        if (!args.containsKey("audio") && !args.containsKey("audioUrl")) {
            return http.missingArg("audio");
        }
        var key = http.resolveCredential(credentialSlot());
        if (key.isEmpty()) return http.missingCredential(credentialSlot());
        // Whisper API requires multipart/form-data with a real file part. The
        // upload pipeline that turns a base64 or URL audio sample into a
        // multipart body is shared with ElevenLabs voice_clone — both remain
        // deferred to Phase R+1. The structured stub keeps the surface stable
        // so items can already declare {@code whisper.transcribe} in manifests.
        return http.notYetWired(NAMESPACE,
            "transcribe requires multipart upload pipeline (deferred to Phase R+1)");
    }

    /** Test seam — exposes the audio shape the future implementation will consume. */
    public Map<String, Object> describe() {
        return Map.of(
            "namespace", NAMESPACE,
            "credential_slot", credentialSlot(),
            "deferred_reason", "multipart_upload"
        );
    }
}
