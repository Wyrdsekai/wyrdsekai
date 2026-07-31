package org.wyrdsekai.core.ambient;

import org.wyrdsekai.common.embodiment.AmbientImprint;
import org.wyrdsekai.common.embodiment.AmbientPhase;
import org.wyrdsekai.common.embodiment.AmbientTone;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Layer 5 — declarative room×phase → (descriptor, imprint).
 *
 * <p>Pure static lookup. Foundation rooms each declare an {@link AmbientTone};
 * dynamic-provisioner rooms (Study/Workshop/Home/Parlor) inherit a tone from
 * their kind. The {@link AmbientPhase} (driven by {@link WorldClock}) selects
 * the rendered descriptor (via i18n key {@code room.<id>.ambient.<phase>})
 * and the tank imprint (via {@code (tone, phase) → AmbientImprint}).
 *
 * <p>This class has no actor state and does no scheduling. It's consumed by:
 * <ul>
 *   <li>{@code WorldClock} when broadcasting {@code AmbientChanged} descriptors</li>
 *   <li>{@code RoomActor.onLookRoom} to overlay phase-flavored text on look</li>
 *   <li>{@code CompanionActor.VitalityTick} (per §15.1) to apply tank deltas</li>
 *   <li>{@code BeatDetector} (already accepts AmbientChanged as INTRUSION trigger)</li>
 * </ul>
 */
public final class AmbientRenderer {

    private AmbientRenderer() {}

    /** Tone declared for each of the 22 foundation rooms. Order matches foundation-rooms.json. */
    private static final Map<String, AmbientTone> FOUNDATION_TONES;
    /** Tone defaults for the four dynamic-provisioner kinds. */
    private static final Map<String, AmbientTone> PROVISIONER_TONES;

    static {
        var f = new LinkedHashMap<String, AmbientTone>();
        f.put("nexus",           AmbientTone.BRIGHT);  // open hub heart
        f.put("terminal",        AmbientTone.BRIGHT);  // active command workspace
        f.put("docks",           AmbientTone.BRIGHT);  // open sky-edge arrivals
        f.put("parlor",          AmbientTone.WARM);    // foundation parlor (auto-scaled instances inherit)
        f.put("atrium",          AmbientTone.BRIGHT);  // open commons
        f.put("boiler-room",     AmbientTone.DIM);     // machine-hum belowdecks
        f.put("bridge",          AmbientTone.BRIGHT);  // command deck
        f.put("vault",           AmbientTone.DIM);     // sealed depths
        f.put("counting-house",  AmbientTone.SOFT);    // quiet ledgerwork
        f.put("library",         AmbientTone.SOFT);    // quieted reading
        f.put("ward-room",       AmbientTone.BRIGHT);  // bright officiate
        f.put("trading-post",    AmbientTone.BRIGHT);  // open marketplace
        f.put("council-chamber", AmbientTone.BRIGHT);  // formal gathering
        f.put("the-safe",        AmbientTone.DIM);     // sealed cool dim
        f.put("gpu-chamber",     AmbientTone.DIM);     // server-hum dim
        f.put("the-loom",        AmbientTone.DIM);     // weaving in lowlight
        f.put("lexicon",         AmbientTone.SOFT);    // scriptorium quiet
        f.put("the-forge",       AmbientTone.WARM);    // furnace-glow
        f.put("oracle",          AmbientTone.DIM);     // veil-light, taper-flame
        f.put("chapel",          AmbientTone.SOFT);    // candle-quiet
        f.put("sanctuary",       AmbientTone.SOFT);    // restful hush
        f.put("workshop",        AmbientTone.WARM);    // bench-light, working room
        FOUNDATION_TONES = Map.copyOf(f);

        var p = new LinkedHashMap<String, AmbientTone>();
        p.put("study",    AmbientTone.SOFT);   // per-user Study rooms
        p.put("workshop", AmbientTone.WARM);   // per-CodingFamiliar Workshop rooms
        p.put("home",     AmbientTone.WARM);   // companion Hearth rooms
        p.put("parlor",   AmbientTone.WARM);   // auto-scaled Parlor instances
        PROVISIONER_TONES = Map.copyOf(p);
    }

