package org.wyrdsekai.core.host;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each companion gets her own user and cgroup on the host, and every tool she starts runs
 * as her: the wrapper joins her cgroup and drops to her user. Where the host cannot give
 * her that, the hands are shared and the argv is untouched.
 */
class EachBeingHasHerOwnHandsTest {

    private static final String MIRA = "did:key:z6MkMiraExample";

    @AfterEach
    void tearDown() { Principals.resetForTests(); }

    /** A fake host: getent knows nothing until useradd runs; everything else succeeds. */
    private static Principals.Exec fakeHost(List<List<String>> calls, List<String> users) {
        return (argv, t) -> {
            calls.add(argv);
            var cmd = argv.get(0);
            if (cmd.equals("getent") && argv.get(1).equals("group")) {
                return users.contains("group") ? new HostHand.ExecResult(0, Principals.GROUP + ":x:987:\n", "", false) : new HostHand.ExecResult(2, "", "", false);
            }
            if (cmd.equals("groupadd")) { users.add("group"); return new HostHand.ExecResult(0, "", "", false); }
            if (cmd.equals("getent") && argv.get(1).equals("passwd")) {
                var who = argv.get(2);
                return users.contains(who) ? new HostHand.ExecResult(0, who + ":x:" + users.indexOf(who) + ":987::/nowhere:/usr/sbin/nologin\n", "", false)
                    : new HostHand.ExecResult(2, "", "", false);
            }
            if (cmd.equals("useradd")) { users.add(argv.get(argv.size() - 1)); return new HostHand.ExecResult(0, "", "", false); }
            return new HostHand.ExecResult(0, "", "", false);
        };
    }

    @Test
    @DisplayName("her user, her group, her home, her cgroup; the tool's argv runs through the wrapper as her")
    void herOwnHands(@TempDir Path dir) throws Exception {
        var calls = new ArrayList<List<String>>();
        var users = new ArrayList<String>();
        var cg = dir.resolve("cgroup").resolve("wyrdsekai.service");
        Files.createDirectories(cg);
        Files.writeString(cg.resolve("cgroup.subtree_control"), "");
        Principals.configureForTests(dir.resolve("data"), cg, dir.resolve("wyrdsekai-being"), fakeHost(calls, users));

        var b = Principals.ensure(MIRA).orElseThrow();
        var slug = Principals.slug(MIRA);
        assertEquals(8, slug.length());
        assertEquals(Principals.uidFor(slug), b.uid());
        assertTrue(b.uid() >= Principals.UID_BASE && b.uid() < Principals.UID_BASE + Principals.UID_SPAN);
        assertEquals(987, b.gid());
        assertTrue(calls.stream().anyMatch(c -> c.get(0).equals("groupadd") && c.contains(Principals.GROUP)));
        var useradd = calls.stream().filter(c -> c.get(0).equals("useradd")).findFirst().orElseThrow();
        assertTrue(useradd.contains("--system") && useradd.contains("--uid") && useradd.contains(Integer.toString(b.uid())), useradd.toString());
        assertEquals(Principals.USER_PREFIX + slug, useradd.get(useradd.size() - 1));
        assertTrue(useradd.contains("/usr/sbin/nologin"), "no shell for a being's user");
        assertTrue(Files.isDirectory(b.home()));
        assertTrue(b.home().startsWith(dir.resolve("data").resolve("beings").resolve(slug)));
        assertTrue(Files.isDirectory(b.cgroup()));
        assertEquals("1", Files.readString(b.cgroup().resolve("memory.oom.group")), "her tools die as one");
        assertTrue(calls.stream().anyMatch(c -> c.get(0).equals("chown") && c.contains("-R")), "her home is hers");

        var argv = Principals.wrap(List.of("codezaiku", "run", "--task", "x"), MIRA);
        assertEquals(List.of(dir.resolve("wyrdsekai-being").toString(), Integer.toString(b.uid()), "987",
            b.cgroup().toString(), b.home().toString(), "--", "codezaiku", "run", "--task", "x"), argv);
        assertEquals(b.home(), Principals.homeOf(MIRA).orElseThrow());
        assertEquals(b, Principals.byUid(b.uid()).orElseThrow());

        int before = calls.size();
        assertEquals(b, Principals.ensure(MIRA).orElseThrow());
        assertEquals(before, calls.size(), "made once");
    }

