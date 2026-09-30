package org.wyrdsekai.core.update;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The .deb postinst's upgrade branch, run for real under /bin/sh with systemctl and docker
 * stubbed. The search sidecar reads its settings only at start, so an upgrade restarts it when
 * what the running container has mounted differs from the new file. That check must not depend
 * on the main service: a node left stopped, or one whose restart failed, still gets it.
 */
class AnUpgradeRestartsSearchOnOldSettingsTest {

    private static final Path BUILD_DEB = Path.of("../packaging/deb/build-deb.sh").toAbsolutePath().normalize();

    private record Run(String out, String docker, String systemctl) {}

    @TempDir Path dir;
    private Path upgrade;

    @BeforeEach
    void extractTheUpgradeBranch() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"), "the postinst is Linux-only");
        assumeTrue(Files.isReadable(BUILD_DEB), "build script present: " + BUILD_DEB);
        var lines = Files.readAllLines(BUILD_DEB);
        int start = lines.indexOf("cat > \"$DEB_ROOT/DEBIAN/postinst\" << 'EOF'");
        assertTrue(start >= 0, "postinst heredoc not found");
        int end = lines.subList(start + 1, lines.size()).indexOf("EOF") + start + 1;
        var postinst = lines.subList(start + 1, end);
        assertEquals("#!/bin/sh", postinst.get(0));
        int from = postinst.indexOf("if [ -n \"$2\" ]; then");
        assertTrue(from >= 0, "upgrade branch not found");
        int to = postinst.subList(from, postinst.size()).indexOf("fi") + from;
        // Point the absolute paths the branch reads at this test's directory.
        var body = String.join("\n", postinst.subList(from, to + 1))
            .replace("/run/wyrdsekai.", dir + "/run/wyrdsekai.")
            .replace("/opt/wyrdsekai/", dir + "/opt/wyrdsekai/");
        upgrade = dir.resolve("upgrade.sh");
        Files.writeString(upgrade, "#!/bin/sh\nset -e\n" + body + "\n");
        Files.createDirectories(dir.resolve("run"));
        Files.createDirectories(dir.resolve("opt/wyrdsekai/docker"));
        Files.writeString(dir.resolve("opt/wyrdsekai/docker/searxng-settings.yml"), "engines:\n  - name: wikipedia full text\n");

        var bin = Files.createDirectories(dir.resolve("bin"));
        stub(bin.resolve("systemctl"), """
            #!/usr/bin/env bash
            echo "$*" >> "$T/systemctl.log"
            [ "$1" = restart ] && exit "${RESTART_RC:-0}"
            exit 0
            """);
        // The container's /etc/searxng/settings.yml is $T/mounted.yml: the file the container
        // was started with, which dpkg's rename of the host file does not change. An exec that
        // cannot start prints docker's error on stdout and exits 127, as real docker does.
        stub(bin.resolve("docker"), """
            #!/usr/bin/env bash
            echo "$*" >> "$T/docker.log"
            case "$1" in
                ps) cat "$T/ps" 2>/dev/null; exit 0 ;;
                exec) [ -f "$T/exec-fails" ] && exit 1
                      if [ -f "$T/exec-cannot-start" ]; then
                          echo 'OCI runtime exec failed: exec failed: unable to start container process: exec: "sha256sum": executable file not found in $PATH: unknown'
                          exit 127
                      fi
                      shift 2; exec "${@//\\/etc\\/searxng\\/settings.yml/$T/mounted.yml}" ;;
            esac
            exit 0
            """);
    }

    @Test
    @DisplayName("a node left stopped still gets search restarted onto the new settings")
    void leftStopped() throws Exception {
        Files.writeString(dir.resolve("run/wyrdsekai.upgrade-state"), "inactive\n");
        runningWith("engines: []\n");
        var r = upgrade();
        assertTrue(r.out().contains("left stopped"), r.out());
        assertFalse(r.systemctl().contains("restart"), r.systemctl());
        assertTrue(r.docker().contains("restart wyrdsekai-searxng"), r.docker());
        assertTrue(r.out().contains("restarted with this version's"), r.out());
    }

    @Test
    @DisplayName("a failed main-service restart does not skip the search restart")
    void mainRestartFailed() throws Exception {
        Files.writeString(dir.resolve("run/wyrdsekai.upgrade-state"), "active\n");
        runningWith("engines: []\n");
        var r = upgrade("RESTART_RC", "1");
        assertTrue(r.out().contains("could not auto-restart"), r.out());
        assertTrue(r.docker().contains("restart wyrdsekai-searxng"), r.docker());
    }

    @Test
    @DisplayName("a normal upgrade restarts search only when the running settings are not the new ones")
    void onlyWhenDifferent() throws Exception {
        runningWith("engines: []\n");
        var r = upgrade();
        assertTrue(r.systemctl().contains("restart wyrdsekai"), r.systemctl());
        assertTrue(r.docker().contains("restart wyrdsekai-searxng"), r.docker());

        Files.writeString(dir.resolve("docker.log"), "");
        runningWith(Files.readString(dir.resolve("opt/wyrdsekai/docker/searxng-settings.yml")));
        var same = upgrade();
        assertFalse(same.docker().contains("restart"), "already running the new settings: " + same.docker());
        assertFalse(same.out().contains("search"), same.out());
    }

    @Test
    @DisplayName("no restart when the container is not running or its settings cannot be read")
    void unknownIsLeftAlone() throws Exception {
        var none = upgrade();
        assertFalse(none.docker().contains("exec") || none.docker().contains("restart"), none.docker());

        runningWith("engines: []\n");
        Files.writeString(dir.resolve("exec-fails"), "");
        var unreadable = upgrade();
        assertTrue(unreadable.docker().contains("exec wyrdsekai-searxng"), unreadable.docker());
        assertFalse(unreadable.docker().contains("restart"), unreadable.docker());

        Files.delete(dir.resolve("exec-fails"));
        Files.writeString(dir.resolve("exec-cannot-start"), "");
        Files.writeString(dir.resolve("docker.log"), "");
        var noDigest = upgrade();
        assertTrue(noDigest.docker().contains("exec wyrdsekai-searxng"), noDigest.docker());
        assertFalse(noDigest.docker().contains("restart"), "docker's error text is not a digest: " + noDigest.docker());
        assertFalse(noDigest.out().contains("search"), noDigest.out());
    }

    private void runningWith(String mounted) throws Exception {
        Files.writeString(dir.resolve("ps"), "wyrdsekai-searxng\n");
        Files.writeString(dir.resolve("mounted.yml"), mounted);
    }

    private Run upgrade(String... env) throws Exception {
        var pb = new ProcessBuilder(List.of("/bin/sh", upgrade.toString(), "configure", "0.4.2")).redirectErrorStream(true);
        pb.environment().put("PATH", dir.resolve("bin") + ":" + System.getenv("PATH"));
        pb.environment().put("T", dir.toString());
        for (int i = 0; i + 1 < env.length; i += 2) pb.environment().put(env[i], env[i + 1]);
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), out);
        return new Run(out, read("docker.log"), read("systemctl.log"));
    }

    private String read(String name) throws Exception {
        var f = dir.resolve(name);
        return Files.exists(f) ? Files.readString(f) : "";
    }

    private static void stub(Path file, String body) throws Exception {
        Files.writeString(file, body);
        assertTrue(file.toFile().setExecutable(true));
    }
}
