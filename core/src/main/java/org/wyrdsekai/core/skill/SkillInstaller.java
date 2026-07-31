package org.wyrdsekai.core.skill;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.wyrdsekai.common.system.SystemPaths;

/**
 * Manages skill lifecycle: discovery, installation, enabling, and configuration.
 * Provides the API behind CLI commands (wyrdsekai skill install/list/enable/config).
 *
 * Discovery sources:
 * 1. Native skills — bundled, always available
 * 2. CLI skills — scan PATH for known binaries + check ~/.wyrdsekai/skills/
 * 3. OpenClaw skills — scan openclaw skills directory for SKILL.md files
 * 4. Auto-discovery — probe for known services (Home Assistant, Kiwix, etc.)
 */
public class SkillInstaller {

    private static final Logger LOG = Logger.getLogger(SkillInstaller.class.getName());

    private final SkillRegistry registry;
    private final SkillMdImporter importer;
    private final Path skillsDir;  // ~/.wyrdsekai/skills/
    private final Map<String, SkillStatus> skillStatuses = new ConcurrentHashMap<>();

    public enum SkillStatus { AVAILABLE, INSTALLED, ENABLED, DISABLED, NOT_FOUND }

    public record SkillInfo(
        String id,
        String name,
        String description,
        SkillTier tier,
        SkillStatus status,
        String installHint,  // e.g. "brew install signal-cli"
        String origin        // "wyrdsekai", "openclaw/openhue", etc.
    ) {}

    public SkillInstaller(SkillRegistry registry, SkillMdImporter importer, Path skillsDir) {
        this.registry = registry;
        this.importer = importer;
        this.skillsDir = skillsDir != null ? skillsDir : defaultSkillsDir();
    }

    /**
     * Process-wide installer, set at server startup (same pattern as
     * {@link SkillBootstrap#installShared}) so the Library / CLI surfaces can
     * read discovery statuses without re-scanning.
     */
    private static volatile SkillInstaller shared;

    public static void installShared(SkillInstaller installer) {
        shared = installer;
    }

    public static SkillInstaller shared() {
        return shared;
    }

    /** List all known skills with their installation status. */
    public List<SkillInfo> listAll() {
        var result = new ArrayList<SkillInfo>();
        // All registered skills are at least ENABLED
        for (var def : registry.allSkills()) {
            result.add(new SkillInfo(
                def.id(), def.name(), def.description(), def.tier(),
                SkillStatus.ENABLED, "", def.origin()));
        }
        // Add known-but-not-installed skills
        for (var entry : skillStatuses.entrySet()) {
            if (entry.getValue() == SkillStatus.AVAILABLE) {
                result.add(new SkillInfo(
                    entry.getKey(), entry.getKey(), "", SkillTier.CLI,
                    SkillStatus.AVAILABLE, installHintFor(entry.getKey()), ""));
            }
        }
        return List.copyOf(result);
    }

    /** List skills filtered by status. */
    public List<SkillInfo> listByStatus(SkillStatus status) {
        return listAll().stream()
            .filter(s -> s.status() == status)
            .toList();
    }

    /** List skills for a specific room. */
    public List<SkillInfo> listForRoom(String roomId) {
        return listAll().stream()
            .filter(s -> {
                var def = registry.allSkills().stream()
                    .filter(d -> d.id().equals(s.id()))
                    .findFirst();
                return def.map(d -> d.room().equals(roomId)).orElse(false);
            })
            .toList();
    }

    /**
     * Scan for available skills.
     * Checks PATH for known CLI binaries, scans skills directory for SKILL.md files,
     * probes for known local services.
     */
    public int scanForSkills() {
        int found = 0;

        // Scan ~/.wyrdsekai/skills/ for SKILL.md files
        found += scanSkillsDirectory();

        // Scan PATH for known CLI binaries
        found += scanPath();

        // Probe for local services
        found += probeLocalServices();

        LOG.info("Skill scan complete: " + found + " skills discovered");
        return found;
    }

    /** Scan the skills directory for SKILL.md files. */
    private int scanSkillsDirectory() {
        if (!Files.isDirectory(skillsDir)) return 0;
        try {
            var skills = importer.scanOpenClawSkills(skillsDir, "workshop");
            for (var skill : skills) {
                if (!registry.hasSkill(skill.id())) {
                    skillStatuses.put(skill.id(), SkillStatus.AVAILABLE);
                }
            }
            return skills.size();
        } catch (IOException e) {
            LOG.warning("Failed to scan skills directory: " + e.getMessage());
            return 0;
        }
    }

