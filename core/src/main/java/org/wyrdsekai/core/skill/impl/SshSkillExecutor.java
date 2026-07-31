package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH status check skill for the Bridge room.
 * Reports whether sshd is configured on this node. Read-only status only.
 */
public class SshSkillExecutor implements SkillExecutor {

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    private final int port;

    public SshSkillExecutor() {
        this(22);
    }

    public SshSkillExecutor(int port) {
        this.port = port;

        define(new SkillDefinition("bridge.ssh.status",
            "SSH Status", "Check whether sshd is configured on this node",
            "bridge", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(),
            SkillAuth.NONE, SkillLocality.LOCAL, true));
    }

    private void define(SkillDefinition skill) {
        skills.put(skill.id(), skill);
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        long start = System.currentTimeMillis();

        if (!"bridge.ssh.status".equals(skillId)) {
            return SkillResult.unavailable(skillId);
        }

        boolean running = isSshdRunning();
        long elapsed = System.currentTimeMillis() - start;

        if (running) {
            return SkillResult.ok(I18n.get("skill.ssh.started", port),
                Map.of("running", true, "port", port),
                elapsed, SkillTier.NATIVE, skillId);
        } else {
            return SkillResult.ok(I18n.get("skill.ssh.stopped"),
                Map.of("running", false, "port", port),
                elapsed, SkillTier.NATIVE, skillId);
        }
    }

    private boolean isSshdRunning() {
        try {
            var socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<SkillDefinition> availableSkills() { return List.copyOf(skills.values()); }

    @Override
    public boolean supports(String skillId) { return skills.containsKey(skillId); }

    @Override
    public SkillTier tier() { return SkillTier.NATIVE; }
}
