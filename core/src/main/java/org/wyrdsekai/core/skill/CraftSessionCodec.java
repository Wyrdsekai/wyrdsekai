package org.wyrdsekai.core.skill;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.soul.SoulItem;

import java.time.Instant;
import java.util.List;

/**
 * Codec for encoding/decoding CraftSession to/from SoulItem.
 * Follows the SkillItemCodec pattern.
 */
public final class CraftSessionCodec {

    private CraftSessionCodec() {}

    /** Decode a CraftSession from a SoulItem. */
    public static CraftSession decode(SoulItem item) {
        if (item == null || !"craft-session".equals(item.category())) return null;
        return decode(item.text());
    }

    /** Decode a CraftSession from a JSON string. */
    public static CraftSession decode(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return Json.mapper().readValue(json, CraftSession.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Encode a CraftSession to a JSON string. */
    public static String encode(CraftSession session) {
        if (session == null) return null;
        try {
            return Json.mapper().writeValueAsString(session);
        } catch (Exception e) {
            return null;
        }
    }

    /** Convert a CraftSession to a SoulItem. */
    public static SoulItem toSoulItem(CraftSession session, String creatorDid) {
        if (session == null) return null;
        String json = encode(session);
        if (json == null) return null;

        return SoulItem.create("craft-session",
            "Craft: " + session.goal(),
            json, creatorDid, 0.3,
            "craft-session", session.status().name().toLowerCase());
    }
}
