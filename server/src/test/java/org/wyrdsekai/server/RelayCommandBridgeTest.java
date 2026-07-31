package org.wyrdsekai.server;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * unit coverage for the in-session relay bridge.
 * The live mint/join paths are exercised end-to-end by the CLI flow
 * (`wyrd phone invite` / `wyrd relay join`); here we pin the pieces that
 * can regress silently: QR rendering and the slash-command wiring.
 */
class RelayCommandBridgeTest {

    @Test
    void asciiQrRendersUniformNonEmptyMatrix() {
        var lines = RelayCommandBridge.asciiQr(
            "wyrdphone://relay.example.org:4443/eyJ2IjoxfQ");
        assertFalse(lines.isEmpty(), "QR should render at least one line");
        var width = lines.get(0).length();
        assertTrue(width >= 21, "QR matrix should be at least version-1 wide");
        for (var line : lines) {
            assertEquals(width, line.length(), "QR lines must be uniform width");
        }
        // Half-block rendering uses exactly this glyph set.
        assertTrue(lines.stream().allMatch(l -> l.chars()
                .allMatch(c -> c == ' ' || c == '▀' || c == '▄' || c == '█')),
            "QR lines should only contain half-block glyphs");
    }

    @Test
    void asciiQrOfDistinctPayloadsDiffer() {
        var a = RelayCommandBridge.asciiQr("wyrdphone://a/payload-one");
        var b = RelayCommandBridge.asciiQr("wyrdphone://b/payload-two");
        assertFalse(a.equals(b), "different payloads must yield different matrices");
    }

    @Test
    void qrPngBase64ProducesDecodablePng() {
        var b64 = RelayCommandBridge.qrPngBase64(
            "wyrdphone://relay.example.org:4443/eyJ2IjoxfQ");
        assertFalse(b64.isEmpty(), "PNG QR should render");
        var bytes = Base64.getDecoder().decode(b64);
        // PNG magic: 89 50 4E 47 0D 0A 1A 0A
        assertEquals((byte) 0x89, bytes[0]);
        assertEquals((byte) 'P', bytes[1]);
        assertEquals((byte) 'N', bytes[2]);
        assertEquals((byte) 'G', bytes[3]);
        assertTrue(bytes.length > 200, "PNG should be a real image, not a stub");
    }

    @Test
    void webSocketWiresStewardGatedInviteWithQrBlock() throws Exception {
        var src = Files.readString(Path.of(
            "src/main/java/org/wyrdsekai/server/ws/WyrdWebSocket.java"));
        assertTrue(src.contains("case \"invite\", \"relay\" ->"),
            "web command switch must route /invite + /relay");
        assertTrue(src.contains("wyrdsekai.invite_qr"),
            "web invite must emit the invite_qr content block");
        assertTrue(src.contains("relaycmd.steward_only"),
            "web relay commands must be steward-gated");
        var web = Files.readString(Path.of(
            "src/main/resources/web/index.html"));
        assertTrue(web.contains("wyrdsekai.invite_qr"),
            "web client must render the invite_qr block");
    }

    @Test
    void inviteCaFingerprintNormalizesColonHex() {
        var payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"v\":2,\"ca_fp\":\"AA:BB:CC:DD\"}".getBytes(StandardCharsets.UTF_8));
        var url = "wyrdrelay://relay.example.org:4443/" + payload + ".sig";
        assertEquals("aabbccdd", RelayCommandBridge.inviteCaFingerprint(url));
    }

    @Test
    void inviteCaFingerprintNullWhenAbsentOrGarbage() {
        var noFp = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"v\":1}".getBytes(StandardCharsets.UTF_8));
        assertNull(RelayCommandBridge.inviteCaFingerprint(
            "wyrdrelay://relay.example.org:4443/" + noFp + ".sig"));
        assertNull(RelayCommandBridge.inviteCaFingerprint("not-a-url"));
    }

    @Test
    void relayJoinRejectsMalformedJoinToken() {
        var r = RelayCommandBridge.relayJoin("wyrdjoin://hostonly-no-slash", null);
        assertFalse(r.ok());
        assertTrue(r.detail().contains("malformed"));
        // Codeless join (commons self-serve) is refused WITHOUT an out-of-band
        // fingerprint — nothing anchors the trust decision, so no TOFU. The
        // error must tell the caller exactly what to bring.
        var r2 = RelayCommandBridge.relayJoin("relay.example.org", null);
        assertFalse(r2.ok());
        assertTrue(r2.detail().contains("--fingerprint"));
    }

    @Test
    void shellCommandWiresStewardGatedInviteAndRelayJoin() throws Exception {
        var src = Files.readString(Path.of(
            "src/main/java/org/wyrdsekai/server/ssh/WyrdShellCommand.java"));
        int slashDispatch = src.indexOf("handleSlashCommand");
        assertTrue(slashDispatch > 0, "slash dispatcher present");
        int inviteCase = src.indexOf("case \"invite\"", slashDispatch);
        int relayCase = src.indexOf("case \"relay\"", slashDispatch);
        assertTrue(inviteCase > 0, "/invite case wired in slash dispatcher");
        assertTrue(relayCase > 0, "/relay case wired in slash dispatcher");
        // Both must check the steward role before reaching the bridge.
        var inviteBody = src.substring(inviteCase,
            src.indexOf("RelayCommandBridge.phoneInvite", inviteCase));
        assertTrue(inviteBody.contains("\"steward\".equals(playerRole)"),
            "/invite phone must be steward-gated");
        var relayBody = src.substring(relayCase,
            src.indexOf("RelayCommandBridge.relayJoin", relayCase));
        assertTrue(relayBody.contains("\"steward\".equals(playerRole)"),
            "/relay join must be steward-gated");
    }
}
