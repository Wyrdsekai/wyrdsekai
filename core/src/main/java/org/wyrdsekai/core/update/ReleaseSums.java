package org.wyrdsekai.core.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The release's checksums, carried between the household's machines.
 *
 * <p>A machine that cannot reach the internet can still take an installer from a household peer,
 * but had nothing to check it against: the checksums came from the release itself, or the install
 * was refused. Now a machine that fetched a release's {@code SHA256SUMS} from the release passes
 * them to the household over the Between, signed with its node key, and a machine that cannot
 * reach the release uses those. Only what a machine fetched itself is passed on; a copy it received
 * is never re-signed (operator, 2026-10-03: "carry to between - yes").
 *
 * <p>Two files, so the stronger source always wins: {@code SHA256SUMS.<v>} is what this machine
 * fetched from the release; {@code SHA256SUMS.<v>.household} is what a household peer passed on,
 * used only when the release cannot be reached. Two peers that disagree leave nothing behind.
 */
public final class ReleaseSums {

    private ReleaseSums() {}

    static final int MAX_BYTES = 64 * 1024;
    static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    static final Pattern LINE = Pattern.compile("[0-9a-f]{64}[ \\t]+\\*?[A-Za-z0-9._~+-]+");

    public enum Outcome { STORED, ALREADY_HAVE, ALREADY_HAVE_RELEASE, CONFLICT, REFUSED }

    /** A version as releases are named. */
    public static boolean isVersion(String v) { return v != null && VERSION.matcher(v).matches(); }

    /** The shape of a SHA256SUMS file and nothing else: hex, whitespace, a bare file name, per line. */
    public static boolean looksLikeSums(String text) {
        if (text == null || text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return false;
        int lines = 0;
        for (var raw : text.split("\n")) {
            var line = raw.strip();
            if (line.isEmpty()) continue;
            if (!LINE.matcher(line).matches()) return false;
            lines++;
        }
        return lines > 0;
    }

    /** What this machine fetched from the release itself. */
    public static Path releaseFile(Path updatesDir, String version) { return updatesDir.resolve("SHA256SUMS." + version); }

    /** What a household peer passed on. */
    public static Path carriedFile(Path updatesDir, String version) { return updatesDir.resolve("SHA256SUMS." + version + ".household"); }

    /**
     * The checksums this machine may pass on: only the ones it fetched from the release itself.
     * A copy that came from a peer is not re-signed by this machine.
     */
    public static Optional<String> ownSums(Path updatesDir, String version) {
        if (updatesDir == null || !isVersion(version)) return Optional.empty();
        var f = releaseFile(updatesDir, version);
        try {
            if (!Files.isRegularFile(f)) return Optional.empty();
            var text = Files.readString(f, StandardCharsets.UTF_8);
            return looksLikeSums(text) ? Optional.of(text) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Keep what a household peer passed on, for the day the release cannot be reached. The release's
     * own copy, when this machine has one, is never replaced; a second peer that disagrees with the
     * first leaves nothing behind, since one of them is wrong and this machine cannot tell which.
     */
    public static Outcome storeCarried(Path updatesDir, String version, String text, String fromNode) {
        if (updatesDir == null || !isVersion(version) || !looksLikeSums(text)) return Outcome.REFUSED;
        try {
            Files.createDirectories(updatesDir);
            if (Files.isRegularFile(releaseFile(updatesDir, version))) return Outcome.ALREADY_HAVE_RELEASE;
            var carried = carriedFile(updatesDir, version);
            if (Files.isRegularFile(carried)) {
                var have = Files.readString(carried, StandardCharsets.UTF_8);
                if (normalize(have).equals(normalize(text))) return Outcome.ALREADY_HAVE;
                Files.deleteIfExists(carried);
                return Outcome.CONFLICT;
            }
            var tmp = updatesDir.resolve(carried.getFileName() + ".partial");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, carried, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return Outcome.STORED;
        } catch (IOException e) {
            return Outcome.REFUSED;
        }
    }

    private static String normalize(String s) {
        var sb = new StringBuilder();
        for (var raw : s.split("\n")) { var l = raw.strip(); if (!l.isEmpty()) sb.append(l.replaceAll("[ \\t]+", " ")).append('\n'); }
        return sb.toString();
    }
}
