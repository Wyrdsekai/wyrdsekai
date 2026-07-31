package org.wyrdsekai.core.external.p;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PyPIAdapterTest {

    private MockServerSupport server;
    private PyPIAdapter adapter;

    @BeforeEach
    void setup() {
        server = MockServerSupport.start();
        adapter = new PyPIAdapter();
        adapter.setBaseUrlOverride(server.baseUrl());
        adapter.setSearchBaseOverride(server.baseUrl());
    }

    @AfterEach
    void tearDown() {
        server.close();
        MockServerSupport.clearCredentials();
    }

    @Test
    void info_returns_top_level_metadata() {
        server.onJson("/pypi/requests/json", 200,
            "{\"info\":{\"name\":\"requests\",\"version\":\"2.32.0\",\"summary\":\"http\",\"license\":\"Apache-2.0\",\"home_page\":\"h\",\"author\":\"k\"}}");
        var resp = adapter.invoke(new AdapterRequest("pypi", "info",
            Map.of("packageName", "requests"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertThat(data).containsEntry("name", "requests")
            .containsEntry("version", "2.32.0")
            .containsEntry("license", "Apache-2.0");
    }

    @Test
    void info_without_package_fails() {
        var resp = adapter.invoke(new AdapterRequest("pypi", "info",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("missing_arg");
    }

    @Test
    void search_returns_results_list_when_backend_serves_json() {
        server.onJson("/search/", 200,
            "{\"results\":[{\"name\":\"requests\",\"version\":\"2.32.0\"}]}");
        var resp = adapter.invoke(new AdapterRequest("pypi", "search",
            Map.of("query", "requests"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (List<Map<String, Object>>) resp.data();
        assertThat(data).hasSize(1);
        assertThat(data.get(0)).containsEntry("name", "requests");
    }

    @Test
    void search_without_query_fails() {
        var resp = adapter.invoke(new AdapterRequest("pypi", "search",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
    }

    @Test
    void unknown_method_fails() {
        var resp = adapter.invoke(new AdapterRequest("pypi", "publish",
            Map.of(), ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isFalse();
        assertThat(resp.error().code()).isEqualTo("unknown_method");
    }

    @Test
    void public_reads_do_not_require_credential() {
        server.onJson("/pypi/numpy/json", 200,
            "{\"info\":{\"name\":\"numpy\",\"version\":\"2.0\"}}");
        var resp = adapter.invoke(new AdapterRequest("pypi", "info",
            Map.of("packageName", "numpy"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertThat(resp.success()).isTrue();
    }
}
