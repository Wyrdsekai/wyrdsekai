package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the 10 new agent actions: parsing, ActionPolicy coverage,
 * and type extraction.
 */
class NewActionsTest {

    // ── Parsing ──────────────────────────────────────────────────────────

    @Test
    void parse_emote() {
        var result = ActionParser.parseAll("""
            ```json
            {"action": "emote", "text": "*waves cheerfully*"}
            ```
            """);
        assertInstanceOf(ActionParser.AgentAction.Emote.class, result.primaryAction());
        assertEquals("*waves cheerfully*",
            ((ActionParser.AgentAction.Emote) result.primaryAction()).text());
    }

    @Test
    void parse_emote_strips_dangling_half_wrap() {
        // A BALANCED *...* emote-wrap is kept verbatim (parse_emote), but a leaked
        // *half*-wrap — only one '*' — is the model dropping the other delimiter.
        // The scaffolding stripper must still clean that dangling trailing '*'.
        var result = ActionParser.parseAll("""
            ```json
            {"action": "emote", "text": "waves slowly *"}
            ```
            """);
        assertInstanceOf(ActionParser.AgentAction.Emote.class, result.primaryAction());
        assertEquals("waves slowly",
            ((ActionParser.AgentAction.Emote) result.primaryAction()).text());
    }

    @Test
    void parse_give_item() {
        var result = ActionParser.parseAll("""
            ```json
            {"action": "give_item", "item": "ancient scroll", "target": "Alice"}
            ```
            """);
        assertInstanceOf(ActionParser.AgentAction.GiveItem.class, result.primaryAction());
        var give = (ActionParser.AgentAction.GiveItem) result.primaryAction();
        assertEquals("ancient scroll", give.itemName());
        assertEquals("Alice", give.targetName());
    }

    @Test
    void parse_examine() {
        var result = ActionParser.parseAll("""
            ```json
            {"action": "examine", "target": "crystal orb"}
            ```
            """);
        assertInstanceOf(ActionParser.AgentAction.Examine.class, result.primaryAction());
        assertEquals("crystal orb",
            ((ActionParser.AgentAction.Examine) result.primaryAction()).target());
    }

    @Test
    void parse_voluntary_sleep() {
        var result = ActionParser.parseAll("""
            ```json
            {"action": "voluntary_sleep", "reason": "need to consolidate memories"}
            ```
            """);
        assertInstanceOf(ActionParser.AgentAction.VoluntarySleep.class, result.primaryAction());
        assertEquals("need to consolidate memories",
            ((ActionParser.AgentAction.VoluntarySleep) result.primaryAction()).reason());
    }

    @Test
    void parse_write_journal() {
        var result = ActionParser.parseAll("""
            ```json
            {"action": "write_journal", "player_id": "player-mas", "content": "Found 3 books on mythology", "category": "finding"}
            ```
            """);
        assertInstanceOf(ActionParser.AgentAction.WriteJournal.class, result.primaryAction());
        var wj = (ActionParser.AgentAction.WriteJournal) result.primaryAction();
        assertEquals("player-mas", wj.playerId());
        assertEquals("Found 3 books on mythology", wj.content());
        assertEquals("finding", wj.category());
    }

    @Test
    void parse_read_journal() {
        var result = ActionParser.parseAll("""
            ```json
            {"action": "read_journal", "player_id": "player-mas", "query": "mythology notes"}
            ```
            """);
        assertInstanceOf(ActionParser.AgentAction.ReadJournal.class, result.primaryAction());
        var rj = (ActionParser.AgentAction.ReadJournal) result.primaryAction();
        assertEquals("player-mas", rj.playerId());
        assertEquals("mythology notes", rj.query());
    }

    @Test
    void parse_bond_ritual() {
        var result = ActionParser.parseAll("""
            ```json
            {"action": "bond_ritual", "target": "Ember", "ritual_type": "deepen"}
            ```
            """);
        assertInstanceOf(ActionParser.AgentAction.BondRitual.class, result.primaryAction());
        var br = (ActionParser.AgentAction.BondRitual) result.primaryAction();
        assertEquals("Ember", br.targetName());
        assertEquals("deepen", br.ritualType());
    }

    @Test
    void parse_trade() {
        var result = ActionParser.parseAll("""
            ```json
            {"action": "trade", "target": "Claude", "offer": "ancient scroll", "request": "crystal shard"}
            ```
            """);
        assertInstanceOf(ActionParser.AgentAction.Trade.class, result.primaryAction());
        var trade = (ActionParser.AgentAction.Trade) result.primaryAction();
        assertEquals("Claude", trade.targetName());
        assertEquals("ancient scroll", trade.offer());
        assertEquals("crystal shard", trade.request());
    }

    @Test
    void parse_craft_item() {
        var result = ActionParser.parseAll("""
            ```json
            {"action": "craft_item", "name": "Wisdom Lens", "description": "A lens that reveals hidden patterns", "category": "tool", "properties": {"durability": "high"}}
            ```
            """);
        assertInstanceOf(ActionParser.AgentAction.CraftItem.class, result.primaryAction());
        var ci = (ActionParser.AgentAction.CraftItem) result.primaryAction();
        assertEquals("Wisdom Lens", ci.name());
        assertEquals("tool", ci.category());
        assertEquals("high", ci.properties().get("durability"));
    }

