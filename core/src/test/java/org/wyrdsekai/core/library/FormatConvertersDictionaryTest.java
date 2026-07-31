package org.wyrdsekai.core.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dictionary converters — regression coverage
 * for the two failure modes the LIVE smokes found that fixture-sized tests
 * never could:
 *
 * <ol>
 *   <li>The full JMdict_e expands inline-DTD entities past the default StAX
 *       caps (JDK 100K expansion cap / Woodstox maxEntityCount) and died at
 *       ~43K of 217K entries in production. The synthetic file here crosses
 *       the 100K-expansion line so the cap-lifting in
 *       {@code newDictionaryXmlFactory} stays load-bearing under whichever
 *       StAX impl the classpath provides.</li>
 *   <li>FreeDict eng-spa declares {@code <!DOCTYPE TEI SYSTEM
 *       "freedict-P5.dtd">} — an external DTD absent from the tarball; the
 *       parser must resolve it to an empty stream, not the filesystem.</li>
 * </ol>
 */
class FormatConvertersDictionaryTest {

    @TempDir
    Path tmp;

    @Test
    void jmdictSurvivesEntityExpansionPastDefaultCaps() throws Exception {
        // 36K entries × 3 entity refs each = 108K expansions (> the 100K cap).
        int entries = 36_000;
        var xml = new StringBuilder(entries * 160);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
           .append("<!DOCTYPE JMdict [\n")
           .append("<!ENTITY n \"noun (common)\">\n")
           .append("<!ENTITY adj-i \"adjective\">\n")
           .append("<!ENTITY v5r \"Godan verb\">\n")
           .append("]>\n<JMdict>\n");
        for (int i = 0; i < entries; i++) {
            xml.append("<entry><k_ele><keb>語").append(i).append("</keb></k_ele>")
               .append("<r_ele><reb>ご").append(i).append("</reb></r_ele>")
               .append("<sense><pos>&n;</pos><pos>&adj-i;</pos><pos>&v5r;</pos>")
               .append("<gloss>word ").append(i).append("</gloss></sense></entry>\n");
        }
        xml.append("</JMdict>\n");
        var xmlFile = tmp.resolve("JMdict_e.xml");
        Files.writeString(xmlFile, xml);

        var out = tmp.resolve("jmdict.jsonl");
        int count = FormatConverters.convertJmdictXml(xmlFile, out, "jmdict", null);

        assertEquals(entries, count, "every entry must convert — a partial count means an entity cap fired");
        try (var lines = Files.lines(out)) {
            assertEquals(entries, lines.count());
        }
    }

    @Test
    void freedictTeiConvertsDespiteMissingExternalDtd() throws Exception {
        var tei = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE TEI SYSTEM "freedict-P5.dtd">
            <TEI xmlns="http://www.tei-c.org/ns/1.0">
              <teiHeader xml:lang="en"><fileDesc><titleStmt><title>test</title></titleStmt></fileDesc></teiHeader>
              <text><body>
                <entry>
                  <form><orth>gato</orth><pron>gato</pron></form>
                  <sense><cit type="trans"><quote>cat</quote></cit></sense>
                  <sense><cit type="trans"><quote>jack</quote></cit></sense>
                </entry>
                <entry>
                  <form><orth>perro</orth></form>
                  <sense><cit type="trans"><quote>dog</quote></cit></sense>
                </entry>
                <entry>
                  <form><orth>huérfano</orth></form>
                </entry>
              </body></text>
            </TEI>
            """;
        var teiFile = tmp.resolve("spa-eng.tei");
        Files.writeString(teiFile, tei);
        // Deliberately NO freedict-P5.dtd on disk — the resolver must supply
        // an empty stream instead of FileNotFoundException.

        var out = tmp.resolve("spa-eng.jsonl");
        int count = FormatConverters.convertFreedictTei(teiFile, out, "freedict-spa-eng", null);

        // huérfano has no translation → skipped; the other two convert.
        assertEquals(2, count);
        var content = Files.readString(out);
        assertTrue(content.contains("gato — cat; jack"), content);
        assertTrue(content.contains("perro — dog"), content);
    }
}
