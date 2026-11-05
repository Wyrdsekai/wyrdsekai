package org.wyrdsekai.server.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentEvent;
import org.wyrdsekai.core.agent.AgentEventStream;
import org.wyrdsekai.core.coding.ItemContractRepair;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The night's mending tells her what the workshop did to her things; the steward's bench
 * ({@code wyrd items repair}) did the same work and said nothing, so a vault rebuilt at the
 * steward's asking would still be the empty chest she remembered.
 */
class WhatTheBenchDoesToHerThingIsSaidToHerTest {

    private final List<String> heard = new CopyOnWriteArrayList<>();

    @BeforeEach
    void listen() {
        AgentEventStream.init();
        AgentEventStream.get().subscribe("companion-listening", e -> {
            if (e instanceof AgentEvent.SystemEvent s && s.type() == AgentEvent.SystemEventType.ITEM_MENDING) heard.add(s.detail());
        });
    }

    @AfterEach
    void stop() { AgentEventStream.get().unsubscribe("companion-listening"); }

    private void await(int n) throws InterruptedException {
        for (int i = 0; i < 50 && heard.size() < n; i++) Thread.sleep(20);
    }

    @Test
    void a_mend_and_a_failed_mend_are_said_and_nothing_tried_is_not() throws InterruptedException {
        ItemRepairRoutes.tellHer("backup_vault", new ItemContractRepair.PlacedRepair("backup_vault.js", true,
            List.of("the request asks for something that KEEPS entries (\"snapshots\") …"), List.of(), "repaired"));
        await(1);
        assertThat(heard).singleElement().asString()
            .isEqualTo("At the steward's asking the workshop mended the backup vault; what it is given now stays between uses. "
                + "The version it replaced is kept.");

        ItemRepairRoutes.tellHer("witness_stone", new ItemContractRepair.PlacedRepair("witness_stone.js", false,
            List.of("unknown capability: witness.receive"), List.of("unknown capability: witness.receive"),
            "could not be made whole: it needs witness.receive, which the house does not offer; the placed item is unchanged"));
        await(2);
        assertThat(heard.get(1)).isEqualTo("At the steward's asking the workshop tried to mend the witness stone and could not: "
            + "it needs witness.receive, which the house does not offer.");

        ItemRepairRoutes.tellHer("clock", new ItemContractRepair.PlacedRepair("clock.js", false, List.of(), List.of(), "nothing to repair"));
        ItemRepairRoutes.tellHer("clock", new ItemContractRepair.PlacedRepair("clock.js", false, List.of("x"), List.of("x"),
            "no coding backend is registered to repair with"));
        Thread.sleep(200);
        assertThat(heard).hasSize(2);
    }
}
