package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A capability nobody is told about is one nobody has.
 *
 * <h2>What went wrong</h2>
 * {@code world.host.*} was built, gated on {@code WYRDSEKAI_HOST_OPEN_ROOTS}, audit-logged
 * and unable to leave the granted roots. The generated contract mentioned it <b>zero</b>
 * times and {@code CRAFTED_ALLOW} did not permit it. Asked on 2026-08-22 for a tool to
 * review and sort a media folder the steward had explicitly granted, the authoring model
 * found no filesystem verb and invented
 * {@code world.web.fetch("/data/.../listings/raw.txt")} — a listing file that never
 * existed. The steward's reaction was the right one: <i>"I thought we built a way to
 * enable this."</i> It had been built. It had never been joined.
 */
class AGrantedDirectoryIsOfferedTest {

    @Test
    @DisplayName("the ceiling permits reading and rearranging inside a granted root")
    void theCeilingPermitsTheGrantedVerbs() {
        var crafted = ItemCapabilitySet.craftedDefault();
        assertThat(crafted.has("host.file_find")).isTrue();
        assertThat(crafted.has("host.dir_make")).isTrue();
        assertThat(crafted.has("host.file_move")).isTrue();
    }

    @Test
    @DisplayName("running programs on the steward's machine is still not granted")
    void theCeilingStillWithholdsExecution() {
        var crafted = ItemCapabilitySet.craftedDefault();
        // A directory grant says nothing about launching applications or opening URLs.
        assertThat(crafted.has("host.app_launch")).isFalse();
        assertThat(crafted.has("host.url_open")).isFalse();
        assertThat(crafted.has("host.file_open")).isFalse();
    }

    @Test
    @DisplayName("the contract states that world.* is synchronous")
    void theCallingConventionIsStated() {
        var block = ItemApiSurface.callingConventionBlock();
        // The first item written against the new host surface guessed a Promise —
        // `world.host.find(glob, 1000).then(...)` — and failed its smoke. Reasonable, for
        // something that touches a disk; wrong, and nothing had said so.
        assertThat(block).contains("SYNCHRONOUS");
        assertThat(block).contains(".then");
        assertThat(block).isNotBlank();
    }

    @Test
    @DisplayName("the contract says which field the person actually hears")
    void theContractNamesTheSpokenField() {
        var block = ItemApiSurface.callingConventionBlock();
        // venture_scout put "generated three radical business ideas with TAM estimates" in
        // `summary` and the three ideas in `details`. The room speaks `summary`, so the
        // steward heard a description of the work instead of the work.
        assertThat(block).contains("summary");
        assertThat(block).contains("WHAT THE PERSON HEARS");
        assertThat(block).contains("details");
    }

    @Test
    @DisplayName("the contract says arguments arrive as one string the item must split")
    void theContractStatesArgumentShape() {
        // trip_compass was asked "Denver, CO to Boston, MA" and answered that the distance
        // between Denver and Denver is 0 miles: it read params.args as one city. The
        // contract had named params.args and never said what a multi-value ask looks like.
        var block = ItemApiSurface.callingConventionBlock();
        assertThat(block).contains("ARGUMENTS ARRIVE AS ONE STRING");
        assertThat(block).contains("YOU split it");
    }

    @Test
    @DisplayName("a node that granted nothing is offered nothing")
    void nothingGrantedMeansNothingAdvertised() {
        // No WYRDSEKAI_HOST_OPEN_ROOTS in this JVM — the block must stay empty rather
        // than teach an author to write against a door that is not there.
        assertThat(ItemApiSurface.hostBlock(ItemCapabilitySet.craftedDefault())).isEmpty();
    }
}
