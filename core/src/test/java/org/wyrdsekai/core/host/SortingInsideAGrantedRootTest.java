package org.wyrdsekai.core.host;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reviewing a granted directory and sorting it — the ask that had no verbs.
 *
 * <p>{@code findFiles} let an item SEE what the steward had granted and nothing let it
 * act, so asked on 2026-08-22 for a tool to review and sort a media folder, the authoring
 * model invented {@code world.web.fetch("/data/.../listings/raw.txt")}. These are the
 * missing verbs, and the boundary they must never cross is the grant itself.
 */
class SortingInsideAGrantedRootTest {

    @Test
    @DisplayName("a file moves inside the grant, and the directory is made for it")
    void sortingWorksInsideTheRoot(@TempDir Path root) throws Exception {
        var src = root.resolve("clip.mp4");
        Files.writeString(src, "x");
        var roots = List.of(root.toRealPath());

        var made = HostActionService.makeDirectory(roots, root.resolve("videos").toString(), "t");
        assertThat(made.get("ok")).isEqualTo(true);

        var moved = HostActionService.moveFile(
            roots, src.toString(), root.resolve("videos/clip.mp4").toString(), "t");
        assertThat(moved.get("ok")).isEqualTo(true);
        assertThat(Files.exists(root.resolve("videos/clip.mp4"))).isTrue();
        assertThat(Files.exists(src)).isFalse();
    }

    @Test
    @DisplayName("nothing leaves the grant, in either direction")
    void theGrantIsTheBoundary(@TempDir Path tmp) throws Exception {
        var root = Files.createDirectories(tmp.resolve("granted"));
        var outside = Files.createDirectories(tmp.resolve("elsewhere"));
        var inside = root.resolve("clip.mp4");
        var theirs = outside.resolve("private.txt");
        Files.writeString(inside, "x");
        Files.writeString(theirs, "x");
        var roots = List.of(root.toRealPath());

        // Out of the grant.
        assertThat(HostActionService.moveFile(
            roots, inside.toString(), outside.resolve("clip.mp4").toString(), "t").get("error"))
            .isEqualTo("outside_roots");
        // Into the grant from outside it.
        assertThat(HostActionService.moveFile(
            roots, theirs.toString(), root.resolve("private.txt").toString(), "t").get("error"))
            .isEqualTo("outside_roots");
        // And a directory outside it cannot be created.
        assertThat(HostActionService.makeDirectory(
            roots, outside.resolve("new").toString(), "t").get("error"))
            .isEqualTo("outside_roots");

        assertThat(Files.exists(inside)).as("the refused move must not have happened").isTrue();
        assertThat(Files.exists(theirs)).isTrue();
    }

    @Test
    @DisplayName("a move never overwrites what is already there")
    void moveNeverOverwrites(@TempDir Path root) throws Exception {
        var a = root.resolve("a.mp4");
        var b = root.resolve("b.mp4");
        Files.writeString(a, "first");
        Files.writeString(b, "second");

        var res = HostActionService.moveFile(
            List.of(root.toRealPath()), a.toString(), b.toString(), "t");
        assertThat(res.get("error")).isEqualTo("destination_exists");
        assertThat(Files.readString(b)).isEqualTo("second");
        assertThat(Files.readString(a)).isEqualTo("first");
    }

    @Test
    @DisplayName("with nothing granted, every verb refuses")
    void nothingGrantedRefuses(@TempDir Path root) throws Exception {
        var f = root.resolve("x.mp4");
        Files.writeString(f, "x");
        assertThat(HostActionService.moveFile(
            List.of(), f.toString(), root.resolve("y.mp4").toString(), "t").get("error"))
            .isEqualTo("no_roots");
        assertThat(HostActionService.makeDirectory(List.of(), root.toString(), "t").get("error"))
            .isEqualTo("no_roots");
    }
}
