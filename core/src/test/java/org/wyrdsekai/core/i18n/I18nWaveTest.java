package org.wyrdsekai.core.i18n;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §104 — Internationalization, Language, and Cultural Cognition.
 * LocalizedItem, MultilingualEmbeddingConfig, MemoryLocalePolicy, FoundationRoomLocale.
 */
class I18nWaveTest {

    // ── LocalizedItem ──

    @Nested
    class LocalizedItemTests {

        @Test
        void create_with_origin_locale() {
            var item = LocalizedItem.of("item-1", "sword", "A rusty sword", "en");
            assertEquals("en", item.originLocale());
            assertEquals("sword", item.name());
            assertEquals(0, item.translationCount());
        }

        @Test
        void fallback_to_origin_when_no_translation() {
            var item = LocalizedItem.of("item-1", "sword", "A rusty sword", "en");
            assertEquals("sword", item.nameFor("ja"));
            assertEquals("A rusty sword", item.descriptionFor("ja"));
        }

        @Test
        void translated_name_returned() {
            var item = LocalizedItem.of("item-1", "sword", "A rusty sword", "en")
                .withTranslation("ja", "剣", "錆びた剣");
            assertEquals("剣", item.nameFor("ja"));
            assertEquals("錆びた剣", item.descriptionFor("ja"));
            assertEquals("sword", item.nameFor("en")); // Origin unchanged
        }

        @Test
        void multiple_translations() {
            var item = LocalizedItem.of("item-1", "sword", "A rusty sword", "en")
                .withTranslation("ja", "剣", "錆びた剣")
                .withTranslation("es", "espada", "Una espada oxidada");
            assertEquals(2, item.translationCount());
            assertTrue(item.hasTranslation("ja"));
            assertTrue(item.hasTranslation("es"));
            assertFalse(item.hasTranslation("fr"));
        }

        @Test
        void origin_locale_preserved_through_translations() {
            var item = LocalizedItem.of("item-1", "手紙", "大切な手紙", "ja")
                .withTranslation("en", "letter", "An important letter");
            assertEquals("ja", item.originLocale());
            assertEquals("手紙", item.name());
        }

        @Test
        void immutable_through_translations() {
            var original = LocalizedItem.of("item-1", "sword", "A sword", "en");
            var translated = original.withTranslation("ja", "剣", "剣");
            assertEquals(0, original.translationCount()); // Original unchanged
            assertEquals(1, translated.translationCount());
        }
    }

    // ── MultilingualEmbeddingConfig ──

    @Nested
    class MultilingualEmbeddingConfigTests {

        @Test
        void default_config() {
            var config = MultilingualEmbeddingConfig.defaultConfig();
            assertEquals("multilingual-e5-large", config.modelName());
            assertEquals(1024, config.dimensions());
            assertTrue(config.supportedLanguageCount() >= 10);
        }

        @Test
        void bge_m3_config() {
            var config = MultilingualEmbeddingConfig.bgeM3();
            assertEquals("bge-m3", config.modelName());
            assertEquals(8192, config.maxTokens());
        }

        @Test
        void phone_config_smaller() {
            var config = MultilingualEmbeddingConfig.phoneConfig();
            assertEquals(384, config.dimensions());
            assertEquals(256, config.maxTokens());
        }

        @Test
        void same_language_full_quality() {
            var config = MultilingualEmbeddingConfig.defaultConfig();
            assertEquals(1.0, config.estimatedQuality("en", "en"));
            assertEquals(1.0, config.estimatedQuality("ja", "ja"));
        }

        @Test
        void cross_lingual_degradation() {
            var config = MultilingualEmbeddingConfig.defaultConfig();
            double quality = config.estimatedQuality("en", "ja");
            assertTrue(quality > 0.5); // Better than 50%
            assertTrue(quality < 1.0); // But not perfect
        }

        @Test
        void unsupported_language_low_quality() {
            var config = MultilingualEmbeddingConfig.defaultConfig();
            double quality = config.estimatedQuality("en", "sw"); // Swahili not in list
            assertEquals(0.3, quality);
        }

        @Test
        void supports_key_languages() {
            var config = MultilingualEmbeddingConfig.defaultConfig();
            assertTrue(config.supportsLanguage("en"));
            assertTrue(config.supportsLanguage("ja"));
            assertTrue(config.supportsLanguage("es"));
            assertTrue(config.supportsLanguage("zh"));
        }
    }

    // ── MemoryLocalePolicy ──

    @Nested
    class MemoryLocalePolicyTests {

        @Test
        void never_translate_during_consolidation() {
            var policy = new MemoryLocalePolicy();
            assertFalse(policy.shouldTranslate("ja", "en"));
            assertFalse(policy.shouldTranslate("en", "ja"));
            assertFalse(policy.shouldTranslate("en", "en"));
        }

        @Test
        void cross_language_consolidation_by_similarity() {
            var policy = new MemoryLocalePolicy();
            assertTrue(policy.canConsolidate("en", "ja", 0.8)); // High similarity
            assertFalse(policy.canConsolidate("en", "ja", 0.5)); // Low similarity
        }

