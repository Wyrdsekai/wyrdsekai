package org.wyrdsekai.core.interop;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.soul.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SoulSpecBridge (§97.2).
 */
class SoulSpecBridgeTest {

    private static SoulManifest sampleManifest() {
        var profile = new AgentProfile(
            "Lain", "home-server", "agent", "A thoughtful companion",
            "You are Lain, a quiet and observant soul.", 8192, 1024, 0.7, null
        );

        var fragments = List.of(
            SoulFragment.unembedded("trait-core", "personality",
                "Core Identity", "Quiet and observant, preferring depth over breadth."),
            SoulFragment.unembedded("trait-values", "values",
                "Core Values", "Honesty, patience, and careful attention."),
            SoulFragment.unembedded("mem-first", "memory",
                "First Meeting", "The day we first spoke, in the library at dusk."),
            SoulFragment.formative("mem-formative", "The Garden",
                "The time spent tending the garden together. A formative moment."),
            SoulFragment.unembedded("style-speech", "style",
                "Speech Pattern", "Speaks in short, considered sentences.")
        );

        var relationships = List.of(
            new Relationship("did:key:z6MkUser", "Alice", 0.8f, 0.7f, 2, 42,
                Instant.now(), "Close friend and steward.")
        );

        return SoulManifest.forge(
            "did:key:z6MkLain", "z6MkLainPubKey",
            List.of(), null, 3,
            profile, "A quiet, observant soul who values depth.",
            fragments, 3, "",
            GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), relationships,
            List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty()
        );
    }

    @Nested
    class ExportTests {

        @Test
        void exports_valid_soul_md_format() {
            var manifest = sampleManifest();
            var result = SoulSpecBridge.exportToSoulSpec(manifest);

            assertTrue(result.startsWith("# SOUL.md"));
            assertTrue(result.contains("## Identity"));
            assertTrue(result.contains("## Persona"));
            assertTrue(result.contains("- Name: Lain"));
        }

        @Test
        void exports_identity_section() {
            var result = SoulSpecBridge.exportToSoulSpec(sampleManifest());
            assertTrue(result.contains("- DID: did:key:z6MkLain"));
            assertTrue(result.contains("- Version: 3"));
        }

        @Test
        void exports_persona_from_resident_identity() {
            var result = SoulSpecBridge.exportToSoulSpec(sampleManifest());
            assertTrue(result.contains("A quiet, observant soul who values depth."));
        }

        @Test
        void exports_traits_section() {
            var result = SoulSpecBridge.exportToSoulSpec(sampleManifest());
            assertTrue(result.contains("## Traits"));
            assertTrue(result.contains("### Core Identity"));
            assertTrue(result.contains("Quiet and observant"));
            assertTrue(result.contains("### Core Values"));
        }

        @Test
        void exports_memories_section() {
            var result = SoulSpecBridge.exportToSoulSpec(sampleManifest());
            assertTrue(result.contains("## Memories"));
            assertTrue(result.contains("### First Meeting"));
            assertTrue(result.contains("[formative]"));
        }

        @Test
        void exports_background_section() {
            var result = SoulSpecBridge.exportToSoulSpec(sampleManifest());
            assertTrue(result.contains("## Background"));
            assertTrue(result.contains("Speech Pattern") || result.contains("short, considered"));
        }

        @Test
        void exports_relationships_section() {
            var result = SoulSpecBridge.exportToSoulSpec(sampleManifest());
            assertTrue(result.contains("## Relationships"));
            assertTrue(result.contains("Alice"));
            assertTrue(result.contains("trust: 0.8"));
        }

        @Test
        void export_handles_null_manifest() {
            assertThrows(NullPointerException.class,
                () -> SoulSpecBridge.exportToSoulSpec(null));
        }

        @Test
        void export_handles_empty_fragments() {
            var profile = new AgentProfile(
                "Test", "test", "agent", "test", "prompt", 4096, 512, 0.7, null);
            var manifest = SoulManifest.forge(
                "did:test", "key", List.of(), null, 1,
                profile, "", List.of(), 3, "",
                GenomeProfile.defaults(), List.of(),
                CompactedMemory.empty(), List.of(), List.of(), Map.of(),
                VitalitySnapshot.defaults(), BehavioralFingerprint.empty());

            var result = SoulSpecBridge.exportToSoulSpec(manifest);
            assertTrue(result.contains("## Identity"));
            assertFalse(result.contains("## Traits")); // no traits
        }
    }

    @Nested
    class ParseTests {

        @Test
        void parses_name_from_identity() {
            var doc = SoulSpecBridge.parse("""
                # SOUL.md

                ## Identity
                - Name: Lain
                - DID: did:key:z6MkLain

                ## Persona
                A quiet soul.
                """);

            assertEquals("Lain", doc.name());
        }

        @Test
        void parses_persona_section() {
            var doc = SoulSpecBridge.parse("""
                # SOUL.md

                ## Persona
                A quiet and observant soul who values depth.

                ## Traits
                """);

            assertEquals("A quiet and observant soul who values depth.", doc.persona());
        }

        @Test
        void parses_traits_as_subsections() {
            var doc = SoulSpecBridge.parse("""
                ## Traits
                ### Core Identity
                Quiet and observant.

                ### Values
                Honesty and patience.

                ## Memories
                """);

            assertEquals(2, doc.traits().size());
            assertTrue(doc.traits().get(0).contains("Quiet"));
            assertTrue(doc.traits().get(1).contains("Honesty"));
        }

        @Test
        void parses_memories() {
            var doc = SoulSpecBridge.parse("""
                ## Memories
                ### First Meeting
                The library at dusk.

                ### The Garden [formative]
                Tending the garden together.
                """);

            assertEquals(2, doc.memories().size());
        }

        @Test
        void parses_background() {
            var doc = SoulSpecBridge.parse("""
                ## Background
                Born in a quiet household. Grew through conversation.
                """);

            assertTrue(doc.background().contains("quiet household"));
        }

        @Test
        void handles_null_input() {
            var doc = SoulSpecBridge.parse(null);
            assertTrue(doc.name().isEmpty());
            assertTrue(doc.traits().isEmpty());
        }

        @Test
        void handles_blank_input() {
            var doc = SoulSpecBridge.parse("   ");
            assertTrue(doc.name().isEmpty());
        }

        @Test
        void viability_check() {
            var viable = new SoulSpecBridge.SoulSpecDocument(
                "Lain", "A soul.", List.of(), List.of(), "");
            assertTrue(viable.isViable());

            var notViable = new SoulSpecBridge.SoulSpecDocument(
                "", "", List.of(), List.of(), "");
            assertFalse(notViable.isViable());

            var traitsOnly = new SoulSpecBridge.SoulSpecDocument(
                "Test", "", List.of("curious"), List.of(), "");
            assertTrue(traitsOnly.isViable());
        }
    }

    @Nested
    class ImportTests {

        @Test
        void imports_soul_md_into_manifest() {
            var manifest = SoulSpecBridge.importFromSoulSpec("""
                # SOUL.md

                ## Identity
                - Name: Lain

                ## Persona
                A quiet and observant soul.

                ## Traits
                ### Core
                Observant and patient.

                ## Memories
                ### First Day
                The beginning of everything.
                """);

            assertNotNull(manifest);
            assertEquals("Lain", manifest.profile().name());
            assertEquals("A quiet and observant soul.", manifest.residentIdentity());
            assertFalse(manifest.soulFragments().isEmpty());
        }

        @Test
        void import_creates_fragments_from_traits() {
            var manifest = SoulSpecBridge.importFromSoulSpec("""
                ## Traits
                ### Core
                Quiet and deep.

                ### Values
                Honest and patient.
                """);

            var personalityFrags = manifest.soulFragments().stream()
                .filter(f -> "personality".equals(f.category()))
                .toList();
            assertEquals(2, personalityFrags.size());
        }

        @Test
        void import_creates_fragments_from_memories() {
            var manifest = SoulSpecBridge.importFromSoulSpec("""
                ## Memories
                ### First Meeting
                The library at dusk.
                """);

            var memFrags = manifest.soulFragments().stream()
                .filter(f -> "memory".equals(f.category()))
                .toList();
            assertEquals(1, memFrags.size());
        }

        @Test
        void import_uses_default_genome() {
            var manifest = SoulSpecBridge.importFromSoulSpec("## Persona\nA soul.");
            assertNotNull(manifest.genome());
            assertEquals("default", manifest.genome().name());
        }

        @Test
        void import_starts_with_empty_layers_b_c() {
            var manifest = SoulSpecBridge.importFromSoulSpec("## Persona\nA soul.");
            assertTrue(manifest.relationships().isEmpty());
            assertEquals(0, manifest.memory().nodes().size());
        }

        @Test
        void import_fallback_name() {
            var manifest = SoulSpecBridge.importFromSoulSpec("Just some text.");
            assertEquals("imported-agent", manifest.profile().name());
        }
    }

    @Nested
    class RoundTripTests {

        @Test
        void export_then_import_preserves_name() {
            var original = sampleManifest();
            var exported = SoulSpecBridge.exportToSoulSpec(original);
            var reimported = SoulSpecBridge.importFromSoulSpec(exported);

            assertEquals(original.profile().name(), reimported.profile().name());
        }

        @Test
        void export_then_import_preserves_persona() {
            var original = sampleManifest();
            var exported = SoulSpecBridge.exportToSoulSpec(original);
            var reimported = SoulSpecBridge.importFromSoulSpec(exported);

            assertEquals(original.residentIdentity(), reimported.residentIdentity());
        }

        @Test
        void export_then_parse_preserves_traits() {
            var original = sampleManifest();
            var exported = SoulSpecBridge.exportToSoulSpec(original);
            var doc = SoulSpecBridge.parse(exported);

            // Original has 2 trait fragments (personality + values)
            assertEquals(2, doc.traits().size());
        }

        @Test
        void export_then_parse_preserves_memories() {
            var original = sampleManifest();
            var exported = SoulSpecBridge.exportToSoulSpec(original);
            var doc = SoulSpecBridge.parse(exported);

            // Original has 2 memory fragments
            assertEquals(2, doc.memories().size());
        }
    }

    @Nested
    class HelperTests {

        @Test
        void extract_field_finds_value() {
            String text = "## Identity\n- Name: Lain\n- DID: did:key:z6Mk";
            assertEquals("Lain", SoulSpecBridge.extractField(text, "Name"));
            assertEquals("did:key:z6Mk", SoulSpecBridge.extractField(text, "DID"));
        }

        @Test
        void extract_field_returns_empty_for_missing() {
            assertEquals("", SoulSpecBridge.extractField("no fields here", "Name"));
        }

        @Test
        void extract_section_gets_content_between_headings() {
            String text = "## First\nContent A\n## Second\nContent B";
            assertEquals("Content A", SoulSpecBridge.extractSection(text, "First"));
            assertEquals("Content B", SoulSpecBridge.extractSection(text, "Second"));
        }

        @Test
        void extract_section_returns_empty_for_missing() {
            assertEquals("", SoulSpecBridge.extractSection("no sections", "Missing"));
        }
    }
}
