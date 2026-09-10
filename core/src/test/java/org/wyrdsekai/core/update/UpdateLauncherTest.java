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
}
