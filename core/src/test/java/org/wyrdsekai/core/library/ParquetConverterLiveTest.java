package org.wyrdsekai.core.library;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.io.LocalOutputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live test for Parquet converter — downloads a real parquet file and converts it.
 * Only runs when PARQUET_TEST=true environment variable is set.
 */
class ParquetConverterLiveTest {

    @Test
    void convert_local_parquet_file() throws Exception {
        // Create a minimal parquet file using Avro for testing
        var tempDir = Files.createTempDirectory("parquet-test-");
        var outputFile = tempDir.resolve("output.jsonl");

        // Write a simple parquet file using the Avro API
        var schema = Schema.createRecord("Test", null, null, false);
        schema.setFields(List.of(
            new Schema.Field("title",
                Schema.create(Schema.Type.STRING)),
            new Schema.Field("text",
                Schema.create(Schema.Type.STRING)),
            new Schema.Field("url",
                Schema.create(Schema.Type.STRING))
        ));

        var parquetFile = tempDir.resolve("test.parquet");

        try (var writer = AvroParquetWriter
                .<GenericRecord>builder(
                    new LocalOutputFile(parquetFile))
                .withSchema(schema)
                .build()) {

            for (int i = 0; i < 100; i++) {
                var record = new GenericData.Record(schema);
                record.put("title", "Article " + i);
                record.put("text", "This is the content of article " + i + " about various topics.");
                record.put("url", "https://example.com/article/" + i);
                writer.write(record);
            }
        }

        // Convert
        int rows = FormatConverters.convertParquet(parquetFile, outputFile, "test-pack", null);

        assertEquals(100, rows, "Should convert all 100 rows");
        assertTrue(Files.exists(outputFile));

        var lines = Files.readAllLines(outputFile);
        assertEquals(100, lines.size());
        assertTrue(lines.getFirst().contains("Article 0"));
        assertTrue(lines.getFirst().contains("test-pack:0"));
    }
}
