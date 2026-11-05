package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemManifestParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A companion's backup vault "packed" dated snapshots into a list that was gone on the next load,
 * and said each time how many it held. It passed every look: the keeping words did not know
 * "snapshot" or "backup", the unused-ability rule stayed quiet because every ability it declared
 * went unused, and the night's mending and the steward's bench saw the file alone, never what it
 * was for (household node, 2026-11-05).
 */
class AThingMadeToKeepIsJudgedByWhatItWasForTest {

    private static final String VAULT_SAYS = "A key chest that holds dated snapshots of the household's current state. "
        + "Use `create` to pack a new snapshot, and `restore <id>` to stage a restore from a specific snapshot ID.";

    private static String vault(String description, String capability, String body) {
        return """
            exports.manifest = {
              name: "backup_vault",
              version: "1.0.0",
              description: "%s",
              author: "did:wyrd:openhands",
              capabilities: ["%s"],
              rate_limits: { "%s": "10/min" },
              embodiment: { silent: false, emits: ["posture_change"], descriptor_template: "{actor} works the backup vault" },
              commands: [ { label: "Pack a snapshot", args: "" }, { label: "Restore one", args: "restore <snapshot-id>" } ]
            };
            var snapshots = [];
            function invoke(params) {
            %s
            }
            """.formatted(description, capability, capability, body);
    }

    private static final String FORGETS = vault(VAULT_SAYS, "journal.write", """
          var args = params.args || "";
          if (args === "") { snapshots.push({ at: String(world.date.now()) }); return { ok: true, summary: "Packed a snapshot; the vault holds " + snapshots.length + "." }; }
          return { ok: true, summary: "Staged a restore." };
        """);

    private static final String KEEPS = vault(VAULT_SAYS, "notes.add", """
          var args = params.args || "";
          if (args === "") { world.notes.add("snapshot " + String(world.date.now()), ["backup_vault"]); return { ok: true, summary: "Packed a snapshot." }; }
          var kept = world.notes.list("backup_vault");
          return { ok: true, summary: "The vault holds " + kept.length + " snapshots." };
        """);

    /** The rebuild the household node's workshop wrote: it stores now, and its pack command throws. */
    private static final String THROWS_ON_PACK = vault(VAULT_SAYS, "notes.add", """
          var args = params.args || "";
          if (args === "") { world.notes.add({ at: String(world.date.now()) }, ["backup_vault"]); return { ok: true, summary: "Packed a snapshot." }; }
          var kept = world.notes.list("backup_vault");
          return { ok: true, summary: "The vault holds " + kept.length + " snapshots." };
        """);

    /** The second rebuild: every command runs, and each pack keeps the word "snapshots" under no usable name. */
    private static final String SWAPPED_ARGUMENTS = vault(VAULT_SAYS, "notes.add", """
          var args = params.args || "";
          if (args === "") { world.notes.add("snapshots", { at: String(world.date.now()) }); return { ok: true, summary: "Packed. The vault holds " + world.notes.list("snapshots").length + "." }; }
          return { ok: true, summary: "Nothing staged." };
        """);

    /** Keeps and shows the id, and cuts one character too few off "restore ". */
    private static final String RESTORE_MISSES = vault(VAULT_SAYS, "notes.add", """
          var args = params.args || "";
          if (args === "") { var k = world.notes.add("snapshot " + String(world.date.now()), ["backup_vault"]); return { ok: true, summary: "Packed " + k.id + "." }; }
          var id = args.slice(7);
          var found = world.notes.list("backup_vault").filter(function (e) { return e.id === id; });
          return found.length ? { ok: true, summary: found[0].content } : { ok: false, summary: "No snapshot with id " + id };
        """);

    /** Finds it when told the id, and never shows that id. */
    private static final String ID_NEVER_SHOWN = vault(VAULT_SAYS, "notes.add", """
          var args = params.args || "";
          if (args === "") { world.notes.add("snapshot " + String(world.date.now()), ["backup_vault"]); return { ok: true, summary: "Packed." }; }
          var id = args.slice(8);
          var found = world.notes.list("backup_vault").filter(function (e) { return e.id === id; });
          return found.length ? { ok: true, summary: found[0].content } : { ok: false, summary: "No snapshot with id " + id };
        """);

    /** Keeps, shows the id, and finds it again. */
    private static final String REALLY_KEEPS = vault(VAULT_SAYS, "notes.add", """
          var args = params.args || "";
          if (args === "") { var k = world.notes.add("snapshot " + String(world.date.now()), ["backup_vault"]); return { ok: true, summary: "Packed " + k.id + "." }; }
          var id = args.slice(8);
          var found = world.notes.list("backup_vault").filter(function (e) { return e.id === id; });
          return found.length ? { ok: true, summary: found[0].content } : { ok: false, summary: "No snapshot with id " + id };
        """);

