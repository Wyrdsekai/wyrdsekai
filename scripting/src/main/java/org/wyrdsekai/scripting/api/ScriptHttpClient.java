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
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

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
 * <p>Item scripts get this client bound to their capability set (2026-09-28): {@code get} and a
 * GET/HEAD {@code fetch} need {@code web.fetch_raw}, {@code post} and a POST/PATCH {@code fetch}
 * need {@code web.post}, PUT needs {@code web.put}, DELETE {@code web.delete}, and the host must be
 * in the item's {@code external_domains}, exactly as for {@code world.web.*}. Before this the global
 * sat outside the capability gate, so an item could POST anywhere it liked. Header values may carry
 * Safe references ({@link SafeRefs}); the secret goes out in the header and is scrubbed from the
 * response.
 *
 * <p>#3 (2026-07-19 OSS hardening) — SSRF guard. Every request (and every redirect hop) resolves
 * the target host and rejects non-public addresses. Two policies:
 * <ul>
 *   <li><b>Restricted</b> (every item with a manifest, crafted and visitor scripts): blocks
 *       loopback, link-local (incl. metadata), site-local (RFC1918), unique-local IPv6, CGNAT,
 *       any-local and multicast. A LAN address is allowed only when the item's manifest names that
 *       exact host in {@code external_domains}; loopback never is.</li>
 *   <li><b>Unrestricted</b> (items built into the server): may reach LAN/loopback services but is
 *       STILL blocked from the never-legitimate ranges — link-local/metadata, any-local,
 *       multicast.</li>
 * </ul>
 * Redirects are followed manually (max {@value #MAX_REDIRECTS}) and re-validated per hop;
 * auto-follow is disabled so a public→internal 3xx cannot slip past.</p>
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
     * blocked never-legitimate ranges.
     */
    private final boolean blockPrivateNetworks;

    /** The calling item's capabilities; null only outside the item executor (no gate). */
    private final ItemCapabilitySet caps;

    /** Secrets behind Safe references, for header values only. */
    private final Function<String, Optional<String>> secrets;

    public ScriptHttpClient() {
        this(defaultClient(), true);
    }

    /** Ungated client with a fixed address policy (tests, the skill sandbox builder). */
    public ScriptHttpClient(boolean blockPrivateNetworks) {
        this(defaultClient(), blockPrivateNetworks);
    }

    /** The client an item script gets: gated by its capabilities, Safe references resolved. */
    public ScriptHttpClient(ItemCapabilitySet caps, Function<String, Optional<String>> secrets) {
        this(defaultClient(), caps, secrets);
    }

    // Visible for testing — blocking policy by default (matches production).
    ScriptHttpClient(HttpClient client) {
        this(client, true);
    }

    ScriptHttpClient(HttpClient client, boolean blockPrivateNetworks) {
        this.client = client;
        this.blockPrivateNetworks = blockPrivateNetworks;
        this.caps = null;
        this.secrets = null;
    }

    ScriptHttpClient(HttpClient client, ItemCapabilitySet caps,
                     Function<String, Optional<String>> secrets) {
        this.client = client;
        this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        this.blockPrivateNetworks = !this.caps.isUnrestricted();
        this.secrets = secrets;
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
        gate("GET");
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
        gate("POST");
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
     *   <li>{@code headers} — Map of header name to value; a value may hold Safe references</li>
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
        if (options == null) return get(url);
        String method = options.getOrDefault("method", "GET").toString().toUpperCase(Locale.ROOT);
        gate(method);
        validateUrl(url);

        var used = new HashSet<String>();
        try {
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
                for (var entry : SafeRefs.resolveHeaders(headers, caps, secrets, used).entrySet()) {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }

            // Method + body
            var bodyPublisher = body != null
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody();

            builder.method(method, bodyPublisher);

            return SafeRefs.redact(truncateBody(sendFollowing(builder.build()).body()), used);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP fetch interrupted: " + url, e);
        } catch (SecurityException | CapabilityDeniedError | SafeRefs.MissingCredential e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(SafeRefs.redact(
                "HTTP fetch failed: " + url + " — " + e.getMessage(), used), e);
        }
    }

    /** The capability an HTTP method needs; the same names {@code world.web.*} uses. */
    static String capabilityFor(String method) {
        return switch (method == null ? "GET" : method.toUpperCase(Locale.ROOT)) {
            case "GET", "HEAD", "OPTIONS" -> "web.fetch_raw";
            case "PUT" -> "web.put";
            case "DELETE" -> "web.delete";
            default -> "web.post";
        };
    }

    private void gate(String method) {
        if (caps != null) caps.require(capabilityFor(method));
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
            validateUrl(nextUrl);   // re-apply scheme, domain allowlist and SSRF host checks per hop
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
        checkDestination(url, caps, blockPrivateNetworks);
    }

    /**
     * May a request go to {@code url}? Throws {@link SecurityException} when not: the scheme is
     * not http(s), a restricted item's manifest does not list the host in
     * {@code external_domains}, or the host resolves to an address the policy blocks. Used for
     * the {@code http} global and for {@code world.web.*}.
     *
     * @param caps         the item's capabilities; null means ungated
     * @param blockPrivate whether loopback and LAN addresses are blocked
     */
    public static void checkDestination(String url, ItemCapabilitySet caps, boolean blockPrivate) {
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
        boolean restricted = caps != null && !caps.isUnrestricted();
        if (restricted && !caps.allowsDomain(host)) {
            throw new SecurityException("Blocked request to " + host
                + ": not in this item's external_domains");
        }
        // A LAN host the manifest names exactly (not through a wildcard) is the steward's to allow.
        boolean namedExactly = restricted && caps.externalDomains().stream()
            .anyMatch(d -> d != null && d.equalsIgnoreCase(host));
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new RuntimeException("DNS resolution failed for host: " + host);
        }
        for (var addr : addrs) {
            if (isBlockedAddress(addr, blockPrivate, namedExactly)) {
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
     * ALL scripts (never a legitimate fetch target); the second only under
     * {@code blockPrivate}, where a LAN address the manifest names exactly is let through.
     */
    private static boolean isBlockedAddress(InetAddress a, boolean blockPrivate, boolean lanNamed) {
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
        if (!blockPrivate) {
            return false;
        }
        // Loopback (127/8, ::1) is this machine: never for a restricted script.
        if (a.isLoopbackAddress()) {
            return true;
        }
        if (lanNamed) {
            return false;
        }
        // RFC1918 (10/8, 172.16/12, 192.168/16).
        if (a.isSiteLocalAddress()) {
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
