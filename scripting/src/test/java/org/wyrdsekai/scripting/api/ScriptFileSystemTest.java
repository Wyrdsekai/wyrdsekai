package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for ScriptFileSystem — sandboxed file access for scripts.
 */
class ScriptFileSystemTest {

    @TempDir
    Path workspace;

    private ScriptFileSystem fs;

    @BeforeEach
    void setUp() {
        fs = new ScriptFileSystem(workspace);
    }

    @Test
    void write_and_read() {
        fs.write("hello.txt", "Hello, World!");
        String content = fs.read("hello.txt");
        assertThat(content).isEqualTo("Hello, World!");
    }

    @Test
    void list_files() {
        fs.write("a.txt", "alpha");
        fs.write("b.txt", "beta");
        fs.write("c.txt", "gamma");

        var files = fs.list(".");
        assertThat(files).containsExactly("a.txt", "b.txt", "c.txt");
    }

    @Test
    void delete_file() {
        fs.write("temp.txt", "temporary");
        assertThat(fs.exists("temp.txt")).isTrue();

        fs.delete("temp.txt");
        assertThat(fs.exists("temp.txt")).isFalse();
    }

    @Test
    void path_traversal_blocked() {
        assertThatThrownBy(() -> fs.read("../../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("traversal");
    }

    @Test
    void absolute_path_blocked() {
        assertThatThrownBy(() -> fs.read("/etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Absolute");
    }

    @Test
    void exists_check() {
        assertThat(fs.exists("nope.txt")).isFalse();
        fs.write("yep.txt", "here");
        assertThat(fs.exists("yep.txt")).isTrue();
    }

    @Test
    void write_creates_parent_directories() {
        fs.write("sub/dir/file.txt", "nested");
        assertThat(fs.exists("sub/dir/file.txt")).isTrue();
        assertThat(fs.read("sub/dir/file.txt")).isEqualTo("nested");
    }

    @Test
    void list_subdirectory() {
        fs.write("sub/a.txt", "a");
        fs.write("sub/b.txt", "b");

        var files = fs.list("sub");
        assertThat(files).containsExactly("a.txt", "b.txt");
    }

    @Test
    void read_nonexistent_throws() {
        assertThatThrownBy(() -> fs.read("missing.txt"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Cannot read");
    }

    @Test
    void blank_path_throws() {
        assertThatThrownBy(() -> fs.read(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");
    }
}
