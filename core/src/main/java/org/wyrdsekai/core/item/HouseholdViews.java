package org.wyrdsekai.core.item;

import org.wyrdsekai.core.agent.AgentCostTracker;
import org.wyrdsekai.core.agent.DriveSnapshotRegistry;
import org.wyrdsekai.core.coding.BackendRegistry;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.governance.CouncilService;
import org.wyrdsekai.core.recipe.QueuedRecipe;
import org.wyrdsekai.core.recipe.RecipeEnrollmentStore;
import org.wyrdsekai.core.recipe.SqlRecipeQueue;
import org.wyrdsekai.core.soul.SaudadeLonelinessDistinction;
import org.wyrdsekai.core.soul.SqlSoulStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ONE implementation of the singleton-backed household read surfaces, shared by
 * BOTH provider hierarchies (player-route {@link HomeOwnerItemProvider} and
 * companion-route {@link ItemWorldApiProviderImpl}).
 *
 * <p>2026-07-18: these surfaces were implemented in only one hierarchy at a time,
 * so a Study furnishing answered real data on the player route and empty on the
 * companion route (or vice versa) — the systemic two-hierarchy bug the bond crystal
 * was one instance of. Because the backing is a process-global singleton
 * ({@code AgentCostTracker.get()}, {@code CouncilService.get()}), a static helper
 * both providers delegate to is drift-proof: there is nothing to override twice.</p>
 */
public final class HouseholdViews {

    private HouseholdViews() {}

