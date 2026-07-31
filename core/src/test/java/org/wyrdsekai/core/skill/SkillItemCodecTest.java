package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.SoulItem;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillItemCodecTest {

    @Test
    void create_sets_version_and_defaults() {
        var def = SkillItemCodec.create("graaljs", "function execute(p) { return 'ok'; }",
            List.of(new SkillItemCodec.Param("loc", "string", "Location", true)),
            "Fetch weather", null, List.of("searxng"));

        assertEquals(1, def.version());
        assertEquals("graaljs", def.runtime());
        assertEquals("Fetch weather", def.description());
        assertEquals(0, def.usageCount());
        assertNull(def.lastUsed());
        assertEquals(1, def.params().size());
        assertEquals(1, def.dependencies().size());
        assertTrue(def.testCases().isEmpty());
    }

    @Test
    void roundtrip_encode_decode() {
        var def = SkillItemCodec.create("graaljs", "function execute(p) { return p.x; }",
            List.of(new SkillItemCodec.Param("x", "string", "Input", true)),
            "Echo input",
            List.of(new SkillItemCodec.TestCase(Map.of("x", "hello"), true, "hello")),
            List.of());

        String json = SkillItemCodec.encode(def);
        assertNotNull(json);
        assertTrue(json.contains("\"version\":1"));
        assertTrue(json.contains("\"runtime\":\"graaljs\""));

        var decoded = SkillItemCodec.decode(json);
        assertNotNull(decoded);
        assertEquals(def.version(), decoded.version());
        assertEquals(def.runtime(), decoded.runtime());
        assertEquals(def.code(), decoded.code());
        assertEquals(def.description(), decoded.description());
        assertEquals(def.params().size(), decoded.params().size());
        assertEquals(def.testCases().size(), decoded.testCases().size());
    }

    @Test
    void decode_null_returns_null() {
        assertNull(SkillItemCodec.decode((String) null));
        assertNull(SkillItemCodec.decode(""));
        assertNull(SkillItemCodec.decode("   "));
    }

    @Test
    void decode_malformed_json_returns_null() {
        assertNull(SkillItemCodec.decode("not json at all"));
        assertNull(SkillItemCodec.decode("{broken"));
    }

    @Test
    void decode_soulitem_checks_category() {
        var def = SkillItemCodec.create("graaljs", "code", null, "desc", null, null);
        String json = SkillItemCodec.encode(def);

        // skill category — should decode
        var skillItem = SoulItem.create("skill", "test", json, "did:key:z6test", 0.5);
        assertNotNull(SkillItemCodec.decode(skillItem));

        // non-skill category — should return null
        var memoryItem = SoulItem.create("memory", "test", json, "did:key:z6test", 0.5);
        assertNull(SkillItemCodec.decode(memoryItem));
    }

    @Test
    void decode_soulitem_null_returns_null() {
        assertNull(SkillItemCodec.decode((SoulItem) null));
    }

    @Test
    void withUsage_increments_count() {
        var def = SkillItemCodec.create("graaljs", "code", null, "desc", null, null);
        assertEquals(0, def.usageCount());
        assertNull(def.lastUsed());

        var used = def.withUsage();
        assertEquals(1, used.usageCount());
        assertNotNull(used.lastUsed());

        var usedAgain = used.withUsage();
        assertEquals(2, usedAgain.usageCount());
    }

    @Test
    void toSoulItem_creates_skill_category() {
        var def = SkillItemCodec.create("graaljs", "function execute(p) { return 'ok'; }",
            null, "Fetch weather for a city", null, null);

        var item = SkillItemCodec.toSoulItem("weather-check", def, "did:key:z6test");
        assertEquals("skill", item.category());
        assertEquals("weather-check", item.label());
        assertEquals("did:key:z6test", item.creatorDid());
        assertEquals(0.6, item.significance(), 0.001);
        assertTrue(item.tags().length > 0);
        assertEquals("weather-check", item.tags()[0]);
        assertTrue(item.verifyIntegrity());
    }

    @Test
    void decode_ignores_unknown_fields() {
        String json = """
            {"version":1,"runtime":"graaljs","code":"x","description":"d",
             "unknownField":"ignored","anotherOne":42}
            """;
        var decoded = SkillItemCodec.decode(json);
        assertNotNull(decoded);
        assertEquals("graaljs", decoded.runtime());
    }
}
