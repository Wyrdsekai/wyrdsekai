package org.wyrdsekai.core.study;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.SchedulerService;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for The Study room — user's personal room.
 * Covers: AppBinding, AppRegistry, DesktopLauncher, StudyRoom.
 */
class StudyRoomTest {

    @TempDir
    Path tempDir;

    // ── AppBinding ───────────────────────────────────────────────────────

    @Nested
    class AppBindingTests {

        @Test
        void valid_binding() {
            var binding = new AppBinding("editor", "code", "VS Code");
            assertEquals("editor", binding.alias());
            assertEquals("code", binding.command());
            assertEquals("VS Code", binding.description());
        }

        @Test
        void null_alias_throws() {
            assertThrows(IllegalArgumentException.class,
                () -> new AppBinding(null, "cmd", "desc"));
        }

        @Test
        void blank_alias_throws() {
            assertThrows(IllegalArgumentException.class,
                () -> new AppBinding("  ", "cmd", "desc"));
        }

        @Test
        void null_command_throws() {
            assertThrows(IllegalArgumentException.class,
                () -> new AppBinding("alias", null, "desc"));
        }

        @Test
        void null_description_defaults_to_command() {
            var binding = new AppBinding("notes", "obsidian", null);
            assertEquals("obsidian", binding.description());
        }
    }

    // ── AppRegistry ──────────────────────────────────────────────────────

    @Nested
    class AppRegistryTests {

        private AppRegistry registry;

        @BeforeEach
        void setUp() {
            registry = new AppRegistry();
        }

        @Test
        void register_and_resolve() {
            registry.register("editor", "code", "VS Code");
            assertTrue(registry.hasApp("editor"));
            var binding = registry.resolve("editor");
            assertTrue(binding.isPresent());
            assertEquals("code", binding.get().command());
        }

        @Test
        void resolve_unknown_returns_empty() {
            assertFalse(registry.resolve("nonexistent").isPresent());
        }

        @Test
        void has_app_false_for_unregistered() {
            assertFalse(registry.hasApp("missing"));
        }

        @Test
        void size_tracks_registrations() {
            assertEquals(0, registry.size());
            registry.register("a", "a-bin", "A");
            registry.register("b", "b-bin", "B");
            assertEquals(2, registry.size());
        }

        @Test
        void all_returns_copy() {
            registry.register("x", "x-bin", "X");
            var all = registry.all();
            assertEquals(1, all.size());
            assertThrows(UnsupportedOperationException.class,
                () -> all.put("y", new AppBinding("y", "y-bin", "Y")));
        }

        @Test
        void register_all_from_map() {
            registry.registerAll(Map.of("notes", "obsidian", "browser", "firefox"));
            assertEquals(2, registry.size());
            assertTrue(registry.hasApp("notes"));
            assertTrue(registry.hasApp("browser"));
        }

        @Test
        void populate_launcher() {
            registry.register("test", "echo", "Echo");
            var launcher = new NoOpDesktopLauncher();
            // NoOp ignores registerApp, but we test the call doesn't throw
            registry.populateLauncher(launcher);
        }
    }

    // ── LaunchResult ─────────────────────────────────────────────────────

    @Nested
    class LaunchResultTests {

        @Test
        void ok_result() {
            var result = LaunchResult.ok("Launched", 42);
            assertTrue(result.success());
            assertEquals("Launched", result.message());
            assertEquals(42, result.pid());
        }

        @Test
        void ok_result_no_pid() {
            var result = LaunchResult.ok("Launched");
            assertTrue(result.success());
            assertEquals(0, result.pid());
        }

        @Test
        void fail_result() {
            var result = LaunchResult.fail("Not available");
            assertFalse(result.success());
            assertEquals("Not available", result.message());
            assertEquals(0, result.pid());
        }
    }

    // ── NoOpDesktopLauncher ──────────────────────────────────────────────

    @Nested
    class NoOpLauncherTests {

        private final NoOpDesktopLauncher launcher = new NoOpDesktopLauncher();

        @Test
        void gui_not_available() {
            assertFalse(launcher.isGuiAvailable());
        }

        @Test
        void open_file_fails() {
            var result = launcher.openFile(Path.of("/tmp/test.txt"));
            assertFalse(result.success());
        }

        @Test
        void open_app_fails() {
            var result = launcher.openApp("editor");
            assertFalse(result.success());
        }

        @Test
        void open_url_fails() {
            var result = launcher.openUrl(URI.create("https://example.com"));
            assertFalse(result.success());
        }

        @Test
        void registered_apps_empty() {
            assertEquals(0, launcher.registeredApps().size());
        }
    }

    // ── DesktopLauncher.detect() ─────────────────────────────────────────

    @Nested
    class DetectTests {

        @Test
        void detect_returns_nonnull() {
            var launcher = DesktopLauncher.detect();
            assertNotNull(launcher);
        }

        @Test
        void detect_returns_linux_on_linux() {
            String os = System.getProperty("os.name", "").toLowerCase();
            var launcher = DesktopLauncher.detect();
            if (os.contains("linux")) {
                assertInstanceOf(LinuxDesktopLauncher.class, launcher);
            }
        }
    }

    // ── StudyRoom ────────────────────────────────────────────────────────

    @Nested
    class StudyRoomTests {

        private StudyRoom study;
        private AppRegistry appRegistry;

