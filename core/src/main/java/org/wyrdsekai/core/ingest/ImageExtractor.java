package org.wyrdsekai.core.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * OCR extractor for image content.
 *
 * <p>Platform-specific implementations:
 * <ul>
 *   <li>Android: MLKit Text Recognition</li>
 *   <li>iOS: Vision framework VNRecognizeTextRequest</li>
 *   <li>Server: Tesseract OCR (command-line fallback)</li>
 * </ul>
 *
 * <p>This server-side implementation uses Tesseract via command line.
 * If Tesseract is not available, extraction is skipped gracefully.</p>
 */
public class ImageExtractor implements ContentExtractor {

    private static final Logger log = LoggerFactory.getLogger(ImageExtractor.class);
    private final boolean tesseractAvailable;

    public ImageExtractor() {
        this.tesseractAvailable = checkTesseract();
    }

    @Override
    public boolean canExtract(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    @Override
    public String extract(IngestContent content) {
        if (!content.hasBinaryData()) return null;
        if (!tesseractAvailable) {
            log.debug("Tesseract not available — skipping OCR for {}", content.id());
            return null;
        }

        try {
            // Write image to temp file, run Tesseract, read output
            var tempImage = Files.createTempFile("ingest-", suffix(content.mimeType()));
            Files.write(tempImage, content.data());

            var tempOutput = Files.createTempFile("ingest-ocr-", ".txt");
            var outputBase = tempOutput.toString().replace(".txt", "");

            var process = new ProcessBuilder("tesseract",
                tempImage.toString(), outputBase, "--oem", "1", "-l", "eng")
                .redirectErrorStream(true)
                .start();
            int exitCode = process.waitFor();

            Files.deleteIfExists(tempImage);

            if (exitCode == 0) {
                var ocrOutput = Path.of(outputBase + ".txt");
                if (Files.exists(ocrOutput)) {
                    var text = Files.readString(ocrOutput).strip();
                    Files.deleteIfExists(ocrOutput);
                    Files.deleteIfExists(tempOutput);
                    return text.isBlank() ? null : text;
                }
            }

            Files.deleteIfExists(tempOutput);
            log.warn("Tesseract OCR failed for {}: exit code {}", content.id(), exitCode);
            return null;
        } catch (Exception e) {
            log.warn("OCR extraction failed for {}: {}", content.id(), e.getMessage());
            return null;
        }
    }

    @Override
    public String name() {
        return "image-ocr-tesseract";
    }

    /** Whether Tesseract is available on this system. */
    public boolean isAvailable() {
        return tesseractAvailable;
    }

    private static boolean checkTesseract() {
        try {
            var process = new ProcessBuilder("tesseract", "--version")
                .redirectErrorStream(true)
                .start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String suffix(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }
}
