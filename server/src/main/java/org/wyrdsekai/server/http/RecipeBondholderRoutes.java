package org.wyrdsekai.server.http;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.persistence.ConversationTurnStore;
import org.wyrdsekai.core.persistence.SubstratePressureStore;
import org.wyrdsekai.core.recipe.BondholderVoiceEligibility;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * / #1028 / #1035 — REST surface backing
 * {@code wyrd recipe bondholder-eligibility} +
 * {@code wyrd recipe bondholder-pairs}.
 *
 * <p>The {@code align-bondholder-voice} recipe's Python wrappers
 * ({@code scripts/voice/check_bondholder_eligibility.py},
 * {@code scripts/voice/build_bondholder_pairs.py}) shell to these
 * endpoints. Two endpoints:</p>
 *
 * <ul>
 *   <li>{@code POST /api/recipes/bondholder/eligibility} — runs the
 *       pure-logic {@link BondholderVoiceEligibility#check} against
 *       data assembled from the {@code bonds} +
 *       {@code bondholder_engagement} tables. Body params:
 *       {@code bondholder_did, agent_did,
 *       min_corpus_pairs, min_bond_age_days, min_distinct_sessions,
 *       required_bond_state, substrate_pressure_threshold,
 *       min_new_turns}. Returns the gate JSON shape (see
 *       {@code BondholderVoiceEligibilityTest}).</li>
 *   <li>{@code POST /api/recipes/bondholder/pairs} — emits a
 *       structured "needs_conversation_log" deny for OSS v0.1. The
 *       pair-mining heuristic needs a turn-by-turn conversation log
 *       that doesn't exist yet at the SQL layer (turns live in actor
 *       state). The endpoint emits {@code pairs_written: 0} with a
 *       v0.1 note so the recipe stops cleanly at the corpus gate.</li>
 * </ul>
 *
 * <p>OSS v0.1 honest limitations:</p>
 * <ul>
 *   <li><b>substrate_pressure_30d</b>: the 30-day rolling mean
 *       substrate-pressure aggregator isn't yet exposed via SQL —
 *       it lives in {@code SustainedSubstratePatternDetector}.
 *       The endpoint returns {@code 0.0} (welfare-permissive) by
 *       default; the welfare gate only fires when this is wired up
 *       in a post-v0.1 phase. Stewards can override via the
 *       {@code substrate_pressure_30d_override} body param for
 *       manual one-off bondholder runs.</li>
 *   <li><b>vector_age_days</b>: per-bondholder vector tracking is not
 *       yet persisted; the endpoint reports {@code null}, which makes
 *       the re-fit hygiene branch in
 *       {@link BondholderVoiceEligibility} skip cleanly (first-fit
 *       semantics). post-v0.1 will persist
 *       {@code last_bondholder_vector_fit_at} per bond.</li>
 * </ul>
 */
public final class RecipeBondholderRoutes {

    private static final Logger log =
        LoggerFactory.getLogger(RecipeBondholderRoutes.class);
    private static final String ADMIN_HEADER = "X-Wyrdsekai-Admin-Token";

    private final String jdbcUrl;

    public RecipeBondholderRoutes(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.post("/api/recipes/bondholder/eligibility", this::handleEligibility);
        app.post("/api/recipes/bondholder/pairs",       this::handlePairs);
    }

    // ── eligibility ──────────────────────────────────────────────────────

