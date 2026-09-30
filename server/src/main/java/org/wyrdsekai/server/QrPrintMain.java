package org.wyrdsekai.server;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Prints a terminal QR code for {@code args[0]}, always as UTF-8. The Windows launcher's
 * {@code wyrd phone invite} draws the invite with it: Windows has no python3 by default,
 * and the Java runtime ships with the node.
 */
public final class QrPrintMain {

    private QrPrintMain() {}

    public static void main(String[] args) {
        if (args.length != 1 || args[0].isBlank()) {
            System.err.println("usage: QrPrintMain <text>");
            System.exit(64);
        }
        System.exit(print(args[0], System.out) ? 0 : 1);
    }

    static boolean print(String data, OutputStream out) {
        var lines = RelayCommandBridge.asciiQr(data);
        if (lines.isEmpty()) return false;
        var ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        for (var line : lines) ps.println(line);
        ps.flush();
        return true;
    }
}
