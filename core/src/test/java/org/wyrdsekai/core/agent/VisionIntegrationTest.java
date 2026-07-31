package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.ImageAttachment;
import org.wyrdsekai.common.protocol.C2SMessage;
import org.wyrdsekai.common.util.Json;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the photo/vision pipeline end-to-end.
 * Tests the data flow from C2SMessage.Say → WorldEvent.Said → VisionAnalyzer.
 */
class VisionIntegrationTest {

    private final ObjectMapper mapper = Json.mapper();

    @Test
    void said_with_attachments_includes_vision_context_marker() {
        var image = ImageAttachment.fromBase64("aW1hZ2VkYXRh", "image/jpeg");
        var said = new WorldEvent.Said(
            "home", Instant.now(), "player1", "Alice",
            "What's this plant?", "en", List.of(image));

        // VisionAnalyzer should detect images
        assertThat(VisionAnalyzer.hasImages(said.attachments())).isTrue();

        // Build the vision prompt for ToolInferRequest
        var firstImage = VisionAnalyzer.firstImage(said.attachments());
        assertThat(firstImage).isNotNull();
        var visionPrompt = VisionAnalyzer.buildVisionPrompt(firstImage, said.text());
        assertThat(visionPrompt).contains("What's this plant?");

        // After analysis, build context for identity inference
        var context = VisionAnalyzer.buildVisionContext(
            firstImage, "A Monstera deliciosa plant.");
        assertThat(context).contains("## Image Attachment");
        assertThat(context).contains("Monstera deliciosa");
    }

    @Test
    void said_without_attachments_works_normally() {
        var said = new WorldEvent.Said(
            "home", Instant.now(), "player1", "Alice", "Hello!");

        assertThat(VisionAnalyzer.hasImages(said.attachments())).isFalse();
        assertThat(VisionAnalyzer.firstImage(said.attachments())).isNull();
    }

    @Test
    void vision_capability_unavailable_gracefully_degrades() {
        var image = ImageAttachment.fromBase64("data", "image/jpeg");
        var said = new WorldEvent.Said(
            "home", Instant.now(), "player1", "Alice",
            "What is this?", "en", List.of(image));

        assertThat(VisionAnalyzer.hasImages(said.attachments())).isTrue();

        // When vision is unavailable, use degraded context
        var degraded = VisionAnalyzer.buildVisionUnavailableContext();
        assertThat(degraded).contains("vision analysis unavailable");
        assertThat(degraded).contains("## Image Attachment");
    }

    @Test
    void image_attachment_serialization_through_c2s_to_worldevent() throws JsonProcessingException {
        // Simulate the full wire path: C2SMessage.Say → JSON → deserialized → WorldEvent.Said

        // 1. Client creates a Say with attachment
        var attachment = ImageAttachment.fromBase64("dGVzdA==", "image/png");
        var say = new C2SMessage.Say("msg-1", "home", "What is this?", List.of(attachment));

        // 2. Serialize to JSON (wire)
        var json = mapper.writeValueAsString(say);
        assertThat(json).contains("\"data\":\"dGVzdA==\"");
        assertThat(json).contains("\"mimeType\":\"image/png\"");

        // 3. Deserialize back
        var deserialized = mapper.readValue(json, C2SMessage.class);
        assertThat(deserialized).isInstanceOf(C2SMessage.Say.class);
        var deserializedSay = (C2SMessage.Say) deserialized;
        assertThat(deserializedSay.attachments()).hasSize(1);
        assertThat(deserializedSay.attachments().getFirst().base64Data()).isEqualTo("dGVzdA==");

        // 4. Server creates WorldEvent.Said with the attachments
        var said = new WorldEvent.Said(
            "home", Instant.now(), "player1", "Alice",
            deserializedSay.text(), "en", deserializedSay.attachments());

        // 5. Verify attachments survived the journey
        assertThat(said.attachments()).hasSize(1);
        assertThat(VisionAnalyzer.hasImages(said.attachments())).isTrue();

        // 6. WorldEvent.Said also round-trips through JSON
        var saidJson = mapper.writeValueAsString(said);
        var deserializedSaid = mapper.readValue(saidJson, WorldEvent.class);
        assertThat(deserializedSaid).isInstanceOf(WorldEvent.Said.class);
        var saidResult = (WorldEvent.Said) deserializedSaid;
        assertThat(saidResult.attachments()).hasSize(1);
        assertThat(saidResult.attachments().getFirst().mimeType()).isEqualTo("image/png");
    }

    @Test
    void multiple_attachments_first_one_used() {
        var image1 = ImageAttachment.fromBase64("first", "image/jpeg");
        var image2 = ImageAttachment.fromBase64("second", "image/png");
        var said = new WorldEvent.Said(
            "home", Instant.now(), "player1", "Alice",
            "Look at these", "en", List.of(image1, image2));

        assertThat(VisionAnalyzer.hasImages(said.attachments())).isTrue();

        // firstImage returns the first usable image
        var first = VisionAnalyzer.firstImage(said.attachments());
        assertThat(first).isNotNull();
        assertThat(first.base64Data()).isEqualTo("first");
        assertThat(first.mimeType()).isEqualTo("image/jpeg");
    }
}
