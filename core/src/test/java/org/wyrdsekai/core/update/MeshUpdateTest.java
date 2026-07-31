package org.wyrdsekai.core.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for mesh update protocol Wave 2: Release channel.
 */
class MeshUpdateTest {

    // --- ReleaseManifest ---

    @Test
    void manifest_roundtrip_json() {
        var manifest = new ReleaseManifest(
            "0.3.0", 1, "abc123", Instant.parse("2026-03-27T10:00:00Z"),
            "0.1.0",
            Map.of("universal", new ReleaseManifest.PackageInfo(
                "https://example.com/pkg.tar.gz", "sha256hash", 45000000)),
            "Test release", false, null);

        var bytes = manifest.toBytes();
        var parsed = ReleaseManifest.fromBytes(bytes);

        assertEquals("0.3.0", parsed.version());
        assertEquals(1, parsed.wireProtocol());
        assertEquals("abc123", parsed.buildHash());
        assertEquals("0.1.0", parsed.minVersion());
        assertFalse(parsed.breaking());
        assertNotNull(parsed.packages().get("universal"));
        assertEquals(45000000, parsed.packages().get("universal").size());
    }

    @Test
    void manifest_fromJson() {
        var json = """
            {"version":"1.0.0","wireProtocol":2,"buildHash":"def456",
             "buildTimestamp":"2026-04-01T00:00:00Z","minVersion":"0.5.0",
             "changelog":"Major release","breaking":true}
            """;
        var m = ReleaseManifest.fromJson(json);
        assertEquals("1.0.0", m.version());
        assertEquals(2, m.wireProtocol());
        assertTrue(m.breaking());
        assertEquals("Major release", m.changelog());
    }

    @Test
    void manifest_ignores_unknown_fields() {
        var json = """
            {"version":"0.2.0","wireProtocol":1,"buildHash":"x","futureField":"ignored"}
            """;
        var m = ReleaseManifest.fromJson(json);
        assertEquals("0.2.0", m.version());
    }

    @Test
    void isNewerThan_compares_semver() {
        var m = manifest("0.3.0");
        assertTrue(m.isNewerThan("0.2.0"));
        assertTrue(m.isNewerThan("0.2.9"));
        assertFalse(m.isNewerThan("0.3.0"));
        assertFalse(m.isNewerThan("0.4.0"));
        assertFalse(m.isNewerThan("1.0.0"));
    }

    @Test
    void isNewerThan_handles_snapshot_suffix() {
        var m = manifest("0.2.0");
        assertTrue(m.isNewerThan("0.1.0-SNAPSHOT"));
        assertFalse(m.isNewerThan("0.2.0-SNAPSHOT")); // same major.minor.patch
    }

    @Test
    void canUpgradeFrom_checks_minVersion() {
        var m = new ReleaseManifest("0.5.0", 1, "x", null, "0.3.0",
            null, null, false, null);
        assertTrue(m.canUpgradeFrom("0.3.0"));
        assertTrue(m.canUpgradeFrom("0.4.0"));
        assertFalse(m.canUpgradeFrom("0.2.0"));
    }

    @Test
    void canUpgradeFrom_null_minVersion_always_true() {
        var m = manifest("0.5.0");
        assertTrue(m.canUpgradeFrom("0.0.1"));
    }

    @Test
    void compareVersions_basic() {
        assertTrue(ReleaseManifest.compareVersions("1.0.0", "0.9.9") > 0);
        assertTrue(ReleaseManifest.compareVersions("0.1.0", "0.2.0") < 0);
        assertEquals(0, ReleaseManifest.compareVersions("1.2.3", "1.2.3"));
        assertTrue(ReleaseManifest.compareVersions("0.10.0", "0.9.0") > 0);
    }

    @Test
    void compareVersions_with_suffix() {
        assertEquals(0, ReleaseManifest.compareVersions("0.1.0-SNAPSHOT", "0.1.0"));
        assertTrue(ReleaseManifest.compareVersions("0.2.0-SNAPSHOT", "0.1.0") > 0);
    }

    // --- UpdateConfig ---

    @Test
    void config_defaults() {
        var config = UpdateConfig.fromEnv();
        assertNotNull(config.policy());
        assertNotNull(config.checkInterval());
        assertNotNull(config.stabilityDelay());
        assertTrue(config.versionCacheSize() > 0);
    }

    @Test
    void config_disabled_when_no_channel() {
        var config = new UpdateConfig("", UpdateConfig.UpdatePolicy.AUTO,
            Duration.ofHours(6), Duration.ofMinutes(5), null, "secondary", null, null, 3);
        assertFalse(config.enabled());
    }

    @Test
    void config_disabled_when_pinned() {
        var config = new UpdateConfig("https://example.com", UpdateConfig.UpdatePolicy.AUTO,
            Duration.ofHours(6), Duration.ofMinutes(5), null, "secondary", null, "0.1.0", 3);
        assertFalse(config.enabled());
    }

