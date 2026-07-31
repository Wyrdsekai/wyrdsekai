package org.wyrdsekai.common.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.util.Json;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ImageAttachment} — image data carrier for vision pipeline.
 */
class ImageAttachmentTest {

    private final ObjectMapper mapper = Json.mapper();

    @Test
    void fromBase64_creates_valid_attachment() {
        var attachment = ImageAttachment.fromBase64("aW1hZ2VkYXRh", "image/jpeg");

        assertThat(attachment.id()).isNotNull().hasSize(8);
        assertThat(attachment.mimeType()).isEqualTo("image/jpeg");
        assertThat(attachment.base64Data()).isEqualTo("aW1hZ2VkYXRh");
        assertThat(attachment.url()).isNull();
        assertThat(attachment.timestamp()).isNotNull();
        assertThat(attachment.hasData()).isTrue();
        assertThat(attachment.hasUrl()).isFalse();
    }

    @Test
    void fromUrl_creates_valid_attachment() {
        var attachment = ImageAttachment.fromUrl("/api/upload/img-12345", "image/png");

        assertThat(attachment.id()).isNotNull().hasSize(8);
        assertThat(attachment.mimeType()).isEqualTo("image/png");
        assertThat(attachment.base64Data()).isNull();
        assertThat(attachment.url()).isEqualTo("/api/upload/img-12345");
        assertThat(attachment.timestamp()).isNotNull();
        assertThat(attachment.hasData()).isFalse();
        assertThat(attachment.hasUrl()).isTrue();
    }

    @Test
    void hasData_and_hasUrl_with_blank_values() {
        var withBlankData = new ImageAttachment("id1", "image/jpeg", "", null, Instant.now());
        assertThat(withBlankData.hasData()).isFalse();
        assertThat(withBlankData.hasUrl()).isFalse();

        var withBlankUrl = new ImageAttachment("id2", "image/jpeg", null, "  ", Instant.now());
        assertThat(withBlankUrl.hasData()).isFalse();
        assertThat(withBlankUrl.hasUrl()).isFalse();
    }

    @Test
    void jackson_serialization_roundtrip() throws JsonProcessingException {
        var original = ImageAttachment.fromBase64("aW1hZ2VkYXRh", "image/jpeg");

        var json = mapper.writeValueAsString(original);
        var deserialized = mapper.readValue(json, ImageAttachment.class);

        assertThat(deserialized.id()).isEqualTo(original.id());
        assertThat(deserialized.mimeType()).isEqualTo("image/jpeg");
        assertThat(deserialized.base64Data()).isEqualTo("aW1hZ2VkYXRh");
        assertThat(deserialized.url()).isNull();
        assertThat(deserialized.timestamp()).isEqualTo(original.timestamp());
    }

    @Test
    void null_fields_handled_gracefully() {
        var attachment = new ImageAttachment(null, null, null, null, null);

        assertThat(attachment.id()).isNull();
        assertThat(attachment.mimeType()).isNull();
        assertThat(attachment.hasData()).isFalse();
        assertThat(attachment.hasUrl()).isFalse();
    }
}
