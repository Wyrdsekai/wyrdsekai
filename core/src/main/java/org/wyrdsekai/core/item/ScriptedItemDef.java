package org.wyrdsekai.core.item;

import org.wyrdsekai.scripting.api.ItemManifest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * (Phase A0) — file-loaded scripted item.
 *
 * <p>Distinct from {@link ToolItem}: a {@link ScriptedItemDef} carries the
 * parsed manifest + source path so the runtime can re-evaluate provenance
 * (sigverify, allowlist enforcement) per-call, plus map back to a
 * {@code ToolItem} for inventory rendering.</p>
 */
public record ScriptedItemDef(
    String itemId,
    String displayName,
    String description,
    ItemManifest manifest,
    String scriptSource,
    Path sourcePath
) {

    /** Convert to a {@link ToolItem} with the same identity + script body. */
    public ToolItem toToolItem() {
        var params = inferParams();
        var item = ToolItem.scripted(itemId, displayName, description, scriptSource, params,
            manifest != null ? manifest.author() : "did:wyrd:disk");
        // carry the script's declared embodiment through
        // (second-node audit 2026-07-11: all 55 items declared one, this conversion
        // dropped it, and attachEmbodiment WARNed for every one at boot).
        if (manifest != null && manifest.embodiment() != null) {
            item = item.withEmbodiment(manifest.embodiment());
        }
        return item;
    }

    /**
     * The parameter schema the model is shown for this item.
     *
     * <p>Prefers the manifest's declared {@code params} — the typed slots the script's
     * {@code invoke(params)} actually reads, each with a description written for the
     * model and a real {@code required} flag.</p>
     *
     * <p>Falls back to the historical single free-form {@code query} slot when a script
     * declares nothing. That fallback used to be the ONLY schema any of the 55 scripted
     * items got, which meant the model was never told what a tool wanted and had to
     * guess. It guessed empty for {@code morning_briefing} — whose script hard-requires
     * an {@code address} — so the weather tool failed every single call, and the
     * companion reported "no weather data" rather than "I called my tool wrong". An
     * undeclared tool is a tool the model cannot use; declare {@code params}.</p>
     */
    private List<ToolItem.ToolParam> inferParams() {
        if (manifest != null && manifest.params() != null && !manifest.params().isEmpty()) {
            return manifest.params().stream()
                .map(p -> new ToolItem.ToolParam(
                    p.name(),
                    p.type() == null || p.type().isBlank() ? "string" : p.type(),
                    p.description(),
                    p.required(),
                    null))
                .toList();
        }
        var fromCommands = paramsFromCommands();
        if (fromCommands != null) return fromCommands;
        return List.of(
            new ToolItem.ToolParam("query", "string",
                "Free-form parameter forwarded to the item's invoke(params) function",
                false, null));
    }

    /**
     * Build the schema from the sub-commands the manifest ALREADY declares.
     *
     * <p>33 shipped items read {@code params.args} (via {@code args || text || target})
     * and declare no {@code params} schema — so the dispatcher handed them only
     * {@code query}, which they never read. Every model-driven call therefore arrived
     * with an empty argument and the item fell back to its default view. Not a crash:
     * just the least useful thing it could do, every single time, invisibly. The player
     * path worked fine ({@code use:item|args} supplies {@code args}), so a companion was
     * strictly worse at using its own furniture than the human was.</p>
     *
     * <p>The fix needs no new authoring, because the information already exists: each
     * manifest lists its sub-verbs as {@code commands: [{label, args}]} — "history" →
     * "Decided history", "security" → "Security ledger". That IS the schema; it was just
     * never shown to the model. Turn it into an {@code args} parameter whose description
     * enumerates the options, and 33 items become callable without touching one script.</p>
     *
     * <p>Optional by design: an empty {@code args} is a legitimate default ("Read the
     * docket"), so this does not need the required-anchor that {@code calculator} and
     * {@code trip_planner} do. Returns {@code null} when the item declares no sub-verbs
     * worth naming, leaving the free-form {@code query} fallback in place.</p>
     */
    private List<ToolItem.ToolParam> paramsFromCommands() {
        if (manifest == null || manifest.commands() == null || manifest.commands().isEmpty()) {
            return null;
        }
        var options = new ArrayList<String>();
        String defaultLabel = null;
        for (var c : manifest.commands()) {
            if (c == null || c.label() == null || c.label().isBlank()) continue;
            var args = c.args() == null ? "" : c.args().strip();
            if (args.isEmpty()) {
                if (defaultLabel == null) defaultLabel = c.label().strip();
            } else {
                options.add("\"" + args + "\" — " + c.label().strip());
            }
        }
        if (options.isEmpty()) return null;   // nothing to choose between; keep `query`

        var desc = new StringBuilder("What to do with this item. ");
        if (defaultLabel != null) {
            desc.append("Leave empty for: ").append(defaultLabel).append(". ");
        }
        desc.append("Options: ").append(String.join("; ", options)).append('.');

        return List.of(new ToolItem.ToolParam(
            "args", "string", desc.toString(), false, null));
    }

    /** Diagnostics: render the manifest as a small map for log lines. */
    public Map<String, Object> manifestSnapshot() {
        if (manifest == null) return Map.of();
        return Map.of(
            "name", manifest.name(),
            "version", manifest.version(),
            "capabilities", manifest.capabilities(),
            "sensitivity", manifest.dataSensitivity());
    }
}