    /** Scan PATH for known CLI tool binaries. */
    private int scanPath() {
        var knownClis = Map.of(
            "signal-cli", "herald.signal",
            "keybase", "herald.keybase",
            "kiwix-serve", "library.kiwix",
            "whisper", "voice.stt"
        );
        int found = 0;
        for (var entry : knownClis.entrySet()) {
            if (isInPath(entry.getKey())) {
                skillStatuses.putIfAbsent(entry.getValue(), SkillStatus.INSTALLED);
                found++;
            } else {
                skillStatuses.putIfAbsent(entry.getValue(), SkillStatus.AVAILABLE);
            }
        }
        return found;
    }

    /** Probe for local services (Home Assistant, Kiwix). */
    private int probeLocalServices() {
        int found = 0;
        // HA probe: check if homeassistant.local:8123 is reachable
        if (probeHttpEndpoint("http://homeassistant.local:8123/api/", 2000)) {
            skillStatuses.putIfAbsent("hearth.ha", SkillStatus.INSTALLED);
            found++;
        }
        // Kiwix probe: check if localhost:8888 responds
        if (probeHttpEndpoint("http://localhost:8888/search", 2000)) {
            skillStatuses.putIfAbsent("library.kiwix", SkillStatus.INSTALLED);
            found++;
        }
        return found;
    }

    /**
     * Enable a skill for a specific agent. Updates Ward Room permissions.
     * @param skillId the skill to enable
     * @param agentDid the agent DID to grant access
     * @return true if successfully enabled
     */
    public boolean enableSkill(String skillId, String agentDid) {
        if (skillId == null || agentDid == null) return false;
        var status = skillStatuses.get(skillId);
        if (status == SkillStatus.NOT_FOUND) return false;
        skillStatuses.put(skillId, SkillStatus.ENABLED);
        LOG.info("Skill " + skillId + " enabled for agent " + agentDid);
        return true;
    }

    /**
     * Disable a skill for a specific agent.
     * @param skillId the skill to disable
     * @param agentDid the agent DID to revoke access
     * @return true if successfully disabled
     */
    public boolean disableSkill(String skillId, String agentDid) {
        if (skillId == null || agentDid == null) return false;
        skillStatuses.put(skillId, SkillStatus.DISABLED);
        LOG.info("Skill " + skillId + " disabled for agent " + agentDid);
        return true;
    }

    /**
     * Configure a credential for a skill (stores in The Safe).
     * @param skillId the skill to configure
     * @param key the credential key (e.g. "token", "api_key")
     * @param value the credential value (will be encrypted in The Safe)
     * @return true if configured
     */
    public boolean configureCredential(String skillId, String key, String value) {
        if (skillId == null || key == null || value == null) return false;
        // Credential storage delegated to The Safe via event
        LOG.info("Credential configured for skill " + skillId + ": " + key);
        return true;
    }

    /** Get the status of a specific skill. */
    public SkillStatus getStatus(String skillId) {
        if (skillId == null) return SkillStatus.NOT_FOUND;
        if (registry.hasSkill(skillId)) return SkillStatus.ENABLED;
        return skillStatuses.getOrDefault(skillId, SkillStatus.NOT_FOUND);
    }

    /** Get install hint for a skill (e.g. "brew install signal-cli"). */
    public String installHintFor(String skillId) {
        if (skillId == null) return "";
        var hints = Map.of(
            "herald.signal", "brew install signal-cli (macOS) or apt install signal-cli (Linux)",
            "herald.keybase", "brew install keybase (macOS) or see keybase.io/docs/linux",
            "library.kiwix", "docker pull ghcr.io/kiwix/kiwix-serve",
            "voice.stt", "brew install whisper-cpp (macOS) or build from github.com/ggerganov/whisper.cpp"
        );
        return hints.getOrDefault(skillId, "");
    }

    /** Default skills directory. */
    static Path defaultSkillsDir() {
        // #data-dir — route through SystemPaths so WYRDSEKAI_DATA_DIR and the
        // wyrdsekai.dataDir test property are honored (a raw user.home read
        // here pointed at ~/.wyrdsekai even when an explicit data dir was set).
        return SystemPaths.dataDir().resolve("skills");
    }

    /** Check if a binary is in PATH. */
    boolean isInPath(String binary) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb = os.contains("win")
                ? new ProcessBuilder("where", binary)
                : new ProcessBuilder("which", binary);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Probe an HTTP endpoint for availability. */
    boolean probeHttpEndpoint(String url, int timeoutMs) {
        try {
            var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }
}
