package org.wyrdsekai.core.external.r;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppleMusicAdapterTest {

    private final AppleMusicAdapter adapter = new AppleMusicAdapter();

    @Test
    void namespace_is_apple_music() {
        assertEquals("apple_music", adapter.namespace());
    }

    @Test
    void capabilities_cover_search_play_queue_library() {
        var caps = adapter.capabilities();
        assertTrue(caps.contains("search"));
        assertTrue(caps.contains("play"));
        assertTrue(caps.contains("queue"));
        assertTrue(caps.contains("library"));
    }

    @Test
    void search_returns_not_yet_wired() {
        var resp = adapter.invoke(new AdapterRequest("apple_music", "search",
            Map.of("query", "x"), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void play_returns_not_yet_wired() {
        var resp = adapter.invoke(new AdapterRequest("apple_music", "play",
            Map.of("catalogId", "x"), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("not_yet_wired", resp.error().code());
    }

    @Test
    void unknown_method_rejected() {
        var resp = adapter.invoke(new AdapterRequest("apple_music", "destroy",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void credential_slot_is_apple_music_user_token() {
        assertEquals("apple.music_user_token", adapter.credentialSlot());
    }
}
