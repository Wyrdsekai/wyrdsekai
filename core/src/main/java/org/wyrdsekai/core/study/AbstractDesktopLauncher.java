package org.wyrdsekai.core.study;

import org.wyrdsekai.common.i18n.I18n;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Shared base for platform-specific desktop launchers.
 * Handles app registry, common validation, and process execution.
 */
abstract class AbstractDesktopLauncher implements DesktopLauncher {

    protected final Map<String, AppBinding> apps = new ConcurrentHashMap<>();

    @Override
    public Map<String, AppBinding> registeredApps() {
        return Map.copyOf(apps);
    }

    @Override
    public void registerApp(AppBinding binding) {
        apps.put(binding.alias(), binding);
    }

    @Override
    public LaunchResult openApp(String alias, String... args) {
        if (!isGuiAvailable()) {
            return LaunchResult.fail(I18n.get("study.launch.no_gui"));
        }
        AppBinding binding = apps.get(alias);
        if (binding == null) {
            return LaunchResult.fail(I18n.get("study.launch.unknown_app"));
        }
        List<String> command = buildAppCommand(binding.command(), args);
        return executeCommand(command, I18n.get("study.launch.app_success", alias));
    }

    @Override
    public LaunchResult openFile(Path file) {
        if (!isGuiAvailable()) {
            return LaunchResult.fail(I18n.get("study.launch.no_gui"));
        }
        if (file == null || !Files.exists(file)) {
            return LaunchResult.fail(I18n.get("study.launch.file_not_found", String.valueOf(file)));
        }
        List<String> command = buildFileOpenCommand(file);
        String name = file.getFileName().toString();
        return executeCommand(command, I18n.get("study.launch.file_success", name));
    }

    @Override
    public LaunchResult openUrl(URI url) {
        if (!isGuiAvailable()) {
            return LaunchResult.fail(I18n.get("study.launch.no_gui"));
        }
        if (url == null) {
            return LaunchResult.fail(I18n.get("study.launch.invalid_url", "null"));
        }
        String scheme = url.getScheme();
        if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
            return LaunchResult.fail(I18n.get("study.launch.scheme_blocked", String.valueOf(scheme)));
        }
        List<String> command = buildUrlOpenCommand(url);
        return executeCommand(command, I18n.get("study.launch.url_success"));
    }

    /** Platform-specific: build command to open a file. */
    protected abstract List<String> buildFileOpenCommand(Path file);

    /** Platform-specific: build command to open a URL. */
    protected abstract List<String> buildUrlOpenCommand(URI url);

    /** Platform-specific: build command to launch an app. */
    protected abstract List<String> buildAppCommand(String binary, String... args);

    /** Execute a command via ProcessBuilder (no shell, explicit args). */
    protected LaunchResult executeCommand(List<String> command, String successNarration) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean started = process.waitFor(2, TimeUnit.SECONDS);
            if (!started) {
                return LaunchResult.ok(successNarration, process.pid());
            }

            int exit = process.exitValue();
            if (exit == 0) {
                return LaunchResult.ok(successNarration, process.pid());
            } else {
                return LaunchResult.fail(I18n.get("study.launch.failed", exit));
            }
        } catch (IOException e) {
            return LaunchResult.fail(I18n.get("study.launch.cannot", e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LaunchResult.fail(I18n.get("study.launch.interrupted"));
        }
    }
}
