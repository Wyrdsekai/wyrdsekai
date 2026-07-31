package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.coding.StubItemWorldApiProvider;
import org.wyrdsekai.core.skill.SkillDraft;
import org.wyrdsekai.core.skill.SkillDraftStore;
import org.wyrdsekai.core.skill.SkillGate;
import org.wyrdsekai.core.skill.SkillVerifier;
import org.wyrdsekai.core.skill.WorkshopPinboard;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * REST surface for drafts.
 *
 * <p>Backs the Workshop pinboard furnishing, the Study Board surfacing
 * (§5), and the {@code wyrd skill drafts} CLI. The route handlers
 * defer to {@link WorkshopPinboard}; the materializer must be supplied
 * by the host because seating a skill needs the steward's
 * {@code FamilyLocker} + {@code WorkbenchSkillExecutor}.</p>
 *
 * <ul>
 *   <li>{@code GET /api/skill/drafts?agent={did}&status=PENDING} — list</li>
 *   <li>{@code GET /api/skill/drafts/{id}} — one draft (full code)</li>
 *   <li>{@code POST /api/skill/drafts/{id}/approve} body: {note}</li>
 *   <li>{@code POST /api/skill/drafts/{id}/reject} body: {reason}</li>
 *   <li>{@code POST /api/skill/drafts/{id}/edit} body: {code, note}</li>
 * </ul>
 */
public final class SkillRoutes {

    private static final Logger log = LoggerFactory.getLogger(SkillRoutes.class);

    private final SkillDraftStore store;
    private final Function<String, WorkshopPinboard.Materializer> materializerFor;
    /**
     * the deterministic verification gate run at approval. Self-contained:
     * the frozen harness rides on the draft ({@code harness_json}), the verifier needs only a
     * sandbox executor, and the provider/caps are constants. A draft with no harness is unverified
     * and permitted (steward approval still applies) — so this is safe to enforce before authoring
     * has populated any harnesses. No model in the loop.
     */
    private final SkillGate gate;

    public SkillRoutes(SkillDraftStore store,
                       Function<String, WorkshopPinboard.Materializer> materializerFor) {
        this.store = store;
        this.materializerFor = materializerFor;
        this.gate = SkillGate.fromPersistedHarness(
            new SkillVerifier(new ItemScriptExecutor()),
            StubItemWorldApiProvider.INSTANCE,
            ItemCapabilitySet.of(List.of()));
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/skill/drafts", this::list);
        app.get("/api/skill/drafts/{id}", this::getOne);
        app.post("/api/skill/drafts/{id}/approve", this::approve);
        app.post("/api/skill/drafts/{id}/reject", this::reject);
        app.post("/api/skill/drafts/{id}/edit", this::edit);
    }

    // ── handlers ─────────────────────────────────────────────────────────

    private void list(Context ctx) {
        var agent = ctx.queryParam("agent");
        if (agent == null || agent.isBlank()) {
            ctx.status(400).json(Map.of("error", "agent parameter required"));
            return;
        }
        var statusParam = ctx.queryParam("status");
        SkillDraft.Status status = parseStatus(statusParam, SkillDraft.Status.PENDING);
        var drafts = store.byAgentAndStatus(agent, status);
        ctx.json(Map.of(
            "agent", agent,
            "status", status.name(),
            "count", drafts.size(),
            "drafts", drafts.stream().map(SkillRoutes::toJson).toList()));
    }

    private void getOne(Context ctx) {
        var id = ctx.pathParam("id");
        var d = store.get(id);
        if (d.isEmpty()) {
            ctx.status(404).json(Map.of("error", "no draft with id " + id));
            return;
        }
        ctx.json(toJsonFull(d.get()));
    }

    private void approve(Context ctx) {
        var draft = requireDraft(ctx);
        if (draft == null) return;
        var idx = pendingIndex(draft);
        if (idx < 1) {
            ctx.status(409).json(Map.of("error",
                "draft is not PENDING (status=" + draft.status() + ")"));
            return;
        }
        var body = parseBody(ctx);
        var note = body.getOrDefault("note", "").toString();
        // Phase 2.1 — pass the verification gate (built in the ctor) into the
        // pinboard so a draft's harness is RUN before materialization. Before this
        // it was `new WorkshopPinboard(store)` (gate=null) — harnesses were authored
        // and stored but never executed, so skills promoted with an unrun proof.
        var pinboard = new WorkshopPinboard(store, gate);
        var mat = materializerFor != null ? materializerFor.apply(draft.agentDid()) : null;
        var decision = pinboard.approve(draft.agentDid(), idx, note, mat);
        if (!decision.ok()) {
            ctx.status(409).json(Map.of("error", decision.message()));
            return;
        }
        ctx.json(Map.of(
            "ok", true,
            "message", decision.message(),
            "draft", toJson(decision.draft())));
    }

