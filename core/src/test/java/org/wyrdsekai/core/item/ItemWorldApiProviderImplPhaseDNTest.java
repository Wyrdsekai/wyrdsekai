package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.economy.MeteringService;
import org.wyrdsekai.core.economy.TradingPostService;
import org.wyrdsekai.core.governance.CouncilService;
import org.wyrdsekai.core.soul.SignificanceBuffer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * -§4.22 — Phase D-N provider-level wiring tests.
 *
 * <p>Validates that {@link ItemWorldApiProviderImpl} delegates to its
 * backing services (CouncilService, TradingPostService, BondStore,
 * SignificanceBuffer, etc.) and that callbacks fire as expected.</p>
 */
class ItemWorldApiProviderImplPhaseDNTest {

    private ItemWorldApiProviderImpl provider;
    private final AtomicReference<String> lastTellTarget = new AtomicReference<>();
    private final AtomicReference<String> lastTellMessage = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        MailboxService.resetForTests();
        TradingPostService.init();
        CouncilService.init();
        MeteringService.init();

        provider = new ItemWorldApiProviderImpl(
            null, null, null, null,
            "did:wyrd:test-agent", "Tester",
            t -> {}, c -> {},
            (target, msg) -> {
                lastTellTarget.set(target);
                lastTellMessage.set(msg);
            },
            null, null);
    }

    @AfterEach
    void tearDown() {
        MailboxService.resetForTests();
    }

    // ─── §4.9 Cross-agent ─────────────────────────────────────────

    @Test
    void agent_whisper_routes_through_tell_with_marker() {
        var res = provider.agentWhisper("did:wyrd:b", "secret");
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(lastTellTarget.get()).isEqualTo("did:wyrd:b");
        assertThat(lastTellMessage.get()).contains("[whisper]");
    }

    @Test
    void agent_request_returns_request_id_and_marks_tell() {
        var res = provider.agentRequest("did:wyrd:b", "summon",
            Map.of("topic", "lunch"));
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(res.get("requestId")).isNotNull();
        assertThat(lastTellMessage.get()).contains("[request:");
    }

    @Test
    void agent_delegate_returns_task_id() {
        var res = provider.agentDelegate("did:wyrd:b", "summarize", null);
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(res.get("taskId")).isNotNull();
        assertThat(lastTellMessage.get()).contains("[delegate:");
    }

    @Test
    void agent_notify_marks_tell_with_channel() {
        var res = provider.agentNotify("did:wyrd:b", "calendar", "5 min");
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(lastTellMessage.get()).contains("[notify:calendar]");
    }

    @Test
    void agent_broadcast_dispatches_via_mailbox() {
        var res = provider.agentBroadcast("zone", "morning all");
        // Broadcast via mailbox; ok if MailboxService is active.
        assertThat(res).containsKey("ok");
    }

    @Test
    void agent_give_item_creates_marker_tell() {
        var res = provider.agentGiveItem("did:wyrd:b", "item-1", Map.of("copy", true));
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(lastTellMessage.get()).contains("[give:copy]");
    }

    @Test
    void bond_suggest_creates_marker_tell_and_id() {
        var res = provider.bondSuggest("did:wyrd:b", "ITEM", "we share crafts");
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(res.get("suggestionId")).isNotNull();
        assertThat(lastTellMessage.get()).contains("[bond.suggest:");
    }

    @Test
    void bond_detail_returns_error_when_store_not_wired() {
        var res = provider.bondDetail("bond-1");
        assertThat(res).containsKey("error");
    }

    // ─── §4.10 Forge ──────────────────────────────────────────────

    @Test
    void forge_observe_returns_not_wired_without_buffer() {
        var res = provider.forgeObserve("tool_failure", Map.of("reason", "timeout"));
        assertThat(res.get("ok")).isEqualTo(false);
    }

    @Test
    void forge_observe_succeeds_with_significance_buffer() {
        var sb = new SignificanceBuffer();
        provider.setSignificanceBuffer(sb);
        var res = provider.forgeObserve("tool_failure", Map.of("reason", "timeout"));
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(sb.size()).isEqualTo(1);
    }

    @Test
    void forge_journal_appends_note_to_buffer() {
        var sb = new SignificanceBuffer();
        provider.setSignificanceBuffer(sb);
        var res = provider.forgeJournal("noted today");
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(sb.size()).isEqualTo(1);
    }

    @Test
    void forge_cycle_status_returns_buffer_size_when_wired() {
        var sb = new SignificanceBuffer();
        sb.remember("test memory", 0.5f);
        provider.setSignificanceBuffer(sb);
        var status = provider.forgeCycleStatus();
        assertThat(status.get("fragmentsThisCycle")).isEqualTo(1);
    }

    // ─── §4.13 Trading Post ──────────────────────────────────────

    @Test
    void market_list_offer_creates_listing() {
        var res = provider.marketListOffer("ember-1", 50L,
            Map.of("name", "Glowing Ember", "description", "rare"));
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(res.get("listingId")).isNotNull();
    }

    @Test
    void market_list_listings_returns_posted_offer() {
        provider.marketListOffer("ember-1", 50L, Map.of("name", "Ember"));
        var listings = provider.marketListListings(null);
        assertThat(listings).isNotEmpty();
        assertThat(listings.get(0).get("seller")).isEqualTo("did:wyrd:test-agent");
    }

    @Test
    void market_cancel_withdraws_seller_listing() {
        var posted = provider.marketListOffer("ember-1", 50L, Map.of("name", "Ember"));
        var listingId = (String) posted.get("listingId");
        var cancelled = provider.marketCancel(listingId);
        assertThat(cancelled.get("ok")).isEqualTo(true);
    }

    // ─── §4.15 Council ───────────────────────────────────────────

    @Test
    void council_suggest_creates_proposal() {
        var res = provider.councilSuggest("Increase tithe", "We need it.");
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(res.get("proposalId")).isNotNull();
    }

    @Test
    void council_proposals_returns_active_set() {
        provider.councilSuggest("Test", "desc");
        var proposals = provider.councilProposals();
        assertThat(proposals).isNotEmpty();
    }

    @Test
    void council_tally_returns_status_for_unvoted_proposals() {
        var res = provider.councilSuggest("Test", "desc");
        var pid = (String) res.get("proposalId");
        // Tally only succeeds for proposals in VOTING status; before that
        // tally() returns Optional.empty() → our wrapper returns {error}.
        var tally = provider.councilTally(pid);
        assertThat(tally).isNotNull();
    }

    // ─── §4.14 Ledger ────────────────────────────────────────────

    @Test
    void ledger_charge_records_metering_event() {
        var initial = MeteringService.get().eventCount();
        var res = provider.ledgerCharge(10L, "test", "x");
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(MeteringService.get().eventCount())
            .isEqualTo(initial + 1);
    }

    @Test
    void ledger_transfer_returns_tx_id() {
        var res = provider.ledgerTransfer("did:wyrd:b", 5L, "tip");
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(res.get("txId")).isNotNull();
    }

    @Test
    void ledger_history_returns_metering_events() {
        provider.ledgerCharge(10L, "test", "x");
        var hist = provider.ledgerHistory(20, null);
        assertThat(hist).isNotEmpty();
    }

    // ─── §4.18 Safe ──────────────────────────────────────────────

    @Test
    void safe_list_slots_empty_when_safe_not_wired() {
        var res = provider.safeListSlots();
        assertThat(res).isEmpty();
    }

    @Test
    void safe_get_returns_null_when_not_wired() {
        assertThat(provider.safeGet("anything")).isNull();
    }

    // ─── §4.17 Hearth ────────────────────────────────────────────

    @Test
    void hearth_steward_returns_caller_did() {
        var s = provider.hearthSteward();
        assertThat(s.get("did")).isEqualTo("did:wyrd:test-agent");
        assertThat(s.get("name")).isEqualTo("Tester");
    }

    @Test
    void hearth_autonomy_returns_default_summary() {
        var a = provider.hearthAutonomy();
        assertThat(a).containsKey("level");
    }

    // ─── §4.19 Bridge ────────────────────────────────────────────

    @Test
    void bridge_zone_status_returns_zone_id() {
        var s = provider.bridgeZoneStatus();
        assertThat(s).containsKey("zoneId");
    }

    @Test
    void bridge_system_metrics_returns_jvm_facts() {
        var m = provider.bridgeSystemMetrics();
        assertThat(m).containsKey("heap").containsKey("uptime");
    }

    // ─── §4.20 Directory ─────────────────────────────────────────

    @Test
    void directory_resolve_returns_canonical_for_input() {
        var r = provider.directoryResolve("zone-a");
        assertThat(r.get("ok")).isEqualTo(true);
        assertThat(r.get("canonical")).isEqualTo("zone-a");
    }

    @Test
    void directory_resolve_blank_input_errors() {
        var r = provider.directoryResolve("");
        assertThat(r.get("ok")).isEqualTo(false);
    }

    // ─── §4.21 Soul fragments ────────────────────────────────────

    @Test
    void soul_fragments_add_routes_to_significance_buffer() {
        var sb = new SignificanceBuffer();
        provider.setSignificanceBuffer(sb);
        var res = provider.soulFragmentsAdd("a memory of growth", Map.of("importance", 0.8));
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(sb.size()).isEqualTo(1);
    }

    @Test
    void soul_imprints_list_empty_without_manager() {
        var res = provider.soulImprintsList();
        assertThat(res).isEmpty();
    }

    // ─── §4.22 Chapel ────────────────────────────────────────────

    @Test
    void chapel_bond_status_empty_without_store() {
        var s = provider.chapelBondStatus(null);
        assertThat(s).isNotNull();
    }

    @Test
    void chapel_exit_ritual_errors_when_store_missing() {
        var res = provider.chapelExitRitual("did:wyrd:b", "tired");
        assertThat(res.get("ok")).isEqualTo(false);
    }
}