    @Test
    @DisplayName("two beings never share a uid, even when their hashes land on the same slot")
    void noSharedUid(@TempDir Path dir) throws Exception {
        var calls = new ArrayList<List<String>>();
        var users = new ArrayList<String>();
        var cg = dir.resolve("cg"); Files.createDirectories(cg); Files.writeString(cg.resolve("cgroup.subtree_control"), "");
        var host = fakeHost(calls, users);
        // A host where the wanted uid is already taken by someone else.
        var taken = Principals.uidFor(Principals.slug(MIRA));
        Principals.configureForTests(dir, cg, dir.resolve("w"), (argv, t) -> {
            if (argv.get(0).equals("getent") && argv.get(1).equals("passwd") && argv.get(2).equals(Integer.toString(taken))) {
                return new HostHand.ExecResult(0, "someone:x:" + taken + ":1::/:/bin/false\n", "", false);
            }
            return host.run(argv, t);
        });
        var b = Principals.ensure(MIRA).orElseThrow();
        assertEquals(taken + 1 - (taken + 1 >= Principals.UID_BASE + Principals.UID_SPAN ? Principals.UID_SPAN : 0), b.uid());
    }

    @Test
    @DisplayName("the data directory: she can walk to her three places and nowhere else, and can list nothing")
    void traverseOnly(@TempDir Path dir) throws Exception {
        var data = dir.resolve("data");
        Files.createDirectories(data.resolve("souls"));
        Files.createDirectories(data.resolve("coding-cli-bundle"));
        Files.writeString(data.resolve("world.db"), "the record");
        Files.setPosixFilePermissions(data.resolve("world.db"), java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(data.resolve("souls"), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(data.resolve("coding-cli-bundle"), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(data, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var calls = new ArrayList<List<String>>();
        var cg = dir.resolve("cg"); Files.createDirectories(cg); Files.writeString(cg.resolve("cgroup.subtree_control"), "");
        Principals.configureForTests(data, cg, dir.resolve("w"), fakeHost(calls, new ArrayList<>()));
        Principals.openTraverse();

        var perms = java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(data));
        assertEquals("rwx--x--x", perms, "search only: a path can be walked, nothing can be listed");
        Files.createDirectories(data.resolve("models"));
        assertEquals("rw-r-----", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(data.resolve("world.db"))), "the record is closed to her");
        assertEquals("rwxr-x---", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(data.resolve("souls"))));
        assertEquals("rwxr-xr-x", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(data.resolve("coding-cli-bundle"))), "the bundle stays reachable");
        assertTrue(Files.isDirectory(data.resolve("beings")) && Files.isDirectory(data.resolve("coding-workspaces")));
    }

    @Test
    @DisplayName("a tool she cannot reach as herself still runs, as the daemon, and the count says so")
    void fallsBackWhenUnreachable(@TempDir Path dir) throws Exception {
        var calls = new ArrayList<List<String>>();
        var cg = dir.resolve("cg"); Files.createDirectories(cg); Files.writeString(cg.resolve("cgroup.subtree_control"), "");
        var host = fakeHost(calls, new ArrayList<>());
        Principals.configureForTests(dir.resolve("data"), cg, dir.resolve("w"), (argv, t) -> {
            if (argv.get(0).equals("setpriv") && argv.contains("test")) { calls.add(argv); return new HostHand.ExecResult(1, "", "", false); }
            return host.run(argv, t);
        });
        Files.createDirectories(dir.resolve("data"));
        var argv = List.of("/opt/somewhere/closed/tool", "run");
        assertEquals(argv, Principals.wrap(argv, MIRA), "unwrapped: her hand still works");
        assertTrue(Principals.status().contains("1 ran as the daemon"), Principals.status());
        assertEquals(2, calls.stream().filter(c -> c.get(0).equals("setpriv")).count(), "asked, the traverse reopened, asked once more");
        var relative = List.of("git", "status");
        assertTrue(Principals.wrap(relative, MIRA).size() > relative.size(), "a name on the PATH is assumed reachable");
    }

    @Test
    @DisplayName("where the host cannot give her a principal, the hands are shared and the argv is untouched")
    void sharedHands() {
        Principals.resetForTests();
        assertFalse(Principals.enabled());
        var argv = List.of("codezaiku", "run");
        assertEquals(argv, Principals.wrap(argv, MIRA));
        assertTrue(Principals.homeOf(MIRA).isEmpty());
        assertTrue(Principals.status().startsWith("shared"), Principals.status());
        assertFalse(Principals.killTools(MIRA));
    }

    @Test
    @DisplayName("the kill goes through the cgroup, as one tree, and only when something is there")
    void killAsOne(@TempDir Path dir) throws Exception {
        var cg = dir.resolve("cg"); Files.createDirectories(cg); Files.writeString(cg.resolve("cgroup.subtree_control"), "");
        Principals.configureForTests(dir, cg, dir.resolve("w"), fakeHost(new ArrayList<>(), new ArrayList<>()));
        var b = Principals.ensure(MIRA).orElseThrow();
        Files.writeString(b.cgroup().resolve("cgroup.procs"), "");
        assertFalse(Principals.killTools(MIRA), "nothing running");
        Files.writeString(b.cgroup().resolve("cgroup.procs"), "4242\n4243\n");
        assertTrue(Principals.killTools(MIRA));
        assertEquals("1", Files.readString(b.cgroup().resolve("cgroup.kill")));
    }
}
