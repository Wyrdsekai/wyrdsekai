package org.wyrdsekai.core.coding;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Result of {@code examine} on a codex/artifact — the file list + a
 * preview of each file's contents.
 * §3.3. Used by {@link CodingNamespaceHandler#handleExamine} when a
 * caller wants more than the cached metadata stub.
 *
 * @param artifactId        the artifact this describes
 * @param backend           backend that produced the artifact
 * @param workspacePath     on-host directory (may be null)
 * @param files             relative paths inside the workspace
 * @param filePreviews      first ~4 KB of each file (key = relative
 *                          path, value = content); files exceeding the
 *                          cap end with a "[…truncated…]" marker
 * @param notes             optional human-readable summary lines
 *                          (e.g. "git ref abc1234", "stale by 2d")
 * @param unsupportedReason null when the backend examined the
 *                          artifact; otherwise human-readable "why not"
 */
public record ExamineResult(
        UUID artifactId,
        String backend,
        String workspacePath,
        List<String> files,
        Map<String, String> filePreviews,
        List<String> notes,
        String unsupportedReason) {

    public ExamineResult {
        if (files == null) files = List.of();
        if (filePreviews == null) filePreviews = Map.of();
        if (notes == null) notes = List.of();
    }

    public static ExamineResult unsupported(String backendName) {
        return new ExamineResult(null, backendName, null,
            List.of(), Map.of(), List.of(),
            backendName + " does not support 'examine' yet");
    }

    public static ExamineResult notFound(String backendName, String artifactId) {
        return new ExamineResult(null, backendName, null,
            List.of(), Map.of(), List.of(),
            backendName + ": no artifact found for id " + artifactId);
    }

    public boolean isUnsupported() { return unsupportedReason != null; }
}