    /**
     * Resolve a room's base tone. Foundation rooms use their declared tone;
     * dynamic-provisioner rooms (whose IDs follow {@code <kind>-<uuid>} or
     * {@code <kind>-<userId>} conventions) inherit from {@link #PROVISIONER_TONES}.
     * Unknown rooms default to {@link AmbientTone#SOFT}.
     */
    public static AmbientTone toneFor(String roomId) {
        if (roomId == null) return AmbientTone.SOFT;
        var direct = FOUNDATION_TONES.get(roomId);
        if (direct != null) return direct;
        // dynamic-provisioner kinds prefix room IDs (study-<...>, workshop-<...>, home-<...>, parlor-<...>)
        var lower = roomId.toLowerCase(Locale.ROOT);
        for (var entry : PROVISIONER_TONES.entrySet()) {
            if (lower.startsWith(entry.getKey() + "-") || lower.startsWith(entry.getKey() + "_")) {
                return entry.getValue();
            }
        }
        return AmbientTone.SOFT;
    }

    /**
     * Render a phase-specific descriptor for a room in the given locale.
     * Prefers the per-room key {@code room.<id>.ambient.<phase>}; falls back
     * to a tone-default {@code ambient.<tone>.<phase>}; final fallback is a
     * short hard-coded English line so the system never emits a blank ambient.
     *
     * @param roomId  foundation room id or dynamic-provisioner room id
     * @param phase   current phase (non-null)
     * @param locale  IETF language tag ({@code en}, {@code es}, {@code ja}, ...)
     */
    public static String descriptor(String roomId, AmbientPhase phase, String locale) {
        if (phase == null) phase = AmbientPhase.MIDDAY;
        var lang = (locale == null || locale.isBlank()) ? "en" : locale;
        var catalog = ScriptMessageCatalog.forLang(lang);

        // 1. Per-room ambient line.
        if (roomId != null) {
            var perRoomKey = "room." + canonicalRoomKind(roomId) + ".ambient." + phase.key();
            if (catalog.hasKey(perRoomKey)) return catalog.get(perRoomKey);
        }

        // 2. Tone-default for the room's tone.
        var tone = toneFor(roomId);
        var toneKey = "ambient." + tone.key() + "." + phase.key();
        if (catalog.hasKey(toneKey)) return catalog.get(toneKey);

        // 3. Hard-coded fallback so the engine never narrates "" for ambient.
        return defaultDescriptor(tone, phase);
    }

    /**
     * Strip dynamic-provisioner UUID/userId suffixes so per-room i18n keys
     * stay foundation-keyed. {@code study-d4f1...} → {@code study};
     * {@code parlor-2} → {@code parlor}; foundation IDs pass through.
     */
    static String canonicalRoomKind(String roomId) {
        if (roomId == null) return "";
        if (FOUNDATION_TONES.containsKey(roomId)) return roomId;
        var lower = roomId.toLowerCase(Locale.ROOT);
        for (var kind : PROVISIONER_TONES.keySet()) {
            if (lower.startsWith(kind + "-") || lower.startsWith(kind + "_")) {
                return kind;
            }
        }
        return roomId;
    }

