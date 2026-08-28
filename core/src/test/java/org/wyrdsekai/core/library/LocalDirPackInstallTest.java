package org.wyrdsekai.core.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * installing a LOCAL markdown pack directory (the codezaiku
 * pattern/ml-* packs, or any steward-curated folder) via the existing
 * {@code installFromUrl(name, "file:///dir", ...)} path: PackDownloader copies the directory,
 * no chunks exist, and the plain-text-tree fallback converts every .md/.txt into indexed chunks.
 */
class LocalDirPackInstallTest {

    @Test
    void file_url_directory_of_markdown_installs_as_a_pack(@TempDir Path tmp) throws Exception {
        // A codezaiku-shaped pattern pack: markdown files in a directory.
        var packSrc = tmp.resolve("ml-fundamentals");
        Files.createDirectories(packSrc);
        Files.writeString(packSrc.resolve("gradient-descent.md"),
            ("# Gradient descent\nWhen to use: optimizing differentiable loss surfaces. "
             + "Pattern: iterate parameter updates against the gradient scaled by a learning rate. "
             + "Pitfalls: too high a learning rate diverges; too low converges slowly. ").repeat(8));
        Files.writeString(packSrc.resolve("overfitting.md"),
            ("# Overfitting\nWhen to use: any supervised training run. Pattern: hold out a "
             + "validation split and watch the gap between train and validation loss. "
             + "Pitfalls: validating on training data tells you nothing. ").repeat(8));

        Path lucenedir = tmp.resolve("lucene");
        Files.createDirectories(lucenedir);
        var lucene = new WyrdLuceneStore(lucenedir, 384);
        var indexer = new KnowledgePackIndexer(lucene);
        var packsDir = tmp.resolve("packs");

        var result = KnowledgePackRegistry.installFromUrl(
            "cp-ml-fundamentals", packSrc.toUri().toString(), packsDir, indexer, null).join();

        assertThat(result.chunksIndexed()).as("markdown tree converted + indexed").isGreaterThan(0);
        assertThat(lucene.searchKnowledgeText("learning rate diverges", 3))
            .as("local pack content is searchable").isNotEmpty();
    }
}
