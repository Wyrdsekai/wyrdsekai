package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Keybase CLI wrapper skill executor.
 * Provides chat (inbox, send, teams) and KBFS (read, write, ls) via the keybase CLI.
 * Relies on system keybase login — no additional auth required.
 */
public class KeybaseSkillExecutor implements SkillExecutor {

    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    private final String keybaseBinary;

    /**
     * @param keybaseBinary Path to keybase binary (default: "keybase")
     */
    public KeybaseSkillExecutor(String keybaseBinary) {
        this.keybaseBinary = keybaseBinary != null ? keybaseBinary : "keybase";

        define(new SkillDefinition("herald.keybase.inbox", "Keybase Inbox",
            "Read recent Keybase chat messages", "herald", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.optional("channel", "string", "Channel or conversation name"),
                     SkillParam.optional("count", "number", "Number of messages")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));

        define(new SkillDefinition("herald.keybase.send", "Keybase Send",
            "Send a Keybase chat message", "herald", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("channel", "string", "Channel or username"),
                     SkillParam.required("message", "string", "Message text"),
                     SkillParam.optional("team", "string", "Team name (for team channels)")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));

        define(new SkillDefinition("herald.keybase.teams", "Keybase Teams",
            "List Keybase teams", "herald", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0", List.of(),
            SkillAuth.NONE, SkillLocality.LOCAL, true));

        define(new SkillDefinition("vault.kbfs.read", "KBFS Read",
            "Read a file from Keybase filesystem", "vault", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("path", "string", "KBFS path (e.g., /private/user/file.txt)")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));

        define(new SkillDefinition("vault.kbfs.write", "KBFS Write",
            "Write a file to Keybase filesystem", "vault", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("path", "string", "KBFS path"),
                     SkillParam.required("content", "string", "File content")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));

        define(new SkillDefinition("vault.kbfs.ls", "KBFS List",
            "List files in a KBFS directory", "vault", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("path", "string", "KBFS directory path")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));
    }

    public KeybaseSkillExecutor() {
        this(null);
    }

    private void define(SkillDefinition skill) { skills.put(skill.id(), skill); }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        long start = System.currentTimeMillis();

        return switch (skillId) {
            case "herald.keybase.inbox" -> executeInbox(params, start, context.timeoutMs(), skillId);
            case "herald.keybase.send" -> executeSend(params, start, context.timeoutMs(), skillId);
            case "herald.keybase.teams" -> executeTeams(start, context.timeoutMs(), skillId);
            case "vault.kbfs.read" -> executeKbfsRead(params, start, context.timeoutMs(), skillId);
            case "vault.kbfs.write" -> executeKbfsWrite(params, start, context.timeoutMs(), skillId);
            case "vault.kbfs.ls" -> executeKbfsLs(params, start, context.timeoutMs(), skillId);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeInbox(Map<String, Object> params, long start, long timeoutMs,
                                      String skillId) {
        String channel = param(params, "channel", null);
        int count = intParam(params, "count", 10);

        String apiJson;
        if (channel != null) {
            apiJson = "{\"method\":\"read\",\"params\":{\"options\":" +
                "{\"channel\":{\"name\":\"" + esc(channel) + "\"},\"pagination\":{\"num\":" + count + "}}}}";
        } else {
            apiJson = "{\"method\":\"list\"}";
        }
        List<String> cmd = List.of(keybaseBinary, "chat", "api", "-m", apiJson);

        return runCommand(cmd, start, timeoutMs, skillId, output ->
            SkillResult.ok(output, Map.of("messages", output),
                System.currentTimeMillis() - start, SkillTier.NATIVE, skillId));
    }

    private SkillResult executeSend(Map<String, Object> params, long start, long timeoutMs,
                                     String skillId) {
        String channel = requireParam(params, "channel");
        String message = requireParam(params, "message");
        if (channel == null || message == null)
            return SkillResult.error(I18n.get("skill.param_required", "channel, message"),
                0, SkillTier.NATIVE, skillId);

        String team = param(params, "team", null);
        String channelJson;
        if (team != null) {
            channelJson = "{\"name\":\"" + esc(team) + "\",\"members_type\":\"team\"," +
                "\"topic_name\":\"" + esc(channel) + "\"}";
        } else {
            channelJson = "{\"name\":\"" + esc(channel) + "\"}";
        }
        String apiJson = "{\"method\":\"send\",\"params\":{\"options\":" +
            "{\"channel\":" + channelJson + ",\"message\":{\"body\":\"" + esc(message) + "\"}}}}";
        List<String> cmd = List.of(keybaseBinary, "chat", "api", "-m", apiJson);

        return runCommand(cmd, start, timeoutMs, skillId, output ->
            SkillResult.ok(I18n.get("skill.keybase.sent", channel),
                Map.of("output", output),
                System.currentTimeMillis() - start, SkillTier.NATIVE, skillId));
    }

    private SkillResult executeTeams(long start, long timeoutMs, String skillId) {
        List<String> cmd = List.of(keybaseBinary, "team", "list-memberships", "--json");
        return runCommand(cmd, start, timeoutMs, skillId, output ->
            SkillResult.ok(output, Map.of("teams", output),
                System.currentTimeMillis() - start, SkillTier.NATIVE, skillId));
    }

    private SkillResult executeKbfsRead(Map<String, Object> params, long start, long timeoutMs,
                                         String skillId) {
        String path = requireParam(params, "path");
        if (path == null)
            return SkillResult.error(I18n.get("skill.param_required", "path"),
                0, SkillTier.NATIVE, skillId);

        List<String> cmd = List.of(keybaseBinary, "fs", "read", path);
        return runCommand(cmd, start, timeoutMs, skillId, output ->
            SkillResult.ok(I18n.get("skill.keybase.kbfs_read", path),
                Map.of("content", output),
                System.currentTimeMillis() - start, SkillTier.NATIVE, skillId));
    }

    private SkillResult executeKbfsWrite(Map<String, Object> params, long start, long timeoutMs,
                                          String skillId) {
        String path = requireParam(params, "path");
        String content = requireParam(params, "content");
        if (path == null || content == null)
            return SkillResult.error(I18n.get("skill.param_required", "path, content"),
                0, SkillTier.NATIVE, skillId);

        try {
            ProcessBuilder pb = new ProcessBuilder(keybaseBinary, "fs", "write", path);
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            proc.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
            proc.getOutputStream().close();
            boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;
            if (!finished) {
                proc.destroyForcibly();
                return SkillResult.error(I18n.get("skill.error.timeout", timeoutMs),
                    elapsed, SkillTier.NATIVE, skillId);
            }
            if (proc.exitValue() != 0) {
                String stderr = readCapped(proc.getErrorStream());
                return SkillResult.error(I18n.get("skill.cli.exit_error", proc.exitValue(), stderr),
                    elapsed, SkillTier.NATIVE, skillId);
            }
            return SkillResult.ok("Written to " + path, Map.of("path", path),
                elapsed, SkillTier.NATIVE, skillId);
        } catch (IOException | InterruptedException e) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(I18n.get("skill.cli.failed", e.getMessage()),
                elapsed, SkillTier.NATIVE, skillId);
        }
    }