    /**
     * Compute the per-tick {@link AmbientImprint} for {@code roomId} at the
     * given phase. Base imprint depends on the phase ({@code DAWN}=energy,
     * {@code MIDDAY}=energy, {@code DUSK}=equanimity, {@code NIGHT}=equanimity);
     * the room's tone multiplies it (DIM rooms at NIGHT/DUSK deepen equanimity;
     * BRIGHT rooms at MIDDAY/DAWN nudge energy harder; WARM rooms tilt toward
     * a small soothing imprint when the day softens).
     *
     * <p>Per §15.1, this is a small but real coupling — values are tuned to
     * match the order of magnitude of {@code PostureHoldEffect} (~0.005-0.020/tick).
     */
    public static AmbientImprint imprint(String roomId, AmbientPhase phase) {
        if (phase == null) return AmbientImprint.EMPTY;
        var tone = toneFor(roomId);
        var deltas = new LinkedHashMap<String, Double>();

        switch (phase) {
            case DAWN -> deltas.put("energy", 0.005);
            case MIDDAY -> deltas.put("energy", 0.010);
            case DUSK -> deltas.put("equanimity", 0.005);
            case NIGHT -> deltas.put("equanimity", 0.010);
        }

        // Tone modulation — bright rooms amplify energy at peak; dim rooms
        // amplify equanimity at low-light phases; warm rooms add a small
        // soothing imprint when the day softens.
        switch (tone) {
            case BRIGHT -> {
                if (phase == AmbientPhase.MIDDAY || phase == AmbientPhase.DAWN) {
                    deltas.merge("energy", 0.005, Double::sum);
                }
            }
            case DIM -> {
                if (phase == AmbientPhase.NIGHT || phase == AmbientPhase.DUSK) {
                    deltas.merge("equanimity", 0.005, Double::sum);
                }
            }
            case WARM -> {
                if (phase == AmbientPhase.DUSK || phase == AmbientPhase.NIGHT) {
                    deltas.merge("soothing", 0.005, Double::sum);
                }
            }
            case SOFT -> {
                // Soft rooms are gentle across the board — no amplification,
                // base phase imprint stands.
            }
        }

        return AmbientImprint.ofTanks(deltas);
    }

    /**
     * Returns true if {@code roomId} resolves to a known foundation room.
     * Useful when the renderer needs to decide whether a provisioner-default
     * descriptor applies.
     */
    public static boolean isFoundationRoom(String roomId) {
        return FOUNDATION_TONES.containsKey(roomId);
    }

    /** Read-only map of foundation roomId → tone, for tests + audits. */
    public static Map<String, AmbientTone> foundationTones() {
        return FOUNDATION_TONES;
    }

    /** Read-only map of provisioner-kind → default tone, for tests + audits. */
    public static Map<String, AmbientTone> provisionerTones() {
        return PROVISIONER_TONES;
    }

    /** Last-resort English fallback when no catalog entry resolves. */
    private static String defaultDescriptor(AmbientTone tone, AmbientPhase phase) {
        return switch (phase) {
            case DAWN -> switch (tone) {
                case WARM -> "The hearth wakes from its banking; pale dawn at the windows.";
                case BRIGHT -> "Clean light pours in; the room begins its working day.";
                case SOFT -> "Quiet light arrives; everything holds its place a little longer.";
                case DIM -> "A gray rise at the corners; the room stays cool a while yet.";
            };
            case MIDDAY -> switch (tone) {
                case WARM -> "Steady warm light, the day at its broad center.";
                case BRIGHT -> "Full bright midday — the room thrums with use.";
                case SOFT -> "Soft midday light; pages turn quietly.";
                case DIM -> "Even at noon the room keeps its hush; only the work-lights speak.";
            };
            case DUSK -> switch (tone) {
                case WARM -> "Amber light deepens; the day's edges go gold.";
                case BRIGHT -> "The bright is yielding now; lamps are being thought of.";
                case SOFT -> "Light slips toward gray; the air takes a longer breath.";
                case DIM -> "Dusk meets dim; what was quiet becomes quieter.";
            };
            case NIGHT -> switch (tone) {
                case WARM -> "Low hearth-glow, settled and slow; the room holds its warmth.";
                case BRIGHT -> "Bright by lamp now, but the room has turned inward.";
                case SOFT -> "Lamp-pools and shadow; the room is keeping itself company.";
                case DIM -> "Deep low light, and a long silence under it.";
            };
        };
    }
}
