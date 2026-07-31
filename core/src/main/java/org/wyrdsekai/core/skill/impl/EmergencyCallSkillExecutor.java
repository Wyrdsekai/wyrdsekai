package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Twilio REST API skill executor for voice calls.
 * Provides emergency calling (bypasses budget), regular dialing, and call status.
 * Emergency contacts stored in-memory; configurable at construction time.
 */
public class EmergencyCallSkillExecutor extends HttpSkillExecutor {

    private static final SkillAuth AUTH = SkillAuth.apiKey("twilio_credentials");
    private final List<EmergencyContact> emergencyContacts;

    /** An emergency contact entry. */
    public record EmergencyContact(String name, String phone, String relationship) {}

    /**
     * @param emergencyContacts List of emergency contacts (can be reconfigured)
     */
    public EmergencyCallSkillExecutor(List<EmergencyContact> emergencyContacts) {
        super("https://api.twilio.com/2010-04-01/Accounts");
        this.emergencyContacts = new ArrayList<>(
            emergencyContacts != null ? emergencyContacts : List.of());

        define(SkillDefinition.native_("herald.call.emergency", "Emergency Call",
            "Place an emergency call to a configured contact", "herald",
            List.of(SkillParam.optional("contact", "string",
                         "Contact name (default: first emergency contact)"),
                     SkillParam.optional("message", "string", "TwiML message to speak")),
            AUTH));

        define(SkillDefinition.native_("herald.call.dial", "Dial Call",
            "Place a phone call via Twilio", "herald",
            List.of(SkillParam.required("to", "string", "Phone number to call (E.164 format)"),
                     SkillParam.optional("message", "string", "TwiML message to speak"),
                     SkillParam.optional("from", "string", "Caller ID (Twilio number)")),
            AUTH));

        define(SkillDefinition.native_("herald.call.status", "Call Status",
            "Check the status of a Twilio call", "herald",
            List.of(SkillParam.required("callSid", "string", "Twilio Call SID")),
            AUTH));
    }

    public EmergencyCallSkillExecutor() {
        this(List.of());
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        String creds = context.credentials().get("twilio_credentials");
        if (creds == null) return notConfigured(skillId, "Twilio credentials (SID:Token)");

        String[] parts = creds.split(":", 2);
        if (parts.length != 2)
            return notConfigured(skillId, "Twilio credentials (expected SID:Token format)");

        String sid = parts[0];
        String basicAuth = Base64.getEncoder().encodeToString(
            creds.getBytes(StandardCharsets.UTF_8));
        var headers = Map.of("Authorization", "Basic " + basicAuth);

        long start = System.currentTimeMillis();

        return switch (skillId) {
            case "herald.call.emergency" ->
                executeEmergency(params, headers, sid, start, skillId, context);
            case "herald.call.dial" ->
                executeDial(params, headers, sid, start, skillId, context);
            case "herald.call.status" ->
                executeStatus(params, headers, sid, start, skillId);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeEmergency(Map<String, Object> params, Map<String, String> headers,
                                          String sid, long start, String skillId,
                                          SkillContext context) {
        // Substrate override (CompanionActor.handleEmergencyCall — 3.2): the
        // §7.2-gated emergency path passes the JURISDICTION number (911/988/…)
        // as an explicit `to`. Deliberately NOT declared in the SkillDefinition
        // params — the model-facing surface stays contact-based so
        // herald.call.emergency (the tiered-permission safety floor) cannot be
        // used as a free dialer; only substrate code hands a raw number in.
        String directTo = param(params, "to", null);
        String message = param(params, "message",
            "This is an automated emergency call from the household companion system.");
        if (directTo != null && !directTo.isBlank()) {
            return dialNumber(directTo, message, headers, sid, start, skillId, context);
        }

        if (emergencyContacts.isEmpty())
            return notConfigured(skillId, "Emergency contacts list");

        String contactName = param(params, "contact", null);
        EmergencyContact contact = contactName != null
            ? emergencyContacts.stream()
                .filter(c -> c.name().equalsIgnoreCase(contactName))
                .findFirst().orElse(emergencyContacts.get(0))
            : emergencyContacts.get(0);

        String twiml = "<Response><Say>" + escXml(message) + "</Say></Response>";
        String from = context.credentials().getOrDefault("twilio_from", "");
        String body = "To=" + enc(contact.phone()) + "&From=" + enc(from)
            + "&Twiml=" + enc(twiml);

        String url = baseUrl + "/" + sid + "/Calls.json";
        var formHeaders = new HashMap<>(headers);
        formHeaders.put("Content-Type", "application/x-www-form-urlencoded");

        var result = httpPost(url, body, formHeaders, 30_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(I18n.get("skill.emergency.calling", contact.name()),
            Map.of("contact", contact.name(), "phone", contact.phone(),
                   "body", result.body()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    /** Shared dial for the substrate's direct-number emergency path. */
    private SkillResult dialNumber(String to, String message, Map<String, String> headers,
                                    String sid, long start, String skillId,
                                    SkillContext context) {
        String twiml = "<Response><Say>" + escXml(message) + "</Say></Response>";
        String from = context.credentials().getOrDefault("twilio_from", "");
        String body = "To=" + enc(to) + "&From=" + enc(from) + "&Twiml=" + enc(twiml);
        String url = baseUrl + "/" + sid + "/Calls.json";
        var formHeaders = new HashMap<>(headers);
        formHeaders.put("Content-Type", "application/x-www-form-urlencoded");
        var result = httpPost(url, body, formHeaders, 30_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);
        return SkillResult.ok(I18n.get("skill.emergency.call_placed", to),
            Map.of("to", to, "body", result.body()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeDial(Map<String, Object> params, Map<String, String> headers,
                                     String sid, long start, String skillId,
                                     SkillContext context) {
        String to = requireParam(params, "to");
        if (to == null) return SkillResult.error(I18n.get("skill.param_required", "to"),
            0, SkillTier.NATIVE, skillId);

        String message = param(params, "message", "Hello, this is an automated call.");
        String from = param(params, "from",
            context.credentials().getOrDefault("twilio_from", ""));

        String twiml = "<Response><Say>" + escXml(message) + "</Say></Response>";
        String body = "To=" + enc(to) + "&From=" + enc(from) + "&Twiml=" + enc(twiml);

        String url = baseUrl + "/" + sid + "/Calls.json";
        var formHeaders = new HashMap<>(headers);
        formHeaders.put("Content-Type", "application/x-www-form-urlencoded");

        var result = httpPost(url, body, formHeaders, 30_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        return SkillResult.ok(I18n.get("skill.emergency.call_placed", to),
            Map.of("to", to, "body", result.body()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeStatus(Map<String, Object> params, Map<String, String> headers,
                                       String sid, long start, String skillId) {
        String callSid = requireParam(params, "callSid");
        if (callSid == null)
            return SkillResult.error(I18n.get("skill.param_required", "callSid"),
                0, SkillTier.NATIVE, skillId);

        String url = baseUrl + "/" + sid + "/Calls/" + callSid + ".json";
        var result = httpGet(url, headers, 15_000);
        long elapsed = System.currentTimeMillis() - start;
        if (!result.ok()) return httpError(skillId, result, elapsed);

        String status = jsonString(result.body(), "status");
        return SkillResult.ok(status != null ? status : result.body(),
            Map.of("body", result.body()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String escXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
