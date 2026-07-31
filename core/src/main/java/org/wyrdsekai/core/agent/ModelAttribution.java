package org.wyrdsekai.core.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Node-level model attribution signature — which LLM(s) were active when a piece of data
 * was authored. Set once at boot from the resolved inference config; stamped onto
 * durable writes (soul fragments, memory items) so post-OSS model updates can answer
 * "which model wrote this?" for corpus mining, regression debugging, and selective
 * regeneration. Coarse by design: a per-turn model id would need invasive plumbing, and
 * the boot-time signature already distinguishes releases (e.g. drive model V6 vs V7).
 */
public final class ModelAttribution {

    private static volatile String signature = "unknown";

    private ModelAttribution() {}

    /** Set at boot (Main) from the resolved inference config. */
    public static void set(String sig) {
        if (sig != null && !sig.isBlank()) signature = sig;
    }

    /** The current node model signature, e.g. {@code "drive=wyrdsekai-3.5-9b-drive-v6-q4km.gguf"}. */
    public static String current() {
        return signature;
    }

    /**
     * Resolve a model file's RELEASE version from the node's models-manifest.jsonl
     * (written by `wyrd setup` / `wyrd model verify|update`). Filenames alone are
     * version-blind — the same name can hold V6 or V7 bytes across releases — so the
     * manifest lookup is what makes {@code authoring_model} release-accurate.
     * Returns the bare filename when no manifest entry exists (honest degradation).
     */
    public static String withVersion(Path modelsDir, String fileName) {
        if (fileName == null) return "unknown";
        try {
            var mf = modelsDir.resolve("models-manifest.jsonl");
            if (!Files.isReadable(mf)) return fileName;
            String version = null;
            for (var line : Files.readAllLines(mf)) {
                if (line.contains("\"file\":\"" + fileName + "\"")) {
                    var m = Pattern.compile("\"version\":\"([^\"]*)\"").matcher(line);
                    if (m.find()) version = m.group(1);   // last entry wins
                }
            }
            return (version == null || version.isBlank() || "unknown".equals(version))
                ? fileName : fileName + "@" + version;
        } catch (Exception e) {
            return fileName;
        }
    }
}
