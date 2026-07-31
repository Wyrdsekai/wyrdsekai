package org.wyrdsekai.core.external.r;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ElevenLabsAdapterTest {

    private MockHttpFixture mock;
    private ElevenLabsAdapter adapter;

    @BeforeEach
    void setup() throws Exception {
        mock = new MockHttpFixture();
        adapter = new ElevenLabsAdapter(new HttpAdapterSupport(), mock.baseUrl());
        CredentialResolver.get().setSafeReader(slot ->
            "elevenlabs.api_key".equals(slot) ? Optional.of("el-tk") : Optional.empty());
    }

    @AfterEach
    void tearDown() {
        mock.close();
        CredentialResolver.get().resetForTests();
    }

    @Test
    void tts_returns_base64_audio() {
        mock.onPath("/v1/text-to-speech/", (ex, body) ->
            new MockHttpFixture.Reply(200, "audio/mpeg", "fake-mp3-bytes"));
        var resp = adapter.invoke(new AdapterRequest("elevenlabs", "tts",
            Map.of("text", "hi", "voiceId", "v1"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals("audio/mpeg", data.get("format"));
        assertNotNull(data.get("audioB64"));
    }

    @Test
    void tts_uses_xi_api_key_header() {
        mock.onPath("/v1/text-to-speech/", (ex, body) ->
            new MockHttpFixture.Reply(200, "audio/mpeg", "x"));
        adapter.invoke(new AdapterRequest("elevenlabs", "tts",
            Map.of("text", "hi", "voiceId", "v1"),
            ItemCapabilitySet.UNRESTRICTED, null));
        var rec = mock.recorded().get(0);
        assertEquals("el-tk", rec.headers.get("xi-api-key"));
    }

    @Test
    void tts_requires_text_and_voice() {
        var r1 = adapter.invoke(new AdapterRequest("elevenlabs", "tts",
            Map.of("voiceId", "v"), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("missing_arg", r1.error().code());
        var r2 = adapter.invoke(new AdapterRequest("elevenlabs", "tts",
            Map.of("text", "x"), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("missing_arg", r2.error().code());
    }

    @Test
    void voice_clone_is_not_yet_wired() {
        var resp = adapter.invoke(new AdapterRequest("elevenlabs", "voice_clone",
            Map.of("name", "me", "samples", List.of("data:audio/wav;base64,abc")),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void unknown_method_rejected() {
        var resp = adapter.invoke(new AdapterRequest("elevenlabs", "delete_voice",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void tts_credential_missing_when_slot_empty() {
        CredentialResolver.get().setSafeReader(s -> Optional.empty());
        var resp = adapter.invoke(new AdapterRequest("elevenlabs", "tts",
            Map.of("text", "x", "voiceId", "v"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("credential_missing", resp.error().code());
    }
}
