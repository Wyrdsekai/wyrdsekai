package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Email skills via Jakarta Mail (IMAP for reading, SMTP for sending).
 * Credentials from The Safe (key: "email_credentials"),
 * format: "user:pass@host:imap_port:smtp_port".
 */
public class EmailSkillExecutor implements SkillExecutor {

    private static final int DEFAULT_LIMIT = 10;
    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();

    public EmailSkillExecutor() {
        var auth = SkillAuth.apiKey("email_credentials");

        define(SkillDefinition.native_("herald.email.inbox",
            "Email Inbox", "List recent inbox messages",
            "herald",
            List.of(SkillParam.optional("limit", "number", "Max messages to retrieve")),
            auth));

        define(SkillDefinition.native_("herald.email.read",
            "Read Email", "Read a specific email message",
            "herald",
            List.of(SkillParam.required("messageId", "string", "Message number")),
            auth));

        define(SkillDefinition.native_("herald.email.send",
            "Send Email", "Send an email message",
            "herald",
            List.of(
                SkillParam.required("to", "string", "Recipient email"),
                SkillParam.required("subject", "string", "Email subject"),
                SkillParam.required("body", "string", "Email body")),
            auth));

        define(SkillDefinition.native_("herald.email.draft",
            "Draft Email", "Save an email draft (does not send)",
            "herald",
            List.of(
                SkillParam.required("to", "string", "Recipient email"),
                SkillParam.required("subject", "string", "Email subject"),
                SkillParam.required("body", "string", "Email body")),
            auth));
    }

    private void define(SkillDefinition skill) {
        skills.put(skill.id(), skill);
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        String creds = context.credentials().get("email_credentials");
        if (creds == null || creds.isBlank()) {
            return SkillResult.error(I18n.get("skill.not_configured", "email_credentials"),
                0, SkillTier.NATIVE, skillId);
        }

        long start = System.currentTimeMillis();

        try {
            return switch (skillId) {
                case "herald.email.inbox" -> executeInbox(params, creds, start, skillId);
                case "herald.email.read" -> executeRead(params, creds, start, skillId);
                case "herald.email.send" -> executeSend(params, creds, start, skillId, context);
                case "herald.email.draft" -> executeDraft(params, creds, start, skillId);
                default -> SkillResult.unavailable(skillId);
            };
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(I18n.get("skill.error.execution", e.getMessage()),
                elapsed, SkillTier.NATIVE, skillId);
        }
    }

    private SkillResult executeInbox(Map<String, Object> params, String creds,
                                      long start, String skillId) throws Exception {
        int limit = intParam(params, "limit", DEFAULT_LIMIT);

        try (Store store = connectStore(creds)) {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            int total = inbox.getMessageCount();
            int from = Math.max(1, total - limit + 1);
            Message[] messages = inbox.getMessages(from, total);

            List<Map<String, String>> summaries = new ArrayList<>();
            for (Message msg : messages) {
                summaries.add(Map.of(
                    "number", String.valueOf(msg.getMessageNumber()),
                    "from", msg.getFrom() != null && msg.getFrom().length > 0
                        ? msg.getFrom()[0].toString() : "unknown",
                    "subject", msg.getSubject() != null ? msg.getSubject() : "(no subject)",
                    "date", msg.getSentDate() != null ? msg.getSentDate().toString() : "unknown"));
            }
            inbox.close(false);

            long elapsed = System.currentTimeMillis() - start;
            String output = I18n.get("skill.email.inbox", summaries.size());
            return SkillResult.ok(output, Map.of("messages", summaries, "total", total),
                elapsed, SkillTier.NATIVE, skillId);
        }
    }

    private SkillResult executeRead(Map<String, Object> params, String creds,
                                     long start, String skillId) throws Exception {
        String msgIdStr = requireParam(params, "messageId");
        if (msgIdStr == null) {
            return SkillResult.error(I18n.get("skill.param_required", "messageId"),
                0, SkillTier.NATIVE, skillId);
        }
        int msgNum = Integer.parseInt(msgIdStr);

        try (Store store = connectStore(creds)) {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            Message msg = inbox.getMessage(msgNum);

            String content = msg.getContent() instanceof String s ? s : msg.getContent().toString();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("from", msg.getFrom() != null ? msg.getFrom()[0].toString() : "unknown");
            data.put("subject", msg.getSubject());
            data.put("date", msg.getSentDate() != null ? msg.getSentDate().toString() : "unknown");
            data.put("body", content.length() > 4096 ? content.substring(0, 4096) + "..." : content);
            inbox.close(false);

            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.ok(content.length() > 512 ? content.substring(0, 512) + "..." : content,
                data, elapsed, SkillTier.NATIVE, skillId);
        }
    }

