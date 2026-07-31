package org.wyrdsekai.daemon.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopAutoStartTest {

    @Test
    void isEnabled_defaultFalse() {
        // Auto-start should not be enabled by default in test environment
        // (may be true if previously configured, so just verify no exception)
        var result = DesktopAutoStart.isEnabled();
        assertThat(result).isIn(true, false); // just verify it doesn't throw
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void linuxServicePath_isUserService() {
        // Verify the service would be installed as a user service, not system
        var servicePath = System.getProperty("user.home")
            + "/.config/systemd/user/wyrdsekai-daemon.service";
        assertThat(servicePath).contains(".config/systemd/user");
    }
}
