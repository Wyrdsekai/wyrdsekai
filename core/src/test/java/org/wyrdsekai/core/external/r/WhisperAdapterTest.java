package org.wyrdsekai.core.external.r;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WhisperAdapterTest {

    private WhisperAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new WhisperAdapter();
        CredentialResolver.get().setSafeReader(slot ->
            "openai.api_key".equals(slot) ? Optional.of("k") : Optional.empty());
    }

    @AfterEach
    void tearDown() {
        CredentialResolver.get().resetForTests();
    }

    @Test
    void namespace_is_whisper() {
        assertEquals("whisper", adapter.namespace());
    }

    @Test
    void transcribe_requires_audio_arg() {
        var resp = adapter.invoke(new AdapterRequest("whisper", "transcribe",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void transcribe_with_audio_returns_not_yet_wired() {
        var resp = adapter.invoke(new AdapterRequest("whisper", "transcribe",
            Map.of("audio", "data:audio/wav;base64,abc"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void transcribe_credential_missing_when_slot_empty() {
        CredentialResolver.get().setSafeReader(s -> Optional.empty());
        var resp = adapter.invoke(new AdapterRequest("whisper", "transcribe",
            Map.of("audioUrl", "https://x/y.wav"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("credential_missing", resp.error().code());
    }

    @Test
    void unknown_method_rejected() {
        var resp = adapter.invoke(new AdapterRequest("whisper", "diarize",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void describe_exposes_deferred_reason() {
        var d = adapter.describe();
        assertEquals("whisper", d.get("namespace"));
        assertEquals("multipart_upload", d.get("deferred_reason"));
    }
}
