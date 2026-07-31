package org.wyrdsekai.core.naming;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ZoneResolveJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FP = "z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";

    @Test void format_ok_hasAllFields() throws Exception {
        var addr = new ZoneAddress(FP, "kitchen");
        var json = ZoneResolveJson.format(new ZoneAddressResolver.Result.Ok(addr));
        var node = MAPPER.readTree(json);

        assertTrue(node.get("ok").asBoolean());
        assertEquals("did:wyrd:" + FP + ":kitchen", node.get("canonical").asText());
        assertEquals(FP, node.get("fingerprint").asText());
        assertEquals("kitchen", node.get("label").asText());
        assertFalse(node.has("code"), "ok payload must not carry error code");
        assertFalse(node.has("message"), "ok payload must not carry error message");
    }

    @Test void format_err_hasCodeAndMessage() throws Exception {
        var json = ZoneResolveJson.format(
            new ZoneAddressResolver.Result.Err("reserved_keyword", "…"));
        var node = MAPPER.readTree(json);

        assertFalse(node.get("ok").asBoolean());
        assertEquals("reserved_keyword", node.get("code").asText());
        assertEquals("…", node.get("message").asText());
        assertFalse(node.has("canonical"), "err payload must not carry canonical");
    }

    @Test void fromService_nullReturnsUnavailable() throws Exception {
        var json = ZoneResolveJson.fromService(null, "kitchen");
        var node = MAPPER.readTree(json);
        assertFalse(node.get("ok").asBoolean());
        assertEquals("unavailable", node.get("code").asText());
    }

    @Test void format_jsonIsSingleLine() {
        // Downstream parsers in docks.js / CLI do JSON.parse on the whole
        // string; a single-line representation is slightly cheaper and
        // matches the rest of the federation JSON surface.
        var json = ZoneResolveJson.format(
            new ZoneAddressResolver.Result.Err("empty_input", "No input"));
        assertFalse(json.contains("\n"), "JSON should be single-line: " + json);
    }
}
