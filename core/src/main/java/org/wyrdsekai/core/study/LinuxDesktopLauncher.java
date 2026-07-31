package org.wyrdsekai.core.study;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Desktop launcher for Linux (X11 and Wayland).
 * Uses xdg-open for files/URLs, direct binary for apps.
 */
public class LinuxDesktopLauncher extends AbstractDesktopLauncher {

    @Override
    public boolean isGuiAvailable() {
        return System.getenv("DISPLAY") != null
            || System.getenv("WAYLAND_DISPLAY") != null;
    }

    @Override
    protected List<String> buildFileOpenCommand(Path file) {
        return List.of("xdg-open", file.toAbsolutePath().toString());
    }

    @Override
    protected List<String> buildUrlOpenCommand(URI url) {
        return List.of("xdg-open", url.toString());
    }

    @Override
    protected List<String> buildAppCommand(String binary, String... args) {
        var command = new ArrayList<String>();
        command.add(binary);
        command.addAll(List.of(args));
        return command;
    }
}
