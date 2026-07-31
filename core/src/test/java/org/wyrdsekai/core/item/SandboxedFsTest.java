package org.wyrdsekai.core.item;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * sandboxed filesystem helper.
 *
 * <p>Pins path-escape rejection (.. + absolute + symlinks), per-file size cap,
 * per-agent quota, and basic CRUD shape.</p>
 */
class SandboxedFsTest {

    @Test
    void resolve_rejects_parent_traversal(@TempDir Path tmp) {
        var fs = new SandboxedFs(tmp, "did_wyrd_a");
        assertThatThrownBy(() -> fs.resolve("../../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("..");
    }

    @Test
    void resolve_rejects_absolute_paths(@TempDir Path tmp) {
        var fs = new SandboxedFs(tmp, "did_wyrd_a");
        assertThatThrownBy(() -> fs.resolve("/etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fs.resolve("\\Windows\\System32"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fs.resolve("C:/foo"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── #14 (2026-07-19 OSS hardening) ─────────────────────────────────────

    @Test
    void dotdot_agentId_cannot_escape_items_dir(@TempDir Path tmp) {
        // "..‑as‑agentId" used to survive sanitisation ('.' was allowed) and the
        // normalize() collapsed items/../fs out of the per-agent tree.
        var fs = new SandboxedFs(tmp, "..");
        assertThat(fs.root().startsWith(tmp.resolve("items"))).isTrue();
    }

    @Test
    void dot_agentId_cannot_escape_items_dir(@TempDir Path tmp) {
        var fs = new SandboxedFs(tmp, ".");
        assertThat(fs.root().startsWith(tmp.resolve("items"))).isTrue();
    }

    @Test
    void resolve_rejects_intermediate_symlink_escape(@TempDir Path tmp) throws IOException {
        var fs = new SandboxedFs(tmp, "did_wyrd_a");
        var root = fs.ensureRoot();
        // Create a symlinked directory INSIDE the sandbox pointing OUTSIDE it.
        var outside = tmp.resolve("outside");
        Files.createDirectories(outside);
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // filesystem without symlink support — skip
        }
        // Accessing a file THROUGH the intermediate symlink must be rejected —
        // the old final-component-only check missed this.
        assertThatThrownBy(() -> fs.resolve("link/passwd"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sandbox");
    }

    @Test
    void resolve_accepts_relative_paths(@TempDir Path tmp) {
        var fs = new SandboxedFs(tmp, "did_wyrd_a");
        var p = fs.resolve("notes/today.txt");
        assertThat(p.toString()).contains("did_wyrd_a");
        assertThat(p.startsWith(fs.root())).isTrue();
    }

    @Test
    void write_then_read_roundtrip(@TempDir Path tmp) throws IOException {
        var fs = new SandboxedFs(tmp, "did_wyrd_a");
        var res = fs.write("greeting.txt", "hello");
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(res.get("size")).isEqualTo(5L);
        assertThat(fs.read("greeting.txt")).isEqualTo("hello");
    }

    @Test
    void write_creates_nested_directories(@TempDir Path tmp) throws IOException {
        var fs = new SandboxedFs(tmp, "did_wyrd_a");
        fs.write("a/b/c/file.txt", "ok");
        assertThat(fs.exists("a/b/c/file.txt")).isTrue();
        assertThat(fs.exists("a/b")).isTrue();
    }

    @Test
    void write_rejects_files_above_per_file_cap(@TempDir Path tmp) {
        var fs = new SandboxedFs(tmp, "did_wyrd_a");
        // 5MB > 4MB per-file cap
        var big = "x".repeat(5 * 1024 * 1024);
        assertThatThrownBy(() -> fs.write("big.txt", big))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("file_too_large");
    }

    @Test
    void list_returns_entries_with_sizes(@TempDir Path tmp) throws IOException {
        var fs = new SandboxedFs(tmp, "did_wyrd_a");
        fs.write("a.txt", "aa");
        fs.write("b.txt", "bbb");
        var entries = fs.list(null);
        assertThat(entries).extracting(m -> m.get("name"))
            .containsExactlyInAnyOrder("a.txt", "b.txt");
    }

    @Test
    void delete_removes_file(@TempDir Path tmp) throws IOException {
        var fs = new SandboxedFs(tmp, "did_wyrd_a");
        fs.write("toremove.txt", "x");
        assertThat(fs.exists("toremove.txt")).isTrue();
        var res = fs.delete("toremove.txt");
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(fs.exists("toremove.txt")).isFalse();
    }

    @Test
    void delete_root_is_rejected(@TempDir Path tmp) throws IOException {
        var fs = new SandboxedFs(tmp, "did_wyrd_a");
        fs.ensureRoot();
        assertThatThrownBy(() -> fs.delete(""))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("cannot_delete_root");
    }

    @Test
    void stat_returns_basic_attributes(@TempDir Path tmp) throws IOException {
        var fs = new SandboxedFs(tmp, "did_wyrd_a");
        fs.write("file.txt", "hi");
        var s = fs.stat("file.txt");
        assertThat(s.get("name")).isEqualTo("file.txt");
        assertThat(s.get("size")).isEqualTo(2L);
        assertThat(s.get("isDir")).isEqualTo(false);
    }

    @Test
    void mkdir_creates_directory(@TempDir Path tmp) throws IOException {
        var fs = new SandboxedFs(tmp, "did_wyrd_a");
        var res = fs.mkdir("subdir");
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(fs.exists("subdir")).isTrue();
    }

    @Test
    void totalBytes_tracks_quota(@TempDir Path tmp) throws IOException {
        var fs = new SandboxedFs(tmp, "did_wyrd_a");
        fs.write("a.txt", "x".repeat(100));
        fs.write("b.txt", "y".repeat(200));
        assertThat(fs.totalBytes()).isEqualTo(300L);
    }

    @Test
    void resolve_rejects_symlink_escape(@TempDir Path tmp) throws IOException {
        var fs = new SandboxedFs(tmp, "did_wyrd_a");
        fs.ensureRoot();
        // Create a target outside the sandbox.
        var outside = tmp.resolve("outside.txt");
        Files.writeString(outside, "secret");
        // Create a symlink inside the sandbox pointing outside.
        var link = fs.root().resolve("link.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            // Some filesystems / privilege configs disallow symlinks; skip.
            return;
        }
        // Accessing the symlink path itself is allowed (path is inside root)
        // but reading should fail because target is outside.
        assertThatThrownBy(() -> fs.resolve("link.txt"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void per_agent_isolation(@TempDir Path tmp) throws IOException {
        var a = new SandboxedFs(tmp, "did_wyrd_a");
        var b = new SandboxedFs(tmp, "did_wyrd_b");
        a.write("shared.txt", "from-a");
        assertThat(b.exists("shared.txt")).isFalse();
        assertThat(a.read("shared.txt")).isEqualTo("from-a");
    }
}
