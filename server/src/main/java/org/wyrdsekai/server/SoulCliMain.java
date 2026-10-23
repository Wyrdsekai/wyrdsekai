package org.wyrdsekai.server;

import org.wyrdsekai.common.system.SystemPaths;
import org.wyrdsekai.core.soul.SoulAdminMain;

/**
 * {@code wyrd soul …} from the launcher: brings her signing key up the way the node does, then
 * runs the soul commands. Without the key, an edit on a stopped node was stored with the previous
 * version's signature and her first start read her as tampered (2026-10-22).
 */
public final class SoulCliMain {

    private SoulCliMain() {}

    public static void main(String[] args) {
        var jdbcUrl = "jdbc:sqlite:" + SystemPaths.dbPath().toAbsolutePath();
        CliSigning.enable(jdbcUrl, SystemPaths.dataDir());
        SoulAdminMain.main(args);
    }
}
