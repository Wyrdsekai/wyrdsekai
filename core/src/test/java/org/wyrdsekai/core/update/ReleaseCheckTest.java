package org.wyrdsekai.core.update;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseCheckTest {

    @AfterEach void restore() { ReleaseCheck.env = System::getenv; ReleaseCheck.latestSource = ReleaseCheck::latest; }

    @Test
    void newer_means_a_release_above_the_installed_base_version_whatever_its_suffix() {
        assertTrue(ReleaseCheck.isNewer("0.2.2", "0.3.0"));
        assertTrue(ReleaseCheck.isNewer("0.2.3~dev17", "0.3.0"), "a dev build below the release is behind it");
        assertFalse(ReleaseCheck.isNewer("0.3.0~dev2", "0.3.0"), "a dev build OF the release is not behind it");
        assertFalse(ReleaseCheck.isNewer("0.3.0", "0.3.0"));
        assertFalse(ReleaseCheck.isNewer("0.3.1", "0.3.0"));
        assertFalse(ReleaseCheck.isNewer("0.1.0-SNAPSHOT", null));
        assertFalse(ReleaseCheck.isNewer("0.1.0-SNAPSHOT", "not a version"));
        assertTrue(ReleaseCheck.isRelease("0.3.0"));
        assertFalse(ReleaseCheck.isRelease("0.2.3~dev17"));
        assertFalse(ReleaseCheck.isRelease("0.1.0-SNAPSHOT"));
    }

    @Test
    void the_installed_version_is_the_packagers_VERSION_file_else_the_build(@TempDir Path root) throws Exception {
        assertEquals(org.wyrdsekai.common.model.AppVersion.get().version(), ReleaseCheck.installedVersion(root));
        Files.writeString(root.resolve("VERSION"), "0.2.3~dev17\n");
        assertEquals("0.2.3~dev17", ReleaseCheck.installedVersion(root));
        assertEquals(org.wyrdsekai.common.model.AppVersion.get().version(), ReleaseCheck.installedVersion(null));
    }

    @Test
    void the_artifact_is_named_the_way_the_release_ships_it() {
        assertEquals("wyrdsekai_0.3.0_amd64.deb", ReleaseCheck.artifactName("0.3.0", "Linux", "amd64", true));
        assertEquals("wyrdsekai-0.3.0.tar.gz", ReleaseCheck.artifactName("0.3.0", "Linux", "amd64", false));
        assertEquals("Wyrdsekai-0.3.0.pkg", ReleaseCheck.artifactName("0.3.0", "Mac OS X", "aarch64", false));
        assertEquals("Wyrdsekai-0.3.0.msi", ReleaseCheck.artifactName("0.3.0", "Windows 11", "amd64", false));
        assertEquals("https://github.com/Wyrdsekai/wyrdsekai/releases/download/v0.3.0", ReleaseCheck.downloadBase("0.3.0"));
    }

    @Test
    void mode_reads_the_conf_and_defaults_to_check() {
        Map<String, String> env = new HashMap<>();
        ReleaseCheck.env = env::get;
        assertEquals("check", ReleaseCheck.mode());
        env.put("WYRDSEKAI_UPDATE", "AUTO");
        assertEquals("auto", ReleaseCheck.mode());
        env.put("WYRDSEKAI_UPDATE", "off");
        assertEquals("off", ReleaseCheck.mode());
        env.put("WYRDSEKAI_UPDATE", "sometimes");
        assertEquals("check", ReleaseCheck.mode());
    }

    @Test
    void the_latest_release_is_remembered_for_a_day_and_survives_github_being_down(@TempDir Path cache) throws Exception {
        var asks = new AtomicInteger();
        ReleaseCheck.latestSource = repo -> { asks.incrementAndGet(); return Optional.of("0.3.0"); };
        assertEquals(Optional.of("0.3.0"), ReleaseCheck.latestCached(ReleaseCheck.REPO, cache));
        assertEquals(1, asks.get());
        assertTrue(Files.isRegularFile(ReleaseCheck.cacheFile(ReleaseCheck.REPO, cache)));
        assertEquals("latest-wyrdsekai.txt", ReleaseCheck.cacheFile(ReleaseCheck.REPO, cache).getFileName().toString());
        // fresh cache: no second ask
        assertEquals(Optional.of("0.3.0"), ReleaseCheck.latestCached(ReleaseCheck.REPO, cache));
        assertEquals(1, asks.get());
        // stale cache and GitHub down: the remembered value still answers
        Files.setLastModifiedTime(ReleaseCheck.cacheFile(ReleaseCheck.REPO, cache),
            java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 2 * 24 * 3600 * 1000L));
        ReleaseCheck.latestSource = repo -> Optional.empty();
        assertEquals(Optional.of("0.3.0"), ReleaseCheck.latestCached(ReleaseCheck.REPO, cache));
        // nothing remembered and GitHub down: empty, no exception
        assertEquals(Optional.empty(), ReleaseCheck.latestCached(ReleaseCheck.CODEZAIKU_REPO, cache));
    }

    @Test
    void status_is_off_without_asking_when_the_conf_says_off(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("VERSION"), "0.3.0");
        Map<String, String> env = new HashMap<>(); env.put("WYRDSEKAI_UPDATE", "off");
        ReleaseCheck.env = env::get;
        ReleaseCheck.latestSource = repo -> { throw new AssertionError("must not ask"); };
        var st = ReleaseCheck.status(root, root);
        assertEquals("off", st.mode()); assertNull(st.latest()); assertFalse(st.updateAvailable());
        env.put("WYRDSEKAI_UPDATE", "check");
        ReleaseCheck.latestSource = repo -> Optional.of("0.3.1");
        var st2 = ReleaseCheck.status(root, root);
        assertTrue(st2.updateAvailable()); assertEquals("0.3.1", st2.latest()); assertEquals("0.3.0", st2.installed());
        assertEquals("0.3.1", st2.toMap().get("latest"));
    }
}
