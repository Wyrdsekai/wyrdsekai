package org.wyrdsekai.core.external.p;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NpmAdapterTest {

    private MockServerSupport server;
    private NpmAdapter adapter;

    @BeforeEach
    void setup() {
        server = MockServerSupport.start();
        adapter = new NpmAdapter();
        adapter.setBaseUrlOverride(server.baseUrl());
        adapter.setApiBaseOverride(server.baseUrl());
    }

    @AfterEach
    void tearDown() {
        server.close();
        MockServerSupport.clearCredentials();
    }

    @Test
    void search_returns_normalised_packages() {
        server.onJson("/-/v1/search", 200,
            "{\"objects\":[{\"package\":{\"name\":\"react\",\"version\":\"19\",\"description\":\"d\"}}]}");
        var resp = adapter.invoke(new AdapterRequest("npm", "search",
            Map.of("query", "react"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (List<Map<String, Object>>) resp.data();
        assertThat(data).hasSize(1);
        assertThat(data.get(0)).containsEntry("name", "react");
    }

    @Test
    void info_returns_latest_and_metadata() {
        server.onJson("/lodash", 200,
            "{\"name\":\"lodash\",\"description\":\"util\",\"dist-tags\":{\"latest\":\"4.17.21\"},\"homepage\":\"h\",\"license\":\"MIT\"}");
        var resp = adapter.invoke(new AdapterRequest("npm", "info",
            Map.of("packageName", "lodash"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertThat(data).containsEntry("latest", "4.17.21")
            .containsEntry("license", "MIT");
    }

    @Test
    void info_without_package_fails() {
        var resp = adapter.invoke(new AdapterRequest("npm", "info",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
    }

    @Test
    void downloads_default_period_is_last_week() {
        server.onJson("/downloads/point/last-week/express", 200,
            "{\"package\":\"express\",\"downloads\":12345,\"start\":\"a\",\"end\":\"b\"}");
        var resp = adapter.invoke(new AdapterRequest("npm", "downloads",
            Map.of("packageName", "express"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertThat(data).containsEntry("downloads", 12345L);
    }

    @Test
    void downloads_custom_period_is_used() {
        server.onJson("/downloads/point/last-month/lodash", 200,
            "{\"downloads\":99}");
        adapter.invoke(new AdapterRequest("npm", "downloads",
            Map.of("packageName", "lodash", "period", "last-month"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(server.lastRequest().path()).contains("last-month");
    }

    @Test
    void public_reads_succeed_without_credential() {
        server.onJson("/-/v1/search", 200, "{\"objects\":[]}");
        var resp = adapter.invoke(new AdapterRequest("npm", "search",
            Map.of("query", "x"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
    }
}
