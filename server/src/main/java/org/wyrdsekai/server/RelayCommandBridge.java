package org.wyrdsekai.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * in-session bridge for the steward slash commands
 * {@code /invite phone} and {@code /relay join <host> <code>}.
 *
 * <p>Both delegate to {@link RelayNkeyAdminMain#run} in-process with
 * captured streams, so the CLI and the in-world surfaces share one code
 * path (NKey signing, fingerprint-pinned TLS, conf persistence). The
 * results are blocking HTTP calls bounded by the admin tool's own
 * timeouts (~15s) — acceptable for an interactive steward command.</p>
 */
public final class RelayCommandBridge {

    private RelayCommandBridge() {}

    /** Outcome of a bridge call: {@code detail} is the human-facing line(s). */
    public record Result(boolean ok, String inviteUrl, String detail) {}

    /**
     * Mint a phone connection invite from the registered relay.
     * Returns the wyrdphone:// URL on success.
     */
    public static Result phoneInvite() {
        var captured = runAdmin("phone-invite");
        if (captured.rc() != 0) {
            return new Result(false, null, captured.firstError());
        }
        var inviteUrl = extractJsonField(captured.stdout(), "invite_url");
        if (inviteUrl == null || inviteUrl.isBlank()) {
            return new Result(false, null, "relay response had no invite_url");
        }
        return new Result(true, inviteUrl, null);
    }

    /**
     * Redeem a join code against {@code host} and enroll
     * this zone's NKey with the relay. {@code hostArg} may be the
     * {@code wyrdjoin://host:port/<code>.<ca_fp_hex>} token relay.sh
     * prints — its embedded CA fingerprint is verified against the
     * redeemed invite BEFORE anything is trusted (the token is the trust
     * decision); pass {@code code} as null in that form. The conf file is
     * updated by the underlying register-nkey path; the zone still needs
     * a restart to attach the relay leg (the caller narrates that).
     */
    public static Result relayJoin(String hostArg, String code) {
        return relayJoin(hostArg, code, null);
    }

    /**
     * As {@link #relayJoin(String, String)}, plus the commons self-serve form
     * {@code code == null} with a bare host asks a
     * commons relay for the invite payload without a code. That form REQUIRES
     * {@code fingerprintArg} — the relay CA's SHA-256 fingerprint carried out
     * of band (the relay's public web page prints it) — because with no code
     * and no fingerprint there is nothing anchoring the trust decision. This
     * surface is non-interactive by design; the bash CLI layers an interactive
     * confirm on top, this method never guesses. {@code fingerprintArg} accepts
     * the colon-separated openssl form or bare hex, any case.
     */
    public static Result relayJoin(String hostArg, String code, String fingerprintArg) {
        String expectedCaFp = (fingerprintArg == null || fingerprintArg.isBlank())
            ? null
            : fingerprintArg.replace(":", "").toLowerCase();
        if (hostArg.startsWith("wyrdjoin://")) {
            var rest = hostArg.substring("wyrdjoin://".length());
            var slash = rest.indexOf('/');
            if (slash <= 0 || slash == rest.length() - 1) {
                return new Result(false, null, "malformed wyrdjoin:// token");
            }
            hostArg = rest.substring(0, slash);
            var tail = rest.substring(slash + 1);
            var dot = tail.indexOf('.');
            if (dot > 0) {
                code = tail.substring(0, dot);
                expectedCaFp = tail.substring(dot + 1).toLowerCase();
            } else {
                code = tail;
            }
        }
        if (code == null || code.isBlank()) {
            // Commons self-serve: a codeless /join is only safe when the CA
            // fingerprint arrived out of band — refuse rather than TOFU.
            if (expectedCaFp == null) {
                return new Result(false, null,
                    "self-serve join needs the relay's CA fingerprint: pass "
                    + "--fingerprint <fp> (published on the relay's page), or use "
                    + "an invite code / wyrdjoin:// token");
            }
            code = "";
        }
        String host = hostArg;
        int port = 4443;
        var colon = hostArg.indexOf(':');
        if (colon > 0) {
            host = hostArg.substring(0, colon);
            try {
                port = Integer.parseInt(hostArg.substring(colon + 1));
            } catch (NumberFormatException e) {
                return new Result(false, null, "bad port in " + hostArg);
            }
        }

        String inviteUrl;
        try {
            var body = new ObjectMapper().writeValueAsString(Map.of("code", code));
            var resp = postJson("https://" + host + ":" + port + "/join", body);
            if (resp.statusCode() != 200) {
                return new Result(false, null, "HTTP " + resp.statusCode() + ": " + resp.body());
            }
            inviteUrl = extractJsonField(resp.body(), "invite_url");
        } catch (Exception e) {
            return new Result(false, null, e.getMessage());
        }
        if (inviteUrl == null || inviteUrl.isBlank()) {
            return new Result(false, null, "join response had no invite_url");
        }

        if (expectedCaFp != null) {
            // The redeemed invite embeds the relay CA's fingerprint; it
            // must match the operator-carried token or someone on-path
            // substituted their own CA.
            var gotFp = inviteCaFingerprint(inviteUrl);
            if (gotFp == null || !gotFp.equals(expectedCaFp)) {
                return new Result(false, null,
                    "relay CA fingerprint does not match the join token — refusing to enroll");
            }
        }

        // Home on the address we REACHED, not the relay's self-advertised
        // primary. A multi-NIC relay (e.g. one on two LANs) advertises a
        // single RELAY_PUBLIC_HOST in the invite it mints, but that address
        // may be unreachable from this zone — we just proved `host` reachable
        // by redeeming over it. The invite token validates by fingerprint, not
        // host (the host is only a dial label, ), so
        // rewriting the dial host is safe and carries through register-nkey's
        // derived NATS leg + registration URL. Without this, a zone that can
        // only reach 10.x redeems fine over 10.x then persists a dead leg
        // pointed at the relay's 1.x primary.
        inviteUrl = rewriteInviteHost(inviteUrl, host, port);

        var captured = runAdmin("register-nkey", inviteUrl);
        if (captured.rc() != 0) {
            return new Result(false, null, captured.firstError());
        }
        return new Result(true, inviteUrl, null);
    }

    /**
     * Rewrite the host[:port] in a {@code scheme://host:port/token} invite URL
     * to {@code host}:{@code port}, preserving the scheme and the token tail
     * verbatim. Used to home a joining zone on the address it actually reached
     * rather than the relay's self-advertised primary (see relayJoin). The
     * token is the trust decision (fingerprint-pinned); the host is only a dial
     * label, so this never weakens verification. Returns the input unchanged if
     * it doesn't parse as {@code scheme://…/…}.
     */
    static String rewriteInviteHost(String inviteUrl, String host, int port) {
        if (inviteUrl == null || host == null || host.isBlank()) return inviteUrl;
        var sep = inviteUrl.indexOf("://");
        if (sep < 0) return inviteUrl;
        var scheme = inviteUrl.substring(0, sep);
        var rest = inviteUrl.substring(sep + 3);
        var slash = rest.indexOf('/');
        if (slash < 0) return inviteUrl;            // no token tail — leave as-is
        var tail = rest.substring(slash);           // includes the leading '/'
        return scheme + "://" + host + ":" + port + tail;
    }

    /**
     * Extract the {@code ca_fp} from a wyrdrelay:// invite URL's base64url
     * JSON payload, normalized to bare lowercase hex. Null when absent or
     * unparseable.
     */
    static String inviteCaFingerprint(String inviteUrl) {
        try {
            var rest = inviteUrl.substring(inviteUrl.indexOf("://") + 3);
            var token = rest.substring(rest.indexOf('/') + 1);
            var payloadB64 = token.split("\\.", 2)[0];
            var json = new String(Base64.getUrlDecoder().decode(payloadB64), StandardCharsets.UTF_8);
            var node = new ObjectMapper().readTree(json);
            if (!node.hasNonNull("ca_fp")) return null;
            return node.get("ca_fp").asText().replace(":", "").toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Render {@code data} as a terminal QR code using half-block glyphs
     * (two matrix rows per text line), quiet zone included. Works on any
     * monospace surface — SSH, telnet, and the web terminal alike.
     */
    public static List<String> asciiQr(String data) {
        try {
            var writer = new QRCodeWriter();
            BitMatrix m = writer.encode(data, BarcodeFormat.QR_CODE, 0, 0,
                Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L,
                       EncodeHintType.MARGIN, 1));
            var lines = new ArrayList<String>();
            for (int y = 0; y < m.getHeight(); y += 2) {
                var sb = new StringBuilder();
                for (int x = 0; x < m.getWidth(); x++) {
                    boolean top = m.get(x, y);
                    boolean bottom = y + 1 < m.getHeight() && m.get(x, y + 1);
                    // Dark modules print as background (space on dark
                    // terminals); inverted glyphs keep contrast sane.
                    sb.append(top ? (bottom ? ' ' : '▄') : (bottom ? '▀' : '█'));
                }
                lines.add(sb.toString());
            }
            return lines;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Render {@code data} as a QR code PNG, base64-encoded. The web /app
     * client embeds it as a data: URI inside a {@code wyrdsekai.invite_qr}
     * content block — a browser can't scan half-block ASCII reliably, a
     * real image it can. ~1–2 KB for a wyrdphone:// invite. Empty string
     * on encode failure (callers fall back to the plain URL).
     */
    public static String qrPngBase64(String data) {
        try {
            var writer = new QRCodeWriter();
            BitMatrix m = writer.encode(data, BarcodeFormat.QR_CODE, 384, 384,
                Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L,
                       EncodeHintType.MARGIN, 2));
            var img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < m.getHeight(); y++) {
                for (int x = 0; x < m.getWidth(); x++) {
                    img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            var out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    private record Captured(int rc, String stdout, String stderr) {
        String firstError() {
            for (var line : stderr.split("\n")) {
                if (!line.isBlank()) return line.replaceFirst("^\\[wyrd\\] ", "");
            }
            return "relay command failed (rc=" + rc + ")";
        }
    }

    private static Captured runAdmin(String... args) {
        var outBuf = new ByteArrayOutputStream();
        var errBuf = new ByteArrayOutputStream();
        int rc;
        try (var out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
             var err = new PrintStream(errBuf, true, StandardCharsets.UTF_8)) {
            rc = RelayNkeyAdminMain.run(out, err, args);
        }
        return new Captured(rc,
            outBuf.toString(StandardCharsets.UTF_8),
            errBuf.toString(StandardCharsets.UTF_8));
    }

    private static String extractJsonField(String text, String field) {
        var mapper = new ObjectMapper();
        for (var line : text.split("\n")) {
            var trimmed = line.trim();
            if (!trimmed.startsWith("{")) continue;
            try {
                var node = mapper.readTree(trimmed);
                if (node.has(field)) return node.get(field).asText();
            } catch (Exception ignored) {
                // Not the JSON line we're after.
            }
        }
        return null;
    }

    /**
     * POST with an accept-any TLS context. Used ONLY to redeem a join code:
     * the invite payload it returns carries the relay's leaf fingerprint +
     * embedded CA, which the subsequent register-nkey call pins against —
     * the invite is the trust anchor, not this transport (same model as
     * `wyrd relay join`'s curl -k fallback).
     */
    private static HttpResponse<String> postJson(String url, String body) throws Exception {
        var trustAll = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        var ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{trustAll}, null);
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        var sslParams = new SSLParameters();
        sslParams.setEndpointIdentificationAlgorithm("");
        var http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .sslContext(ctx)
            .sslParameters(sslParams)
            .build();
        var req = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(12))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
