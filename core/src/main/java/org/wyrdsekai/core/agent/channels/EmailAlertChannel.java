package org.wyrdsekai.core.agent.channels;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.AlertChannel;

import java.util.Properties;
import java.util.concurrent.CompletableFuture;

/**
 * Alert channel via email (SMTP). Universal — everyone has email.
 * Supports common providers (Gmail, Outlook, Yahoo, iCloud) with auto-detected SMTP.
 */
public class EmailAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailAlertChannel.class);

    private final String toAddress;
    private final String smtpHost;
    private final int smtpPort;
    private final String smtpUser;
    private final String smtpPassword;
    private final String fromAddress;

    /**
     * Full SMTP configuration.
     */
    public EmailAlertChannel(String toAddress, String smtpHost, int smtpPort,
                              String smtpUser, String smtpPassword, String fromAddress) {
        this.toAddress = toAddress;
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.smtpUser = smtpUser;
        this.smtpPassword = smtpPassword;
        this.fromAddress = fromAddress != null ? fromAddress : smtpUser;
    }

    /**
     * Auto-detect SMTP from email domain.
     */
    public EmailAlertChannel(String toAddress, String smtpUser, String smtpPassword) {
        this.toAddress = toAddress;
        this.smtpUser = smtpUser;
        this.smtpPassword = smtpPassword;
        this.fromAddress = smtpUser;

        var domain = smtpUser.contains("@") ? smtpUser.substring(smtpUser.indexOf('@') + 1) : "";
        var detected = detectSmtp(domain);
        this.smtpHost = detected[0];
        this.smtpPort = Integer.parseInt(detected[1]);
    }

    @Override
    public String name() { return "email"; }

    @Override
    public CompletableFuture<Boolean> send(String message, String priority, String fromAgent, String deepLink) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var props = new Properties();
                props.put("mail.smtp.auth", "true");
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.host", smtpHost);
                props.put("mail.smtp.port", String.valueOf(smtpPort));
                props.put("mail.smtp.connectiontimeout", "10000");
                props.put("mail.smtp.timeout", "10000");

                var session = Session.getInstance(props, new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(smtpUser, smtpPassword);
                    }
                });

                var msg = new MimeMessage(session);
                msg.setFrom(new InternetAddress(fromAddress, fromAgent + " — Wyrdsekai"));
                msg.setRecipient(Message.RecipientType.TO, new InternetAddress(toAddress));
                msg.setSubject("[" + fromAgent + "] " + truncateSubject(message));

                var body = new StringBuilder();
                body.append(message);
                if (deepLink != null && !deepLink.isBlank()) {
                    body.append("\n\n→ ").append(deepLink);
                }
                body.append("\n\n— ").append(fromAgent).append(" (Wyrdsekai companion)");
                msg.setText(body.toString(), "UTF-8");

                Transport.send(msg);
                log.debug("Email notification sent to {}", toAddress);
                return true;
            } catch (Exception e) {
                log.warn("Email notification failed to {}: {}", toAddress, e.getMessage());
                return false;
            }
        });
    }

    private static String[] detectSmtp(String domain) {
        return switch (domain.toLowerCase()) {
            case "gmail.com", "googlemail.com" -> new String[]{"smtp.gmail.com", "587"};
            case "outlook.com", "hotmail.com", "live.com" -> new String[]{"smtp.office365.com", "587"};
            case "yahoo.com", "yahoo.co.jp" -> new String[]{"smtp.mail.yahoo.com", "587"};
            case "icloud.com", "me.com", "mac.com" -> new String[]{"smtp.mail.me.com", "587"};
            default -> new String[]{"smtp." + domain, "587"};
        };
    }

    private static String truncateSubject(String message) {
        var clean = message.replace('\n', ' ').replace('\r', ' ');
        return clean.length() <= 78 ? clean : clean.substring(0, 75) + "...";
    }
}
