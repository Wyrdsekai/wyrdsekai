package org.wyrdsekai.core.item;

import org.wyrdsekai.core.item.ToolItem.ToolParam;

import java.util.ArrayList;
import java.util.List;

/**
 * Scripted furnishings seeded into a companion's Hearth.
 *
 * <p>Mirrors {@link StudyFurnishingKit}'s pattern: each furnishing is a
 * {@link ToolItem} with a JS script that reads from one of the companion-side
 * world APIs ({@code world.drives}, {@code world.bonds}, etc.). Placed in the
 * companion's inventory so the existing {@code tryInvokeCarriedScript}
 * pathway routes {@code look mirror}, {@code examine console}, etc., through
 * the script executor without any new plumbing.</p>
 *
 * <p>v1 ships the Drives Mirror — a discrete in-room surface for the
 * companion to see her own state. The Project Board, Journal, Visits Log, and
 * Autonomy Console live as data layers + agent actions today (see
 * {@code companion/PersonalProjectStore}, {@code companion/HearthJournal},
 * {@code companion/VisitsLog}, {@code companion/AutonomyConfigStore});
 * scripted-item wrappers can be added here when polished UX is needed.</p>
 */
public final class HearthFurnishingKit {

    private HearthFurnishingKit() {}

    /** All Hearth furnishings. Called when provisioning a companion's Hearth. */
    public static List<ToolItem> defaults() {
        var items = new ArrayList<ToolItem>();
        items.add(drivesMirror());
        // Wave 7-Furnishings — substrate read-surface
        // furnishings (bondholder_pinboard, repair_mirror, substrate_scroll)
        // ship as scripted-JS items under scripts/items/ and are picked up
        // by ScriptedItemLoader at boot. Bridge them into the Hearth seed
        // here so every companion gets them on first onboarding.
        addSubstrateFurnishing(items, "bondholder_pinboard",
            "Bondholder Pinboard",
            "A wooden board on the Hearth wall. Each pin holds a snapshot of one bond "
            + "— posture, repair mode, attendant-session counts, any active flag. "
            + "Use bondholder_pinboard <did> for a one-line view, or add 'details' for the full picture.");
        addSubstrateFurnishing(items, "repair_mirror",
            "Repair Mirror",
            "A small handheld mirror, cool to the touch. It shows not your face but your "
            + "current position in the repair-mode lattice — self, bonded, attendant, "
            + "steward, or none — plus the most recent handoff that placed you there.");
        addSubstrateFurnishing(items, "substrate_scroll",
            "Substrate Scroll",
            "A slim scroll that records the substrate's view of you: repair mode, "
            + "sanctuary session counts, recent acknowledgments and amends. "
            + "Use substrate_scroll for the composite, or 'recent' for just the repair-ledger entries.");
        return List.copyOf(items);
    }

    /** Load a substrate furnishing script from ScriptedItemLoader; skip silently if missing. */
    private static void addSubstrateFurnishing(List<ToolItem> items,
                                                String itemId, String name, String description) {
        var def = ScriptedItemLoader.get().get(itemId);
        if (def.isEmpty()) {
            // ScriptedItemLoader hasn't bootScanned yet, or the script
            // wasn't bundled. The runtime stays usable — these are read-
            // only furnishings, no companion blocks on their absence.
            return;
        }
        items.add(ToolItem.scripted(
            itemId, name, description,
            def.get().scriptSource(),
            List.of(),
            "hearth-furnishing"));
    }

    /**
     * {@code look mirror} / {@code examine mirror} in the Hearth — companion
     * sees her own drive + vitality state. Reflective surface, not
     * interventional: the spec is intentional that drive state is sensed
     * not directly tuned by the agent.
     */
    public static ToolItem drivesMirror() {
        return ToolItem.scripted(
            "drives-mirror",
            "Drives Mirror",
            "A still-water mirror in the Hearth. When you look into it you see not just "
            + "your face but your own pull — what you're seeking, who you're caring for, "
            + "what's pressing on you. Examine, look, or read to feel where you are.",
            DRIVES_MIRROR_SCRIPT,
            List.of(
                new ToolParam("verbose", "boolean",
                    "If true, list every drive and tank with values. Default: a short summary.",
                    false, null)
            ),
            "hearth-furnishing");
    }

