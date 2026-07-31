package org.wyrdsekai.core.study;

import org.wyrdsekai.common.i18n.I18n;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

/**
 * No-op launcher for headless/unknown platforms.
 * Always reports GUI unavailable. Used as fallback.
 */
public class NoOpDesktopLauncher implements DesktopLauncher {

    @Override
    public LaunchResult openFile(Path file) {
        return LaunchResult.fail(I18n.get("study.launch.not_available"));
    }

    @Override
    public LaunchResult openApp(String alias, String... args) {
        return LaunchResult.fail(I18n.get("study.launch.not_available"));
    }

    @Override
    public LaunchResult openUrl(URI url) {
        return LaunchResult.fail(I18n.get("study.launch.not_available"));
    }

    @Override
    public boolean isGuiAvailable() {
        return false;
    }

    @Override
    public Map<String, AppBinding> registeredApps() {
        return Map.of();
    }

    @Override
    public void registerApp(AppBinding binding) {
        // No-op
    }
}
