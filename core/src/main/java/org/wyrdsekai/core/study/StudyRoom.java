package org.wyrdsekai.core.study;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.ScheduledAction;
import org.wyrdsekai.core.skill.SchedulerService;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The Study — user's personal room. Server-side logic.
 * Coordinates between DesktopLauncher, AppRegistry, SchedulerService,
 * and the room scripting system.
 *
 * One Study per human in the household (§111, §6.1).
 */
public class StudyRoom {

    private final String userId;
    private final String roomId;
    private final DesktopLauncher launcher;
    private final AppRegistry appRegistry;
    private final SchedulerService schedulerService;
    private final List<String> visitingAgents = new CopyOnWriteArrayList<>();
    private final Map<String, Path> mountedPaths = new LinkedHashMap<>();
    private int maxVisitors = 3;

    public StudyRoom(String userId, String roomId,
                     DesktopLauncher launcher, AppRegistry appRegistry,
                     SchedulerService schedulerService) {
        this.userId = userId;
        this.roomId = roomId;
        this.launcher = launcher;
        this.appRegistry = appRegistry;
        this.schedulerService = schedulerService;
        appRegistry.populateLauncher(launcher);
    }

    public String userId() { return userId; }
    public String roomId() { return roomId; }

    // --- Desktop Launching (human-only, local-only) ---

    /** Open an app by alias. Only for verified human local sessions. */
    public LaunchResult openApp(String alias, boolean isHuman, boolean isLocal, String... args) {
        if (!isHuman) return LaunchResult.fail(I18n.get("study.launch.not_human"));
        if (!isLocal) return LaunchResult.fail(I18n.get("study.launch.not_local"));
        if (!appRegistry.hasApp(alias)) {
            return LaunchResult.fail(I18n.get("study.launch.unknown_app"));
        }
        return launcher.openApp(alias, args);
    }

    /** Open a file. Path must be within a mounted directory. */
    public LaunchResult openFile(String relativePath, boolean isHuman, boolean isLocal) {
        if (!isHuman) return LaunchResult.fail(I18n.get("study.launch.not_human"));
        if (!isLocal) return LaunchResult.fail(I18n.get("study.launch.not_local"));

        Path resolved = resolveMount(relativePath);
        if (resolved == null) {
            return LaunchResult.fail(I18n.get("study.launch.file_not_found", relativePath));
        }
        return launcher.openFile(resolved);
    }

    /** Open a URL in the browser. */
    public LaunchResult openUrl(String url, boolean isHuman, boolean isLocal) {
        if (!isHuman) return LaunchResult.fail(I18n.get("study.launch.browse_only"));
        if (!isLocal) return LaunchResult.fail(I18n.get("study.launch.not_local"));

        try {
            URI uri = URI.create(url);
            return launcher.openUrl(uri);
        } catch (IllegalArgumentException e) {
            return LaunchResult.fail(I18n.get("study.launch.invalid_url", url));
        }
    }

    /** Check if app launching is available. */
    public boolean canLaunch() {
        return launcher.isGuiAvailable();
    }

    // --- Filesystem Mounts ---

    /** Mount a directory as a labeled shelf section. */
    public void mount(String label, Path directory) {
        mountedPaths.put(label, directory.toAbsolutePath());
    }

    /** Unmount a labeled shelf section. */
    public void unmount(String label) {
        mountedPaths.remove(label);
    }

    /** Get all mounted paths. */
    public Map<String, Path> mounts() {
        return Map.copyOf(mountedPaths);
    }

    /** Resolve a relative path through the mount system. Returns null if not in any mount. */
    Path resolveMount(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;

        // Format: "label/sub/path/file.ext" or just "file.ext" (searches all mounts)
        int slash = relativePath.indexOf('/');
        if (slash > 0) {
            String label = relativePath.substring(0, slash);
            String rest = relativePath.substring(slash + 1);
            Path mount = mountedPaths.get(label);
            if (mount != null) {
                Path resolved = mount.resolve(rest).normalize();
                // Security: prevent traversal above mount point
                if (resolved.startsWith(mount)) {
                    return resolved;
                }
            }
        }

        // Search all mounts for the file
        for (var entry : mountedPaths.entrySet()) {
            Path candidate = entry.getValue().resolve(relativePath).normalize();
            if (candidate.startsWith(entry.getValue()) && Files.exists(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    // --- Agent Visits ---

    /** Agent knocks on the Study door. Returns true if accepted. */
    public boolean agentKnock(String agentDid) {
        return visitingAgents.size() < maxVisitors;
    }

    /** Agent enters the Study (after user approval). */
    public boolean agentEnter(String agentDid) {
        if (visitingAgents.size() >= maxVisitors) return false;
        if (visitingAgents.contains(agentDid)) return true;
        visitingAgents.add(agentDid);
        return true;
    }

    /** Agent leaves the Study. */
    public void agentLeave(String agentDid) {
        visitingAgents.remove(agentDid);
    }

    /** Get list of agents currently in the Study. */
    public List<String> visitingAgents() {
        return List.copyOf(visitingAgents);
    }

    /** Set maximum visitors. */
    public void setMaxVisitors(int max) {
        this.maxVisitors = Math.max(1, max);
    }

    // --- Schedule Board ---

    /** Get scheduled actions for display on the schedule board. */
    public List<ScheduledAction> scheduleBoard() {
        if (schedulerService == null) return List.of();
        return schedulerService.allActive();
    }

    // --- Room Description ---

    /** Generate the dynamic room description. */
    public String describe() {
        var sb = new StringBuilder();
        sb.append(I18n.get("study.description.chair")).append('\n');
        sb.append(I18n.get("study.description.desk")).append('\n');

        if (!mountedPaths.isEmpty()) {
            sb.append(I18n.get("study.description.shelves"));
            for (var entry : mountedPaths.entrySet()) {
                sb.append("\n  ").append(I18n.get("study.description.shelves.label", entry.getKey()));
            }
            sb.append('\n');
        } else {
            sb.append(I18n.get("study.description.shelves.empty")).append('\n');
        }

        sb.append(I18n.get("study.description.wardrobe")).append('\n');

        for (String agent : visitingAgents) {
            sb.append(I18n.get("study.description.agent.here", agent)).append('\n');
        }

        return sb.toString();
    }
}