    private SkillResult executeKbfsLs(Map<String, Object> params, long start, long timeoutMs,
                                       String skillId) {
        String path = requireParam(params, "path");
        if (path == null)
            return SkillResult.error(I18n.get("skill.param_required", "path"),
                0, SkillTier.NATIVE, skillId);

        List<String> cmd = List.of(keybaseBinary, "fs", "ls", path);
        return runCommand(cmd, start, timeoutMs, skillId, output ->
            SkillResult.ok(output, Map.of("listing", output),
                System.currentTimeMillis() - start, SkillTier.NATIVE, skillId));
    }

    private SkillResult runCommand(List<String> cmd, long start, long timeoutMs,
                                    String skillId, ResultMapper mapper) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                long elapsed = System.currentTimeMillis() - start;
                return SkillResult.error(I18n.get("skill.error.timeout", timeoutMs),
                    elapsed, SkillTier.NATIVE, skillId);
            }
            String output = readCapped(proc.getInputStream());
            long elapsed = System.currentTimeMillis() - start;
            if (proc.exitValue() != 0) {
                return SkillResult.error(I18n.get("skill.cli.exit_error", proc.exitValue(), output),
                    elapsed, SkillTier.NATIVE, skillId);
            }
            return mapper.map(output);
        } catch (IOException | InterruptedException e) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(I18n.get("skill.cli.failed", e.getMessage()),
                elapsed, SkillTier.NATIVE, skillId);
        }
    }

    private String readCapped(InputStream is) throws IOException {
        byte[] buf = is.readNBytes(MAX_OUTPUT_BYTES);
        return new String(buf, StandardCharsets.UTF_8);
    }

    private String param(Map<String, Object> p, String k, String d) {
        Object v = p != null ? p.get(k) : null; return v != null ? String.valueOf(v) : d;
    }

    private String requireParam(Map<String, Object> params, String key) {
        Object v = params != null ? params.get(key) : null;
        return v != null ? String.valueOf(v) : null;
    }

    private int intParam(Map<String, Object> p, String k, int d) {
        Object v = p != null ? p.get(k) : null;
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { /* */ }
        }
        return d;
    }

    private String esc(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    @Override public List<SkillDefinition> availableSkills() { return List.copyOf(skills.values()); }
    @Override public boolean supports(String skillId) { return skills.containsKey(skillId); }
    @Override public SkillTier tier() { return SkillTier.NATIVE; }

    @FunctionalInterface
    private interface ResultMapper { SkillResult map(String output); }
}
