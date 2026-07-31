package org.wyrdsekai.core.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HostActionService refusal paths. The happy paths spawn real processes so
 * they stay out of unit scope; what matters here is that with NO steward
 * configuration every verb refuses safely, and the command tokenizer
 * honors quoting (allowlist commands may have quoted arguments).
 */
class HostActionServiceTest {

    @Test
    void canHandle_covers_the_three_host_verbs_only() {
        assertTrue(HostActionService.canHandle("app_launch"));
        assertTrue(HostActionService.canHandle("file_open"));
        assertTrue(HostActionService.canHandle("url_open"));
        assertFalse(HostActionService.canHandle("forge"));
        assertFalse(HostActionService.canHandle(null));
    }

    @Test
    void launch_refuses_when_nothing_is_allowlisted() {
        // No WYRDSEKAI_HOST_APPS in the test env, no host.apps profile key.
        var result = HostActionService.launchApp("editor", "tester");
        assertEquals(false, result.get("ok"));
        assertEquals("none_configured", result.get("error"));
    }

    @Test
    void openFile_refuses_when_no_roots_configured() {
        var result = HostActionService.openFile("/etc/passwd", "tester");
        assertEquals(false, result.get("ok"));
        assertEquals("no_roots", result.get("error"));
    }

    @Test
    void openUrl_refuses_non_http_schemes() {
        assertEquals("bad_scheme",
            HostActionService.openUrl("file:///etc/passwd", "tester").get("error"));
        assertEquals("bad_scheme",
            HostActionService.openUrl("javascript:alert(1)", "tester").get("error"));
        assertEquals("bad_scheme",
            HostActionService.openUrl(null, "tester").get("error"));
    }

    @Test
    void find_locates_files_under_roots_only(@TempDir Path tmp) throws Exception {
        var root = Files.createDirectories(tmp.resolve("root"));
        var nested = Files.createDirectories(root.resolve("ebooks"));
        Files.writeString(nested.resolve("dune.epub"), "x");
        Files.writeString(nested.resolve("notes.txt"), "x");
        var elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("secret.epub"), "x");

        var result = HostActionService.findFiles(List.of(root), "*.epub", 50, "tester");
        assertEquals(true, result.get("ok"));
        var matches = (List<?>) result.get("matches");
        assertEquals(1, matches.size(), "must not see files outside the granted root");
        assertTrue(String.valueOf(matches.getFirst()).endsWith("dune.epub"));

        // Substring mode (no glob characters)
        var byName = HostActionService.findFiles(List.of(root), "dune", 50, "tester");
        assertEquals(1, ((List<?>) byName.get("matches")).size());

        // No roots → refusal
        assertEquals("no_roots",
            HostActionService.findFiles(List.of(), "*.epub", 50, "tester").get("error"));
    }

    @Test
    void find_refuses_with_no_configured_roots() {
        // Public entry point — no WYRDSEKAI_HOST_OPEN_ROOTS in the test env.
        assertEquals("no_roots",
            HostActionService.findFiles("*.epub", 50, "tester").get("error"));
    }

    @Test
    void tokenize_splits_on_whitespace_and_honors_quotes() {
        assertEquals(List.of("/usr/bin/gedit", "--new-window"),
            HostActionService.tokenize("/usr/bin/gedit --new-window"));
        assertEquals(List.of("flatpak", "run", "org.gnome.TextEditor", "my file.txt"),
            HostActionService.tokenize("flatpak run org.gnome.TextEditor \"my file.txt\""));
        assertEquals(List.of(), HostActionService.tokenize("   "));
    }
}
