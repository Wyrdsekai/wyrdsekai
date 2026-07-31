package org.wyrdsekai.core.codemode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Track A Phase 2b — extract a free-form code-mode
 * payload from a raw LLM response.
 *
 * <p>Free-form responses interleave prose narration with one (or rarely
 * more) ```js / ```javascript fenced code blocks:
 *
 * <pre>
 * "Let me check both the library and the searching glass at once…"
 * ```js
 * const a = library_card.search("mythology");
 * const b = searching_glass.search("mythology");
 * console.log(`primary=${a.length} secondary=${b.length}`);
 * ```
 * "I found seven sources — three look most relevant."
 * </pre>
 *
 * <p>Phase 2b extracts the <em>first</em> ```js block (treated as the script)
 * plus the surrounding narration (everything outside the block, joined with a
 * single space). Subsequent ```js blocks are surfaced via
 * {@link Extracted#extraBlocks()} so the dispatcher can warn-log them — Phase
 * 2b runs only the first.
 *
 * <p>This parser is intentionally narrower than {@link
 * org.wyrdsekai.core.agent.ActionParser} — it knows about JS-fenced blocks
 * specifically, not the full action-JSON corpus. The dispatch contract per
 * spec §A4 is: <em>free-form parser runs first; if it finds a ```js block,
 * the runtime takes the code-mode path</em>. Otherwise the response flows
 * through the existing JSON-action parser unchanged.
 *
 * <p>Stateless and side-effect free.
 */
public final class FreeFormCodeModeParser {

    private FreeFormCodeModeParser() {}

    /**
     * Result of a free-form parse attempt.
     *
     * @param hasScript     true if a ```js / ```javascript block was found
     * @param script        the source of the first ```js block (null when
     *                       {@code hasScript == false})
     * @param narration     prose surrounding the first block, joined into a
     *                       single string (empty when none / null when no
     *                       script was found)
     * @param extraBlocks   any additional ```js blocks beyond the first;
     *                       Phase 2b runs only the first, dispatcher warns
     *                       on extras
     */
    public record Extracted(
            boolean hasScript,
            String script,
            String narration,
            List<String> extraBlocks) {

        public static Extracted miss() {
            return new Extracted(false, null, null, List.of());
        }
    }

    // Match ```js or ```javascript fenced blocks. The body is a non-greedy
    // capture (.*?) with DOTALL so newlines are part of the body.
    //
    // Tolerates:
    //   ```js\n...\n```
    //   ```javascript\n...\n```
    //   ```JS ... ``` (case-insensitive lang tag)
    //   trailing whitespace before/after the closing fence
    //
    // Rejects (intentionally):
    //   ``` ... ``` with no language hint — too easy to confuse with action-JSON
    //                blocks, which are already handled by ActionParser. Spec §A4
    //                said the JS-block extractor runs first; we want a positive
    //                language tag to avoid stealing action-JSON traffic.
    private static final Pattern JS_BLOCK = Pattern.compile(
        "```(?:js|javascript)\\s*\\n([\\s\\S]*?)\\n?```",
        Pattern.CASE_INSENSITIVE);

    /**
     * Run the free-form parse. {@code null} or blank input returns a miss.
     */
    public static Extracted parse(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) return Extracted.miss();

        var matcher = JS_BLOCK.matcher(llmOutput);
        if (!matcher.find()) return Extracted.miss();

        // First match: the script that runs.
        var firstStart = matcher.start();
        var firstEnd = matcher.end();
        var script = matcher.group(1);
        if (script == null) script = "";
        script = script.strip();

        // Subsequent matches → extras (warn-log; not executed by Phase 2b).
        var extras = new ArrayList<String>();
        while (matcher.find()) {
            var extra = matcher.group(1);
            if (extra != null) {
                extra = extra.strip();
                if (!extra.isEmpty()) extras.add(extra);
            }
        }

        // Narration: everything outside the first block, lightly normalised.
        // We keep prose only relative to the first block — extras become noise
        // for the dispatcher and we don't want their commentary leaking back
        // into the speech path.
        var before = firstStart > 0 ? llmOutput.substring(0, firstStart) : "";
        var after = firstEnd < llmOutput.length() ? llmOutput.substring(firstEnd) : "";
        // If extras existed in the tail, scrub them out of the narration so
        // their JS source doesn't get spoken as prose. We only need to strip
        // the leading run of fenced blocks — anything else is genuine prose.
        if (!extras.isEmpty()) {
            after = JS_BLOCK.matcher(after).replaceAll("");
        }
        var narration = (before + " " + after)
            .replaceAll("\\s+", " ")
            .strip();

        return new Extracted(true, script, narration, List.copyOf(extras));
    }

    /**
     * Convenience: a script is "extractable" iff a parse succeeds AND the
     * captured script is non-empty. Used by callers that just want to know
     * whether to take the code-mode path.
     */
    public static boolean hasJavaScriptBlock(String llmOutput) {
        var parsed = parse(llmOutput);
        return parsed.hasScript() && parsed.script() != null && !parsed.script().isBlank();
    }

    // The Pattern field above is package-private via reflection in tests, not
    // exposed publicly — the parse() entrypoint is the contract.
    @SuppressWarnings("unused")
    static Matcher debugMatcher(String input) {
        return JS_BLOCK.matcher(input);
    }
}
