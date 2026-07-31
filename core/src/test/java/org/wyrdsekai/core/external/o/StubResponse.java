package org.wyrdsekai.core.external.o;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;

/**
 * Test-only minimal {@link HttpResponse} stub for adapter unit tests.
 * Only the surface used by the adapters ({@link #statusCode}, {@link #body})
 * is implemented; everything else returns a sensible default.
 */
final class StubResponse implements HttpResponse<String> {

    private final int status;
    private final String body;

    StubResponse(int status, String body) {
        this.status = status;
        this.body = body;
    }

    @Override public int statusCode() { return status; }
    @Override public HttpRequest request() { return null; }
    @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
    @Override public HttpHeaders headers() {
        return HttpHeaders.of(Map.of(), (a, b) -> true);
    }
    @Override public String body() { return body; }
    @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
    @Override public URI uri() { return URI.create("https://stub/"); }
    @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
}
