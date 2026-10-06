package org.wyrdsekai.core.vault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A being's home was never copied: nothing named {@code beings}, so every copy logged it as
 * unclassified and left it out, her familiars' memory with it (household node, 2026-10-04). It is
 * kept now, without the caches her tools refill, and read so that no link she can plant is followed:
 * the vault reads as root, and a restore puts what it read back in her home as hers.
 */
class TheBeingsHomesAreKeptTest {

    @AfterEach
    void tearDown() {
        Vault.resetForTests();
        BodyMap.resetForTests();
    }

    private static Path household(Path dir) throws Exception {
        var data = dir.resolve("data");
        Files.createDirectories(data);
        SchemaInitializer.initialize(data.resolve("world.db"));
        var home = data.resolve("beings/1a2b3c4d/home");
        write(data.resolve("beings/1a2b3c4d/did"), "did:key:z6MkBeingA\n");
        write(home.resolve(".codezaiku/familiar-memory/session-1/memory.md"), "what the familiar learned");
        write(home.resolve(".codezaiku/config"), "model = local");
        write(home.resolve(".local/share/tool/sessions.json"), "{\"sessions\":1}");
        write(home.resolve(".cache/pip/wheel.bin"), "refilled");
        write(home.resolve(".npm/_cacache/index"), "refilled");
        write(home.resolve(".local/lib/python3/site-packages/pkg.py"), "refilled");
        write(home.resolve("project/node_modules/dep/index.js"), "refilled");
        write(data.resolve("coding-workspaces/ws-1/notes.md"), "a workspace page");
        return data;
    }

    private static void write(Path p, String text) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    private static String read(Path p) {
        try { return Files.readString(p); } catch (Exception e) { return ""; }
    }

    private static List<String> paths(Vault.Manifest m) {
        return m.files().stream().map(Vault.Entry::path).toList();
    }

    @Test
    void her_home_is_kept_without_the_caches_and_comes_back_whole(@TempDir Path dir) throws Exception {
        var data = household(dir);
        var vault = new Vault(data, dir.resolve("vault"));
        var m = vault.snapshot("first", false).orElseThrow();
        var paths = paths(m);
        assertThat(paths).contains("beings/1a2b3c4d/did",
            "beings/1a2b3c4d/home/.codezaiku/familiar-memory/session-1/memory.md",
            "beings/1a2b3c4d/home/.codezaiku/config",
            "beings/1a2b3c4d/home/.local/share/tool/sessions.json",
            "coding-workspaces/ws-1/notes.md");
        assertThat(paths).noneMatch(p -> p.contains("/.cache/") || p.contains("/.npm/") || p.contains("/.local/lib/")
            || p.contains("/node_modules/"));
        assertThat(m.classes().get("unclassified")).doesNotContain("beings", "coding-workspaces");
        assertThat(m.classes().get("replaceable")).contains("beings/1a2b3c4d/home/.cache",
            "beings/1a2b3c4d/home/.npm", "beings/1a2b3c4d/home/.local/lib",
            "beings/1a2b3c4d/home/project/node_modules");

        var out = dir.resolve("out");
        assertThat(vault.restoreTo(m, out)).isEmpty();
        assertThat(Files.readString(out.resolve("beings/1a2b3c4d/home/.codezaiku/familiar-memory/session-1/memory.md")))
            .isEqualTo("what the familiar learned");
        assertThat(Files.readString(out.resolve("beings/1a2b3c4d/did"))).isEqualTo("did:key:z6MkBeingA\n");
    }

    @Test
    void a_link_she_plants_is_never_followed(@TempDir Path dir) throws Exception {
        var data = household(dir);
        var secret = dir.resolve("outside/root-only.key");
        write(secret, "only root may read this");
        var outsideDir = dir.resolve("outside");
        var home = data.resolve("beings/1a2b3c4d/home");
        Files.createSymbolicLink(home.resolve(".codezaiku/familiar-memory/session-1/stolen.key"), secret);
        Files.createSymbolicLink(home.resolve("everything"), outsideDir);
        Files.createSymbolicLink(data.resolve("coding-workspaces/ws-1/stolen.key"), secret);

        if (System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            // Where being users exist, the walk holds directory handles: a link swapped in after a
            // look cannot be opened through, which a planted link alone does not show.
            try (var ds = Files.newDirectoryStream(data)) {
                assertThat(ds).isInstanceOf(SecureDirectoryStream.class);
            }
        }
        var vault = new Vault(data, dir.resolve("vault"));
        var m = vault.snapshot("first", false).orElseThrow();
        assertThat(paths(m)).noneMatch(p -> p.contains("stolen") || p.contains("everything"));
        var out = dir.resolve("out");
        assertThat(vault.restoreTo(m, out)).isEmpty();
        try (var walk = Files.walk(out)) {
            assertThat(walk.filter(Files::isRegularFile).map(TheBeingsHomesAreKeptTest::read))
                .doesNotContain("only root may read this");
        }
    }

    @Test
    void the_files_nobody_named_are_kept_and_the_trainer_is_fetched_again(@TempDir Path dir) throws Exception {
        var data = household(dir);
        write(data.resolve("tunnel-key.json"), "{\"k\":\"x\"}");
        write(data.resolve("relay-tls-seen"), "sha256:abc");
        write(data.resolve("hardware-seen.json"), "{\"cpu\":\"x\"}");
        write(data.resolve("serving-profile.last"), "single-sparse");
        write(data.resolve("shadow.jsonl"), "{\"turn\":1}\n");
        write(data.resolve("brainwrite-venv/bin/python"), "a venv");

        var m = new Vault(data, dir.resolve("vault")).snapshot("first", false).orElseThrow();
        assertThat(paths(m)).contains("tunnel-key.json", "relay-tls-seen", "hardware-seen.json",
            "serving-profile.last", "shadow.jsonl");
        assertThat(paths(m)).noneMatch(p -> p.startsWith("brainwrite-venv/"));
        assertThat(m.classes().get("replaceable")).contains("brainwrite-venv");
        assertThat(m.classes().get("unclassified")).isEmpty();
    }

    @Test
    void an_unchanged_file_in_her_home_costs_no_new_chunks(@TempDir Path dir) throws Exception {
        var data = household(dir);
        var vault = new Vault(data, dir.resolve("vault"));
        var m1 = vault.snapshot("first", false).orElseThrow();
        long before;
        try (var s = Files.walk(vault.dir().resolve("chunks"))) { before = s.filter(Files::isRegularFile).count(); }
        var m2 = vault.snapshot("second", false).orElseThrow();
        long after;
        try (var s = Files.walk(vault.dir().resolve("chunks"))) { after = s.filter(Files::isRegularFile).count(); }
        assertThat(after).isEqualTo(before);
        var rel = "beings/1a2b3c4d/home/.codezaiku/familiar-memory/session-1/memory.md";
        assertThat(m2.files().stream().filter(e -> e.path().equals(rel)).findFirst().orElseThrow().chunks())
            .isEqualTo(m1.files().stream().filter(e -> e.path().equals(rel)).findFirst().orElseThrow().chunks());
    }
}
