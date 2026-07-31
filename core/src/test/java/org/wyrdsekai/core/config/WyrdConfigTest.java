package org.wyrdsekai.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WyrdConfigTest {

    @Test
    void parsesSectionsAndKeys() {
        var toml = """
            [node]
            name = "home-server"
            zone = "alpha"

            [relay]
            url = "nats://192.0.2.108:4222"
            user = "hh-c460390412f0"
            """;
        var m = WyrdConfig.parseToml(toml);
        assertEquals("home-server", m.get("node.name"));
        assertEquals("alpha", m.get("node.zone"));
        assertEquals("nats://192.0.2.108:4222", m.get("relay.url"));
        assertEquals("hh-c460390412f0", m.get("relay.user"));
    }

    @Test
    void stripsCommentsAndPreservesQuotedHashes() {
        var toml = """
            # full-line comment
            [node]
            name = "home-server"  # trailing comment
            note = "value with # hash inside string"
            """;
        var m = WyrdConfig.parseToml(toml);
        assertEquals("home-server", m.get("node.name"));
        assertEquals("value with # hash inside string", m.get("node.note"));
    }

    @Test
    void handlesBareValuesAndBooleans() {
        var toml = """
            [peer_training]
            host = true
            iters = 60
            """;
        var m = WyrdConfig.parseToml(toml);
        assertEquals("true", m.get("peer_training.host"));
        assertEquals("60", m.get("peer_training.iters"));
    }

    @Test
    void emptyAndBlankLinesSkipped() {
        var toml = """

            [node]

            name = "x"


            """;
        var m = WyrdConfig.parseToml(toml);
        assertEquals(1, m.size());
        assertEquals("x", m.get("node.name"));
    }

    @Test
    void singleQuotedStringsStripped() {
        var toml = """
            [a]
            k = 'value-with-spaces'
            """;
        assertEquals("value-with-spaces", WyrdConfig.parseToml(toml).get("a.k"));
    }
}
