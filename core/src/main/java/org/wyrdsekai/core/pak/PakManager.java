package org.wyrdsekai.core.pak;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Manages .wyrdpak extension packages: install, list, remove, create.
 *
 * <p>Installed extensions live in {@code ~/.wyrdsekai/extensions/<name>/}.
 * Each directory contains the extracted contents of a .wyrdpak ZIP plus the manifest.</p>
 */
public final class PakManager {

    private static final Logger log = LoggerFactory.getLogger(PakManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String MANIFEST_FILE = "manifest.json";

    private final Path extensionsDir;

    public PakManager(Path extensionsDir) {
        this.extensionsDir = extensionsDir;
    }

    /** Default extensions directory: ~/.wyrdsekai/extensions/ */
    public static PakManager defaultManager() {
        return new PakManager(Path.of(
            System.getProperty("user.home"), ".wyrdsekai", "extensions"));
    }

    /**
     * Install a .wyrdpak file. Extracts contents to extensions/<name>/.
     *
     * @param pakFile path to the .wyrdpak file
     * @return the installed manifest
     * @throws IOException on read/write errors
     * @throws IllegalArgumentException if manifest is missing or invalid
     */
    public PakManifest install(Path pakFile) throws IOException {
        // First pass: read manifest from ZIP
        PakManifest manifest = readManifestFromZip(pakFile);

        var targetDir = extensionsDir.resolve(manifest.name());
        if (Files.exists(targetDir)) {
            log.info("Removing existing installation of '{}'", manifest.name());
            deleteDirectory(targetDir);
        }

        Files.createDirectories(targetDir);

        // Second pass: extract all files
        try (var zis = new ZipInputStream(Files.newInputStream(pakFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                var target = targetDir.resolve(entry.getName()).normalize();
                // Security: prevent zip-slip
                if (!target.startsWith(targetDir)) {
                    throw new IOException("Zip entry escapes target directory: " + entry.getName());
                }
                Files.createDirectories(target.getParent());
                Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        log.info("Installed extension: {}", manifest.displayString());
        return manifest;
    }

    /**
     * List all installed extensions.
     *
     * @return list of manifests, sorted by name
     */
    public List<PakManifest> list() {
        var result = new ArrayList<PakManifest>();
        if (!Files.isDirectory(extensionsDir)) return result;

        try (var dirs = Files.newDirectoryStream(extensionsDir, Files::isDirectory)) {
            for (var dir : dirs) {
                var manifestPath = dir.resolve(MANIFEST_FILE);
                if (Files.exists(manifestPath)) {
                    try {
                        result.add(mapper.readValue(manifestPath.toFile(), PakManifest.class));
                    } catch (Exception e) {
                        log.warn("Invalid manifest in {}: {}", dir, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list extensions: {}", e.getMessage());
        }

        result.sort(Comparator.comparing(PakManifest::name));
        return result;
    }

    /**
     * Remove an installed extension by name.
     *
     * @param name extension name
     * @return true if removed, false if not found
     */
    public boolean remove(String name) throws IOException {
        var dir = extensionsDir.resolve(name);
        if (!Files.isDirectory(dir)) return false;
        deleteDirectory(dir);
        log.info("Removed extension: {}", name);
        return true;
    }

    /**
     * Create a .wyrdpak file from a source directory.
     * The directory must contain a manifest.json.
     *
     * @param sourceDir  directory containing extension files
     * @param outputFile path for the output .wyrdpak file
     * @return the manifest of the created package
     */
    public static PakManifest create(Path sourceDir, Path outputFile) throws IOException {
        var manifestPath = sourceDir.resolve(MANIFEST_FILE);
        if (!Files.exists(manifestPath)) {
            throw new IllegalArgumentException("Source directory must contain " + MANIFEST_FILE);
        }

        var manifest = mapper.readValue(manifestPath.toFile(), PakManifest.class);

        try (var zos = new ZipOutputStream(Files.newOutputStream(outputFile))) {
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    var entryName = sourceDir.relativize(file).toString();
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        log.info("Created package: {} → {}", manifest.displayString(), outputFile);
        return manifest;
    }

    /**
     * Get the extensions directory (for FoundationRoomLoader integration).
     */
    public Path extensionsDir() {
        return extensionsDir;
    }

    /**
     * Get all room script paths from installed extensions.
     *
     * @return list of absolute paths to room script files
     */
    public List<Path> allRoomScripts() {
        var scripts = new ArrayList<Path>();
        for (var manifest : list()) {
            var extDir = extensionsDir.resolve(manifest.name());
            for (var room : manifest.rooms()) {
                var scriptPath = extDir.resolve(room);
                if (Files.exists(scriptPath)) {
                    scripts.add(scriptPath);
                }
            }
        }
        return scripts;
    }

    /**
     * Get all i18n file paths from installed extensions.
     *
     * @return list of absolute paths to i18n JSON files
     */
    public List<Path> allI18nFiles() {
        var files = new ArrayList<Path>();
        for (var manifest : list()) {
            var extDir = extensionsDir.resolve(manifest.name());
            for (var i18n : manifest.i18n()) {
                var i18nPath = extDir.resolve(i18n);
                if (Files.exists(i18nPath)) {
                    files.add(i18nPath);
                }
            }
        }
        return files;
    }

    /**
     * Get all soul seed file paths from installed extensions.
     *
     * @return list of absolute paths to soul seed JSON files
     */
    public List<Path> allSoulSeeds() {
        var seeds = new ArrayList<Path>();
        for (var manifest : list()) {
            var extDir = extensionsDir.resolve(manifest.name());
            for (var soul : manifest.souls()) {
                var soulPath = extDir.resolve(soul);
                if (Files.exists(soulPath)) {
                    seeds.add(soulPath);
                }
            }
        }
        return seeds;
    }

    // --- Internal ---

    private PakManifest readManifestFromZip(Path pakFile) throws IOException {
        try (var zis = new ZipInputStream(Files.newInputStream(pakFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (MANIFEST_FILE.equals(entry.getName())) {
                    return mapper.readValue(zis, PakManifest.class);
                }
            }
        }
        throw new IllegalArgumentException("No manifest.json found in " + pakFile);
    }

    private static void deleteDirectory(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc)
                    throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
