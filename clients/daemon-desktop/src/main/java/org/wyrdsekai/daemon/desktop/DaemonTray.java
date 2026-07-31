package org.wyrdsekai.daemon.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.daemon.common.DaemonConfig;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;

/**
 * System tray icon for the desktop inference daemon.
 * Shows status, peer count, and provides start/stop/quit controls.
 *
 * Uses AWT SystemTray — available on Windows, Linux (with tray support),
 * and macOS (with limitations).
 */
public final class DaemonTray {

    private static final Logger log = LoggerFactory.getLogger(DaemonTray.class);

    private static TrayIcon trayIcon;
    private static MenuItem statusItem;
    private static MenuItem peersItem;
    private static MenuItem statsItem;

    private DaemonTray() {}

    /** Check if the system tray is supported on this platform. */
    public static boolean isSupported() {
        return SystemTray.isSupported();
    }

    /** Install the tray icon and menu. */
    public static void install(DaemonService service, DaemonConfig config) {
        if (!isSupported()) {
            log.warn("System tray not supported on this platform");
            return;
        }

        try {
            var popup = new PopupMenu();

            statusItem = new MenuItem("Status: Starting...");
            statusItem.setEnabled(false);
            popup.add(statusItem);

            peersItem = new MenuItem("Peers: 0");
            peersItem.setEnabled(false);
            popup.add(peersItem);

            statsItem = new MenuItem("Requests: 0");
            statsItem.setEnabled(false);
            popup.add(statsItem);

            popup.addSeparator();

            var stopItem = new MenuItem("Stop Daemon");
            stopItem.addActionListener((ActionEvent e) -> {
                service.stop();
                updateStatus("Stopped");
            });
            popup.add(stopItem);

            var startItem = new MenuItem("Start Daemon");
            startItem.addActionListener((ActionEvent e) -> {
                Thread.ofVirtual().start(service::start);
                updateStatus("Starting...");
            });
            popup.add(startItem);

            popup.addSeparator();

            var quitItem = new MenuItem("Quit");
            quitItem.addActionListener((ActionEvent e) -> {
                service.stop();
                SystemTray.getSystemTray().remove(trayIcon);
                System.exit(0);
            });
            popup.add(quitItem);

            // Create tray icon (simple colored square)
            var image = createIcon(Color.GRAY);
            trayIcon = new TrayIcon(image, "Wyrdsekai Daemon", popup);
            trayIcon.setImageAutoSize(true);

            SystemTray.getSystemTray().add(trayIcon);
            log.info("System tray icon installed");

            // Start status update timer
            startStatusUpdater(service);

        } catch (Exception e) {
            log.error("Failed to install system tray: {}", e.getMessage());
        }
    }

    static void updateStatus(String status) {
        if (statusItem != null) {
            statusItem.setLabel("Status: " + status);
        }
    }

    private static void startStatusUpdater(DaemonService service) {
        Thread.ofVirtual().name("tray-updater").start(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                    if (service.isRunning()) {
                        updateStatus("Running");
                        trayIcon.setImage(createIcon(new Color(0, 180, 0)));
                        var stats = service.stats();
                        statsItem.setLabel(String.format("Requests: %d | %s",
                            stats.requestsServed(), stats.uptimeFormatted()));
                        if (service.gossip() != null) {
                            peersItem.setLabel("Peers: " + service.gossip().peerCount());
                        }
                    } else {
                        updateStatus("Stopped");
                        trayIcon.setImage(createIcon(Color.GRAY));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private static Image createIcon(Color color) {
        var size = 16;
        var img = new BufferedImage(size, size,
            BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setColor(color);
        g.fillOval(2, 2, size - 4, size - 4);
        g.setColor(Color.WHITE);
        g.drawOval(2, 2, size - 4, size - 4);
        g.dispose();
        return img;
    }
}
