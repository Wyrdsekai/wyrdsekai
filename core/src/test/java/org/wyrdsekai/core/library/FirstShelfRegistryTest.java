package org.wyrdsekai.core.library;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * registry tier model, install-time URL resolution, and the new
 * format plumbing (bz2 shards, JMdict, text trees).
 */
class FirstShelfRegistryTest {

    // ── Registry tier model ────────────────────────────────────────────────

    @Test
    void builtin_registry_parses_tier_fields() {
        var simple = KnowledgePackRegistry.find("simple-wikipedia").orElseThrow();
        assertThat(simple.effectiveTier()).isEqualTo(1);
        assertThat(simple.urlResolver()).isEqualTo("wikimedia-cirrus");
        assertThat(simple.resolverArgs()).containsEntry("index", "simplewiki_content");

        var jmdict = KnowledgePackRegistry.find("jmdict").orElseThrow();
        assertThat(jmdict.effectiveTier()).isZero();
        assertThat(jmdict.language()).containsExactly("ja", "en");

        var fitness = KnowledgePackRegistry.find("stackexchange-fitness").orElseThrow();
        assertThat(fitness.effectiveTier()).isEqualTo(2);
        assertThat(fitness.shelf()).isEqualTo("qa");
        assertThat(fitness.recommended()).isTrue();
    }

    @Test
    void list_tier_and_shelf_partition_the_catalog() {
        assertThat(KnowledgePackRegistry.listTier(0)).extracting(KnowledgePackRegistry.PackInfo::name)
            .contains("jmdict");
        assertThat(KnowledgePackRegistry.listTier(1)).extracting(KnowledgePackRegistry.PackInfo::name)
            .contains("simple-wikipedia", "gutenberg-classics");
        assertThat(KnowledgePackRegistry.listShelf("qa")).extracting(KnowledgePackRegistry.PackInfo::name)
            .contains("stackexchange-pets", "stackexchange-health", "stackexchange-fitness", "medquad");
        assertThat(KnowledgePackRegistry.listShelf("coding")).extracting(KnowledgePackRegistry.PackInfo::name)
            .contains("python-docs");
    }

    @Test
    void essential_backcompat_reads_tier_le_1() {
        var essentials = KnowledgePackRegistry.listEssential();
        assertThat(essentials).extracting(KnowledgePackRegistry.PackInfo::name)
            .contains("simple-wikipedia", "gutenberg-classics", "jmdict", "medquad");
    }

    @Test
    void effective_tier_defaults_essential_to_1_and_rest_to_2() {
        var legacyEssential = new KnowledgePackRegistry.PackInfo(
            "x", "X", "d", List.of(), "cc", "general", "MIT", List.of(), "1 MB", true);
        assertThat(legacyEssential.effectiveTier()).isEqualTo(1);
        var legacyPlain = new KnowledgePackRegistry.PackInfo(
            "y", "Y", "d", List.of(), "cc", "general", "MIT", List.of(), "1 MB", null);
        assertThat(legacyPlain.effectiveTier()).isEqualTo(2);
    }

    // ── Wikimedia cirrus resolver (pure parsing + injected lister) ─────────

    private static final String BASE_LISTING = """
        <a href="../">../</a>
        <a href="20260524/">20260524/</a>
        <a href="20260531/">20260531/</a>
        <a href="20260607/">20260607/</a>
        """;
    private static final String INDEX_LISTING = """
        <a href="../">../</a>
        <a href="_SUCCESS">_SUCCESS</a>
        <a href="simplewiki_content-20260607-00000.json.bz2">simplewiki_content-20260607-00000.json.bz2</a>
        """;

    @Test
    void cirrus_resolver_picks_latest_dump_and_lists_shards() throws IOException {
        var urls = WikimediaCirrusResolver.resolve("https://example.test/dumps/", "simplewiki_content",
            url -> url.endsWith("/dumps/") ? BASE_LISTING : INDEX_LISTING);
        assertThat(urls).containsExactly(
            "https://example.test/dumps/20260607/index_name%3Dsimplewiki_content/simplewiki_content-20260607-00000.json.bz2");
    }

