package org.wyrdsekai.core.study;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Desktop launcher for Windows.
 * Uses `cmd /c start` for files, PowerShell for apps.
 * Detects GUI session via SESSIONNAME env var.
 */
public class WindowsDesktopLauncher extends AbstractDesktopLauncher {

    @Override
    public boolean isGuiAvailable() {
        String session = System.getenv("SESSIONNAME");
        return session != null && !session.isEmpty();
    }

    @Override
    protected List<String> buildFileOpenCommand(Path file) {
        return List.of("cmd", "/c", "start", "", file.toAbsolutePath().toString());
    }

    @Override
    protected List<String> buildUrlOpenCommand(URI url) {
        return List.of("cmd", "/c", "start", "", url.toString());
    }

    @Override
    protected List<String> buildAppCommand(String binary, String... args) {
        var command = new ArrayList<String>();
        command.add("cmd");
        command.add("/c");
        command.add("start");
        command.add("");
        command.add(binary);
        command.addAll(List.of(args));
        return command;
    }
}