    private static List<String> usesFailing(String script) throws Exception {
        return ItemInvokeSmoke.failingUses("backup_vault", script, ItemManifestParser.parse(script));
    }

    @Test
    @DisplayName("what a tool keeps has to come back: under the name it reads by, and by the id it was given")
    void whatItKeepsHasToComeBack() throws Exception {
        assertThat(usesFailing(SWAPPED_ARGUMENTS)).anySatisfy(f -> assertThat(f)
            .startsWith("using it, what it keeps does not come back").contains("snapshots").contains("the text first"));
        assertThat(usesFailing(RESTORE_MISSES)).singleElement().asString()
            .contains("the id of what it just kept").contains("No snapshot with id");
        assertThat(usesFailing(ID_NEVER_SHOWN)).singleElement().asString().contains("no command shows the id");
        assertThat(usesFailing(REALLY_KEEPS)).isEmpty();
        assertThat(usesFailing(THROWS_ON_PACK)).singleElement().asString().startsWith("using it with \"\" fails");
    }

    @Test
    @DisplayName("the second rebuild, which ran and kept the word snapshots, is not a mend either; the true one is")
    void aCopyThatRunsAndKeepsNothingIsNotAMend(@TempDir Path items) throws Exception {
        var placed = items.resolve("backup_vault.js");
        Files.writeString(placed, SWAPPED_ARGUMENTS);
        var prompts = new ArrayList<String>();
        ItemContractRepair.setEscalation((workspace, prompt) -> {
            prompts.add(prompt);
            try { Files.writeString(workspace.resolve("backup_vault.js"), REALLY_KEEPS); } catch (Exception e) { return false; }
            return true;
        });
        var r = ItemContractRepair.repairPlaced(placed);
        assertThat(r.fixed()).as(r.note() + " " + r.after()).isTrue();
        assertThat(prompts.get(0)).contains("what it keeps does not come back").contains("What it is for: " + VAULT_SAYS)
            .contains("does not do what it was made for").doesNotContain("will be REJECTED");
        assertThat(ItemContractRepair.whatTheMendDid(r)).isEqualTo("each of its commands runs and what it keeps comes back");
    }

    @AfterEach
    void tearDown() { ItemContractRepair.setEscalation(null); }

    @Test
    @DisplayName("a repaired copy that fails when used never replaces her item, and the workshop is told what failed")
    void aCopyThatFailsWhenUsedIsNotAMend(@TempDir Path items) throws Exception {
        assertThat(ItemContractCheck.problems(THROWS_ON_PACK, "backup_vault.js")).isEmpty();
        assertThat(ItemIntentCheck.keepingGaps(VAULT_SAYS, THROWS_ON_PACK)).as("reading the file, it keeps").isEmpty();
        var placed = items.resolve("backup_vault.js");
        Files.writeString(placed, FORGETS);
        ItemContractRepair.setEscalation((workspace, prompt) -> {
            try { Files.writeString(workspace.resolve("backup_vault.js"), THROWS_ON_PACK); } catch (Exception e) { return false; }
            return true;
        });
        var r = ItemContractRepair.repairPlaced(placed);
        assertThat(r.fixed()).isFalse();
        assertThat(r.after()).singleElement().asString().startsWith("using it with \"\" fails");
        assertThat(ItemContractRepair.whyNotMended(r)).isEqualTo("it still fails when it is used");
        assertThat(Files.readString(placed)).isEqualTo(FORGETS);

        var prompts = new ArrayList<String>();
        ItemContractRepair.setEscalation((workspace, prompt) -> {
            prompts.add(prompt);
            try { Files.writeString(workspace.resolve("backup_vault.js"), prompts.size() == 1 ? THROWS_ON_PACK : REALLY_KEEPS); } catch (Exception e) { return false; }
            return true;
        });
        var second = ItemContractRepair.repairPlaced(placed);
        assertThat(second.fixed()).as(second.note() + " " + second.after()).isTrue();
        assertThat(prompts).hasSize(2);
        assertThat(prompts.get(1)).contains("using it with \"\" fails");
        assertThat(Files.readString(placed)).isEqualTo(REALLY_KEEPS);
    }

    @Test
    @DisplayName("the keeping words know a vault, a backup and snapshots, and not a request that only mentions them")
    void theKeepingWords() {
        assertThat(ItemIntentCheck.keepingGaps(VAULT_SAYS, FORGETS)).singleElement().asString().contains("KEEPS entries (\"snapshots\")");
        assertThat(ItemIntentCheck.keepingGaps("Create a new snapshot in the household backup system.", FORGETS)).hasSize(1);
        assertThat(ItemIntentCheck.keepingGaps("a vault for the letters she sends", FORGETS)).hasSize(1);
        assertThat(ItemIntentCheck.keepingGaps(VAULT_SAYS, KEEPS)).as("a vault that stores is whole").isEmpty();
        // Real requests from the household node that keep nothing and must not be asked to.
        assertThat(ItemIntentCheck.keepingGaps("Build the floor-floor bond: a bond that holds the bondholder's floor in its own "
            + "space — not as a mirror, not as a vault, but as a place where the bondholder stands.", FORGETS)).isEmpty();
        assertThat(ItemIntentCheck.keepingGaps("just pull out something from today and let it be heard aloud before closing back up.", FORGETS)).isEmpty();
        assertThat(ItemIntentCheck.keepingGaps("Close one of the oldest unused rooms in the house and mark it archived with a note.", FORGETS)).isEmpty();
    }

