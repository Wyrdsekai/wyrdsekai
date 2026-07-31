package org.wyrdsekai.scripting.api;

import org.graalvm.polyglot.HostAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Java-backed HTTP client exposed to GraalJS scripts.
 * Available at {@link org.wyrdsekai.scripting.sandbox.SandboxLevel#SKILL_BASIC} and above.
 *
 * <p>Scripts use this as:
 * <pre>
 *   var body = http.get("https://api.example.com/data");
 *   var result = http.post("https://api.example.com/submit", JSON.stringify({x: 1}));
 *   var resp = http.fetch("https://api.example.com/data", {method: "PUT", body: "...", headers: {"X-Key": "abc"}});
 * </pre>
 *
 * <p>#3 (2026-07-19 OSS hardening) — SSRF guard. The raw {@code http} global is
 * bound outside the capability system, so before this an item script could reach
 * the cloud metadata endpoint (169.254.169.254), loopback admin ports, and
 * RFC1918 hosts, and could be bounced there via a redirect from a public URL.
 * Now every request (and every redirect hop) resolves the target host and rejects
 * non-public addresses. Two policies:
 * <ul>
 *   <li><b>Untrusted</b> (agent-crafted / visitor scripts): blocks loopback,
 *       link-local (incl. metadata), site-local (RFC1918), unique-local IPv6,
 *       CGNAT, any-local and multicast.</li>
 *   <li><b>Trusted</b> (bundled / disk-installed items): may reach LAN/loopback
 *       services (local model server, household IoT) but is STILL blocked from the
 *       never-legitimate ranges — link-local/metadata, any-local, multicast.</li>
 * </ul>
 * Redirects are followed manually (max {@value #MAX_REDIRECTS}) and re-validated
 * per hop; auto-follow is disabled so a public→internal 3xx cannot slip past.</p>
 */
public class ScriptHttpClient {

    private static final Logger log = LoggerFactory.getLogger(ScriptHttpClient.class);

    /** Default request timeout. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    /** Maximum response body size (1 MB). */
    private static final int MAX_RESPONSE_SIZE = 1_048_576;

    /** Maximum redirect hops followed (each re-validated). */
    private static final int MAX_REDIRECTS = 5;

    private final HttpClient client;

    /**
     * When true, private/loopback ranges are blocked in addition to the always-
     * blocked never-legitimate ranges. Untrusted scripts get {@code true};
     * trusted bundled items get {@code false} so they can reach LAN services.
     */
    private final boolean blockPrivateNetworks;

    public ScriptHttpClient() {
        this(defaultClient(), true);
    }

    /** Trust-scoped constructor used by the sandbox executor. */
    public ScriptHttpClient(boolean blockPrivateNetworks) {
        this(defaultClient(), blockPrivateNetworks);
    }

    // Visible for testing — blocking policy by default (matches production).
    ScriptHttpClient(HttpClient client) {
        this(client, true);
    }

    ScriptHttpClient(HttpClient client, boolean blockPrivateNetworks) {
        this.client = client;
        this.blockPrivateNetworks = blockPrivateNetworks;
    }

    private static HttpClient defaultClient() {
        return HttpClient.newBuilder()
            .connectTimeout(DEFAULT_TIMEOUT)
            // Auto-follow disabled: we follow manually so each hop is re-validated
            // against the SSRF policy (a public URL must not 3xx into 169.254.x).
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    /**
     * Perform an HTTP GET request. Returns the response body as a string.
     *
     * @param url The URL to fetch
     * @return Response body
     * @throws RuntimeException on network errors or timeouts
     */
    @HostAccess.Export
    public String get(String url) {
        validateUrl(url);
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(DEFAULT_TIMEOUT)
                .GET()
                .build();
            return truncateBody(sendFollowing(request).body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP GET interrupted: " + url, e);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("HTTP GET failed: " + url + " — " + e.getMessage(), e);
        }
    }

    /**
     * Perform an HTTP POST request with a string body. Returns the response body.
     *
     * @param url  The URL to post to
     * @param body The request body (typically JSON)
     * @return Response body
     * @throws RuntimeException on network errors or timeouts
     */
    @HostAccess.Export
    public String post(String url, String body) {
        validateUrl(url);
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(DEFAULT_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body != null ? body : ""))
                .build();
            return truncateBody(sendFollowing(request).body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP POST interrupted: " + url, e);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("HTTP POST failed: " + url + " — " + e.getMessage(), e);
        }
    }

    /**
     * General-purpose fetch, supporting arbitrary method, headers, and body.
     *
     * <p>Options map keys:
     * <ul>
     *   <li>{@code method} — HTTP method (GET, POST, PUT, DELETE, PATCH). Default: GET</li>
     *   <li>{@code body} — Request body string</li>
     *   <li>{@code headers} — Map of header name to value</li>
     *   <li>{@code timeout} — Timeout in seconds (max 30)</li>
     * </ul>
     *
     * @param url     The URL to fetch
     * @param options Options map
     * @return Response body
     * @throws RuntimeException on network errors or timeouts
     */
    @HostAccess.Export
    @SuppressWarnings("unchecked")
    public String fetch(String url, Map<String, Object> options) {
        validateUrl(url);
        if (options == null) return get(url);

        try {
            String method = options.getOrDefault("method", "GET").toString().toUpperCase();
            String body = options.containsKey("body") ? options.get("body").toString() : null;

            int timeoutSec = 15;
            if (options.containsKey("timeout")) {
                try {
                    timeoutSec = Math.min(30, Math.max(1,
                        Integer.parseInt(options.get("timeout").toString())));
                } catch (NumberFormatException ignored) {}
            }

            var builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec));

            // Headers
            if (options.containsKey("headers") && options.get("headers") instanceof Map<?, ?> headers) {
                for (var entry : headers.entrySet()) {
                    builder.header(entry.getKey().toString(), entry.getValue().toString());
                }
            }

            // Method + body
            var bodyPublisher = body != null
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody();

            builder.method(method, bodyPublisher);

            return truncateBody(sendFollowing(builder.build()).body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP fetch interrupted: " + url, e);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("HTTP fetch failed: " + url + " — " + e.getMessage(), e);
        }
    }

    /**
     * Send a request, following up to {@link #MAX_REDIRECTS} redirects manually,
     * re-validating the target of each hop against the SSRF policy. Redirects are
     * reissued as GET (drops any request body) — the common case (link following,
     * http→https upgrade) is GET, and downgrading a redirected POST is safe.
     */
    private HttpResponse<String> sendFollowing(HttpRequest initial)
            throws IOException, InterruptedException {
        var request = initial;
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int hops = 0;
        while (isRedirect(response.statusCode()) && hops++ < MAX_REDIRECTS) {
            var location = response.headers().firstValue("Location").orElse(null);
            if (location == null || location.isBlank()) break;
            var next = request.uri().resolve(location);
            var nextUrl = next.toString();
            validateUrl(nextUrl);   // re-apply scheme + SSRF host checks per hop
            request = HttpRequest.newBuilder()
                .uri(next)
                .timeout(DEFAULT_TIMEOUT)
                .GET()
                .build();
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        return response;
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303
            || status == 307 || status == 308;
    }

    private void validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL must not be blank");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException("URL must use http:// or https:// scheme: " + url);
        }
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed URL: " + url);
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL has no host: " + url);
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new RuntimeException("DNS resolution failed for host: " + host);
        }
        for (var addr : addrs) {
            if (isBlockedAddress(addr)) {
                // Do not echo the resolved address family details back to the
                // script beyond what it needs — but name the host so legit
                // callers understand the denial.
                throw new SecurityException(
                    "Blocked request to non-public address (" + host + " → "
                        + addr.getHostAddress() + ")");
            }
        }
    }

    /**
     * True if this address must not be reached. The first group is blocked for
     * ALL scripts (never a legitimate fetch target); the second only for
     * untrusted scripts ({@link #blockPrivateNetworks}).
     */
    private boolean isBlockedAddress(InetAddress a) {
        // Never legitimate for any script — includes cloud metadata
        // (169.254.169.254 is link-local) and IPv6 link-local (fe80::/10).
        if (a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        // IPv6 unique-local fc00::/7 (not covered by isSiteLocalAddress).
        if (b.length == 16 && (b[0] & 0xFE) == 0xFC) {
            return true;
        }
        if (!blockPrivateNetworks) {
            return false;
        }
        // Untrusted-only: loopback (127/8, ::1) and RFC1918 (10/8, 172.16/12, 192.168/16).
        if (a.isLoopbackAddress() || a.isSiteLocalAddress()) {
            return true;
        }
        // CGNAT 100.64.0.0/10.
        if (b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64) {
            return true;
        }
        return false;
    }

    private static String truncateBody(String body) {
        if (body != null && body.length() > MAX_RESPONSE_SIZE) {
            return body.substring(0, MAX_RESPONSE_SIZE);
        }
        return body;
    }
}