    @Test
    void config_primary_node() {
        var config = new UpdateConfig("https://example.com", UpdateConfig.UpdatePolicy.AUTO,
            Duration.ofHours(6), Duration.ofMinutes(5), null, "primary", null, null, 3);
        assertTrue(config.isPrimary());
    }

    @Test
    void classifyDelta_major() {
        assertEquals(UpdateConfig.VersionDelta.MAJOR,
            UpdateConfig.classifyDelta("0.1.0", "1.0.0"));
    }

    @Test
    void classifyDelta_minor() {
        assertEquals(UpdateConfig.VersionDelta.MINOR,
            UpdateConfig.classifyDelta("0.1.0", "0.2.0"));
    }

    @Test
    void classifyDelta_patch() {
        assertEquals(UpdateConfig.VersionDelta.PATCH,
            UpdateConfig.classifyDelta("0.1.0", "0.1.1"));
    }

    @Test
    void effectivePolicy_breaking_always_prompt() {
        var config = new UpdateConfig("https://x", UpdateConfig.UpdatePolicy.AUTO,
            Duration.ofHours(6), Duration.ofMinutes(5), null, "secondary", null, null, 3);
        var m = new ReleaseManifest("0.1.1", 1, "x", null, null, null, null, true, null);
        assertEquals(UpdateConfig.UpdatePolicy.PROMPT, config.effectivePolicy("0.1.0", m));
    }

    @Test
    void effectivePolicy_auto_patch() {
        var config = new UpdateConfig("https://x", UpdateConfig.UpdatePolicy.AUTO,
            Duration.ofHours(6), Duration.ofMinutes(5), null, "secondary", null, null, 3);
        var m = manifest("0.1.1");
        assertEquals(UpdateConfig.UpdatePolicy.AUTO, config.effectivePolicy("0.1.0", m));
    }

    @Test
    void effectivePolicy_auto_major_prompts() {
        var config = new UpdateConfig("https://x", UpdateConfig.UpdatePolicy.AUTO,
            Duration.ofHours(6), Duration.ofMinutes(5), null, "secondary", null, null, 3);
        var m = manifest("1.0.0");
        assertEquals(UpdateConfig.UpdatePolicy.PROMPT, config.effectivePolicy("0.1.0", m));
    }

    // --- UpdateChannelPoller ---

    @Test
    void poller_file_channel(@TempDir Path tempDir) throws Exception {
        // Write a manifest to a temp file
        var manifestJson = """
            {"version":"0.2.0","wireProtocol":1,"buildHash":"test123",
             "buildTimestamp":"2026-03-27T10:00:00Z","changelog":"Test"}
            """;
        var manifestFile = tempDir.resolve("latest.json");
        Files.writeString(manifestFile, manifestJson);

        var poller = new UpdateChannelPoller(
            manifestFile.toUri().toString(), Duration.ofHours(1), null);

        var result = poller.check();
        assertNotNull(poller.latestManifest());
        assertEquals("0.2.0", poller.latestManifest().version());
        assertNotNull(poller.lastCheck());
        assertNull(poller.lastError());

        poller.close();
    }

    @Test
    void poller_mesh_url_does_not_poll() {
        var poller = new UpdateChannelPoller("mesh://", Duration.ofHours(1), null);
        poller.start(Duration.ofSeconds(0));
        // Should not throw or poll
        assertNull(poller.latestManifest());
        poller.close();
    }

    @Test
    void poller_invalid_url_records_error() {
        var poller = new UpdateChannelPoller(
            "http://localhost:99999/nonexistent", Duration.ofHours(1), null);
        poller.check();
        assertNotNull(poller.lastError());
        poller.close();
    }

    // --- UpdateEngine ---

    @Test
    void engine_rejects_older_version(@TempDir Path tempDir) {
        var engine = new UpdateEngine(tempDir, UpdateConfig.fromEnv());
        var manifest = manifest("0.0.1"); // older than 0.1.0-SNAPSHOT
        var result = engine.apply(manifest, null);
        assertFalse(result.success());
        assertTrue(result.message().contains("Not newer"));
    }

    @Test
    void engine_rejects_incompatible_minVersion(@TempDir Path tempDir) {
        var engine = new UpdateEngine(tempDir, UpdateConfig.fromEnv());
        var manifest = new ReleaseManifest("9.0.0", 1, "x", null, "5.0.0",
            null, null, false, null);
        var result = engine.apply(manifest, null);
        assertFalse(result.success());
        assertTrue(result.message().contains("Cannot upgrade"));
    }

