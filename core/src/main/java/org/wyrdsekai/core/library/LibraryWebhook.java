package org.wyrdsekai.core.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * A librarian's change, pushed (LIBRARY_PROTOCOL.md contract 1.5, {@code library_subscribe}).
 *
 * <p>The reference librarian POSTs every change on its feed to a subscribed address, signed
 * with a shared secret: {@code X-ResearchZosho-Signature: sha256=<hmac-sha256(secret, body)>}.
 * This class is the pure half of receiving one: verify the signature in constant time, read
 * the change, and say what it means for the household — a recall to apply to a companion's
 * finding, or a write-up that landed for a question this house handed over. The feed stays
 * the source of truth; the sleep-time recall still reads it, so a lost post costs a night,
 * never a notice.
 */
public final class LibraryWebhook {

    private static final Logger log = LoggerFactory.getLogger(LibraryWebhook.class);
    private static final ObjectMapper M = new ObjectMapper();
    public static final String SIGNATURE_HEADER = "X-ResearchZosho-Signature";

    private LibraryWebhook() {}

    /** One pushed change. */
    public record Change(String libraryId, String libraryName, long seq, String at, String kind,
                         String id, String event, String detail) {
        /** The state the entry moved TO on a state change, else null. */
        public String toState() {
            if (event == null || !event.startsWith("state:")) return null;
            int arrow = event.lastIndexOf('→');
            if (arrow < 0) arrow = event.lastIndexOf("->") >= 0 ? event.lastIndexOf("->") + 1 : -1;
            return arrow > 0 ? event.substring(arrow + 1).strip().toLowerCase(Locale.ROOT) : null;
        }
        /** A retirement, dispute or supersession of something a patron may have cited. */
        public boolean isRecall() {
            var to = toState();
            return to != null && (to.equals("retired") || to.equals("disputed") || to.equals("superseded"));
        }
        /**
         * A notice about a SOURCE behind an entry, not the entry's standing: {@code revised} (a cited
         * preprint has a newer version) or {@code supplied} (the person supplied the document behind a
         * locator the runner was refused). Worth a note on a finding that cites it, never a dispute.
         */
        public boolean isSourceNotice() {
            return "revised".equals(event) || "supplied".equals(event);
        }
        /** A new investigation on the shelves — the write-up of an overnight ask. */
        public boolean isLandedInvestigation() {
            return "investigation".equals(kind) && "added".equals(event);
        }
        /** The writer named in an {@code added} detail ("draft by patron:did:…"), or "". */
        public String writer() {
            if (detail == null) return "";
            int by = detail.lastIndexOf(" by ");
            return by < 0 ? "" : detail.substring(by + 4).strip();
        }
    }

    /** The env var holding the signing secret for a service: {@code WYRDSEKAI_LIBRARY_WEBHOOK_SECRET_<ID>}. */
    public static String secretEnvVar(String serviceId) {
        return "WYRDSEKAI_LIBRARY_WEBHOOK_SECRET_" + serviceId.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');
    }

    /** HMAC-SHA256 of {@code body} with {@code secret}, hex — what the librarian sends. */
    public static String hmac(String secret, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Whether {@code signatureHeader} ({@code sha256=<hex>}) signs {@code body} with {@code secret}. Constant time. */
    public static boolean verify(String secret, byte[] body, String signatureHeader) {
        if (secret == null || secret.isBlank() || signatureHeader == null || body == null) return false;
        var sig = signatureHeader.strip();
        if (sig.startsWith("sha256=")) sig = sig.substring(7);
        var expected = hmac(secret, body);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
            sig.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    /** The change in a verified body, or null when the body is not one. */
    public static Change parse(byte[] body) {
        try {
            JsonNode n = M.readTree(body);
            JsonNode c = n.path("change");
            if (!c.isObject()) return null;
            return new Change(n.path("library_id").asText(""), n.path("library_name").asText(""),
                c.path("seq").asLong(0), c.path("at").asText(""), c.path("kind").asText(""),
                c.path("id").asText(""), c.path("event").asText(""), c.path("detail").asText(""));
        } catch (Exception e) {
            return null;
        }
    }

    /** What applying a change did: the owners' findings it marked disputed, and the ones it only annotated. */
    public record Applied(int marked, List<String> markedIds, List<String> notedIds) {
        public Applied(int marked, List<String> markedIds) { this(marked, markedIds, List.of()); }
        public int noted() { return notedIds.size(); }
    }

    /**
     * Apply a recall to every owner's findings that came from this library and cite the
     * changed entry: mark them disputed with the reason. Mark, never delete. Findings that
     * are not from this library, or cite something else, are untouched.
     */
    public static Applied apply(WyrdLuceneStore store, List<String> ownerDids, Change c) {
        var marked = new java.util.ArrayList<String>();
        var noted = new java.util.ArrayList<String>();
        if (store == null || c == null || c.id() == null || c.id().isBlank()) return new Applied(0, marked, noted);
        boolean recall = c.isRecall(), notice = c.isSourceNotice();
        if (!recall && !notice) return new Applied(0, marked, noted);
        var libName = c.libraryName() == null || c.libraryName().isBlank() ? c.libraryId() : c.libraryName();
        var to = c.toState();
        for (var owner : ownerDids == null ? List.<String>of() : ownerDids) {
            for (var f : FindingsLedger.list(store, owner, null, 1000)) {
                if (!f.federated() || !c.libraryId().equals(f.originLibrary()) || f.originId() == null) continue;
                if (!(f.originId().equals(c.id()) || f.originId().equals(LibraryRecall.baseId(c.id())))) continue;
                if (f.state() == FindingsLedger.State.RETIRED || f.state() == FindingsLedger.State.DISPUTED) continue;
                var tail = c.detail() == null || c.detail().isBlank() ? "" : ": " + c.detail();
                if (recall) {
                    var why = libName + " " + to + " " + c.id() + tail;
                    if (FindingsLedger.setState(store, f.id(), FindingsLedger.State.DISPUTED, why, null)) marked.add(f.id());
                } else {
                    // A source moved under the finding; its standing did not. Note it where she will
                    // see it at review, keep the state, and let the next recall decide.
                    var note = libName + " says a source behind " + c.id() + " was " + c.event() + tail;
                    if (FindingsLedger.setState(store, f.id(), f.state(), note, f.supersededBy())) noted.add(f.id());
                }
            }
        }
        if (!marked.isEmpty()) log.info("[library-webhook] {} {} {} → {} finding(s) marked disputed", c.libraryName(), c.event(), c.id(), marked.size());
        if (!noted.isEmpty()) log.info("[library-webhook] {} {} {} → {} finding(s) annotated, state kept", c.libraryName(), c.event(), c.id(), noted.size());
        return new Applied(marked.size(), marked, noted);
    }
}