        @Test
        void code_switch_never_normalized() {
            var policy = new MemoryLocalePolicy();
            assertFalse(policy.shouldNormalizeCodeSwitch("Hello, お元気ですか？"));
            assertFalse(policy.shouldNormalizeCodeSwitch("Let's go, vamos!"));
        }

        @Test
        void detect_japanese() {
            var policy = new MemoryLocalePolicy();
            assertEquals("ja", policy.detectLanguage("こんにちは世界"));
        }

        @Test
        void detect_english() {
            var policy = new MemoryLocalePolicy();
            assertEquals("en", policy.detectLanguage("Hello world"));
        }

        @Test
        void detect_spanish() {
            var policy = new MemoryLocalePolicy();
            assertEquals("es", policy.detectLanguage("¿Cómo estás?"));
        }

        @Test
        void detect_korean() {
            var policy = new MemoryLocalePolicy();
            assertEquals("ko", policy.detectLanguage("안녕하세요"));
        }

        @Test
        void detect_chinese() {
            var policy = new MemoryLocalePolicy();
            assertEquals("zh", policy.detectLanguage("你好世界"));
        }

        @Test
        void detect_unknown_for_empty() {
            var policy = new MemoryLocalePolicy();
            assertEquals("unknown", policy.detectLanguage(""));
            assertEquals("unknown", policy.detectLanguage(null));
        }

        @Test
        void dream_order_by_recency() {
            var policy = new MemoryLocalePolicy();
            var recency = Map.of("f1", 0.3, "f2", 0.9, "f3", 0.6);
            var locales = Map.of("f1", "en", "f2", "ja", "f3", "es");
            var order = policy.dreamOrder(recency, locales);
            assertEquals("f2", order.get(0)); // Most recent first
            assertEquals("f3", order.get(1));
            assertEquals("f1", order.get(2));
        }

        @Test
        void fragment_locale_monolingual() {
            var fl = new MemoryLocalePolicy.FragmentLocale(
                "frag-1", "en", false, List.of("en"));
            assertTrue(fl.isMonolingual());
            assertFalse(fl.isMultilingual());
        }

        @Test
        void fragment_locale_multilingual() {
            var fl = new MemoryLocalePolicy.FragmentLocale(
                "frag-2", "en", true, List.of("en", "ja"));
            assertTrue(fl.isMultilingual());
            assertFalse(fl.isMonolingual());
        }
    }

    // ── FoundationRoomLocale ──

    @Nested
    class FoundationRoomLocaleTests {

        @Test
        void english_variant_exists() {
            var frl = new FoundationRoomLocale();
            var variant = frl.variantFor("nexus", "en");
            assertNotNull(variant);
            assertEquals("The Nexus", variant.localizedName());
        }

        @Test
        void japanese_variant_exists() {
            var frl = new FoundationRoomLocale();
            var variant = frl.variantFor("nexus", "ja");
            assertNotNull(variant);
            assertEquals("結び目", variant.localizedName());
        }

        @Test
        void spanish_variant_exists() {
            var frl = new FoundationRoomLocale();
            var variant = frl.variantFor("nexus", "es");
            assertNotNull(variant);
            assertEquals("El Nexo", variant.localizedName());
        }

        @Test
        void fallback_to_english_for_unknown_locale() {
            var frl = new FoundationRoomLocale();
            var variant = frl.variantFor("nexus", "fr");
            assertNotNull(variant);
            assertEquals("en", variant.locale()); // Fell back to English
        }

        @Test
        void culturally_adapted_home_room() {
            var frl = new FoundationRoomLocale();
            var en = frl.variantFor("home", "en");
            var ja = frl.variantFor("home", "ja");
            assertEquals("The Hearth", en.localizedName());
            assertEquals("居間", ja.localizedName());
            assertTrue(ja.localizedDescription().contains("畳"));
        }

        @Test
        void genkan_vs_foyer() {
            var frl = new FoundationRoomLocale();
            var en = frl.variantFor("entry", "en");
            var ja = frl.variantFor("entry", "ja");
            assertEquals("The Foyer", en.localizedName());
            assertEquals("玄関", ja.localizedName());
        }

        @Test
        void register_custom_variant() {
            var frl = new FoundationRoomLocale();
            frl.register(new FoundationRoomLocale.RoomVariant(
                "nexus", "fr", "Le Nexus",
                "Un carrefour scintillant où tous les chemins convergent.",
                List.of("terminal", "quais", "pont")));
            assertTrue(frl.hasVariant("nexus", "fr"));
            assertEquals("Le Nexus", frl.variantFor("nexus", "fr").localizedName());
        }

        @Test
        void available_locales_for_room() {
            var frl = new FoundationRoomLocale();
            var locales = frl.availableLocales("nexus");
            assertTrue(locales.contains("en"));
            assertTrue(locales.contains("ja"));
            assertTrue(locales.contains("es"));
            assertEquals(3, locales.size());
        }

        @Test
        void localized_rooms_list() {
            var frl = new FoundationRoomLocale();
            var rooms = frl.localizedRooms();
            assertTrue(rooms.contains("nexus"));
            assertTrue(rooms.contains("home"));
            assertTrue(rooms.contains("entry"));
        }
    }
}