    @Test
    void engine_rejects_no_package(@TempDir Path tempDir) {
        var engine = new UpdateEngine(tempDir, UpdateConfig.fromEnv());
        var manifest = manifest("9.0.0"); // newer but no package
        var result = engine.apply(manifest, null);
        assertFalse(result.success());
        assertTrue(result.message().contains("No package URL"));
    }

    @Test
    void engine_apply_with_local_package(@TempDir Path tempDir) throws Exception {
        // Build a fake update package
        var installDir = tempDir.resolve("install");
        var libDir = installDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("old.jar"), "old content");

        // Create a new package
        var pkgDir = tempDir.resolve("pkg-build");
        var newLib = pkgDir.resolve("lib");
        Files.createDirectories(newLib);
        Files.writeString(newLib.resolve("new.jar"), "new content");

        var builder = new UpdatePackageBuilder(pkgDir);
        var outputDir = tempDir.resolve("output");
        var buildResult = builder.build(outputDir);

        // Apply update
        var engine = new UpdateEngine(installDir, UpdateConfig.fromEnv());
        var manifest = new ReleaseManifest("9.0.0", 1, "x", null, null,
            Map.of("universal", new ReleaseManifest.PackageInfo(
                buildResult.packagePath().toUri().toString(),
                buildResult.sha256(), buildResult.size())),
            null, false, null);

        var result = engine.apply(manifest, buildResult.packagePath().toUri().toString());
        assertTrue(result.success(), "Update should succeed: " + result.message());

        // Verify old lib saved
        assertTrue(Files.isDirectory(installDir.resolve("lib.prev")),
            "lib.prev should exist");
        assertTrue(Files.exists(installDir.resolve("lib.prev/old.jar")),
            "Old jar should be preserved");

        // Verify new lib installed
        assertTrue(Files.exists(installDir.resolve("lib/new.jar")),
            "New jar should be installed");
    }

    @Test
    void engine_rollback(@TempDir Path tempDir) throws Exception {
        var installDir = tempDir.resolve("install");
        var libDir = installDir.resolve("lib");
        var libPrev = installDir.resolve("lib.prev");
        Files.createDirectories(libDir);
        Files.createDirectories(libPrev);
        Files.writeString(libDir.resolve("current.jar"), "current");
        Files.writeString(libPrev.resolve("previous.jar"), "previous");

        var engine = new UpdateEngine(installDir, UpdateConfig.fromEnv());
        var result = engine.rollback();
        assertTrue(result.success(), "Rollback should succeed");

        // Previous version should now be in lib/
        assertTrue(Files.exists(installDir.resolve("lib/previous.jar")));
        // Current should be in lib.failed/
        assertTrue(Files.exists(installDir.resolve("lib.failed/current.jar")));
    }

    @Test
    void engine_rollback_no_previous(@TempDir Path tempDir) {
        var installDir = tempDir.resolve("install");
        var engine = new UpdateEngine(installDir, UpdateConfig.fromEnv());
        var result = engine.rollback();
        assertFalse(result.success());
        assertTrue(result.message().contains("No previous version"));
    }

    @Test
    void engine_tracks_history(@TempDir Path tempDir) {
        var engine = new UpdateEngine(tempDir, UpdateConfig.fromEnv());
        engine.apply(manifest("0.0.1"), null); // will fail (not newer)
        assertFalse(engine.history().isEmpty());
        assertFalse(engine.history().getFirst().success());
    }

    // --- UpdatePackageBuilder ---

    @Test
    void packageBuilder_builds_from_temp_install(@TempDir Path tempDir) throws Exception {
        // Create a minimal fake installation
        var installDir = tempDir.resolve("install");
        var libDir = installDir.resolve("lib");
        Files.createDirectories(libDir);
        Files.writeString(libDir.resolve("test.jar"), "fake jar content");

        var binDir = installDir.resolve("bin");
        Files.createDirectories(binDir);
        Files.writeString(binDir.resolve("wyrdsekai"), "#!/bin/bash\necho hello");

        var outputDir = tempDir.resolve("output");

        var builder = new UpdatePackageBuilder(installDir);
        var result = builder.build(outputDir);

        assertNotNull(result);
        assertTrue(Files.exists(result.packagePath()), "Package file should exist");
        assertTrue(result.size() > 0, "Package should have non-zero size");
        assertNotNull(result.sha256());
        assertEquals(64, result.sha256().length(), "SHA-256 should be 64 hex chars");
        assertTrue(Files.exists(result.manifestPath()), "Manifest file should exist");

        // Manifest should be parseable
        var manifestJson = Files.readString(result.manifestPath());
        var manifest = ReleaseManifest.fromJson(manifestJson);
        assertNotNull(manifest.packages().get("universal"));
    }

    // --- Helpers ---

    private static ReleaseManifest manifest(String version) {
        return new ReleaseManifest(version, 1, "x", null, null, null, null, false, null);
    }
}
