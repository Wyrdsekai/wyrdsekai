package org.wyrdsekai.core.agent.research;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArgotCodecTest {

    private ArgotCodec codec;

    @BeforeEach
    void setUp() {
        codec = new ArgotCodec();
        codec.generateCodebook("zone-a", List.of("hello", "trade", "danger", "help"), "seed42");
    }

    @Test void generate_codebook() {
        var cb = codec.getCodebook("zone-a");
        assertThat(cb).isPresent();
        assertThat(cb.get().size()).isEqualTo(4);
        assertThat(cb.get().zoneId()).isEqualTo("zone-a");
    }

    @Test void tokens_are_opaque() {
        var cb = codec.getCodebook("zone-a").orElseThrow();
        var token = cb.conceptToToken().get("hello");
        assertThat(token).startsWith("§");
        assertThat(token).doesNotContain("hello");
    }

    @Test void encode_message() {
        var encoded = codec.encode("zone-a", "hello friend I need help");
        assertThat(encoded.tokensUsed()).isEqualTo(2); // "hello" and "help"
        assertThat(encoded.encodedText()).contains("§");
        assertThat(encoded.encodedText()).contains("friend"); // non-concept word unchanged
    }

    @Test void decode_message() {
        var encoded = codec.encode("zone-a", "hello friend");
        var decoded = codec.decode("zone-a", encoded.encodedText());
        assertThat(decoded.decodedText()).contains("hello");
        assertThat(decoded.decodedText()).contains("friend");
        assertThat(decoded.tokensDecoded()).isGreaterThanOrEqualTo(1);
    }

    @Test void encode_roundtrip() {
        var original = "trade danger help";
        var encoded = codec.encode("zone-a", original);
        var decoded = codec.decode("zone-a", encoded.encodedText());
        assertThat(decoded.decodedText()).isEqualTo(original);
    }

    @Test void unknown_zone_passthrough() {
        var encoded = codec.encode("zone-unknown", "hello world");
        assertThat(encoded.tokensUsed()).isEqualTo(0);
        assertThat(encoded.encodedText()).isEqualTo("hello world");
    }

    @Test void different_zones_different_tokens() {
        codec.generateCodebook("zone-b", List.of("hello", "trade"), "other-seed");
        var tokenA = codec.getCodebook("zone-a").orElseThrow().conceptToToken().get("hello");
        var tokenB = codec.getCodebook("zone-b").orElseThrow().conceptToToken().get("hello");
        assertThat(tokenA).isNotEqualTo(tokenB);
    }

    @Test void codebook_count() {
        assertThat(codec.codebookCount()).isEqualTo(1);
        codec.generateCodebook("zone-b", List.of("hello"), "seed");
        assertThat(codec.codebookCount()).isEqualTo(2);
    }

    @Test void decode_without_codebook() {
        var decoded = codec.decode("nonexistent", "some text");
        assertThat(decoded.confidence()).isEqualTo(0.0);
        assertThat(decoded.decodedText()).isEqualTo("some text");
    }
}