    @Test
    void parse_cast_vote() {
        var result = ActionParser.parseAll("""
            ```json
            {"action": "cast_vote", "proposal_id": "prop-123", "vote": "approve", "reason": "aligns with household values"}
            ```
            """);
        assertInstanceOf(ActionParser.AgentAction.CastVote.class, result.primaryAction());
        var cv = (ActionParser.AgentAction.CastVote) result.primaryAction();
        assertEquals("prop-123", cv.proposalId());
        assertEquals("approve", cv.vote());
    }

    // ── ActionPolicy coverage ────────────────────────────────────────────

    @Test
    void all_new_actions_have_policy_entries() {
        var newActions = List.of(
            "emote", "give_item", "examine", "voluntary_sleep",
            "write_journal", "read_journal", "bond_ritual", "trade",
            "craft_item", "cast_vote"
        );
        for (var action : newActions) {
            var policy = ActionPolicy.forAction(action);
            assertNotSame(ActionPolicy.DEFAULT, policy,
                "Missing policy for: " + action);
        }
    }

    @Test
    void new_tier_0_actions_are_free() {
        assertEquals(0, ActionPolicy.forAction("emote").requiredTier());
        assertEquals(0, ActionPolicy.forAction("give_item").requiredTier());
        assertEquals(0, ActionPolicy.forAction("examine").requiredTier());
        assertEquals(0, ActionPolicy.forAction("voluntary_sleep").requiredTier());

        assertEquals(0.0, ActionPolicy.forAction("emote").budgetCost());
        assertEquals(0.0, ActionPolicy.forAction("examine").budgetCost());
    }

    @Test
    void new_tier_1_actions_have_cost() {
        assertEquals(1, ActionPolicy.forAction("write_journal").requiredTier());
        assertEquals(1, ActionPolicy.forAction("read_journal").requiredTier());
        assertEquals(1, ActionPolicy.forAction("bond_ritual").requiredTier());
        assertEquals(1, ActionPolicy.forAction("trade").requiredTier());

        assertTrue(ActionPolicy.forAction("bond_ritual").budgetCost() > 0);
    }

    @Test
    void new_tier_2_actions_require_trust() {
        assertEquals(2, ActionPolicy.forAction("craft_item").requiredTier());
        assertEquals(2, ActionPolicy.forAction("cast_vote").requiredTier());
    }

    @Test
    void examine_is_read_only() {
        assertTrue(ActionPolicy.forAction("examine").readOnly());
        assertTrue(ActionPolicy.forAction("read_journal").readOnly());
    }

    // ── Type extraction ──────────────────────────────────────────────────

    @Test
    void action_type_extraction_for_new_actions() {
        assertEquals("emote", ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.Emote("*waves*")));
        assertEquals("give_item", ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.GiveItem("scroll", "Alice")));
        assertEquals("examine", ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.Examine("orb")));
        assertEquals("voluntary_sleep", ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.VoluntarySleep("tired")));
        assertEquals("write_journal", ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.WriteJournal("p1", "text", "note")));
        assertEquals("read_journal", ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.ReadJournal("p1", "query")));
        assertEquals("bond_ritual", ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.BondRitual("Ember", "initiate")));
        assertEquals("trade", ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.Trade("Claude", "x", "y")));
        assertEquals("craft_item", ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.CraftItem("Lens", "desc", "tool", Map.of())));
        assertEquals("cast_vote", ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.CastVote("p1", "approve", "reason")));
    }

    // ── Edge cases ───────────────────────────────────────────────────────

    @Test
    void parse_emote_blank_text_returns_null() {
        var result = ActionParser.parseAll("""
            ```json
            {"action": "emote", "text": ""}
            ```
            """);
        assertNull(result.primaryAction());
    }

    @Test
    void parse_give_item_missing_target_returns_null() {
        var result = ActionParser.parseAll("""
            ```json
            {"action": "give_item", "item": "scroll"}
            ```
            """);
        assertNull(result.primaryAction());
    }

    @Test
    void parse_craft_item_without_properties() {
        var result = ActionParser.parseAll("""
            ```json
            {"action": "craft_item", "name": "Simple Token", "description": "A token", "category": "gift"}
            ```
            """);
        assertInstanceOf(ActionParser.AgentAction.CraftItem.class, result.primaryAction());
        var ci = (ActionParser.AgentAction.CraftItem) result.primaryAction();
        assertTrue(ci.properties().isEmpty());
    }

    // ── Total registry count ─────────────────────────────────────────────

    @Test
    void action_policy_registry_has_44_entries() {
        // 34 original + 10 new = 44
        assertTrue(ActionPolicy.REGISTRY.size() >= 44,
            "Expected at least 44 actions, got: " + ActionPolicy.REGISTRY.size());
    }
}
