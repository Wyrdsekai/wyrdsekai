package org.wyrdsekai.core.item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every shipped item must be callable BY THE MODEL, not just by a human typing
 * {@code use:thing|args}.
 *
 * <p>The audit that produced this test found that of 55 disk-loaded items, exactly 4
 * declared a parameter schema. The rest were handed a single free-form {@code query}
 * slot — and 33 of them do not even READ {@code query}; they read
 * {@code args || text || target}. So every model-driven call arrived with an empty
 * argument and the item quietly fell back to its default view. It never crashed, which
 * is precisely why nobody noticed: a companion was strictly worse at using its own
 * furniture than the human standing next to it.</p>
 *
 * <p>These tests assert the two properties that make an item usable by a companion:
 * the model is TOLD what the item wants, and the runtime supplies what the model
 * cannot possibly know (its own identity).</p>
 */
class EveryItemIsModelCallableTest {

    private static final Path ITEMS = Path.of("../scripts/items");

    /** params.foo — what invoke() actually reaches for. */
    private static final Pattern READS = Pattern.compile("params\\s*\\.\\s*([a-zA-Z_]\\w*)");
    /**
     * What the dispatcher actually INJECTS — nothing else arrives unless the model names
     * it, which means unless the schema tells the model it exists.
     *
     * <p>This list is short on purpose, and getting it wrong hid a real bug: an earlier
     * version also listed {@code text}, {@code topic}, {@code template} and {@code args},
     * because the dispatcher mentions them. It only CHECKS for those (to decide whether
     * to add {@code query}); it never supplies them. That false generosity passed
     * {@code nostr_quill} — which reads {@code content || text || message} and declares
     * no schema — as "reachable" when in truth the model has no way to give it anything.</p>
     */
    private static final List<String> DISPATCHER_SUPPLIES =
        List.of("query", "agentDid", "targetDid");

    private static ScriptedItemDef defOf(Path js) throws IOException {
        var src = Files.readString(js);
        var commands = new ArrayList<ItemManifest.Command>();
        var m = Pattern.compile("commands:\\s*\\[(.*?)\\n\\s*\\]", Pattern.DOTALL).matcher(src);
        if (m.find()) {
            var c = Pattern.compile("\\{\\s*label:\\s*\"([^\"]*)\"\\s*,\\s*args:\\s*\"([^\"]*)\"")
                .matcher(m.group(1));
            while (c.find()) commands.add(new ItemManifest.Command(c.group(1), c.group(2)));
        }
        var params = new ArrayList<ItemManifest.Param>();
        var pm = Pattern.compile("params:\\s*\\[(.*?)\\n\\s*\\]", Pattern.DOTALL).matcher(src);
        if (pm.find()) {
            var p = Pattern.compile(
                "name:\\s*\"(\\w+)\"[^}]*?required:\\s*(true|false)", Pattern.DOTALL)
                .matcher(pm.group(1));
            while (p.find()) {
                params.add(new ItemManifest.Param(
                    p.group(1), "string", "declared", Boolean.parseBoolean(p.group(2))));
            }
        }
        var manifest = new ItemManifest(js.getFileName().toString(), "1.0", "d", "a",
            List.of(), Map.of(), "low", List.of(), List.of(), List.of(), List.of(),
            null, null, null, "1.0", null, commands, null, params);
        return new ScriptedItemDef(
            js.getFileName().toString().replace(".js", ""),
            js.getFileName().toString(), "d", manifest, "function invoke(p){}", js);
    }

    @Test
    @DisplayName("every item's schema names a parameter the item actually reads")
    void everyItemIsReachable() throws IOException {
        var unreachable = new ArrayList<String>();
        try (Stream<Path> files = Files.list(ITEMS)) {
            for (var js : files.filter(p -> p.toString().endsWith(".js")).sorted().toList()) {
                var src = Files.readString(js);
                var body = src.contains("function invoke")
                    ? src.substring(src.indexOf("function invoke")) : src;

                var reads = new ArrayList<String>();
                Matcher r = READS.matcher(body);
                while (r.find()) reads.add(r.group(1));
                if (reads.isEmpty()) continue;              // takes no arguments at all

                // What the model is offered, plus what the dispatcher injects for it.
                var offered = new ArrayList<>(DISPATCHER_SUPPLIES);
                defOf(js).toToolItem().params().forEach(p -> offered.add(p.name()));

                if (reads.stream().noneMatch(offered::contains)) {
                    unreachable.add(js.getFileName() + " reads " + reads);
                }
            }
        }
        assertTrue(unreachable.isEmpty(),
            "these items read parameters that neither the model is told about nor the "
                + "dispatcher supplies — so no companion can ever drive them: " + unreachable);
    }

    @Test
    @DisplayName("an item's declared sub-commands become a schema the model can see")
    void subCommandsBecomeASchema() throws IOException {
        // agenda_board declares commands but no params. Its sub-verbs ("history",
        // "tally <proposalId>") were invisible to the model, which could therefore only
        // ever get the default docket view.
        var params = defOf(ITEMS.resolve("agenda_board.js")).toToolItem().params();
        assertEquals(1, params.size());
        assertEquals("args", params.getFirst().name(),
            "the item reads params.args — the schema must offer THAT, not `query`");

        var desc = params.getFirst().description();
        assertTrue(desc.contains("history"), "the model must be told the sub-verbs: " + desc);
        assertTrue(desc.contains("Decided history"),
            "and what they mean, from the manifest's own labels: " + desc);
        assertFalse(params.getFirst().required(),
            "empty args is a legitimate default (Read the docket) — this is not an anchor");
    }

    @Test
    @DisplayName("an explicit params: schema still wins over the derived one")
    void explicitSchemaWins() throws IOException {
        var params = defOf(ITEMS.resolve("morning_briefing.js")).toToolItem().params();
        assertTrue(params.stream().anyMatch(p -> p.name().equals("address") && p.required()),
            "a hand-authored schema must not be overridden by the commands-derived one");
    }
}
