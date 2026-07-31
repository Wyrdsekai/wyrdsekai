package org.wyrdsekai.core.study;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Desktop launcher for macOS.
 * Uses `open` command for files, URLs, and apps.
 * Detects SSH sessions to disable GUI launching.
 */
public class MacOsDesktopLauncher extends AbstractDesktopLauncher {

    @Override
    public boolean isGuiAvailable() {
        // macOS always has a GUI — unless accessed via SSH
        return System.getenv("SSH_TTY") == null;
    }

    @Override
    protected List<String> buildFileOpenCommand(Path file) {
        return List.of("open", file.toAbsolutePath().toString());
    }

    @Override
    protected List<String> buildUrlOpenCommand(URI url) {
        return List.of("open", url.toString());
    }

    @Override
    protected List<String> buildAppCommand(String binary, String... args) {
        var command = new ArrayList<String>();
        command.add("open");
        command.add("-a");
        command.add(binary);
        if (args.length > 0) {
            command.add("--args");
            command.addAll(List.of(args));
        }
        return command;
    }
}