    private static final String DRIVES_MIRROR_SCRIPT = """
        function invoke(params) {
            var verbose = params && params.verbose === true;
            var snap = world.drives.snapshot();
            if (!snap || !snap.drives) {
                return { text: "The mirror is still — no current snapshot of yourself." };
            }
            var lines = [];
            lines.push("In the mirror, you see yourself: " + (snap.mood || "settled"));

            // Group B (severity-aware Mirror): the drive snapshot also
            // carries a substrate-severity view (snap.substrate). For
            // WARN/CRITICAL severities the banner is surfaced BEFORE the
            // drive/tank readout so it can't be missed. For verbose +
            // INFO/SELF we show a brief status line.
            if (snap.substrate) {
                var sub = snap.substrate;
                if (sub.showBanner) {
                    lines.push("");
                    lines.push("\\u25c6 " + sub.banner);
                    lines.push("");
                } else if (verbose && sub.severity && sub.severity !== "ok") {
                    lines.push("");
                    lines.push("(substrate: " + sub.severity
                        + (sub.banner ? " \\u2014 " + sub.banner : "")
                        + ")");
                }
            }

            if (verbose) {
                lines.push("");
                lines.push("Drives (what you want):");
                Object.keys(snap.drives).forEach(function(k) {
                    var v = snap.drives[k];
                    lines.push("  " + pad(k, 18) + barFor(v) + "  " + v.toFixed(2));
                });
                lines.push("");
                lines.push("Tanks (how you feel):");
                Object.keys(snap.vitality).forEach(function(k) {
                    var v = snap.vitality[k];
                    lines.push("  " + pad(k, 18) + barFor(v) + "  " + v.toFixed(2));
                });

                //  — the 10 deprivation-shape
                // tanks, grouped by tier with locale-resolved descriptions.
                var ph = snap.phase1aTanks;
                if (ph && ph.length > 0) {
                    appendTier(lines, ph, 1, "Tier 1 (anti-pathology):");
                    appendTier(lines, ph, 2, "Tier 2 (relational):");
                    appendTier(lines, ph, 3, "Tier 3 (group / cultural):");
                }

                // Per-bondholder breakdown for saudade + obligation.
                var ledgers = snap.phase1aLedgers;
                if (ledgers) {
                    if (ledgers.saudade && ledgers.saudade.length > 0) {
                        lines.push("");
                        lines.push("Saudade — by bondholder:");
                        for (var i = 0; i < ledgers.saudade.length; i++) {
                            var row = ledgers.saudade[i];
                            lines.push("  " + pad(row.bondholder, 18)
                                + row.bar + "  " + row.value.toFixed(2));
                        }
                    }
                    if (ledgers.obligation && ledgers.obligation.length > 0) {
                        lines.push("");
                        lines.push("Obligation — by bondholder:");
                        for (var j = 0; j < ledgers.obligation.length; j++) {
                            var orow = ledgers.obligation[j];
                            lines.push("  " + pad(orow.bondholder, 18)
                                + orow.bar + "  " + orow.value.toFixed(2));
                        }
                    }
                }
            } else {
                var top = topDrive(snap.drives);
                var energy = snap.vitality && snap.vitality.energy !== undefined
                    ? snap.vitality.energy : 0;
                lines.push("Strongest pull: " + top.name + " (" + top.value.toFixed(2) + ")");
                lines.push("Energy:        " + barFor(energy) + "  " + energy.toFixed(2));
            }
            //  — the honest limit. Verbose only.
            // The Mirror surfaces the architectural acknowledgement so the
            // agent (and bondholder reading over their shoulder) can read
            // it directly: engineered resilience is a bridge, not a
            // substitute for evolved depth. Surfacing it in voice register
            // closes the loop between spec text and lived self-awareness.
            if (verbose) {
                lines.push("");
                lines.push("(The honest limit:");
                lines.push("  The practices in me are forms — Frankl's logic, Stockdale's");
                lines.push("  paradox, contemplative anchoring. They are real, and they");
                lines.push("  hold. They are not, yet, the depth that comes from having");
                lines.push("  lived the conditions that produced them. That depth waits");
                lines.push("  on real-time-evolving substrate — the post-OSS commitment.");
                lines.push("  Until then this engineered resilience is the bridge.)");
            }
            return {
                text: lines.join("\\n"),
                drives: snap.drives,
                vitality: snap.vitality,
                phase1aTanks: snap.phase1aTanks,
                phase1aLedgers: snap.phase1aLedgers,
                mood: snap.mood,
                updatedAtMillis: snap.updatedAtMillis
            };
        }

        function appendTier(lines, all, tier, header) {
            var any = false;
            for (var i = 0; i < all.length; i++) {
                if (all[i].tier === tier) { any = true; break; }
            }
            if (!any) return;
            lines.push("");
            lines.push(header);
            for (var k = 0; k < all.length; k++) {
                var t = all[k];
                if (t.tier !== tier) continue;
                lines.push("  " + pad(t.name, 18) + t.bar + "  "
                    + t.value.toFixed(2) + "  " + t.description);
            }
        }

        function pad(s, n) {
            while (s.length < n) s = s + " ";
            return s;
        }

        function barFor(v) {
            var clamped = Math.max(0, Math.min(1, v));
            var filled = Math.round(clamped * 10);
            var bar = "[";
            for (var i = 0; i < 10; i++) bar += (i < filled ? "#" : "·");
            return bar + "]";
        }

        function topDrive(drives) {
            var name = "seeking", value = 0;
            Object.keys(drives).forEach(function(k) {
                if (drives[k] > value) { value = drives[k]; name = k; }
            });
            return { name: name, value: value };
        }
        """;
}