    /** Household-wide cost overview (the treasury). Empty when no tracker. */
    public static Map<String, Object> treasurySummary() {
        var tracker = AgentCostTracker.get();
        if (tracker == null) return Map.of();
        try {
            long inferences = 0, mcpCalls = 0, tokens = 0;
            double cost = 0.0;
            var agents = tracker.trackedAgents();
            for (var agentId : agents) {
                var maybe = tracker.summary(agentId);
                if (maybe.isEmpty()) continue;
                var s = maybe.get();
                inferences += s.totalInferences();
                mcpCalls += s.totalMcpCalls();
                tokens += s.totalTokens();
                cost += s.totalMonetaryCost();
            }
            var m = new LinkedHashMap<String, Object>();
            m.put("agents", agents.size());
            m.put("inferences", inferences);
            m.put("mcpCalls", mcpCalls);
            m.put("tokens", tokens);
            m.put("monetaryCost", cost);
            return m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Per-agent cost rows, sorted by agent id. */
    public static List<Map<String, Object>> treasuryPerMember() {
        var tracker = AgentCostTracker.get();
        if (tracker == null) return List.of();
        try {
            var out = new ArrayList<Map<String, Object>>();
            for (var agentId : tracker.trackedAgents()) {
                var maybe = tracker.summary(agentId);
                if (maybe.isEmpty()) continue;
                var s = maybe.get();
                var m = new LinkedHashMap<String, Object>();
                m.put("agentId", agentId);
                m.put("inferences", s.totalInferences());
                m.put("mcpCalls", s.totalMcpCalls());
                m.put("tokens", s.totalTokens());
                m.put("monetaryCost", s.totalMonetaryCost());
                var budgetNote = tracker.checkBudget(agentId);
                if (budgetNote != null) m.put("budgetNote", budgetNote);
                out.add(m);
            }
            out.sort((a, b) -> String.valueOf(a.get("agentId"))
                .compareTo(String.valueOf(b.get("agentId"))));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** One principal's own spend + budget note. Empty when unknown. */
    public static Map<String, Object> budgetSummary(String principalId) {
        if (principalId == null) return Map.of();
        var tracker = AgentCostTracker.get();
        if (tracker == null) return Map.of();
        try {
            var maybe = tracker.summary(principalId);
            if (maybe.isEmpty()) return Map.of();
            var s = maybe.get();
            var m = new HashMap<String, Object>();
            m.put("inferences", s.totalInferences());
            m.put("mcpCalls", s.totalMcpCalls());
            m.put("tokens", s.totalTokens());
            m.put("monetaryCost", s.totalMonetaryCost());
            var budgetNote = tracker.checkBudget(principalId);
            if (budgetNote != null) m.put("budgetNote", budgetNote);
            return m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Active governance proposals. */
    public static List<Map<String, Object>> councilProposals() {
        var svc = CouncilService.get();
        if (svc == null) return List.of();
        try {
            var out = new ArrayList<Map<String, Object>>();
            for (var p : svc.activeProposals()) out.add(proposalView(p));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Recent proposal history (active + resolved), newest-first, capped. */
    public static List<Map<String, Object>> councilHistory(int limit) {
        var svc = CouncilService.get();
        if (svc == null) return List.of();
        try {
            var out = new ArrayList<Map<String, Object>>();
            int cap = Math.max(1, Math.min(limit, 100));
            for (var p : svc.allProposals()) {
                if (out.size() >= cap) break;
                out.add(proposalView(p));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String jdbcUrl() {
        var s = System.getProperty("wyrdsekai.jdbc.url");
        if (s != null && !s.isBlank()) return s;
        var url = WyrdConfig.get().jdbcUrl();
        return url == null || url.isBlank() ? null : url;
    }

    /** Enrolled recipes + queue depth + last run — the recipes-console furnishing. */
    public static List<Map<String, Object>> recipeEnrolled() {
        var jdbcUrl = jdbcUrl();
        if (jdbcUrl == null) return List.of();
        var rows = new ArrayList<Map<String, Object>>();
        try {
            var enrollStore = new RecipeEnrollmentStore(jdbcUrl);
            var queue = new SqlRecipeQueue(jdbcUrl);
            for (var e : enrollStore.listAll()) {
                var qrows = queue.findByRecipe(e.recipeId(), e.agentDid());
                QueuedRecipe lastTerminal = null;
                int pending = 0;
                for (var q : qrows) {
                    if (q.status() == QueuedRecipe.Status.PENDING
                            || q.status() == QueuedRecipe.Status.IN_PROGRESS) {
                        pending++;
                    }
                    if (q.isTerminal() && (lastTerminal == null
                            || (q.completedAt() != null
                                && (lastTerminal.completedAt() == null
                                    || q.completedAt().isAfter(lastTerminal.completedAt()))))) {
                        lastTerminal = q;
                    }
                }
                var row = new LinkedHashMap<String, Object>();
                row.put("recipeId", e.recipeId());
                row.put("agentDid", e.agentDid());
                row.put("enabled", e.enabled());
                row.put("cadenceTier", e.cadenceTier().name());
                row.put("consecutiveSuccesses", e.consecutiveSuccesses());
                row.put("gapKeys", new ArrayList<>(e.gapKeys()));
                row.put("queueDepth", pending);
                if (lastTerminal != null) {
                    row.put("lastStatus", lastTerminal.status().name());
                    row.put("lastRunAt", lastTerminal.completedAt() == null ? null
                        : lastTerminal.completedAt().toString());
                    row.put("nextFireEstimate", lastTerminal.completedAt() == null ? null
                        : lastTerminal.completedAt().plus(e.cadenceTier().period()).toString());
                }
                rows.add(row);
            }
        } catch (Exception ignored) {}
        return rows;
    }

    /** Recent recipe runs (succeeded + failed), newest-first, capped. */
    public static List<Map<String, Object>> recipeRecentRuns(int limit) {
        var jdbcUrl = jdbcUrl();
        if (jdbcUrl == null) return List.of();
        int cap = limit <= 0 ? 10 : Math.min(limit, 100);
        var rows = new ArrayList<Map<String, Object>>();
        try {
            var queue = new SqlRecipeQueue(jdbcUrl);
            var combined = new ArrayList<QueuedRecipe>();
            combined.addAll(queue.listByStatus(QueuedRecipe.Status.SUCCEEDED));
            combined.addAll(queue.listByStatus(QueuedRecipe.Status.FAILED));
            combined.sort((a, b) -> {
                var aT = a.completedAt();
                var bT = b.completedAt();
                if (aT == null && bT == null) return 0;
                if (aT == null) return 1;
                if (bT == null) return -1;
                return bT.compareTo(aT);
            });
            for (var q : combined) {
                if (rows.size() >= cap) break;
                var row = new LinkedHashMap<String, Object>();
                row.put("recipeId", q.recipeId());
                row.put("agentDid", q.agentDid());
                row.put("status", q.status().name());
                row.put("triggerSource", q.triggerSource().name());
                row.put("triggerReason", q.triggerReason());
                row.put("cadenceTier", q.cadenceTier().name());
                row.put("completedAt", q.completedAt() == null ? null : q.completedAt().toString());
                row.put("message", q.message());
                rows.add(row);
            }
        } catch (Exception ignored) {}
        return rows;
    }

    /** Registered coding backends + health — the coding-slate furnishing. */
    public static List<Map<String, Object>> codingBackendsStatus() {
        var registry = BackendRegistry.get();
        if (registry == null) return List.of();
        var backends = registry.backends();
        if (backends.isEmpty()) return List.of();
        var out = new ArrayList<Map<String, Object>>(backends.size());
        for (var b : backends) {
            var row = new LinkedHashMap<String, Object>();
            row.put("name", b.name());
            row.put("tier", b.tier().name());
            row.put("enabled", true);
            boolean healthy = false;
            try {
                healthy = Boolean.TRUE.equals(
                    b.healthCheck().toCompletableFuture().get(150, TimeUnit.MILLISECONDS));
            } catch (Exception ignored) {}
            row.put("healthy", healthy);
            row.put("lastTask", null);
            row.put("successRate30d", null);
            out.add(row);
        }
        return List.copyOf(out);
    }

    /**
     * The Compass furnishing's channel list, parsed from a companion's
     * {@code notify.*} worldKnowledge config (the canonical source that
     * {@code CompanionActor.initNotificationChannels} also reads). One row per
     * configured channel: {@code {channel, destination, enabled}}. Empty when no
     * channels are configured. Pure function — no state, no drift.
     */
    public static List<Map<String, Object>> notificationChannels(Map<String, String> wk) {
        if (wk == null || wk.isEmpty()) return List.of();
        // channel → the key whose presence means "configured" + holds the destination.
        String[][] channels = {
            {"ntfy", "notify.ntfy.topic"},
            {"email", "notify.email.address"},
            {"discord", "notify.discord.webhookUrl"},
            {"webhook", "notify.webhook.url"},
            {"telegram", "notify.telegram.chatId"},
            {"slack", "notify.slack.channelId"},
            {"line", "notify.line.userId"},
            {"keybase", "notify.keybase.channel"},
            {"signal", "notify.signal.recipient"},
            {"matrix", "notify.matrix.roomId"},
            {"whatsapp", "notify.whatsapp.to"},
        };
        var out = new ArrayList<Map<String, Object>>();
        for (var c : channels) {
            var dest = wk.get(c[1]);
            if (dest == null || dest.isBlank()) continue;
            // A channel disabled via notify.<c>.enabled=false stays visible but off.
            var enabledFlag = wk.get("notify." + c[0] + ".enabled");
            boolean enabled = enabledFlag == null || !"false".equalsIgnoreCase(enabledFlag);
            var m = new LinkedHashMap<String, Object>();
            m.put("channel", c[0]);
            m.put("destination", dest);
            m.put("enabled", enabled);
            out.add(m);
        }
        return out;
    }

    /**
     * The steward's Compass: notification channels across the whole household,
     * one section per companion (a steward has no channels of their own — the
     * companions are who reach the human when they're away). Reads each
     * companion's {@code notify.*} worldKnowledge from the soul store, tags each
     * row with the companion it belongs to. Empty when no companion has channels.
     */
    public static List<Map<String, Object>> notificationChannelsForHousehold() {
        var jdbcUrl = jdbcUrl();
        if (jdbcUrl == null) return List.of();
        var out = new ArrayList<Map<String, Object>>();
        try (var store = new SqlSoulStore(jdbcUrl)) {
            for (var comp : CompanionCodexView.list()) {
                var did = comp.get("did");
                var name = comp.get("name");
                if (!(did instanceof String d) || d.isBlank()) continue;
                var wk = store.latest(d).map(m -> m.worldKnowledge()).orElse(null);
                for (var row : notificationChannels(wk)) {
                    var m = new LinkedHashMap<>(row);
                    m.put("companion", name == null ? d : name);
                    out.add(m);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * A bondholder's glimpse into a companion's inner life — the Drives Mirror,
     * player route (2026-07-18: operator was reduced to reading world.db + logs to
     * see this). Drives, the vitality tanks, a mood one-liner, and the
     * saudade-vs-loneliness diagnosis (longing for a specific absent person vs
     * general social drain). Reads {@code DriveSnapshotRegistry} by the
     * companion's key (did or entityId). Omits the companion-instance substrate
     * severity (that needs the actor's own protection flags). Empty when the
     * companion hasn't published a snapshot yet.
     */
    public static Map<String, Object> driveSnapshotFor(String companionKey) {
        if (companionKey == null || companionKey.isBlank()) return Map.of();
        var snap = DriveSnapshotRegistry.get(companionKey).orElse(null);
        if (snap == null || snap.drives() == null || snap.vitality() == null) return Map.of();
        var d = snap.drives();
        var drives = new LinkedHashMap<String, Double>();
        drives.put("seeking", d.seeking());
        drives.put("care", d.care());
        drives.put("play", d.play());
        drives.put("vigilance", d.vigilance());
        drives.put("affiliation", d.affiliation());
        drives.put("grief", d.grief());
        drives.put("frustration", d.frustration());
        drives.put("creativity", d.creativity());

        var v = snap.vitality();
        var vitality = new LinkedHashMap<String, Double>();
        vitality.put("energy", v.energy());
        vitality.put("confidence", v.confidence());
        vitality.put("focus", v.focus());
        vitality.put("momentum", v.momentum());
        vitality.put("rapport", v.rapport());
        vitality.put("errorPressure", v.errorPressure());

        var dominant = drives.entrySet().stream()
            .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("seeking");
        String mood = v.energy() < 0.25 ? "depleted, " + dominant + " thin"
            : v.energy() > 0.75 ? "alert, " + dominant + " bright"
            : "settled, " + dominant + " present";

        var out = new LinkedHashMap<String, Object>();
        out.put("drives", drives);
        out.put("vitality", vitality);
        out.put("mood", mood);
        out.put("updatedAtMillis", snap.updatedAt().toEpochMilli());

        // Saudade-vs-loneliness: longing for a named absent bondholder vs general drain.
        try {
            var saudadeMap = new HashMap<String, Double>();
            if (snap.saudadeByBondholder() != null) {
                for (var e : snap.saudadeByBondholder().entrySet()) {
                    if (e.getValue() != null) saudadeMap.put(e.getKey(), e.getValue().currentValue());
                }
            }
            var input = SaudadeLonelinessDistinction.Input
                .of(v.loneliness(), saudadeMap);
            var view = SaudadeLonelinessDistinction.diagnose(input);
            var sl = new LinkedHashMap<String, Object>();
            sl.put("diagnosis", view.diagnosis().name().toLowerCase());
            sl.put("topBondholder", view.topSaudadeBondholder().orElse(""));
            sl.put("topSaudadeValue", view.topSaudadeValue());
            out.put("saudadeLoneliness", sl);
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * The Drives Mirror keyed to the household's primary companion (first with a
     * published snapshot) — what a steward's Study Mirror shows. Also tags the
     * companion name so a multi-companion household reads clearly.
     */
    public static Map<String, Object> primaryCompanionDriveSnapshot() {
        for (var comp : CompanionCodexView.list()) {
            var did = comp.get("did");
            var entityId = comp.get("entityId");
            var key = did instanceof String s && !s.isBlank() ? s
                : (entityId instanceof String e ? e : null);
            if (key == null) continue;
            var snap = driveSnapshotFor(key);
            if (!snap.isEmpty()) {
                var out = new LinkedHashMap<>(snap);
                out.put("companion", comp.getOrDefault("name", key));
                return out;
            }
        }
        return Map.of();
    }

    private static Map<String, Object> proposalView(CouncilService.Proposal p) {
        var m = new HashMap<String, Object>();
        m.put("id", p.id());
        m.put("title", p.title());
        m.put("description", p.description());
        m.put("type", p.type() == null ? null : p.type().name());
        m.put("status", p.status() == null ? null : p.status().name());
        m.put("proposer", p.proposer());
        m.put("approveCount", p.approvals());
        m.put("rejectCount", p.rejections());
        m.put("totalVotes", p.totalVotes());
        return m;
    }
}
