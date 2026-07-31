package org.wyrdsekai.core.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.AppVersion;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Builds a universal update package from the current installation.
 * Package format: tar.gz containing lib/, bin/, scripts/, schema/, manifest.json.
 *
 * Used by `wyrdsekai update --publish` to create a distributable package
 * from the running installation that mesh peers can pull.
 */
public final class UpdatePackageBuilder {

    private static final Logger log = LoggerFactory.getLogger(UpdatePackageBuilder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final Path installDir; // ~/.wyrdsekai/

    public UpdatePackageBuilder(Path installDir) {
        this.installDir = installDir;
    }

    /**
     * Build a package from the current installation.
     *
     * @param outputDir Directory to write the package to
     * @return The built package info (path, sha256, size, manifest)
     */
    public PackageBuildResult build(Path outputDir) throws IOException {
        var appVer = AppVersion.get();
        var packageName = "wyrdsekai-" + appVer.version() + ".tar.gz";
        var packagePath = outputDir.resolve(packageName);
        Files.createDirectories(outputDir);

        log.info("[Update] Building package v{} -> {}", appVer.version(), packagePath);

        // Collect files to include
        var filesToInclude = new ArrayList<PackageEntry>();

        // lib/ — JARs
        var libDir = installDir.resolve("lib");
        if (Files.isDirectory(libDir)) {
            try (var stream = Files.walk(libDir)) {
                stream.filter(Files::isRegularFile)
                    .forEach(p -> filesToInclude.add(new PackageEntry(
                        "lib/" + libDir.relativize(p), p)));
            }
        }

        // bin/ — startup script
        var binDir = installDir.resolve("bin");
        if (Files.isDirectory(binDir)) {
            try (var stream = Files.walk(binDir)) {
                stream.filter(Files::isRegularFile)
                    .forEach(p -> filesToInclude.add(new PackageEntry(
                        "bin/" + binDir.relativize(p), p)));
            }
        }

        // scripts/ — room scripts + i18n
        var scriptsDir = findScriptsDir();
        if (scriptsDir != null && Files.isDirectory(scriptsDir)) {
            try (var stream = Files.walk(scriptsDir)) {
                stream.filter(Files::isRegularFile)
                    .forEach(p -> filesToInclude.add(new PackageEntry(
                        "scripts/" + scriptsDir.relativize(p), p)));
            }
        }

        // Write tar.gz
        long totalSize = writeTarGz(packagePath, filesToInclude);

        // Calculate SHA-256
        var sha256 = sha256(packagePath);

        // Build manifest
        var manifest = new ReleaseManifest(
            appVer.version(), appVer.wireProtocol(), appVer.buildHash(),
            appVer.buildTimestamp(), null,
            Map.of("universal", new ReleaseManifest.PackageInfo(
                packagePath.getFileName().toString(), sha256, totalSize)),
            null, false, null);

        // Write manifest alongside package
        var manifestPath = outputDir.resolve("manifest.json");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);

        log.info("[Update] Package built: {} ({} files, {} bytes, sha256={})",
            packageName, filesToInclude.size(), totalSize, sha256.substring(0, 12) + "...");

        return new PackageBuildResult(packagePath, manifestPath, manifest, sha256, totalSize);
    }

    public record PackageBuildResult(
        Path packagePath,
        Path manifestPath,
        ReleaseManifest manifest,
        String sha256,
        long size
    ) {}

    private record PackageEntry(String archivePath, Path sourcePath) {}

    /**
     * Write files as a tar.gz archive.
     * Uses a simple tar implementation (512-byte header blocks).
     */
    private long writeTarGz(Path output, List<PackageEntry> entries) throws IOException {
        try (var fos = new FileOutputStream(output.toFile());
             var gzos = new GZIPOutputStream(fos);
             var bos = new BufferedOutputStream(gzos)) {

            for (var entry : entries) {
                var data = Files.readAllBytes(entry.sourcePath());
                writeTarEntry(bos, entry.archivePath(), data);
            }

            // End-of-archive marker (two 512-byte zero blocks)
            bos.write(new byte[1024]);
            bos.flush();
        }
        return Files.size(output);
    }

    /**
     * Write a single tar entry (POSIX ustar format).
     */
    private void writeTarEntry(OutputStream out, String name, byte[] data) throws IOException {
        var header = new byte[512];

        // Name (0-99)
        var nameBytes = name.getBytes();
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 100));

        // Mode (100-107) — 0644
        System.arraycopy("0000644\0".getBytes(), 0, header, 100, 8);

        // UID/GID (108-123) — 0
        System.arraycopy("0000000\0".getBytes(), 0, header, 108, 8);
        System.arraycopy("0000000\0".getBytes(), 0, header, 116, 8);

        // Size (124-135) — octal
        var sizeStr = String.format("%011o\0", data.length);
        System.arraycopy(sizeStr.getBytes(), 0, header, 124, 12);

        // Mtime (136-147) — current time
        var mtime = String.format("%011o\0", System.currentTimeMillis() / 1000);
        System.arraycopy(mtime.getBytes(), 0, header, 136, 12);

        // Checksum placeholder (148-155) — spaces
        Arrays.fill(header, 148, 156, (byte) ' ');

        // Type (156) — regular file
        header[156] = '0';

        // Magic (257-262) — "ustar\0"
        System.arraycopy("ustar\0".getBytes(), 0, header, 257, 6);

        // Version (263-264) — "00"
        System.arraycopy("00".getBytes(), 0, header, 263, 2);

        // Calculate and write checksum
        long checksum = 0;
        for (byte b : header) checksum += (b & 0xFF);
        var csStr = String.format("%06o\0 ", checksum);
        System.arraycopy(csStr.getBytes(), 0, header, 148, 8);

        out.write(header);
        out.write(data);

        // Pad to 512-byte boundary
        int remainder = data.length % 512;
        if (remainder > 0) {
            out.write(new byte[512 - remainder]);
        }
    }

    private String sha256(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var is = new FileInputStream(file.toFile())) {
                var buf = new byte[8192];
                int read;
                while ((read = is.read(buf)) != -1) {
                    digest.update(buf, 0, read);
                }
            }
            var hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IOException("SHA-256 failed", e);
        }
    }

    private Path findScriptsDir() {
        // Only check within the install directory — never walk the source tree
        var candidate = installDir.resolve("scripts");
        if (Files.isDirectory(candidate)) return candidate;
        return null;
    }
}
