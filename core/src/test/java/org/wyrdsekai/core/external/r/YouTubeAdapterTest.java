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

class YouTubeAdapterTest {

    private MockHttpFixture mock;
    private YouTubeAdapter adapter;

    @BeforeEach
    void setup() throws Exception {
        mock = new MockHttpFixture();
        adapter = new YouTubeAdapter(new HttpAdapterSupport(), mock.baseUrl(), mock.baseUrl());
        CredentialResolver.get().setSafeReader(slot ->
            "youtube.api_key".equals(slot) ? Optional.of("yt-key") : Optional.empty());
    }

    @AfterEach
    void tearDown() {
        mock.close();
        CredentialResolver.get().resetForTests();
    }

    @Test
    void search_returns_items() {
        mock.onPath("/search", (ex, body) -> MockHttpFixture.Reply.json(
            "{\"items\":[{\"id\":{\"videoId\":\"v1\"}}]}"));
        var resp = adapter.invoke(new AdapterRequest("youtube", "search",
            Map.of("query", "rust async"), ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertNotNull(data.get("items"));
    }

    @Test
    void search_requires_query() {
        var resp = adapter.invoke(new AdapterRequest("youtube", "search",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void channel_videos_requires_channel_id() {
        var resp = adapter.invoke(new AdapterRequest("youtube", "channel_videos",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void channel_videos_returns_items() {
        mock.onPath("/search", (ex, body) -> MockHttpFixture.Reply.json(
            "{\"items\":[{\"id\":{\"videoId\":\"v1\"}}]}"));
        var resp = adapter.invoke(new AdapterRequest("youtube", "channel_videos",
            Map.of("channelId", "UCabc"), ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
    }

    @Test
    void transcript_handles_empty_body_as_unavailable() {
        mock.onPath("/", (ex, body) -> new MockHttpFixture.Reply(200, "text/xml", ""));
        var resp = adapter.invoke(new AdapterRequest("youtube", "transcript",
            Map.of("videoId", "v1"), ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals(false, data.get("available"));
    }

    @Test
    void transcript_requires_video_id() {
        var resp = adapter.invoke(new AdapterRequest("youtube", "transcript",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("missing_arg", resp.error().code());
    }

    @Test
    void unknown_method_rejected() {
        var resp = adapter.invoke(new AdapterRequest("youtube", "upload",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertEquals("unknown_method", resp.error().code());
    }
}
