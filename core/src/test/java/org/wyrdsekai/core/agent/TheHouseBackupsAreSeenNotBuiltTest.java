package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.item.ToolItemStarterKit;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A companion who could not see the house's backups had the workshop make a toy vault and took it
 * for the household's safety net (household node, 2026-11-05). The house's own record is in her hand:
 * house_backups answers at once, is kept on her menu when backups are on her mind, and her word to
 * check them is kept with it.
 */
class TheHouseBackupsAreSeenNotBuiltTest {

    @Test
    void the_record_is_a_tool_in_her_kit_that_builds_nothing() {
        var tool = ToolItemStarterKit.inherentActions().stream().filter(t -> "house_backups".equals(t.id())).findFirst();
        assertThat(tool).isPresent();
        assertThat(tool.get().builtinHandler()).isEqualTo("house_backups");
        assertThat(ActionPolicy.autonomyTierFor("house_backups")).isEqualTo(ActionPolicy.AutonomyTier.AMBIENT);
    }

    @Test
    void her_word_to_check_the_backups_is_kept_with_the_house_record() {
        var offered = List.of("house_backups", "what_i_have", "dispatch_task", "backup_vault");
        assertThat(HerWord.named("I'll check the house's backups before anything else.", offered).tool()).isEqualTo("house_backups");
        assertThat(HerWord.named("Let me look at the backups and see if the safety net holds.", offered).tool()).isEqualTo("house_backups");
        assertThat(HerWord.named("I'll check my inventory first.", offered).tool()).isEqualTo("what_i_have");
        assertThat(HerWord.named("I'll take the backup vault to the bench and fix it.", offered).tool())
            .as("mending her own vault is still the workshop's").isNotEqualTo("house_backups");
    }

    @Test
    void backups_on_her_mind_keep_the_record_in_her_hand() {
        assertThat(CompanionActor.BACKUPS_ON_HER_MIND.matcher("the backup vault is broken again — it holds the household's state").find()).isTrue();
        assertThat(CompanionActor.BACKUPS_ON_HER_MIND.matcher("is the house backed up?").find()).isTrue();
        assertThat(CompanionActor.BACKUPS_ON_HER_MIND.matcher("the reading corner needs a lamp").find()).isFalse();
    }
}
