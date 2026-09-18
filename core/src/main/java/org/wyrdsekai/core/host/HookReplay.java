package org.wyrdsekai.core.host;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A candidate rule set tried against what her hands have actually done. The history is the
 * world the rule is dreamed in: deciding is a pure function of an event, so the answer is
 * exact, and nothing of hers is touched to get it. A rule that would have cut something in
 * her history is a rule that would cut her doing her ordinary work.
 */
public final class HookReplay {

    private HookReplay() {}

    /** What a candidate would have done over the history. */
    public record Result(long events, int distinct, Duration span, long allowed, long recorded, List<HookHistory.Row> wouldCut) {
        public long wouldCutEvents() { return wouldCut.stream().mapToLong(HookHistory.Row::count).sum(); }
        public boolean clean() { return wouldCut.isEmpty(); }

        public Map<String, Object> asMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("events", events);
            m.put("distinct", distinct);
            m.put("spanHours", Math.round(span.toMinutes() / 6.0) / 10.0);
            m.put("allowed", allowed);
            m.put("recorded", recorded);
            m.put("wouldCutEvents", wouldCutEvents());
            var cuts = new ArrayList<Map<String, Object>>();
            for (var r : wouldCut.stream().limit(50).toList()) {
                var c = new LinkedHashMap<String, Object>();
                c.put("kind", r.kind()); c.put("uid", r.uid()); c.put("comm", r.comm()); c.put("arg", r.arg());
                c.put("count", r.count()); c.put("last", r.last().toString());
                cuts.add(c);
            }
            m.put("wouldCut", cuts);
            return m;
        }
    }

    public static Result replay(HookRules candidate, HookHistory history, String dataDir, Function<Integer, String> slugOfUid) {
        long allowed = 0, recorded = 0;
        var cut = new ArrayList<HookHistory.Row>();
        for (var r : history.rows()) {
            switch (candidate.decide(r.asEvent(), dataDir, slugOfUid)) {
                case CUT -> cut.add(r);
                case RECORD -> recorded += r.count();
                case ALLOW -> allowed += r.count();
            }
        }
        return new Result(history.events(), history.distinct(), history.span(), allowed, recorded, cut);
    }
}
