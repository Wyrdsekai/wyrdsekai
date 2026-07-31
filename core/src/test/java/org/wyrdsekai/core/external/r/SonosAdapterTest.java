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

class SonosAdapterTest {

    private MockHttpFixture mock;
    private SonosAdapter adapter;

    @BeforeEach
    void setup() throws Exception {
        mock = new MockHttpFixture();
        adapter = new SonosAdapter(new HttpAdapterSupport(), mock.baseUrl());
        CredentialResolver.get().setSafeReader(slot ->
            "sonos.access_token".equals(slot) ? Optional.of("son-tk") : Optional.empty());
    }

    @AfterEach
    void tearDown() {
        mock.close();
        CredentialResolver.get().resetForTests();
    }

    @Test
    void play_posts_to_groups_endpoint() {
        mock.onPath("/control/api/v1/groups/g1/playback/play", (ex, body) ->
            MockHttpFixture.Reply.json("{}"));
        var resp = adapter.invoke(new AdapterRequest("sonos", "play",
            Map.of("group", "g1"), ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
    }

    @Test
    void pause_uses_pause_path() {
        mock.onPath("/control/api/v1/groups/g1/playback/pause", (ex, body) ->
            MockHttpFixture.Reply.json("{}"));
        var resp = adapter.invoke(new AdapterRequest("sonos", "pause",
            Map.of("group", "g1"), ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        assertEquals("/control/api/v1/groups/g1/playback/pause", mock.recorded().get(0).path);
    }

    @Test
    void skip_uses_skip_path() {
        mock.onPath("/control/api/v1/groups/g1/playback/skipToNextTrack", (ex, body) ->
            MockHttpFixture.Reply.json("{}"));
        var resp = adapter.invoke(new AdapterRequest("sonos", "skip",
            Map.of("group", "g1"), ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
    }

    @Test
    void queue_requires_uri() {
        var resp = adapter.invoke(new AdapterRequest("sonos", "queue",
            Map.of("group", "g1"), ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void missing_group_arg_fails() {
        var resp = adapter.invoke(new AdapterRequest("sonos", "play",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void bearer_auth_used() {
        mock.onPath("/control/api/v1/groups/g1/playback/play", (ex, body) ->
            MockHttpFixture.Reply.json("{}"));
        adapter.invoke(new AdapterRequest("sonos", "play",
            Map.of("group", "g1"), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("Bearer son-tk", mock.recorded().get(0).headers.get("authorization"));
    }
}
