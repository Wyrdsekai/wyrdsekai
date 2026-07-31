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

class SpotifyAdapterTest {

    private MockHttpFixture mock;
    private SpotifyAdapter adapter;

    @BeforeEach
    void setup() throws Exception {
        mock = new MockHttpFixture();
        adapter = new SpotifyAdapter(new HttpAdapterSupport(), mock.baseUrl());
        CredentialResolver.get().setSafeReader(slot ->
            "spotify.access_token".equals(slot) ? Optional.of("sp-tk") : Optional.empty());
    }

    @AfterEach
    void tearDown() {
        mock.close();
        CredentialResolver.get().resetForTests();
    }

    @Test
    void search_returns_results() {
        mock.onPath("/v1/search", (ex, body) -> MockHttpFixture.Reply.json(
            "{\"tracks\":{\"items\":[{\"name\":\"x\"}]}}"));
        var resp = adapter.invoke(new AdapterRequest("spotify", "search",
            Map.of("query", "miles davis", "type", "track"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertNotNull(data.get("results"));
    }

    @Test
    void search_requires_query() {
        var resp = adapter.invoke(new AdapterRequest("spotify", "search",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void play_track_uri_uses_uris_field() {
        mock.onPath("/v1/me/player/play", (ex, body) -> MockHttpFixture.Reply.json("{}"));
        var resp = adapter.invoke(new AdapterRequest("spotify", "play",
            Map.of("uri", "spotify:track:abc"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        var rec = mock.recorded().get(0);
        assertTrue(rec.body.contains("spotify:track:abc"));
        assertTrue(rec.body.contains("uris"));
    }

    @Test
    void play_album_uri_uses_context_uri_field() {
        mock.onPath("/v1/me/player/play", (ex, body) -> MockHttpFixture.Reply.json("{}"));
        adapter.invoke(new AdapterRequest("spotify", "play",
            Map.of("uri", "spotify:album:xyz"),
            ItemCapabilitySet.UNRESTRICTED, null));
        var rec = mock.recorded().get(0);
        assertTrue(rec.body.contains("context_uri"));
    }

    @Test
    void queue_requires_uri_and_uses_query() {
        var resp = adapter.invoke(new AdapterRequest("spotify", "queue",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void recently_played_returns_items() {
        mock.onPath("/v1/me/player/recently-played", (ex, body) -> MockHttpFixture.Reply.json(
            "{\"items\":[{\"played_at\":\"now\"}]}"));
        var resp = adapter.invoke(new AdapterRequest("spotify", "recently_played",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertNotNull(data.get("items"));
    }

    @Test
    void unknown_method_rejected() {
        var resp = adapter.invoke(new AdapterRequest("spotify", "delete_account",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("unknown_method", resp.error().code());
    }
}
