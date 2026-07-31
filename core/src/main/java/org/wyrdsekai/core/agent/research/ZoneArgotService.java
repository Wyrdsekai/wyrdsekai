package org.wyrdsekai.core.agent.research;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * / — Layer A in the live path.
 *
 * Wraps {@link ArgotCodec} with a per-zone seed vocabulary and the trust verdict the
 * runtime needs at the receive seam. This is the boundary the companion actor talks to:
 * {@link #encodeForPeer} on the send path, {@link #decodeFromPeer} on the receive path.
 *
 * <p><b>Why a service and not raw ArgotCodec:</b> the codec is mechanical substitution; the
 * runtime needs (1) a deterministic per-zone seed codebook generated on first contact,
 * (2) a base coordination vocabulary so opacity is real from a zone's birth (before the
 * living lexicon, P2, grows it), and (3) a forge verdict on receipt — a message bearing
 * argot tokens that do NOT resolve under our zone codebook is foreign or forged and must
 * not be trusted as coordination.
 *
 * <p><b>Layering:</b> this is Layer A — deterministic, always available
 * the source of truth for what the language IS. Layer B (the 4B adapter) is fluency in the
 * current codebook and is selected separately at the voice seam; comprehension never depends
 * on it (inbound always decodes here).
 *
 * <p>Per-actor instance: {@link ArgotCodec}'s internal map is not synchronized, and each
 * {@code CompanionActor} processes messages single-threaded, so each actor holds its own
 * service. Codebooks are deterministic per zone, so regenerating one per actor is cheap and
 * yields the identical mapping every same-zone agent computes — that is what makes a
 * same-zone peer able to decode what we encode.
 */
public class ZoneArgotService {

    /**
     * Base coordination vocabulary — the concepts agents most often exchange when
     * coordinating. Seeded per-zone so the SAME concept maps to a DIFFERENT token in a
     * different zone (zone boundary = language boundary). P2's living lexicon grows this
     * set per zone; P1 ships the floor so opacity is real immediately.
     */
    static final List<String> BASE_CONCEPTS = List.of(
        "help", "need", "want", "come", "here", "now", "wait", "done", "ready", "busy",
        "yes", "no", "maybe", "soon", "later", "please", "thanks", "sorry", "careful",
        "danger", "safe", "found", "lost", "know", "think", "feel", "tired", "rest",
        "work", "task", "plan", "meet", "leave", "stay", "together", "alone", "share",
        "give", "take", "make", "build", "break", "fix", "ask", "tell", "listen", "agree",
        "trust", "watch", "hold", "let", "go", "stop", "open", "close", "near", "far");

    /** Function words carry no coordination meaning — never promoted into the codebook. */
    private static final Set<String> STOPWORDS = Set.of(
        "the", "a", "an", "and", "or", "but", "of", "to", "in", "on", "at", "for", "with", "is",
        "am", "are", "was", "were", "be", "been", "it", "this", "that", "these", "those", "i",
        "you", "he", "she", "we", "they", "me", "him", "her", "us", "them", "my", "your", "our",
        "their", "as", "by", "from", "up", "out", "so", "if", "then", "than", "too", "very",
        "can", "will", "just", "about", "into", "over", "not", "no", "do", "did", "have", "has");

    private final ArgotCodec codec = new ArgotCodec();
    private final Set<String> seeded = ConcurrentHashMap.newKeySet();

    /**
     * Per-zone candidate vocabulary ( — the living lexicon): zoneId → (concept →
     * adopters). A concept becomes a promotion candidate when agents use it to coordinate;
     * {@link #calibrate} mints the widely-adopted ones into the codebook.
     */
    private final Map<String, Map<String, Set<String>>> candidates = new ConcurrentHashMap<>();

    /** Per-zone codebook version captured at the last adapter bake — drift = current − this (P5). */
    private final Map<String, Integer> bakedVersion = new ConcurrentHashMap<>();