    @Test
    void cirrus_resolver_fails_honestly_when_index_has_no_shards() {
        assertThatThrownBy(() -> WikimediaCirrusResolver.resolve(
                "https://example.test/dumps/", "nosuch_content",
                url -> url.endsWith("/dumps/") ? BASE_LISTING : "<a href=\"../\">../</a>"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("No .json.bz2 shards");
    }

    // ── PackDownloader bz2 + name routing ──────────────────────────────────

    @Test
    void bz2_shard_decompresses_under_its_original_name(@TempDir Path tmp) throws Exception {
        // A tiny cirrus-style payload, bz2-compressed via commons-compress
        var payload = """
            {"index":{"_id":"1"}}
            {"title":"Water","text":"Water freezes at zero degrees Celsius and is essential to all known life forms on Earth today."}
            """;
        var src = tmp.resolve("simplewiki_content-20260607-00000.json.bz2");
        try (var out = new BZip2CompressorOutputStream(
                Files.newOutputStream(src))) {
            out.write(payload.getBytes());
        }

        var packDir = tmp.resolve("pack");
        PackDownloader.download(src.toUri().toString(), packDir, null);

        assertThat(packDir.resolve("simplewiki_content-20260607-00000.json")).exists();
        assertThat(Files.readString(packDir.resolve("simplewiki_content-20260607-00000.json")))
            .contains("Water freezes");
    }

    @Test
    void url_basename_strips_query_and_path() {
        assertThat(PackDownloader.urlBasename("https://x.test/a/b/file.json.bz2?x=1")).isEqualTo("file.json.bz2");
        assertThat(PackDownloader.urlBasename("https://x.test/")).isEqualTo("download");
    }

    // ── Converters: new-format cirrus, JMdict, text tree ───────────────────

    @Test
    void cirrus_converter_handles_docs_without_action_lines(@TempDir Path tmp) throws Exception {
        var shard = tmp.resolve("simplewiki_content-20260607-00000.json");
        Files.writeString(shard, """
            {"title":"Mount Fuji","text":"Mount Fuji is the tallest mountain in Japan at 3776 metres, an active stratovolcano last erupting in 1707."}
            {"title":"Tea","text":"Tea is a drink made by steeping cured leaves of the tea plant in hot water, second only to water in consumption."}
            """);
        var out = tmp.resolve("wikipedia.jsonl");
        int n = FormatConverters.convertWikipediaCirrus(shard, out, "simple-wikipedia", null);
        assertThat(n).isEqualTo(2);
        assertThat(Files.readString(out)).contains("Mount Fuji").contains("3776");
    }

    @Test
    void jmdict_converter_emits_headword_chunks(@TempDir Path tmp) throws Exception {
        var xml = tmp.resolve("JMdict_e.xml");
        Files.writeString(xml, """
            <?xml version="1.0" encoding="UTF-8"?>
            <JMdict>
            <entry><ent_seq>1</ent_seq>
              <k_ele><keb>図書館</keb></k_ele>
              <r_ele><reb>としょかん</reb></r_ele>
              <sense><gloss>library</gloss></sense>
            </entry>
            <entry><ent_seq>2</ent_seq>
              <r_ele><reb>ありがとう</reb></r_ele>
              <sense><gloss>thank you</gloss><gloss>thanks</gloss></sense>
            </entry>
            </JMdict>
            """);
        var out = tmp.resolve("jmdict.jsonl");
        int n = FormatConverters.convertJmdictXml(xml, out, "jmdict", null);
        assertThat(n).isEqualTo(2);
        var lines = Files.readAllLines(out);
        assertThat(lines.get(0)).contains("図書館 (としょかん)").contains("library");
        assertThat(lines.get(1)).contains("ありがとう").contains("thank you; thanks");
    }

    @Test
    void text_tree_converter_chunks_doc_trees(@TempDir Path tmp) throws Exception {
        var docs = tmp.resolve("python-3.14-docs-text/library");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("json.txt"),
            ("The json module exposes loads and dumps for parsing and serializing JSON documents. "
             + "It raises JSONDecodeError on malformed input and supports custom encoders. ").repeat(10));
        var chunks = tmp.resolve("chunks");
        int n = FormatConverters.convertPlainTextTree(tmp, chunks, "python-docs", null);
        assertThat(n).isGreaterThan(0);
        assertThat(Files.readString(chunks.resolve("text-tree.jsonl")))
            .contains("json module").contains("python-docs");
    }
}
