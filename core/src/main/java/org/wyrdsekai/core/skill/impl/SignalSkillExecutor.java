package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Signal CLI wrapper skill executor.
 * Uses signal-cli via ProcessBuilder for inbox reading, sending, group listing,
 * and contact listing. Requires signal-cli installed and a registered phone number.
 */
public class SignalSkillExecutor implements SkillExecutor {

    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final SkillAuth AUTH = SkillAuth.localBridge("signal_account");

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    private final String signalCliBinary;
    private final String phoneNumber;

    public SignalSkillExecutor(String signalCliBinary, String phoneNumber) {
        this.signalCliBinary = signalCliBinary != null ? signalCliBinary : "signal-cli";
        this.phoneNumber = phoneNumber;

        define(new SkillDefinition("herald.signal.inbox", "Signal Inbox",
            "Receive recent Signal messages", "herald", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.optional("timeout", "number", "Receive timeout in seconds")),
            AUTH, SkillLocality.LOCAL, true));

        define(new SkillDefinition("herald.signal.send", "Signal Send",
            "Send a Signal message", "herald", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("recipient", "string", "Phone number or group ID"),
                     SkillParam.required("message", "string", "Message text"),
                     SkillParam.optional("group", "boolean", "Send to group")),
            AUTH, SkillLocality.LOCAL, true));

        define(new SkillDefinition("herald.signal.groups", "Signal Groups",
            "List Signal groups", "herald", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0", List.of(),
            AUTH, SkillLocality.LOCAL, true));

        define(new SkillDefinition("herald.signal.contacts", "Signal Contacts",
            "List Signal contacts", "herald", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0", List.of(),
            AUTH, SkillLocality.LOCAL, true));
    }

    public SignalSkillExecutor() { this(null, null); }

    private void define(SkillDefinition skill) { skills.put(skill.id(), skill); }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        if (phoneNumber == null || phoneNumber.isBlank())
            return SkillResult.error(I18n.get("skill.not_configured", "Signal phone number"),
                0, SkillTier.NATIVE, skillId);

        long start = System.currentTimeMillis();
        return switch (skillId) {
            case "herald.signal.inbox" ->
                executeInbox(params, start, context.timeoutMs(), skillId);
            case "herald.signal.send" ->
                executeSend(params, start, context.timeoutMs(), skillId);
            case "herald.signal.groups" ->
                executeGroups(start, context.timeoutMs(), skillId);
            case "herald.signal.contacts" ->
                executeContacts(start, context.timeoutMs(), skillId);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeInbox(Map<String, Object> params, long start,
                                      long timeoutMs, String skillId) {
        int timeout = intParam(params, "timeout", 1);
        List<String> cmd = List.of(signalCliBinary, "-a", phoneNumber,
            "receive", "--json", "-t", String.valueOf(timeout));
        return runCommand(cmd, start, timeoutMs, skillId, output -> {
            int count = countLines(output);
            return SkillResult.ok(I18n.get("skill.signal.inbox", count),
                Map.of("messages", output, "count", count),
                System.currentTimeMillis() - start, SkillTier.NATIVE, skillId);
        });
    }

    private SkillResult executeSend(Map<String, Object> params, long start,
                                     long timeoutMs, String skillId) {
        String recipient = requireParam(params, "recipient");
        String message = requireParam(params, "message");
        if (recipient == null || message == null)
            return SkillResult.error(I18n.get("skill.param_required", "recipient, message"),
                0, SkillTier.NATIVE, skillId);

        boolean isGroup = "true".equalsIgnoreCase(param(params, "group", "false"));
        List<String> cmd = new ArrayList<>();
        cmd.add(signalCliBinary); cmd.add("-a"); cmd.add(phoneNumber);
        cmd.add("send"); cmd.add("-m"); cmd.add(message);
        if (isGroup) cmd.add("-g");
        cmd.add(recipient);

        return runCommand(cmd, start, timeoutMs, skillId, output ->
            SkillResult.ok(I18n.get("skill.signal.sent", recipient),
                Map.of("output", output),
                System.currentTimeMillis() - start, SkillTier.NATIVE, skillId));
    }

    private SkillResult executeGroups(long start, long timeoutMs, String skillId) {
        List<String> cmd = List.of(signalCliBinary, "-a", phoneNumber, "listGroups", "--json");
        return runCommand(cmd, start, timeoutMs, skillId, output ->
            SkillResult.ok(output, Map.of("groups", output),
                System.currentTimeMillis() - start, SkillTier.NATIVE, skillId));
    }

    private SkillResult executeContacts(long start, long timeoutMs, String skillId) {
        List<String> cmd = List.of(signalCliBinary, "-a", phoneNumber, "listContacts", "--json");
        return runCommand(cmd, start, timeoutMs, skillId, output ->
            SkillResult.ok(output, Map.of("contacts", output),
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
            if (proc.exitValue() != 0)
                return SkillResult.error(I18n.get("skill.cli.exit_error", proc.exitValue(), output),
                    elapsed, SkillTier.NATIVE, skillId);
            return mapper.map(output);
        } catch (IOException | InterruptedException e) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(I18n.get("skill.cli.failed", e.getMessage()),
                elapsed, SkillTier.NATIVE, skillId);
        }
    }

    private String readCapped(InputStream is) throws IOException {
        return new String(is.readNBytes(MAX_OUTPUT_BYTES), StandardCharsets.UTF_8);
    }

    private String param(Map<String, Object> p, String k, String d) {
        Object v = p != null ? p.get(k) : null; return v != null ? String.valueOf(v) : d;
    }
    private String requireParam(Map<String, Object> p, String k) {
        Object v = p != null ? p.get(k) : null; return v != null ? String.valueOf(v) : null;
    }
    private int intParam(Map<String, Object> p, String k, int d) {
        Object v = p != null ? p.get(k) : null;
        if (v instanceof Number n) return n.intValue();
        if (v != null) { try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { /* */ } }
        return d;
    }
    private int countLines(String s) {
        if (s == null || s.isBlank()) return 0;
        return s.split("\n").length;
    }

    @Override public List<SkillDefinition> availableSkills() { return List.copyOf(skills.values()); }
    @Override public boolean supports(String skillId) { return skills.containsKey(skillId); }
    @Override public SkillTier tier() { return SkillTier.NATIVE; }

    @FunctionalInterface
    private interface ResultMapper { SkillResult map(String output); }
}
