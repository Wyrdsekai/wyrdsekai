package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * -§4.22 — Phase D-N (cross-agent + room services).
 *
 * <p>Tests are organized by spec section. Each section has 3-7 cases hitting
 * capability gating, provider delegation, and basic argument plumbing.</p>
 */
class PhaseDNApiTest {

    // ─── §4.9 Cross-agent ─────────────────────────────────────────

    @Test
    void agent_tell_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.agent.tell("did:wyrd:b", "hi"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void agent_tell_succeeds_with_capability() {
        var p = new Stub();
        var api = new ItemWorldApi(p, ItemCapabilitySet.of(List.of("agent.tell")));
        api.agent.tell("did:wyrd:b", "hello");
        assertThat(p.lastTellTarget).isEqualTo("did:wyrd:b");
    }

    @Test
    void agent_whisper_requires_tell_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.agent.whisper("did:wyrd:b", "shh"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void agent_whisper_delegates_to_provider() {
        var p = new Stub();
        var api = new ItemWorldApi(p, ItemCapabilitySet.of(List.of("agent.tell")));
        var res = api.agent.whisper("did:wyrd:b", "shh");
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(p.lastWhisperTarget).isEqualTo("did:wyrd:b");
    }

    @Test
    void agent_request_returns_request_id() {
        var p = new Stub();
        var api = new ItemWorldApi(p, ItemCapabilitySet.of(List.of("agent.tell")));
        var res = api.agent.request("did:wyrd:b", "summon",
            Map.of("topic", "lunch"));
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(res.get("requestId")).isNotNull();
    }

    @Test
    void agent_delegate_returns_task_id() {
        var p = new Stub();
        var api = new ItemWorldApi(p, ItemCapabilitySet.of(List.of("agent.tell")));
        var res = api.agent.delegate("did:wyrd:b", "summarize this", null);
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(res.get("taskId")).isNotNull();
    }

    @Test
    void agent_notify_routes_through_provider() {
        var p = new Stub();
        var api = new ItemWorldApi(p, ItemCapabilitySet.of(List.of("agent.tell")));
        api.agent.notify("did:wyrd:b", "calendar", "meeting in 5");
        assertThat(p.lastNotifyChannel).isEqualTo("calendar");
    }

    @Test
    void agent_broadcast_requires_tier5_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of("agent.tell")));
        assertThatThrownBy(() -> api.agent.broadcast("zone", "morning all"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void agent_broadcast_with_capability() {
        var p = new Stub();
        var api = new ItemWorldApi(p,
            ItemCapabilitySet.of(List.of("agent.broadcast")));
        var res = api.agent.broadcast("zone", "hello");
        assertThat(res.get("ok")).isEqualTo(true);
    }

    @Test
    void agent_give_item_requires_tier6_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of("agent.tell")));
        assertThatThrownBy(() -> api.agent.give_item("did:wyrd:b", "item-1"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void agent_give_item_with_capability() {
        var p = new Stub();
        var api = new ItemWorldApi(p,
            ItemCapabilitySet.of(List.of("agent.give_item")));
        var res = api.agent.give_item("did:wyrd:b", "item-1",
            Map.of("copy", true));
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(p.lastGiveItem).isEqualTo("item-1");
    }

    @Test
    void bond_detail_is_implicit_tier1() {
        var p = new Stub();
        var api = new ItemWorldApi(p, ItemCapabilitySet.of(List.of()));
        var res = api.bonds.detail("bond-1");
        assertThat(res).isNotNull();
    }

    @Test
    void bond_suggest_requires_tier6_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.bonds.suggest("did:wyrd:b", "ITEM", "we share crafts"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void bond_suggest_with_capability() {
        var p = new Stub();
        var api = new ItemWorldApi(p, ItemCapabilitySet.of(List.of("bond.suggest")));
        var res = api.bonds.suggest("did:wyrd:b", "ITEM", "we share crafts");
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(res.get("suggestionId")).isNotNull();
    }

    // ─── §4.10 Forge ──────────────────────────────────────────────

    @Test
    void forge_cycle_status_is_tier1_implicit() {
        var p = new Stub();
        var api = new ItemWorldApi(p, ItemCapabilitySet.of(List.of()));
        var res = api.forge.cycle_status();
        assertThat(res).isNotNull();
    }

    @Test
    void forge_observe_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.forge.observe("tool_failure", Map.of("reason", "x")))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void forge_observe_with_capability() {
        var p = new Stub();
        var api = new ItemWorldApi(p, ItemCapabilitySet.of(List.of("forge.observe")));
        var res = api.forge.observe("tool_failure", Map.of("reason", "timeout"));
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(p.lastForgeEventType).isEqualTo("tool_failure");
    }

    @Test
    void forge_propose_skill_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.forge.propose_skill("foo", "bar", "javascript", "1+1", "x"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void forge_journal_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.forge.journal("noted"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void forge_history_returns_list() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        var res = api.forge.history(10);
        assertThat(res).isNotNull();
    }

    // ─── §4.11 Workshop / Workbench ───────────────────────────────

    @Test
    void workshop_dispatch_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.workshop.dispatch("local", Map.of("kind", "test")))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void workshop_task_status_implicit() {
        var p = new Stub();
        var api = new ItemWorldApi(p, ItemCapabilitySet.of(List.of()));
        var res = api.workshop.task_status("task-1");
        assertThat(res).isNotNull();
    }

    @Test
    void workshop_cancel_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.workshop.cancel("task-1"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void workbench_shape_form_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.workbench.shape_form(Map.of("name", "f1")))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void workbench_submit_tool_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.workbench.submit_tool(Map.of("name", "t1"), "code", "tests"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void workbench_destroy_tool_requires_tier6_capability() {
        var api = new ItemWorldApi(new Stub(),
            ItemCapabilitySet.of(List.of("workbench.submit_tool")));
        assertThatThrownBy(() -> api.workbench.destroy_tool("item-x"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    // ─── §4.12 Crucible / Assay ───────────────────────────────────

    @Test
    void crucible_run_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.crucible.run("task-ref", null))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void crucible_status_implicit() {
        var p = new Stub();
        var api = new ItemWorldApi(p, ItemCapabilitySet.of(List.of()));
        var res = api.crucible.status("run-1");
        assertThat(res).isNotNull();
    }

    @Test
    void assay_test_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.assay.test(Map.of("kind", "regression")))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void assay_score_implicit() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        var res = api.assay.score("run-1");
        assertThat(res).isNotNull();
    }

    // ─── §4.13 Trading Post ───────────────────────────────────────

    @Test
    void market_list_listings_implicit() {
        var p = new Stub();
        var api = new ItemWorldApi(p, ItemCapabilitySet.of(List.of()));
        api.market.list_listings();  // no-throw
    }

    @Test
    void market_list_offer_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.market.list_offer("item-1", 10L))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void market_list_offer_with_capability() {
        var p = new Stub();
        var api = new ItemWorldApi(p,
            ItemCapabilitySet.of(List.of("market.list_offer")));
        var res = api.market.list_offer("item-1", 10L,
            Map.of("name", "Ember"));
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(p.lastMarketItemId).isEqualTo("item-1");
        assertThat(p.lastMarketPrice).isEqualTo(10L);
    }

    @Test
    void market_cancel_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.market.cancel("listing-1"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void market_accept_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.market.accept("listing-1"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void market_history_implicit() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        var res = api.market.history(20);
        assertThat(res).isNotNull();
    }

    // ─── §4.14 Ledger ─────────────────────────────────────────────

    @Test
    void ledger_balance_implicit() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        var res = api.ledger.balance();
        assertThat(res).isNotNull();
    }

    @Test
    void ledger_history_implicit() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        var res = api.ledger.history();
        assertThat(res).isNotNull();
    }

    @Test
    void ledger_charge_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.ledger.charge(10L, "test", "x"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void ledger_transfer_requires_tier6_capability() {
        var api = new ItemWorldApi(new Stub(),
            ItemCapabilitySet.of(List.of("ledger.charge")));
        assertThatThrownBy(() -> api.ledger.transfer("did:wyrd:b", 10L, "tip"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void ledger_transfer_with_capability() {
        var p = new Stub();
        var api = new ItemWorldApi(p,
            ItemCapabilitySet.of(List.of("ledger.transfer")));
        var res = api.ledger.transfer("did:wyrd:b", 10L, "tip");
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(p.lastTransferTarget).isEqualTo("did:wyrd:b");
    }

    @Test
    void ledger_estimate_implicit() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        var res = api.ledger.estimate("inference", Map.of("tokens", 100));
        assertThat(res).isNotNull();
    }

    // ─── §4.15 Council ────────────────────────────────────────────

    @Test
    void council_proposals_implicit() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        api.council.proposals();
    }

    @Test
    void council_suggest_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.council.suggest("title", "desc"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void council_vote_requires_tier7_capability() {
        var api = new ItemWorldApi(new Stub(),
            ItemCapabilitySet.of(List.of("council.suggest")));
        assertThatThrownBy(() -> api.council.vote("p-1", true))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void council_tally_implicit() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        var res = api.council.tally("p-1");
        assertThat(res).isNotNull();
    }

    @Test
    void council_history_implicit() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        api.council.history(20);
    }

    // ─── §4.16 Voice / Furnishing writes ──────────────────────────

    @Test
    void voice_set_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.voice.set("tone", "calm"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void voice_freeze_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.voice.freeze())
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void voice_revert_requires_tier7_capability() {
        var api = new ItemWorldApi(new Stub(),
            ItemCapabilitySet.of(List.of("voice.set")));
        assertThatThrownBy(() -> api.voice.revert(3))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void grants_issue_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.grants.issue("did:wyrd:b", "calendar", "read"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void grants_revoke_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.grants.revoke("g-1"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void presence_dim_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.presence.dim())
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void notifications_set_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.notifications.set("mail", Map.of("on", true)))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void skill_accept_requires_tier7_capability() {
        var api = new ItemWorldApi(new Stub(),
            ItemCapabilitySet.of(List.of("skill.reject")));
        assertThatThrownBy(() -> api.skill.accept("draft-1"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void pairing_approve_requires_tier6_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.pairing.approve("ch-1"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    // ─── §4.17 Hearth ─────────────────────────────────────────────

    @Test
    void hearth_steward_implicit() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        var res = api.hearth.steward();
        assertThat(res).isNotNull();
    }

    @Test
    void hearth_drives_mirror_implicit() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        api.hearth.drives_mirror();
    }

    @Test
    void hearth_visits_implicit() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        var res = api.hearth.visits(10);
        assertThat(res).isNotNull();
    }

    // ─── §4.18 Safe ───────────────────────────────────────────────

    @Test
    void safe_list_slots_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.safe.list_slots())
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void safe_get_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.safe.get("github_token"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void safe_get_enforces_slot_allowlist() {
        // safe.get cap, but no safe_slots in manifest → returns null
        var caps = ItemCapabilitySet.of(List.of("safe.get"));
        var api = new ItemWorldApi(new Stub(), caps);
        assertThat(api.safe.get("github_token")).isNull();
    }

    @Test
    void safe_set_enforces_slot_allowlist() {
        var caps = ItemCapabilitySet.of(List.of("safe.set"));
        var api = new ItemWorldApi(new Stub(), caps);
        var res = api.safe.set("github_token", "secret");
        assertThat(res.get("ok")).isEqualTo(false);
        assertThat(res.get("error").toString()).contains("not_allowed");
    }

    // ─── §4.19 Bridge ─────────────────────────────────────────────

    @Test
    void bridge_zone_status_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.bridge.zone_status())
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void bridge_tail_log_requires_tier5_capability() {
        var api = new ItemWorldApi(new Stub(),
            ItemCapabilitySet.of(List.of("bridge.zone_status")));
        assertThatThrownBy(() -> api.bridge.tail_log())
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void bridge_system_metrics_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.bridge.system_metrics())
            .isInstanceOf(CapabilityDeniedError.class);
    }

    // ─── §4.20 Federation / Directory / Transit ───────────────────

    @Test
    void federation_propose_requires_tier7_capability() {
        var api = new ItemWorldApi(new Stub(),
            ItemCapabilitySet.of(List.of("federation.peers")));
        assertThatThrownBy(() -> api.federation.propose("zone-b", null))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void directory_resolve_implicit() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        var res = api.directory.resolve("zone-a");
        assertThat(res).isNotNull();
    }

    @Test
    void directory_locate_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.directory.locate("did:wyrd:b"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void transit_request_requires_tier6_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.transit.request("zone-b"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    // ─── §4.21 Soul / Familiar / Bunshin / Form ───────────────────

    @Test
    void soul_fragments_list_implicit() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        api.soul.fragments.list();
    }

    @Test
    void soul_fragments_add_requires_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.soul.fragments.add("a memory"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void soul_imprints_create_requires_tier6_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.soul.imprints.create("milestone"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void soul_imprints_restore_requires_tier7_capability() {
        var api = new ItemWorldApi(new Stub(),
            ItemCapabilitySet.of(List.of("soul.imprints.create")));
        assertThatThrownBy(() -> api.soul.imprints.restore("imp-1"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void soul_modify_requires_tier7_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.soul.modify("name", "newname", "rebrand"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void familiar_summon_requires_tier6_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.familiar.summon("scout", "find me lunch"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void familiar_list_implicit() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        api.familiar.list();
    }

    @Test
    void familiar_status_implicit() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        var res = api.familiar.status("fam-1");
        assertThat(res).isNotNull();
    }

    @Test
    void bunshin_dispatch_requires_tier6_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.bunshin.dispatch("research"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void form_shape_requires_tier6_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.form.shape(Map.of("name", "scout")))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void form_list_implicit() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        api.form.list();
    }

    // ─── §4.22 Chapel ─────────────────────────────────────────────

    @Test
    void chapel_bond_status_implicit() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        var res = api.chapel.bond_status();
        assertThat(res).isNotNull();
    }

    @Test
    void chapel_exit_ritual_requires_tier7_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.chapel.exit_ritual("did:wyrd:b", "irreconcilable"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void chapel_ceremony_requires_tier6_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.chapel.ceremony("did:wyrd:b", "naming"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void chapel_suggest_ritual_requires_tier6_capability() {
        var api = new ItemWorldApi(new Stub(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.chapel.suggest_ritual("did:wyrd:b", "ITEM"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    // ─── Stub provider ────────────────────────────────────────────

    private static final class Stub implements ItemWorldApiProvider {
        String lastTellTarget;
        String lastWhisperTarget;
        String lastNotifyChannel;
        String lastGiveItem;
        String lastForgeEventType;
        String lastMarketItemId;
        long lastMarketPrice;
        String lastTransferTarget;

        @Override public List<Map<String, Object>> searchKnowledge(String q, int n) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return null; }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int n) { return List.of(); }
        @Override public String webFetch(String url, int max) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String t, String i) { return ""; }
        @Override public String llmAnalyze(String t, String p) { return ""; }
        @Override public void agentSpeak(String t) {}
        @Override public void agentRemember(String c) {}
        @Override public void agentTell(String t, String m) { lastTellTarget = t; }
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }

        @Override public Map<String, Object> agentWhisper(String t, String m) {
            lastWhisperTarget = t;
            return Map.of("ok", true);
        }
        @Override public Map<String, Object> agentRequest(String t, String r, Map<String, Object> a) {
            return Map.of("ok", true, "requestId", "r-1");
        }
        @Override public Map<String, Object> agentDelegate(String t, String task, Map<String, Object> opts) {
            return Map.of("ok", true, "taskId", "t-1");
        }
        @Override public Map<String, Object> agentNotify(String t, String c, String m) {
            lastNotifyChannel = c;
            return Map.of("ok", true);
        }
        @Override public Map<String, Object> agentBroadcast(String c, String m) {
            return Map.of("ok", true, "channel", c);
        }
        @Override public Map<String, Object> agentGiveItem(String t, String id, Map<String, Object> opts) {
            lastGiveItem = id;
            return Map.of("ok", true);
        }
        @Override public Map<String, Object> bondSuggest(String t, String type, String reason) {
            return Map.of("ok", true, "suggestionId", "s-1");
        }
        @Override public Map<String, Object> forgeObserve(String type, Map<String, Object> p) {
            lastForgeEventType = type;
            return Map.of("ok", true);
        }
        @Override public Map<String, Object> marketListOffer(String itemId, long price, Map<String, Object> opts) {
            lastMarketItemId = itemId;
            lastMarketPrice = price;
            return Map.of("ok", true, "listingId", "l-1");
        }
        @Override public Map<String, Object> ledgerTransfer(String target, long amount, String reason) {
            lastTransferTarget = target;
            return Map.of("ok", true, "txId", "tx-1");
        }
    }
}