    private void handleEligibility(Context ctx) {
        if (!authorised(ctx)) return;

        String bondholderDid = ctx.queryParam("bondholder");
        String agentDid = ctx.queryParam("agent");
        if (bondholderDid == null || agentDid == null) {
            ctx.status(400).json(Map.of("error",
                "bondholder + agent query params required"));
            return;
        }

        var t = parseThresholds(ctx);
        Inputs inputs = gatherInputs(bondholderDid, agentDid, ctx);
        var decision = BondholderVoiceEligibility.check(
            new BondholderVoiceEligibility.Inputs(
                inputs.corpusPairs,
                inputs.bondAge,
                inputs.distinctSessions,
                inputs.bondState,
                inputs.substratePressure30d,
                inputs.vectorAge,
                inputs.newTurnsSinceLastFit),
            t);

        var out = new LinkedHashMap<String, Object>();
        out.put("bondholder_did", bondholderDid);
        out.put("agent_did", agentDid);
        out.put("bondholder_eligible", decision.asGateValue());
        out.put("eligibility_deny_reason",
            decision.reason() == null ? null : decision.reason().name());
        out.put("eligibility_detail", decision.detail());
        // Surface raw inputs so chronicle entries can reason about the deny.
        out.put("corpus_pairs", inputs.corpusPairs);
        out.put("bond_age_days",
            inputs.bondAge == null ? 0 : inputs.bondAge.toDays());
        out.put("distinct_sessions", inputs.distinctSessions);
        out.put("bond_state", inputs.bondState);
        out.put("substrate_pressure_30d", inputs.substratePressure30d);
        out.put("vector_age_days",
            inputs.vectorAge == null ? null : inputs.vectorAge.toDays());
        out.put("new_turns_since_last_fit", inputs.newTurnsSinceLastFit);
        ctx.json(out);
    }

    // ── pairs (#1037 real pair-mining) ────────────────────────────────────

    private void handlePairs(Context ctx) {
        if (!authorised(ctx)) return;

        String bondholderDid = ctx.queryParam("bondholder");
        String agentDid = ctx.queryParam("agent");
        String outputPath = ctx.queryParam("output");
        int maxPairs = parseIntOrDefault(ctx.queryParam("max-pairs"), 200);
        if (bondholderDid == null || agentDid == null || outputPath == null) {
            ctx.status(400).json(Map.of("error",
                "bondholder + agent + output query params required"));
            return;
        }

        // #1037 — real pair mining. Read recent bondholder HEARD turns
        // from conversation_turns, pair each with a static neutral
        // baseline (shipped as a classpath resource). The contrast
        // direction repeng learns: "talk like the bondholder, not like
        // a generic assistant." Quality scales with bondholder corpus
        // size; recipe's gate-corpus enforces the 30-pair floor.
        var cfg = WyrdConfig.get();
        int lookbackDays = cfg == null ? 90 : cfg.bondholderPairsLookbackDays();
        int minChars = cfg == null ? 10 : cfg.bondholderPairsMinTurnChars();

        var turnStore = new ConversationTurnStore(jdbcUrl);
        var turns = turnStore.recentBondholderTurns(
            agentDid, bondholderDid, lookbackDays, minChars, maxPairs);

        List<String> negatives = loadBaselineNegatives();
        int pairsWritten = 0;
        try (var w = Files.newBufferedWriter(Path.of(outputPath))) {
            if (!turns.isEmpty() && !negatives.isEmpty()) {
                for (int i = 0; i < turns.size(); i++) {
                    String pos = turns.get(i).content();
                    String neg = negatives.get(i % negatives.size());
                    w.write(emitPairLine(pos, neg));
                    w.newLine();
                    pairsWritten++;
                }
            }
        } catch (IOException e) {
            log.warn("write pairs file failed at {}: {}", outputPath, e.getMessage());
        }

        var resp = new LinkedHashMap<String, Object>();
        resp.put("bondholder_did", bondholderDid);
        resp.put("agent_did", agentDid);
        resp.put("pairs_written", pairsWritten);
        resp.put("pairs_path", outputPath);
        resp.put("max_pairs", maxPairs);
        resp.put("lookback_days", lookbackDays);
        resp.put("min_turn_chars", minChars);
        if (pairsWritten == 0) {
            resp.put("note", "no bondholder turns found in lookback window — "
                + "recipe will stop at corpus gate. Wait for more "
                + "conversation or extend lookback_days.");
        }
        ctx.json(resp);
    }

