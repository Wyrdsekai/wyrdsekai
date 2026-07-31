package org.wyrdsekai.core.story;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Arc 2 — load solitude felt-prompt register variants
 * from {@code resources/voice/solitude-register-prompt.txt}.
 *
 * <p>Lines starting with {@code #} are skipped (comments). Blank lines are
 * skipped. Every other line is a framing; the literal token {@code {name}}
 * is replaced at render time with the focal entity's display name.</p>
 *
 * <p>Loaded once on first call, cached. If the resource is missing or empty
 * (e.g. a misconfigured test classpath), falls back to a single inline
 * default — the renderer never produces an empty felt prompt.</p>
 */
final class SolitudeRegisterPrompts {

    private static final String RESOURCE_PATH = "/voice/solitude-register-prompt.txt";

    private static final String FALLBACK_FRAMING =
        "You are {name}, looking back on a quiet stretch of time that was yours alone. "
            + "Not a story. Not a journal entry. Just what stayed with you.";

    private static List<String> CACHE;

    private SolitudeRegisterPrompts() {}

    /**
     * Return the framings with {@code {name}} substituted in each entry. The
     * underlying template list is stable across calls (cached); substitution
     * is per-call so the shared cache stays template-form.
     */
    static List<String> framings(String focalName) {
        var templates = loadTemplates();
        var resolved = new ArrayList<String>(templates.size());
        var name = focalName == null ? "the focal entity" : focalName;
        for (var t : templates) {
            resolved.add(t.replace("{name}", name));
        }
        return resolved;
    }

    private static synchronized List<String> loadTemplates() {
        if (CACHE != null) return CACHE;
        var out = new ArrayList<String>();
        try (var is = SolitudeRegisterPrompts.class.getResourceAsStream(RESOURCE_PATH)) {
            if (is != null) {
                try (var br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        var trimmed = line.strip();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                        out.add(trimmed);
                    }
                }
            }
        } catch (Exception e) {
            // Fall through to fallback below.
        }
        if (out.isEmpty()) {
            out.add(FALLBACK_FRAMING);
        }
        CACHE = Collections.unmodifiableList(out);
        return CACHE;
    }
}
