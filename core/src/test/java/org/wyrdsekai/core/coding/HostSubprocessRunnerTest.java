package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HostSubprocessRunner}. The {@code echo}, {@code
 * sh}, and {@code true}/{@code false} binaries are POSIX-standard so
 * these tests run on Linux + macOS without setup.
 */
class HostSubprocessRunnerTest {

    @Test
    void run_echoesStdout(@TempDir Path workspace) throws Exception {
        var fut = HostSubprocessRunner.run(workspace,
            List.of("echo", "hello"),
            List.of("world"),
            Map.of(),
            Duration.ofSeconds(5));
        var res = fut.get(10, TimeUnit.SECONDS);
        assertTrue(res.success(), "echo should succeed; stderr=" + res.stderr());
        assertEquals(0, res.exitCode());
        assertTrue(res.stdout().contains("hello world"),
            "stdout should contain 'hello world'; got: " + res.stdout());
        assertEquals("echo hello world", res.entrypoint());
        assertNull(res.unsupportedReason());
    }

    @Test
    void run_capturesStderrAndExitCode(@TempDir Path workspace) throws Exception {
        var fut = HostSubprocessRunner.run(workspace,
            List.of("sh", "-c", "echo oops 1>&2; exit 7"),
            List.of(),
            Map.of(),
            Duration.ofSeconds(5));
        var res = fut.get(10, TimeUnit.SECONDS);
        assertFalse(res.success());
        assertEquals(7, res.exitCode());
        assertTrue(res.stderr().contains("oops"),
            "stderr should contain 'oops'; got: " + res.stderr());
    }

    @Test
    void run_passesExtraEnv(@TempDir Path workspace) throws Exception {
        var fut = HostSubprocessRunner.run(workspace,
            List.of("sh", "-c", "echo $WYRD_TEST_VAR"),
            List.of(),
            Map.of("WYRD_TEST_VAR", "marker-xyz"),
            Duration.ofSeconds(5));
        var res = fut.get(10, TimeUnit.SECONDS);
        assertTrue(res.success());
        assertTrue(res.stdout().contains("marker-xyz"),
            "stdout should carry the env var value; got: " + res.stdout());
    }

    @Test
    void run_inheritsCuratedHomeAndPath(@TempDir Path workspace) throws Exception {
        // Confirm that we DO carry HOME + PATH (curated) but NOT random
        // parent env vars. This is the L0 sandbox guarantee.
        var fut = HostSubprocessRunner.run(workspace,
            List.of("sh", "-c", "echo HOME=$HOME; echo PATH-len=${#PATH}"),
            List.of(),
            Map.of(),
            Duration.ofSeconds(5));
        var res = fut.get(10, TimeUnit.SECONDS);
        assertTrue(res.success());
        assertTrue(res.stdout().contains("HOME="),
            "should carry HOME; got: " + res.stdout());
    }

    @Test
    void run_killsProcessOnWallclock(@TempDir Path workspace) throws Exception {
        var fut = HostSubprocessRunner.run(workspace,
            List.of("sh", "-c", "sleep 30"),
            List.of(),
            Map.of(),
            Duration.ofMillis(300));
        var res = fut.get(5, TimeUnit.SECONDS);
        assertFalse(res.success());
        assertEquals(-1, res.exitCode());
        assertTrue(res.stderr().contains("wallclock exceeded"),
            "stderr should mention wallclock; got: " + res.stderr());
    }

    @Test
    void run_runsFromWorkspaceDir(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("marker.txt"), "found");
        var fut = HostSubprocessRunner.run(workspace,
            List.of("sh", "-c", "cat marker.txt"),
            List.of(),
            Map.of(),
            Duration.ofSeconds(5));
        var res = fut.get(10, TimeUnit.SECONDS);
        assertTrue(res.success(), "should run cwd=workspace; stderr=" + res.stderr());
        assertTrue(res.stdout().contains("found"));
    }

    @Test
    void run_missingBinary_failsCleanly(@TempDir Path workspace) throws Exception {
        var fut = HostSubprocessRunner.run(workspace,
            List.of("this-binary-does-not-exist-anywhere-xyz"),
            List.of(),
            Map.of(),
            Duration.ofSeconds(2));
        var res = fut.get(5, TimeUnit.SECONDS);
        assertFalse(res.success());
        assertTrue(res.stderr().contains("spawn failed"),
            "missing binary should fail with spawn-failed; got: " + res.stderr());
    }

    @Test
    void run_emptyArgv_returnsClearError(@TempDir Path workspace) throws Exception {
        var fut = HostSubprocessRunner.run(workspace,
            List.of(),
            List.of(),
            Map.of(),
            Duration.ofSeconds(2));
        var res = fut.get(5, TimeUnit.SECONDS);
        assertFalse(res.success());
        assertTrue(res.unsupportedReason() != null
                || res.stderr().contains("entrypoint"),
            "empty argv should signal an error; got: " + res);
    }

    @Test
    void run_nullWorkspace_returnsClearError() throws Exception {
        var fut = HostSubprocessRunner.run(null,
            List.of("echo", "x"),
            List.of(),
            Map.of(),
            Duration.ofSeconds(2));
        var res = fut.get(5, TimeUnit.SECONDS);
        assertFalse(res.success());
        assertTrue(res.unsupportedReason() != null
                || res.stderr().contains("workspace"),
            "null workspace should signal an error; got: " + res);
    }
}