    private void reject(Context ctx) {
        var draft = requireDraft(ctx);
        if (draft == null) return;
        var idx = pendingIndex(draft);
        if (idx < 1) {
            ctx.status(409).json(Map.of("error",
                "draft is not PENDING (status=" + draft.status() + ")"));
            return;
        }
        var body = parseBody(ctx);
        var reason = body.getOrDefault("reason", "").toString();
        var pinboard = new WorkshopPinboard(store);
        var decision = pinboard.reject(draft.agentDid(), idx, reason);
        if (!decision.ok()) {
            ctx.status(409).json(Map.of("error", decision.message()));
            return;
        }
        ctx.json(Map.of("ok", true, "draft", toJson(decision.draft())));
    }

    private void edit(Context ctx) {
        var draft = requireDraft(ctx);
        if (draft == null) return;
        var idx = pendingIndex(draft);
        if (idx < 1) {
            ctx.status(409).json(Map.of("error",
                "draft is not PENDING (status=" + draft.status() + ")"));
            return;
        }
        var body = parseBody(ctx);
        var code = body.getOrDefault("code", "").toString();
        var note = body.getOrDefault("note", "").toString();
        if (code.isBlank()) {
            ctx.status(400).json(Map.of("error", "code field required"));
            return;
        }
        var pinboard = new WorkshopPinboard(store);
        var mat = materializerFor != null ? materializerFor.apply(draft.agentDid()) : null;
        var decision = pinboard.editAndApprove(draft.agentDid(), idx, code, note, mat);
        if (!decision.ok()) {
            ctx.status(409).json(Map.of("error", decision.message()));
            return;
        }
        ctx.json(Map.of(
            "ok", true,
            "message", decision.message(),
            "draft", toJson(decision.draft())));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Return the draft if it exists, otherwise write 404 and return null. */
    private SkillDraft requireDraft(Context ctx) {
        var id = ctx.pathParam("id");
        var d = store.get(id);
        if (d.isEmpty()) {
            ctx.status(404).json(Map.of("error", "no draft with id " + id));
            return null;
        }
        return d.get();
    }

    /** 1-based index of the draft in the agent's PENDING list, or -1. */
    private int pendingIndex(SkillDraft d) {
        var list = store.byAgentAndStatus(d.agentDid(), SkillDraft.Status.PENDING);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).draftId().equals(d.draftId())) return i + 1;
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseBody(Context ctx) {
        try {
            var body = ctx.body();
            if (body == null || body.isBlank()) return Map.of();
            return ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static SkillDraft.Status parseStatus(String raw, SkillDraft.Status def) {
        if (raw == null || raw.isBlank()) return def;
        try { return SkillDraft.Status.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return def; }
    }

    /** Compact JSON for list views — no full code body. */
    private static Map<String, Object> toJson(SkillDraft d) {
        var m = new LinkedHashMap<String, Object>();
        m.put("draftId", d.draftId());
        m.put("agentDid", d.agentDid());
        m.put("status", d.status().name());
        m.put("name", d.name());
        m.put("description", d.description());
        m.put("rationale", d.rationale());
        m.put("runtime", d.runtime());
        m.put("closesGaps", d.closesGaps());
        m.put("replaces", d.replaces());
        m.put("proposedAt", instantOrNull(d.proposedAt()));
        m.put("proposedByModel", d.proposedByModel());
        m.put("decidedAt", instantOrNull(d.decidedAt()));
        m.put("decisionNote", d.decisionNote());
        return m;
    }

    /** Detail view — same as compact but with code body. */
    private static Map<String, Object> toJsonFull(SkillDraft d) {
        var m = toJson(d);
        m.put("code", d.code());
        return m;
    }

    private static String instantOrNull(Instant t) {
        return t == null ? null : t.toString();
    }
}