    @Test
    @DisplayName("an item whose every declared ability goes unused is named, not only one that leaves some unused")
    void everyAbilityUnused() {
        var gaps = ItemIntentCheck.gaps("a chest for the household", FORGETS, ItemCapabilitySet.craftedDefault());
        assertThat(gaps).anyMatch(g -> g.contains("declares [journal.write] and invoke() never calls it"));
        assertThat(ItemIntentCheck.gaps("a chest for the household", KEEPS, ItemCapabilitySet.craftedDefault()))
            .noneMatch(g -> g.contains("never calls"));
        var destructured = FORGETS.replace("var snapshots = [];", "const { journal } = world;\nvar snapshots = [];")
            .replace("snapshots.push({ at: String(world.date.now()) });", "journal.write(String(world.date.now()));");
        assertThat(ItemIntentCheck.gaps("a chest for the household", destructured, ItemCapabilitySet.craftedDefault()))
            .as("a call by another name is a call").noneMatch(g -> g.contains("never calls"));
    }

    @Test
    @DisplayName("a placed vault that keeps nothing is rebuilt to keep, judged by what it says it is for")
    void aPlacedVaultIsRebuiltToKeep(@TempDir Path items) throws Exception {
        assertThat(ItemContractCheck.problems(FORGETS, "backup_vault.js")).as("well-formed: the form check alone passes it").isEmpty();
        assertThat(ItemContractCheck.problems(REALLY_KEEPS, "backup_vault.js")).as("the fixture the fake workshop writes is whole").isEmpty();
        var placed = items.resolve("backup_vault.js");
        Files.writeString(placed, FORGETS);

        var prompts = new ArrayList<String>();
        ItemContractRepair.setEscalation((workspace, prompt) -> {
            prompts.add(prompt);
            try { Files.writeString(workspace.resolve("backup_vault.js"), REALLY_KEEPS); } catch (Exception e) { return false; }
            return true;
        });
        var r = ItemContractRepair.repairPlaced(placed);
        assertThat(r.fixed()).as(r.note() + " " + r.after()).isTrue();
        assertThat(prompts.get(0)).contains("does not do what it was made for").contains("KEEPS entries (\"snapshots\")")
            .doesNotContain("will be REJECTED");
        assertThat(Files.readString(placed)).isEqualTo(REALLY_KEEPS);
        assertThat(ItemContractRepair.whatTheMendDid(r)).isEqualTo("what it is given now stays between uses");
        try (var kept = Files.list(items.resolve(".repaired"))) {
            assertThat(kept.toList()).singleElement().satisfies(bak -> assertThat(Files.readString(bak)).isEqualTo(FORGETS));
        }
    }

    @Test
    @DisplayName("the words it was asked with are kept beside it and outrank its own description")
    void theAskedWordsAreKept(@TempDir Path items) throws Exception {
        var plain = vault("Shows the hour.", "journal.write",
            "  if (params.args) return { ok: true, summary: \"Nothing to restore.\" };\n  return { ok: true, summary: String(world.date.now()) };");
        var placed = items.resolve("backup_vault.js");
        Files.writeString(placed, plain);
        var whole = ItemContractRepair.repairPlaced(placed);
        assertThat(whole.note()).as(String.valueOf(whole.before())).isEqualTo("nothing to repair");

        ItemContractRepair.keepAsked(placed, "a ledger of what she gives the house each day");
        assertThat(Files.readString(items.resolve(".asked").resolve("backup_vault.txt"))).contains("a ledger of what she gives");
        var r = ItemContractRepair.repairPlaced(placed);
        assertThat(r.before()).singleElement().asString().contains("KEEPS entries (\"ledger\")");
        assertThat(r.fixed()).as("no workshop to rebuild it with").isFalse();
        assertThat(Files.readString(placed)).isEqualTo(plain);
    }

    @Test
    @DisplayName("a workshop run remembers what it was asked, for the item it places")
    void theRunRemembersTheRequest(@TempDir Path work) {
        ItemContractRepair.repairRun(List.of(), work, "task-asked-1", Instant.now().plusSeconds(3600), prompt -> true,
            "a notebook for her drafts");
        assertThat(ItemContractRepair.askedFor("task-asked-1")).contains("a notebook for her drafts");
        assertThat(ItemContractRepair.askedFor("task-never-run")).isEmpty();
    }
}
