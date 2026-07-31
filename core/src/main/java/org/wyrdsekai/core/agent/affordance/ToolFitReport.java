package org.wyrdsekai.core.agent.affordance;

import java.util.ArrayList;
import java.util.List;

/**
 * pure analysis of the affordance log. The fit metric is
 * the affordance analogue of the agent-fraction provenance report: does the tool the
 * agent <i>wanted</i> actually get surfaced, and does it get <i>picked</i>?
 *
 * <p>A {@code Mismatch} is the fuel for {@code tune-tool-affordance}: a pass where a
 * want named verb V but V wasn't in the surfaced set (surfacing miss), or V was
 * surfaced but a different tool was emitted (selection miss). The first is fixable by
 * raising V's need-coupling; both are bounded-nudged by {@link ToolAffordanceTuner}.</p>
 */
public final class ToolFitReport {

    private ToolFitReport() {}

    /** wantVerb that didn't win, the need that was dominant, and how it lost. */
    public record Mismatch(String wantVerb, String dominantNeed, boolean surfaced, String emitted) {}

    public record Fit(int passesWithWant, int surfacedWanted, int emittedWanted,
                      double surfacedFraction, double emittedFraction, List<Mismatch> mismatches) {}

    public static Fit compute(List<ToolAffordanceLog.Row> rows) {
        int withWant = 0, surfaced = 0, emitted = 0;
        var miss = new ArrayList<Mismatch>();
        if (rows != null) {
            for (var r : rows) {
                if (r.wantVerb() == null || r.wantVerb().isBlank()) continue;
                withWant++;
                boolean wasSurfaced = r.surfaced() != null && r.surfaced().contains(r.wantVerb());
                boolean wasEmitted = r.wantVerb().equals(r.emittedTool());
                if (wasSurfaced) surfaced++;
                if (wasEmitted) emitted++;
                if (!wasSurfaced || (r.emittedTool() != null && !wasEmitted)) {
                    miss.add(new Mismatch(r.wantVerb(), r.dominantNeed(), wasSurfaced, r.emittedTool()));
                }
            }
        }
        double sf = withWant == 0 ? 1.0 : (double) surfaced / withWant;
        double ef = withWant == 0 ? 1.0 : (double) emitted / withWant;
        return new Fit(withWant, surfaced, emitted, sf, ef, miss);
    }
}