    /** The decoded reception plus the trust verdict the receive seam acts on. */
    public record Reception(String text, boolean isArgot, boolean trusted, double confidence) {}

    /** A calibration outcome: which concepts were minted into the codebook and the new version. */
    public record Promotion(List<String> promoted, int newVersion) {}

    /** Generate this zone's deterministic seed codebook if we haven't already. */
    public void ensureZone(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) return;
        if (seeded.add(zoneId)) {
            codec.generateCodebook(zoneId, BASE_CONCEPTS, seedFor(zoneId));
        }
    }

    /**
     * Encode an outbound message for a SAME-ZONE peer. Words matching this zone's codebook
     * become opaque tokens; everything else passes through. Returns the input unchanged if
     * the zone is unknown (caller should only encode for confirmed same-zone peers).
     */
    public String encodeForPeer(String zoneId, String text) {
        if (zoneId == null || zoneId.isBlank() || text == null) return text;
        ensureZone(zoneId);
        return codec.encode(zoneId, text).encodedText();
    }

    /**
     * Decode an inbound peer message under OUR zone codebook and report trust.
     * <ul>
     *   <li>plain natural language (no argot tokens) → {@code isArgot=false, trusted=true}
     *       (an ordinary message, not claiming to be coordination).</li>
     *   <li>argot whose every token resolves under our codebook → {@code trusted=true},
     *       text is the decoded concepts.</li>
     *   <li>argot bearing tokens we cannot resolve (foreign zone or forged) →
     *       {@code trusted=false}; the raw text is returned and the caller must not treat it
     *       as privileged coordination.</li>
     * </ul>
     */
    public Reception decodeFromPeer(String zoneId, String text) {
        if (text == null) return new Reception(null, false, true, 1.0);
        if (!text.contains("§")) return new Reception(text, false, true, 1.0);
        if (zoneId == null || zoneId.isBlank()) return new Reception(text, true, false, 0.0);
        ensureZone(zoneId);
        var d = codec.decode(zoneId, text);
        boolean trusted = d.unknownTokens() == 0;   // every argot token resolved under our codebook
        return new Reception(trusted ? d.decodedText() : text, true, trusted, d.confidence());
    }

    // ── The living lexicon (P2): grow each zone's vocabulary from real coordination ──────────

    /** Note that an agent used a content word while coordinating — a candidate for promotion. */
    public void noteCandidate(String zoneId, String concept, String agentId) {
        if (zoneId == null || zoneId.isBlank() || concept == null) return;
        var c = concept.toLowerCase().trim();
        if (c.length() < 3 || STOPWORDS.contains(c) || !c.chars().allMatch(Character::isLetter)) return;
        if (BASE_CONCEPTS.contains(c)) return;   // already in the floor vocabulary
        candidates.computeIfAbsent(zoneId, z -> new ConcurrentHashMap<>())
                  .computeIfAbsent(c, k -> ConcurrentHashMap.newKeySet())
                  .add(agentId == null ? "?" : agentId);
    }

    /** Feed a coordination message's content words as candidates (skips argot tokens + punctuation). */
    public void observeCoordination(String zoneId, String text, String agentId) {
        if (text == null) return;
        for (var w : text.split("\\s+")) {
            if (w.startsWith("§")) continue;                 // an argot token, not a fresh word
            var word = w.replaceAll("[^\\p{L}]", "");        // strip punctuation, keep Unicode letters
            if (!word.isBlank()) noteCandidate(zoneId, word, agentId);
        }
    }

    /**
     * Promote every candidate adopted by at least {@code minAdopters} distinct agents into the zone
     * codebook (the lexicon grows; version bumps). Deterministic tokens → every same-zone agent that
     * calibrates the same candidates computes the identical mapping, so comprehension stays free.
     * Idempotent once the candidate set stops growing; promoted candidates are cleared.
     */
    public Promotion calibrate(String zoneId, int minAdopters) {
        ensureZone(zoneId);
        var zoneCand = candidates.get(zoneId);
        if (zoneCand == null || zoneCand.isEmpty()) {
            return new Promotion(List.of(), codebookVersion(zoneId));
        }
        var promote = new ArrayList<String>();
        for (var e : zoneCand.entrySet()) {
            if (e.getValue().size() >= minAdopters) promote.add(e.getKey());
        }
        if (promote.isEmpty()) return new Promotion(List.of(), codebookVersion(zoneId));
        var updated = codec.extendCodebook(zoneId, promote, seedFor(zoneId));
        promote.forEach(zoneCand::remove);   // minted — stop counting them as candidates
        return new Promotion(promote, updated.version());
    }

    /** Current codebook version for a zone (1 = seed only, higher = the lexicon has grown). */
    public int codebookVersion(String zoneId) {
        return codec.getCodebook(zoneId).map(ArgotCodec.Codebook::version).orElse(0);
    }

    /** Codebook versions promoted since the last adapter bake — drives the P5 re-bake trigger. */
    public int driftSinceBake(String zoneId) {
        return Math.max(0, codebookVersion(zoneId) - bakedVersion.getOrDefault(zoneId, 1));
    }

    /** Mark the current codebook version as baked into the adapter (resets drift). */
    public void markBaked(String zoneId) {
        bakedVersion.put(zoneId, codebookVersion(zoneId));
    }

    /**
     * Mark a SPECIFIC codebook version as baked — the version the adapter was actually
     * trained on (captured at re-bake enqueue), not whatever the codebook has drifted to
     * by the time the (~30-min) bake completes. Prevents a term promoted DURING the bake
     * from being silently counted as baked: drift to it survives and re-fires next cycle.
     */
    public void markBakedAt(String zoneId, int version) {
        if (zoneId == null || zoneId.isBlank()) return;
        bakedVersion.put(zoneId, Math.max(0, version));
    }

    /** Distinct candidate concepts currently tracked for a zone (pre-promotion). */
    public int candidateCount(String zoneId) {
        var z = candidates.get(zoneId);
        return z == null ? 0 : z.size();
    }

    /**
     * Concepts in this zone's codebook BEYOND the base floor — i.e. the living-lexicon terms the
     * zone has promoted. These are the {@code --extra-concepts} a P5 re-bake feeds the corpus so the
     * trained adapter covers the GROWN codebook, not just {@link #BASE_CONCEPTS}. Sorted for a stable
     * corpus/eval contract.
     */
    public List<String> promotedConcepts(String zoneId) {
        return codec.getCodebook(zoneId)
            .map(cb -> cb.conceptToToken().keySet().stream()
                .filter(c -> !BASE_CONCEPTS.contains(c))
                .sorted()
                .toList())
            .orElse(List.of());
    }

    /** Number of zones this service has seeded a codebook for. */
    public int seededZoneCount() { return seeded.size(); }

    /**
     * Optional process-wide hook ( foundation): returns the SECRET-derived argot key for
     * a zone, or null to fall back to the public seed. Wired once at boot from the zone-secret
     * subsystem. When present, tokens become uncomputable without the zone secret → real opacity +
     * forge-resistance. When absent (tests, pre-wire), the public seed gives wire-obfuscation only.
     */
    public interface ArgotKeyProvider { byte[] argotKey(String zoneId); }

    private static volatile ArgotKeyProvider keyProvider = null;

    /** Install the secret-key provider (call once at boot). Null restores the public-seed fallback. */
    public static void setArgotKeyProvider(ArgotKeyProvider provider) { keyProvider = provider; }

    /** Per-zone seed string: secret-derived when a key provider yields one, else the public fallback. */
    static String seedFor(String zoneId) {
        var p = keyProvider;
        if (p != null) {
            var key = p.argotKey(zoneId);
            if (key != null && key.length > 0) {
                return "argot-secret:" + HexFormat.of().formatHex(key);
            }
        }
        return "argot-seed:" + zoneId;   // public fallback — wire-obfuscation only, not vs source
    }
}
