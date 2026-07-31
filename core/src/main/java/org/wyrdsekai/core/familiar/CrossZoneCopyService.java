package org.wyrdsekai.core.familiar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.soul.SoulItem;

import java.time.Instant;
import java.util.HashSet;
import java.util.function.BiConsumer;

/**
 * Routes thought-form and tool copies across zones via NATS federation relay.
 *
 * <p>. Local handoff stays through
 * {@link ForeignCopyInbox} — this service only kicks in when the recipient's
 * home zone is not this one. Pattern mirrors {@link
 * org.wyrdsekai.core.agent.CrossZoneTellService}: singleton; wired after
 * {@code RelaySessionTransport} connects so it can {@code accept(subject, bytes)}.</p>
 *
 * <p>NATS subjects:
 * <ul>
 *   <li>{@code federation.<targetZone>.familiar_copy} — form copy</li>
 *   <li>{@code federation.<targetZone>.familiar_tool} — skill-item copy</li>
 * </ul>
 * On receipt, the target zone drops the payload into its local
 * {@link ForeignCopyInbox} / {@link ForeignToolInbox}. The recipient agent
 * drains it on next spawn, same as for intra-zone copies.</p>
 */
public final class CrossZoneCopyService {

    private static final Logger log = LoggerFactory.getLogger(CrossZoneCopyService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private static volatile CrossZoneCopyService instance;

    private final String localZoneId;
    private volatile BiConsumer<String, byte[]> relayPublisher;

    public CrossZoneCopyService(String localZoneId) {
        this.localZoneId = localZoneId;
    }

    public static void init(String localZoneId) {
        instance = new CrossZoneCopyService(localZoneId);
    }

    public static CrossZoneCopyService get() { return instance; }

    public static void resetForTests() { instance = null; }

    /** Wired by the relay integration layer. Without it, routing is a no-op. */
    public void setRelayPublisher(BiConsumer<String, byte[]> publisher) {
        this.relayPublisher = publisher;
    }

    public String localZoneId() { return localZoneId; }

    // ── Outbound ───────────────────────────────────────────────────────────

    /**
     * Attempt to deliver a form copy across zones. Returns true if the
     * payload was published to a relay; false if the service is not wired
     * or {@code targetZone} is local (caller should handle locally).
     */
    public boolean sendFormCopy(String targetZone, ThoughtForm form,
                                 String senderDid, String recipientDid,
                                 FormTransfer.Intent intent, String note) {
        if (targetZone == null || targetZone.equals(localZoneId)) return false;
        if (relayPublisher == null) {
            log.warn("CrossZoneCopyService: cannot route form copy to zone '{}' — relay not connected",
                targetZone);
            return false;
        }
        try {
            var payload = serialize(form, senderDid, recipientDid, intent, note);
            var subject = "federation." + targetZone + ".familiar_copy";
            relayPublisher.accept(subject, MAPPER.writeValueAsBytes(payload));
            log.info("Routed form copy '{}' → {}@{} ({})",
                form.name(), recipientDid, targetZone, intent);
            return true;
        } catch (Exception e) {
            log.warn("Failed to route form copy to zone '{}': {}", targetZone, e.getMessage());
            return false;
        }
    }

    /** Tool-item variant — same envelope shape, different subject. */
    public boolean sendToolCopy(String targetZone, SoulItem tool,
                                 String senderDid, String recipientDid,
                                 FormTransfer.Intent intent, String note) {
        if (targetZone == null || targetZone.equals(localZoneId)) return false;
        if (relayPublisher == null) {
            log.warn("CrossZoneCopyService: cannot route tool copy to zone '{}' — relay not connected",
                targetZone);
            return false;
        }
        try {
            var payload = MAPPER.createObjectNode();
            payload.put("kind", "tool");
            payload.put("senderDid", senderDid);
            payload.put("recipientDid", recipientDid);
            payload.put("intent", intent == null ? "GIFT" : intent.name());
            payload.put("note", note == null ? "" : note);
            payload.put("timestamp", Instant.now().toString());
            payload.put("toolLabel", tool.label());
            payload.put("toolJson", MAPPER.writeValueAsString(tool));
            var subject = "federation." + targetZone + ".familiar_tool";
            relayPublisher.accept(subject, MAPPER.writeValueAsBytes(payload));
            log.info("Routed tool copy '{}' → {}@{} ({})",
                tool.label(), recipientDid, targetZone, intent);
            return true;
        } catch (Exception e) {
            log.warn("Failed to route tool copy to zone '{}': {}", targetZone, e.getMessage());
            return false;
        }
    }

    // ── Inbound ────────────────────────────────────────────────────────────

    /**
     * Handle an incoming federation.{localZone}.familiar_copy payload. The
     * payload is drop-into-inbox; the recipient agent's next spawn drains
     * and accepts through the normal copy path.
     */
    public void receiveFormCopy(byte[] payloadBytes) {
        try {
            var node = (ObjectNode) MAPPER.readTree(payloadBytes);
            var form = deserializeForm(node);
            var senderDid = node.get("senderDid").asText();
            var recipientDid = node.get("recipientDid").asText();
            var intent = parseIntent(node.path("intent").asText("GIFT"));
            var note = node.has("note") ? node.get("note").asText() : null;

            ForeignCopyInbox.get().deliver(new ForeignCopyInbox.PendingCopy(
                form, senderDid, recipientDid, intent, note, Instant.now()));
            log.info("Received form copy '{}' from {} → {} (intent={})",
                form.name(), senderDid, recipientDid, intent);
        } catch (Exception e) {
            log.warn("Failed to handle incoming form copy: {}", e.getMessage());
        }
    }

    /** Handle an incoming federation.{localZone}.familiar_tool payload. */
    public void receiveToolCopy(byte[] payloadBytes) {
        try {
            var node = (ObjectNode) MAPPER.readTree(payloadBytes);
            var senderDid = node.get("senderDid").asText();
            var recipientDid = node.get("recipientDid").asText();
            var intent = parseIntent(node.path("intent").asText("GIFT"));
            var note = node.has("note") ? node.get("note").asText() : null;
            var toolJson = node.get("toolJson").asText();
            var tool = MAPPER.readValue(toolJson, SoulItem.class);

            ForeignToolInbox.get().deliver(new ForeignToolInbox.PendingTool(
                tool, senderDid, recipientDid, intent, note, Instant.now()));
            log.info("Received tool copy '{}' from {} → {} (intent={})",
                tool.label(), senderDid, recipientDid, intent);
        } catch (Exception e) {
            log.warn("Failed to handle incoming tool copy: {}", e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static ObjectNode serialize(ThoughtForm form, String senderDid,
                                         String recipientDid, FormTransfer.Intent intent,
                                         String note) {
        var node = MAPPER.createObjectNode();
        node.put("kind", "form");
        node.put("senderDid", senderDid);
        node.put("recipientDid", recipientDid);
        node.put("intent", intent == null ? "GIFT" : intent.name());
        node.put("note", note == null ? "" : note);
        node.put("timestamp", Instant.now().toString());
        // Inline form fields — ThoughtForm has no toJson; hand-roll with the
        // same spec fields. Provenance is preserved via a nested object so
        // the original author chain can't be stripped.
        node.put("id", form.id());
        node.put("name", form.name());
        node.put("version", form.version());
        node.put("systemPrompt", form.systemPrompt());
        var tools = MAPPER.createArrayNode();
        if (form.toolSurface() != null) form.toolSurface().forEach(tools::add);
        node.set("toolSurface", tools);
        node.put("maxTrials", form.maxTrials());
        node.put("maxNestDepth", form.maxNestDepth());
        node.put("evalCriteria", form.evalCriteria() == null ? "" : form.evalCriteria());
        node.put("bondCharge", form.bondCharge());
        node.put("provenanceJson", serializeProvenance(form.provenance()));
        return node;
    }

    private static ThoughtForm deserializeForm(ObjectNode node) throws Exception {
        var toolSurface = new HashSet<String>();
        if (node.has("toolSurface") && node.get("toolSurface") instanceof ArrayNode arr) {
            arr.forEach(t -> toolSurface.add(t.asText()));
        }
        var provenance = deserializeProvenance(node.path("provenanceJson").asText(""));
        return new ThoughtForm(
            node.get("id").asText(),
            node.get("name").asText(),
            node.path("version").asText("1.0.0"),
            provenance,
            node.get("systemPrompt").asText(),
            toolSurface,
            Tanks.defaults(),
            Tanks.maxCeiling(),
            node.path("maxTrials").asInt(3),
            node.path("maxNestDepth").asInt(0),
            node.path("evalCriteria").asText(""),
            Instant.now(),
            Instant.now(),
            0L, 0L, 0L,
            (float) node.path("bondCharge").asDouble(0.0));
    }

    private static String serializeProvenance(Provenance provenance) {
        try { return MAPPER.writeValueAsString(provenance); }
        catch (Exception e) { return "{}"; }
    }

    private static Provenance deserializeProvenance(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) {
            return Provenance.authoredBy("unknown", "deserialized without provenance");
        }
        try { return MAPPER.readValue(json, Provenance.class); }
        catch (Exception e) {
            return Provenance.authoredBy("unknown", "deserialized (parse failure)");
        }
    }

    private static FormTransfer.Intent parseIntent(String s) {
        if (s == null) return FormTransfer.Intent.GIFT;
        try { return FormTransfer.Intent.valueOf(s.toUpperCase()); }
        catch (Exception e) { return FormTransfer.Intent.GIFT; }
    }
}
