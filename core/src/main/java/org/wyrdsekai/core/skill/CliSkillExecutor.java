package org.wyrdsekai.core.skill;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.coding.EgressGate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Executes CLI skills via fork+exec. No shell expansion — uses ProcessBuilder
 * with explicit arguments. Credentials injected as ephemeral environment variables.
 *
 * Safety:
 * - No shell expansion (ProcessBuilder, not Runtime.exec(String))
 * - Timeout kills process
 * - stderr logged but not returned to agent
 * - Credentials ONLY as env vars, NEVER as CLI arguments
 * - stdout capped at 64KB
 */
public class CliSkillExecutor implements SkillExecutor {

    private static final int MAX_OUTPUT_BYTES = 64 * 1024; // 64KB

    private final Map<String, CliSkillBinding> bindings = new ConcurrentHashMap<>();

    /** Register a CLI skill with its binary and argument mapping. */
    public void registerBinding(CliSkillBinding binding) {
        bindings.put(binding.skillId(), binding);
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        CliSkillBinding binding = bindings.get(skillId);
        if (binding == null) {
            return SkillResult.unavailable(skillId);
        }

        // Build command line
        List<String> command = new ArrayList<>();
        command.add(binding.binary());
        command.addAll(binding.buildArgs(params));

        long start = System.currentTimeMillis();

        try {
            // 0.1-class hardening: route through the shared egress gate so the
            // skill subprocess gets a SCRUBBED env (no SSH_AUTH_SOCK, no ambient
            // keys) — only the allowlist plus this skill's own credentials and
            // declared env vars. Same seam as the coding backends.
            var skillEnv = new HashMap<String, String>();
            if (context.credentials() != null) {
                skillEnv.putAll(context.credentials());
            }
            if (binding.envVars() != null) {
                skillEnv.putAll(binding.envVars());
            }
            ProcessBuilder pb = EgressGate.gatedProcessBuilder(command, skillEnv);
            pb.redirectErrorStream(false);

            Process process = pb.start();
            boolean finished = process.waitFor(context.timeoutMs(), TimeUnit.MILLISECONDS);

            if (!finished) {
                process.destroyForcibly();
                long elapsed = System.currentTimeMillis() - start;
                return SkillResult.error(I18n.get("skill.error.timeout", context.timeoutMs()),
                    elapsed, SkillTier.CLI, skillId);
            }

            // Read stdout (capped)
            String stdout = readCapped(process.getInputStream(), MAX_OUTPUT_BYTES);
            long elapsed = System.currentTimeMillis() - start;

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return SkillResult.ok(stdout, Map.of("exitCode", exitCode),
                    elapsed, SkillTier.CLI, skillId);
            } else {
                return SkillResult.error(I18n.get("skill.cli.exit_error", exitCode, stdout),
                    elapsed, SkillTier.CLI, skillId);
            }
        } catch (IOException | InterruptedException e) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(I18n.get("skill.cli.failed", e.getMessage()),
                elapsed, SkillTier.CLI, skillId);
        }
    }

    @Override
    public List<SkillDefinition> availableSkills() {
        return bindings.values().stream()
            .map(CliSkillBinding::definition)
            .toList();
    }

    @Override
    public boolean supports(String skillId) {
        return bindings.containsKey(skillId);
    }

    @Override
    public SkillTier tier() {
        return SkillTier.CLI;
    }

    /** Read from an InputStream, capped at maxBytes. */
    private static String readCapped(InputStream is, int maxBytes) throws IOException {
        byte[] buf = is.readNBytes(maxBytes);
        return new String(buf, StandardCharsets.UTF_8);
    }

    /**
     * Binding between a skill ID and a CLI binary.
     *
     * @param skillId    Skill ID this binding serves
     * @param binary     Path to CLI binary
     * @param definition Skill definition
     * @param argMapper  Maps skill params to CLI arguments
     * @param envVars    Additional environment variables
     */
    public record CliSkillBinding(
        String skillId,
        String binary,
        SkillDefinition definition,
        CliArgMapper argMapper,
        Map<String, String> envVars
    ) {
        public CliSkillBinding {
            if (skillId == null) throw new IllegalArgumentException("Skill ID required");
            if (binary == null) throw new IllegalArgumentException("Binary path required");
            if (envVars == null) envVars = Map.of();
        }

        /** Build CLI arguments from skill params. */
        public List<String> buildArgs(Map<String, Object> params) {
            if (argMapper != null) {
                return argMapper.mapArgs(params);
            }
            // Default: pass params as --key value pairs
            List<String> args = new ArrayList<>();
            if (params != null) {
                for (var entry : params.entrySet()) {
                    args.add("--" + entry.getKey());
                    args.add(String.valueOf(entry.getValue()));
                }
            }
            return args;
        }
    }

    /** Maps skill parameters to CLI arguments. */
    @FunctionalInterface
    public interface CliArgMapper {
        List<String> mapArgs(Map<String, Object> params);
    }
}