    private SkillResult executeSend(Map<String, Object> params, String creds,
                                     long start, String skillId, SkillContext context) throws Exception {
        if (!context.isHumanSession()) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(I18n.get("skill.email.send_denied"),
                elapsed, SkillTier.NATIVE, skillId);
        }

        String to = requireParam(params, "to");
        String subject = requireParam(params, "subject");
        String body = requireParam(params, "body");
        if (to == null || subject == null || body == null) {
            return SkillResult.error(I18n.get("skill.param_required", "to, subject, body"),
                0, SkillTier.NATIVE, skillId);
        }

        String[] parts = parseCreds(creds);
        Session session = createSmtpSession(parts);
        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(parts[0]));
        msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
        msg.setSubject(subject);
        msg.setText(body);

        Transport.send(msg, parts[0], parts[1]);

        long elapsed = System.currentTimeMillis() - start;
        String output = I18n.get("skill.email.sent", to);
        return SkillResult.ok(output, Map.of("to", to, "subject", subject),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeDraft(Map<String, Object> params, String creds,
                                      long start, String skillId) throws Exception {
        String to = requireParam(params, "to");
        String subject = requireParam(params, "subject");
        String body = requireParam(params, "body");
        if (to == null || subject == null || body == null) {
            return SkillResult.error(I18n.get("skill.param_required", "to, subject, body"),
                0, SkillTier.NATIVE, skillId);
        }

        try (Store store = connectStore(creds)) {
            String[] parts = parseCreds(creds);
            Session session = createImapSession(parts);
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(parts[0]));
            msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to));
            msg.setSubject(subject);
            msg.setText(body);
            msg.setFlag(Flags.Flag.DRAFT, true);

            Folder drafts = store.getFolder("Drafts");
            if (!drafts.exists()) drafts = store.getFolder("INBOX");
            drafts.open(Folder.READ_WRITE);
            drafts.appendMessages(new Message[]{msg});
            drafts.close(false);

            long elapsed = System.currentTimeMillis() - start;
            String output = I18n.get("skill.email.draft_saved", subject);
            return SkillResult.ok(output, Map.of("to", to, "subject", subject),
                elapsed, SkillTier.NATIVE, skillId);
        }
    }

    private String[] parseCreds(String creds) {
        int atIdx = creds.indexOf('@');
        if (atIdx < 0) throw new IllegalArgumentException("Invalid email credentials format");
        String userPass = creds.substring(0, atIdx);
        String hostPorts = creds.substring(atIdx + 1);
        String[] up = userPass.split(":", 2);
        String[] hp = hostPorts.split(":", 3);
        if (up.length < 2 || hp.length < 3)
            throw new IllegalArgumentException("Invalid email credentials format");
        return new String[]{up[0], up[1], hp[0], hp[1], hp[2]};
    }

    private Session createImapSession(String[] parts) {
        Properties props = new Properties();
        props.put("mail.imap.host", parts[2]);
        props.put("mail.imap.port", parts[3]);
        props.put("mail.imap.ssl.enable", "true");
        return Session.getInstance(props);
    }

    private Session createSmtpSession(String[] parts) {
        Properties props = new Properties();
        props.put("mail.smtp.host", parts[2]);
        props.put("mail.smtp.port", parts[4]);
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        return Session.getInstance(props);
    }

    private Store connectStore(String creds) throws Exception {
        String[] parts = parseCreds(creds);
        Session session = createImapSession(parts);
        Store store = session.getStore("imap");
        store.connect(parts[2], parts[0], parts[1]);
        return store;
    }

    private String requireParam(Map<String, Object> params, String key) {
        Object v = params != null ? params.get(key) : null;
        return v != null ? String.valueOf(v) : null;
    }

    private int intParam(Map<String, Object> params, String key, int defaultValue) {
        Object v = params != null ? params.get(key) : null;
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try { return Integer.parseInt(String.valueOf(v)); }
            catch (NumberFormatException e) { /* fall through */ }
        }
        return defaultValue;
    }

    @Override
    public List<SkillDefinition> availableSkills() { return List.copyOf(skills.values()); }

    @Override
    public boolean supports(String skillId) { return skills.containsKey(skillId); }

    @Override
    public SkillTier tier() { return SkillTier.NATIVE; }
}
