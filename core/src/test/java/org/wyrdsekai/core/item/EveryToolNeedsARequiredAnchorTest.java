package org.wyrdsekai.core.item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An optional parameter is a parameter the model will not fill.
 *
 * <p>second-node 2026-07-13 ran the controlled experiment for us — same build, same model, same
 * turn. {@code morning_briefing} declares {@code address} as required, and the model
 * filled it: {@code {address=San Francisco, CA 94102, day=tomorrow}}. The calculator
 * declared every parameter optional, and the model called it with NOTHING —
 * {@code {"action":"calculator"}} — so it fell through to a tally over an empty list and
 * had to refuse. {@code trip_planner} was equally unanchored and merely got lucky: the
 * model volunteered a destination unprompted.</p>
 *
 * <p>So: a tool that declares a schema must declare at least one REQUIRED parameter —
 * the thing it cannot do its job without. A schema of all-optional fields tells the model
 * "call me with nothing", and it will.</p>
 */
class EveryToolNeedsARequiredAnchorTest {

    private static final Path ITEMS = Path.of("../scripts/items");

    /** The `params: [ ... ]` block of a manifest, if it declares one. */
    private static final Pattern PARAMS_BLOCK =
        Pattern.compile("params:\\s*\\[(.*?)\\n\\s*\\]", Pattern.DOTALL);
    private static final Pattern REQUIRED_TRUE =
        Pattern.compile("required:\\s*true");

    /**
     * An item HARD-REQUIRES a parameter when it refuses to work without one — an
     * {@code ok:false} return whose message says as much ("address is required", "I need
     * something to calculate"). Items with a legitimate no-argument default (bond_chapel
     * inspects, expense_summary summarizes the last 30 days) are NOT in this class, and
     * an all-optional schema is right for them: forcing a fake anchor onto a tool that
     * works fine without one would just make the model invent an argument.
     */
    private static final Pattern HARD_REQUIRES = Pattern.compile(
        "ok:\\s*false[^}]{0,220}?(is required|are required|I need|needs a)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Test
    @DisplayName("an item that cannot work without a parameter marks that parameter required")
    void toolsThatCannotWorkWithoutAParamAnchorOnIt() throws IOException {
        var unanchored = new ArrayList<String>();
        try (Stream<Path> files = Files.list(ITEMS)) {
            for (var f : files.filter(p -> p.toString().endsWith(".js")).toList()) {
                var src = Files.readString(f);
                var m = PARAMS_BLOCK.matcher(src);
                if (!m.find()) continue;                      // no schema of its own
                if (!HARD_REQUIRES.matcher(src).find()) continue;   // works with no args
                if (!REQUIRED_TRUE.matcher(m.group(1)).find()) {
                    unanchored.add(f.getFileName().toString());
                }
            }
        }
        assertTrue(unanchored.isEmpty(),
            "these items REFUSE to work without a parameter, yet declare every parameter "
                + "optional — so the model is free to call them with nothing, and it will. "
                + "That is exactly what it did to the calculator: {\"action\":\"calculator\"}, "
                + "no arguments at all. " + unanchored
                + " — mark the parameter the tool cannot work without as required:true.");
    }

    @Test
    @DisplayName("the calculator anchors on `expression` — the parameter it was called without")
    void calculatorAnchorsOnExpression() throws IOException {
        var src = Files.readString(ITEMS.resolve("calculator.js")).replaceAll("\\s+", " ");
        assertTrue(src.matches("(?s).*name: \"expression\".*required: true.*"),
            "the model called the calculator with {\"action\":\"calculator\"} and no "
                + "arguments, because nothing in its schema was required");
    }

    @Test
    @DisplayName("trip_planner anchors on `destination` — it cannot geocode nothing")
    void tripPlannerAnchorsOnDestination() throws IOException {
        var src = Files.readString(ITEMS.resolve("trip_planner.js")).replaceAll("\\s+", " ");
        assertTrue(src.matches("(?s).*name: \"destination\".*required: true.*"),
            "trip_planner had four optional params and no anchor; it only worked because "
                + "the model volunteered a destination on its own");
    }
}
