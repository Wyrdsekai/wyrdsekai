package org.wyrdsekai.core.update;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UpdateLauncherTest {

    @Test
    void each_platform_runs_the_updater_outside_the_service_that_will_be_stopped() {
        var wyrd = Path.of("/opt/wyrdsekai/bin/wyrd");
        var log = Path.of("/var/lib/wyrdsekai/logs/self-update.log");
        var linux = UpdateLauncher.command("Linux", wyrd, log);
        assertTrue(linux.get(0).equals("systemd-run") || linux.get(0).equals("setsid"), linux.toString());
        assertTrue(String.join(" ", linux).contains("update now --yes"), linux.toString());
        var mac = UpdateLauncher.command("Mac OS X", Path.of("/usr/local/wyrdsekai/bin/wyrd"), log);
        assertEquals("launchctl", mac.get(0));
        assertTrue(mac.contains("submit") && mac.contains("--yes"), mac.toString());
        var win = UpdateLauncher.command("Windows 11", Path.of("C:\\Program Files\\Wyrdsekai\\app\\bin\\wyrd.ps1"), log);
        assertEquals("powershell", win.get(0));
        assertTrue(win.contains("stage"), "Windows only stages: the .msi needs an elevation prompt a service cannot show");
        assertFalse(win.contains("now"));
    }

    @Test
    void an_installer_taken_from_a_household_node_is_named_on_every_platform() {
        var log = Path.of("/var/lib/wyrdsekai/logs/self-update.log");
        var from = "http://198.51.100.65:7070";
        var linux = UpdateLauncher.command("Linux", Path.of("/opt/wyrdsekai/bin/wyrd"), log, from);
        assertTrue(String.join(" ", linux).contains("update now --yes --no-siblings --from " + from), linux.toString());
        var mac = UpdateLauncher.command("Mac OS X", Path.of("/usr/local/wyrdsekai/bin/wyrd"), log, from);
        assertTrue(mac.indexOf("--from") > 0 && mac.get(mac.indexOf("--from") + 1).equals(from), mac.toString());
        var win = UpdateLauncher.command("Windows 11", Path.of("C:\\Program Files\\Wyrdsekai\\app\\bin\\wyrd.ps1"), log, from);
        assertTrue(win.contains("stage") && win.contains("--from"), win.toString());
        assertFalse(String.join(" ", UpdateLauncher.command("Linux", Path.of("/opt/wyrdsekai/bin/wyrd"), log, null)).contains("--from"));
    }

    @Test
    void the_nodes_own_update_leaves_codezaiku_and_researchzosho_to_their_own_settings() {
        // Their contract: they update on their own only by CODEZAIKU_UPDATE / RESEARCHZOSHO_UPDATE; a
        // person's `wyrd update now` asks them. The node's automatic update is nobody asking.
        var log = Path.of("/var/lib/wyrdsekai/logs/self-update.log");
        var linux = UpdateLauncher.command("Linux", Path.of("/opt/wyrdsekai/bin/wyrd"), log);
        assertTrue(String.join(" ", linux).contains("update now --yes --no-siblings"), linux.toString());
        var mac = UpdateLauncher.command("Mac OS X", Path.of("/usr/local/wyrdsekai/bin/wyrd"), log);
        assertTrue(mac.contains("--no-siblings"), mac.toString());
    }
}