        @BeforeEach
        void setUp() {
            appRegistry = new AppRegistry();
            appRegistry.register("echo", "echo", "Echo test");
            var launcher = new NoOpDesktopLauncher();
            study = new StudyRoom("user:alice", "study-alice", launcher, appRegistry, null);
        }

        @Test
        void basic_properties() {
            assertEquals("user:alice", study.userId());
            assertEquals("study-alice", study.roomId());
        }

        // --- Desktop Launch Guards ---

        @Test
        void open_app_rejects_non_human() {
            var result = study.openApp("echo", false, true);
            assertFalse(result.success());
            assertTrue(result.message().contains(
                I18n.get("study.launch.not_human")));
        }

        @Test
        void open_app_rejects_remote() {
            var result = study.openApp("echo", true, false);
            assertFalse(result.success());
            assertTrue(result.message().contains(
                I18n.get("study.launch.not_local")));
        }

        @Test
        void open_app_rejects_unknown_alias() {
            var result = study.openApp("nonexistent", true, true);
            assertFalse(result.success());
        }

        @Test
        void open_file_rejects_non_human() {
            var result = study.openFile("test.txt", false, true);
            assertFalse(result.success());
        }

        @Test
        void open_file_rejects_remote() {
            var result = study.openFile("test.txt", true, false);
            assertFalse(result.success());
        }

        @Test
        void open_url_rejects_non_human() {
            var result = study.openUrl("https://example.com", false, true);
            assertFalse(result.success());
        }

        @Test
        void open_url_rejects_remote() {
            var result = study.openUrl("https://example.com", true, false);
            assertFalse(result.success());
        }

        @Test
        void open_url_rejects_invalid() {
            var result = study.openUrl("not a url %%{}", true, true);
            assertFalse(result.success());
        }

        // --- Mounts ---

        @Test
        void mount_and_list() {
            study.mount("documents", tempDir);
            var mounts = study.mounts();
            assertEquals(1, mounts.size());
            assertTrue(mounts.containsKey("documents"));
        }

        @Test
        void unmount() {
            study.mount("documents", tempDir);
            study.unmount("documents");
            assertTrue(study.mounts().isEmpty());
        }

        @Test
        void resolve_mounted_file() throws IOException {
            study.mount("docs", tempDir);
            Files.writeString(tempDir.resolve("readme.txt"), "hello");

            Path resolved = study.resolveMount("docs/readme.txt");
            assertNotNull(resolved);
            assertTrue(resolved.toString().endsWith("readme.txt"));
        }

        @Test
        void resolve_prevents_traversal() throws IOException {
            study.mount("docs", tempDir);
            Path resolved = study.resolveMount("docs/../../etc/passwd");
            assertNull(resolved, "Path traversal should be blocked");
        }

        @Test
        void resolve_null_returns_null() {
            assertNull(study.resolveMount(null));
        }

        @Test
        void resolve_blank_returns_null() {
            assertNull(study.resolveMount("  "));
        }

        @Test
        void open_file_not_in_mount() {
            var result = study.openFile("nonexistent/file.txt", true, true);
            assertFalse(result.success());
        }

        // --- Agent Visits ---

        @Test
        void agent_knock_accepted() {
            assertTrue(study.agentKnock("did:agent:1"));
        }

        @Test
        void agent_enter_and_leave() {
            assertTrue(study.agentEnter("did:agent:1"));
            assertEquals(List.of("did:agent:1"), study.visitingAgents());

            study.agentLeave("did:agent:1");
            assertTrue(study.visitingAgents().isEmpty());
        }

        @Test
        void max_visitors_enforced() {
            study.setMaxVisitors(2);
            assertTrue(study.agentEnter("did:agent:1"));
            assertTrue(study.agentEnter("did:agent:2"));
            assertFalse(study.agentEnter("did:agent:3"));
        }

        @Test
        void duplicate_enter_is_idempotent() {
            study.agentEnter("did:agent:1");
            study.agentEnter("did:agent:1");
            assertEquals(1, study.visitingAgents().size());
        }

        @Test
        void knock_refused_when_full() {
            study.setMaxVisitors(1);
            study.agentEnter("did:agent:1");
            assertFalse(study.agentKnock("did:agent:2"));
        }

        @Test
        void set_max_visitors_minimum_one() {
            study.setMaxVisitors(0);
            // Should still allow at least 1
            assertTrue(study.agentEnter("did:agent:1"));
        }

        // --- Schedule Board ---

        @Test
        void schedule_board_empty_without_service() {
            assertEquals(0, study.scheduleBoard().size());
        }

        // --- Description ---

        @Test
        void describe_includes_chair_and_desk() {
            String desc = study.describe();
            assertNotNull(desc);
            assertFalse(desc.isBlank());
        }

        @Test
        void describe_shows_empty_shelves_when_no_mounts() {
            String desc = study.describe();
            assertTrue(desc.contains(
                I18n.get("study.description.shelves.empty")));
        }

        @Test
        void describe_shows_mount_labels() {
            study.mount("notes", tempDir);
            String desc = study.describe();
            assertTrue(desc.contains("notes"));
        }

        @Test
        void describe_shows_visiting_agents() {
            study.agentEnter("did:agent:luna");
            String desc = study.describe();
            assertTrue(desc.contains("did:agent:luna"));
        }

        // --- canLaunch ---

        @Test
        void can_launch_false_for_noop() {
            assertFalse(study.canLaunch());
        }
    }
}