    /** Bundled neutral-baseline negatives at
     *  {@code voice/bondholder-baseline-negatives.jsonl}. One per line. */
    private static List<String> loadBaselineNegatives() {
        var out = new ArrayList<String>();
        try (var in = RecipeBondholderRoutes.class.getClassLoader()
                .getResourceAsStream("voice/bondholder-baseline-negatives.jsonl")) {
            if (in == null) return out;
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : body.split("\\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                try {
                    var node = new ObjectMapper()
                        .readTree(t);
                    if (node.has("text")) out.add(node.get("text").asText());
                } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            log.warn("loadBaselineNegatives failed: {}", e.getMessage());
        }
        return out;
    }

    private static String emitPairLine(String positive, String negative) {
        var mapper = new ObjectMapper();
        try {
            var node = mapper.createObjectNode();
            node.put("positive", positive);
            node.put("negative", negative);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            // Fallback: hand-format. Strict JSON encoding via Jackson
            // is preferred; this path only fires on jackson-config bugs.
            return "{\"positive\":\"" + positive.replace("\"", "\\\"")
                + "\",\"negative\":\"" + negative.replace("\"", "\\\"") + "\"}";
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Aggregated record of what we could collect from SQL. */
    private record Inputs(
        int corpusPairs,
        Duration bondAge,
        int distinctSessions,
        String bondState,
        double substratePressure30d,
        Duration vectorAge,
        int newTurnsSinceLastFit
    ) {}

    private Inputs gatherInputs(String bondholderDid, String agentDid, Context ctx) {
        long now = System.currentTimeMillis();
        Long formedAt = null;
        String bondState = null;
        long interactionCount = 0;
        try (Connection conn = openDb()) {
            // 1. Bond row: state + formed_at + interaction_count.
            //    Bond rows can be (agent_a=companion, agent_b=bondholder) or
            //    (agent_a=bondholder, agent_b=companion); accept either.
            try (PreparedStatement ps = conn.prepareStatement(
                "SELECT state, formed_at, interaction_count FROM bonds "
                    + "WHERE (agent_a_did=? AND agent_b_did=?) "
                    + "   OR (agent_a_did=? AND agent_b_did=?) "
                    + "ORDER BY formed_at DESC LIMIT 1")) {
                ps.setString(1, agentDid);
                ps.setString(2, bondholderDid);
                ps.setString(3, bondholderDid);
                ps.setString(4, agentDid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        bondState = rs.getString(1);
                        formedAt = rs.getLong(2);
                        interactionCount = rs.getLong(3);
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("gatherInputs bond lookup failed: {}", e.getMessage());
        }

        Duration bondAge = formedAt == null
            ? Duration.ZERO
            : Duration.ofMillis(Math.max(0, now - formedAt));

        // 2. Distinct-session count from bondholder_engagement.
        //    "Session" = consecutive TELL/LISTEN events with no gap >30min.
        //    For SQL simplicity, count distinct day-buckets — close-enough
        //    proxy for "showed up across N different days" which is what
        //    the FEW_DISTINCT_SESSIONS gate is actually trying to catch.
        int distinctSessions = 0;
        try (Connection conn = openDb();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT DISTINCT (event_ts / (24*3600*1000)) "
                     + "FROM bondholder_engagement "
                     + "WHERE companion_did=? AND bondholder_did=? "
                     + "AND event_type IN ('TELL','LISTEN')")) {
            ps.setString(1, agentDid);
            ps.setString(2, bondholderDid);
            try (ResultSet rs = ps.executeQuery()) {
                Set<Long> buckets = new HashSet<>();
                while (rs.next()) buckets.add(rs.getLong(1));
                distinctSessions = buckets.size();
            }
        } catch (SQLException e) {
            // bondholder_engagement schema not present in older installs
            log.debug("bondholder_engagement query failed: {}", e.getMessage());
        }

        // 3. Welfare: substrate_pressure_30d. #1036 lifted this from the
        //    v0.1 stub: every classifier dispatch in CompanionActor writes
        //    a sample to substrate_pressure_samples, so the rolling-mean
        //    is now real. Window + aggregation knobs come from WyrdConfig;
        //    steward can still override via query param for one-off runs.
        double substratePressure;
        String override = ctx.queryParam("substrate-pressure-30d-override");
        if (override != null && !override.isBlank()) {
            substratePressure = parseDoubleOrDefault(override, 0.0);
        } else {
            try {
                var store = new SubstratePressureStore(jdbcUrl);
                var cfg = WyrdConfig.get();
                int windowDays = cfg == null ? 30 : cfg.substratePressureWindowDays();
                String mode = cfg == null ? "mean" : cfg.substratePressureAggregation();
                substratePressure = "p95".equalsIgnoreCase(mode)
                    ? store.aggregateP95(bondholderDid, windowDays)
                    : store.aggregateMean(bondholderDid, windowDays);
            } catch (RuntimeException e) {
                log.warn("substrate pressure aggregation failed (did={}): {}",
                    bondholderDid, e.getMessage());
                substratePressure = 0.0;
            }
        }

        // 4. corpus_pairs — for v0.1, pull from interaction_count as a
        //    coarse proxy. Real pair-mining is in #1035 follow-up.
        int corpusPairs = (int) Math.min(Integer.MAX_VALUE, interactionCount);

        // 5. Vector age / new turns: OSS v0.1 first-fit only.
        Duration vectorAge = null;  // Never re-fit in v0.1 — first fit allowed.
        int newTurnsSinceLastFit = 0;

        return new Inputs(corpusPairs, bondAge, distinctSessions, bondState,
            substratePressure, vectorAge, newTurnsSinceLastFit);
    }

    private BondholderVoiceEligibility.Thresholds parseThresholds(Context ctx) {
        var def = BondholderVoiceEligibility.Thresholds.defaults();
        return new BondholderVoiceEligibility.Thresholds(
            parseIntOrDefault(ctx.queryParam("min-corpus-pairs"),
                def.minCorpusPairs()),
            parseIntOrDefault(ctx.queryParam("min-bond-age-days"),
                def.minBondAgeDays()),
            parseIntOrDefault(ctx.queryParam("min-distinct-sessions"),
                def.minDistinctSessions()),
            orDefault(ctx.queryParam("required-bond-state"),
                def.requiredBondState()),
            parseDoubleOrDefault(ctx.queryParam("substrate-pressure-threshold"),
                def.substratePressureThreshold()),
            def.vectorTtlDays(),
            parseIntOrDefault(ctx.queryParam("min-new-turns"),
                def.minNewTurnsSinceLastFit())
        );
    }

    private Connection openDb() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private boolean authorised(Context ctx) {
        String expected = System.getenv("WYRDSEKAI_ADMIN_TOKEN");
        if (expected == null || expected.isBlank()) {
            String remote = ctx.ip();
            boolean local = "127.0.0.1".equals(remote)
                || "0:0:0:0:0:0:0:1".equals(remote) || "::1".equals(remote);
            if (!local) {
                ctx.status(403).json(Map.of("error", "admin_token_required"));
                return false;
            }
            return true;
        }
        String got = ctx.header(ADMIN_HEADER);
        if (!expected.equals(got)) {
            ctx.status(403).json(Map.of("error", "invalid_admin_token"));
            return false;
        }
        return true;
    }

    private static String orDefault(String v, String d) {
        return v == null || v.isBlank() ? d : v;
    }

    private static int parseIntOrDefault(String v, int d) {
        if (v == null || v.isBlank()) return d;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return d; }
    }

    private static double parseDoubleOrDefault(String v, double d) {
        if (v == null || v.isBlank()) return d;
        try { return Double.parseDouble(v.trim()); }
        catch (NumberFormatException e) { return d; }
    }
}
