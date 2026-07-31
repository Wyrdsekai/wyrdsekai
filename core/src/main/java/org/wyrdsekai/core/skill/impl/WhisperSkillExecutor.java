package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Speech-to-text via whisper.cpp HTTP API.
 * Posts audio data to a local whisper.cpp server for transcription.
 */
public class WhisperSkillExecutor extends HttpSkillExecutor {

    private static final String DEFAULT_BASE_URL = "http://localhost:8080";

    public WhisperSkillExecutor() {
        this(DEFAULT_BASE_URL);
    }

    public WhisperSkillExecutor(String baseUrl) {
        super(baseUrl);

        define(new SkillDefinition("scriptorium.whisper.transcribe",
            "Transcribe Audio", "Transcribe audio to text via whisper.cpp",
            "scriptorium", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(
                SkillParam.optional("audio_url", "string", "URL of audio file"),
                SkillParam.optional("audio_path", "string", "Local path to audio file")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return notConfigured(skillId, "whisper_url");
        }

        long start = System.currentTimeMillis();

        return switch (skillId) {
            case "scriptorium.whisper.transcribe" -> executeTranscribe(params, start, skillId, context);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeTranscribe(Map<String, Object> params, long start,
                                           String skillId, SkillContext context) {
        String audioUrl = param(params, "audio_url", null);
        String audioPath = param(params, "audio_path", null);

        if (audioUrl == null && audioPath == null) {
            return SkillResult.error(I18n.get("skill.param_required", "audio_url or audio_path"),
                0, SkillTier.NATIVE, skillId);
        }

        try {
            String requestBody;
            if (audioPath != null) {
                byte[] audioBytes = Files.readAllBytes(Path.of(audioPath));
                String base64 = Base64.getEncoder().encodeToString(audioBytes);
                requestBody = "{\"audio\":\"" + base64 + "\",\"response_format\":\"json\"}";
            } else {
                requestBody = "{\"url\":\"" + escapeJson(audioUrl) + "\",\"response_format\":\"json\"}";
            }

            String url = baseUrl + "/inference";
            var result = httpPost(url, requestBody, Map.of(), context.timeoutMs());
            long elapsed = System.currentTimeMillis() - start;

            if (!result.ok()) return httpError(skillId, result, elapsed);

            String text = jsonString(result.body(), "text");
            if (text == null) text = result.body();

            String output = I18n.get("skill.whisper.transcribed", truncate(text, 200));
            return SkillResult.ok(output, Map.of("text", text, "raw", result.body()),
                elapsed, SkillTier.NATIVE, skillId);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(I18n.get("skill.error.execution", e.getMessage()),
                elapsed, SkillTier.NATIVE, skillId);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
