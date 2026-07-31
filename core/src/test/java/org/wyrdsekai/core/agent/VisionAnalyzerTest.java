package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.ImageAttachment;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link VisionAnalyzer} — vision analysis prompt and context builder.
 */
class VisionAnalyzerTest {

    @Test
    void buildVisionPrompt_with_user_message() {
        var image = ImageAttachment.fromBase64("aW1hZ2VkYXRh", "image/jpeg");
        var prompt = VisionAnalyzer.buildVisionPrompt(image, "What's this plant?");

        assertThat(prompt).contains("Analyze the following image");
        assertThat(prompt).contains("The user asks: What's this plant?");
        assertThat(prompt).contains("image/jpeg");
        assertThat(prompt).contains("aW1hZ2VkYXRh");
    }

    @Test
    void buildVisionPrompt_without_user_message() {
        var image = ImageAttachment.fromBase64("aW1hZ2VkYXRh", "image/jpeg");
        var prompt = VisionAnalyzer.buildVisionPrompt(image, null);

        assertThat(prompt).contains("Analyze the following image");
        assertThat(prompt).doesNotContain("The user asks:");
        assertThat(prompt).contains("base64 data: aW1hZ2VkYXRh");
    }

    @Test
    void buildVisionContext_formats_correctly() {
        var image = ImageAttachment.fromBase64("data", "image/png");
        var context = VisionAnalyzer.buildVisionContext(image,
            "A potted Monstera deliciosa with fenestrated leaves.");

        assertThat(context).contains("## Image Attachment");
        assertThat(context).contains("The human shared an image");
        assertThat(context).contains("[Vision analysis: A potted Monstera deliciosa");
    }

    @Test
    void buildVisionPrompt_fromBase64_image() {
        var image = ImageAttachment.fromBase64("c29tZWJhc2U2NA==", "image/png");
        var prompt = VisionAnalyzer.buildVisionPrompt(image, "Read this sign");

        assertThat(prompt).contains("base64 data: c29tZWJhc2U2NA==");
        assertThat(prompt).doesNotContain("url:");
    }

    @Test
    void buildVisionPrompt_fromUrl_image() {
        var image = ImageAttachment.fromUrl("/api/upload/img-42", "image/jpeg");
        var prompt = VisionAnalyzer.buildVisionPrompt(image, "Is this receipt correct?");

        assertThat(prompt).contains("url: /api/upload/img-42");
        assertThat(prompt).doesNotContain("base64 data:");
    }

    @Test
    void hasImages_and_firstImage_with_null_and_empty() {
        assertThat(VisionAnalyzer.hasImages(null)).isFalse();
        assertThat(VisionAnalyzer.hasImages(List.of())).isFalse();
        assertThat(VisionAnalyzer.firstImage(null)).isNull();
        assertThat(VisionAnalyzer.firstImage(List.of())).isNull();

        // Attachment with neither data nor URL
        var emptyAttachment = new ImageAttachment("id", "image/jpeg", null, null, Instant.now());
        assertThat(VisionAnalyzer.hasImages(List.of(emptyAttachment))).isFalse();
        assertThat(VisionAnalyzer.firstImage(List.of(emptyAttachment))).isNull();

        // Valid attachment
        var validAttachment = ImageAttachment.fromBase64("data", "image/jpeg");
        assertThat(VisionAnalyzer.hasImages(List.of(validAttachment))).isTrue();
        assertThat(VisionAnalyzer.firstImage(List.of(validAttachment))).isNotNull();
    }

    @Test
    void buildVisionUnavailableContext_formats_correctly() {
        var context = VisionAnalyzer.buildVisionUnavailableContext();

        assertThat(context).contains("## Image Attachment");
        assertThat(context).contains("vision analysis unavailable");
    }
}
