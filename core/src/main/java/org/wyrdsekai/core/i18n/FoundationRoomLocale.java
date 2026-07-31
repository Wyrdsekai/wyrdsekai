package org.wyrdsekai.core.i18n;

import java.util.*;

/**
 * Locale-specific Foundation room descriptions (§104.9).
 * Foundation rooms have culturally-adapted variants:
 * Japanese: genkan/washitsu/engawa
 * Western: foyer/living-room/porch
 *
 * The room's function stays the same, but its in-world
 * manifestation adapts to cultural context.
 */
public class FoundationRoomLocale {

    /** A locale-specific room description. */
    public record RoomVariant(
        String roomId,
        String locale,
        String localizedName,
        String localizedDescription,
        List<String> localizedExits
    ) {}

    private final Map<String, Map<String, RoomVariant>> variants = new HashMap<>();

    public FoundationRoomLocale() {
        loadDefaults();
    }

    /** Get room variant for locale, falling back to English. */
    public RoomVariant variantFor(String roomId, String locale) {
        var roomVariants = variants.getOrDefault(roomId, Map.of());
        return roomVariants.getOrDefault(locale, roomVariants.get("en"));
    }

    /** Register a locale variant for a room. */
    public void register(RoomVariant variant) {
        variants.computeIfAbsent(variant.roomId(), k -> new HashMap<>())
            .put(variant.locale(), variant);
    }

    /** Check if a room has a variant for a locale. */
    public boolean hasVariant(String roomId, String locale) {
        return variants.containsKey(roomId) &&
            variants.get(roomId).containsKey(locale);
    }

    /** Get all registered locales for a room. */
    public Set<String> availableLocales(String roomId) {
        return variants.containsKey(roomId) ?
            Set.copyOf(variants.get(roomId).keySet()) : Set.of();
    }

    /** Get all rooms that have locale variants. */
    public Set<String> localizedRooms() {
        return Set.copyOf(variants.keySet());
    }

    private void loadDefaults() {
        // Nexus — culturally neutral, same across locales
        register(new RoomVariant("nexus", "en",
            "The Nexus",
            "A shimmering crossroads where all paths converge. Threads of light connect to every room in the household.",
            List.of("terminal", "docks", "bridge")));
        register(new RoomVariant("nexus", "ja",
            "結び目",
            "すべての道が交わる光り輝く十字路。光の糸が家の全ての部屋へと繋がっている。",
            List.of("端末", "波止場", "橋")));
        register(new RoomVariant("nexus", "es",
            "El Nexo",
            "Una encrucijada resplandeciente donde todos los caminos convergen. Hilos de luz conectan cada habitación del hogar.",
            List.of("terminal", "muelles", "puente")));

        // Home room equivalent — culturally adapted
        register(new RoomVariant("home", "en",
            "The Hearth",
            "A warm, quiet room with comfortable furniture. Your companion's personal space.",
            List.of("nexus")));
        register(new RoomVariant("home", "ja",
            "居間",
            "畳の香りが漂う落ち着いた和室。床の間には季節の花が飾られている。あなたの仲間の居場所。",
            List.of("結び目")));
        register(new RoomVariant("home", "es",
            "El Hogar",
            "Una habitación cálida y tranquila con muebles cómodos. El espacio personal de tu compañero.",
            List.of("nexo")));

        // Entry — genkan vs foyer
        register(new RoomVariant("entry", "en",
            "The Foyer",
            "The entrance to the household. A place of arrivals and departures.",
            List.of("nexus")));
        register(new RoomVariant("entry", "ja",
            "玄関",
            "家の入口。靴を脱いで上がる、内と外の境界。",
            List.of("結び目")));
        register(new RoomVariant("entry", "es",
            "El Vestíbulo",
            "La entrada al hogar. Un lugar de llegadas y partidas.",
            List.of("nexo")));
    }
}
