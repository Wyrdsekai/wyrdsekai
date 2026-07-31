package org.wyrdsekai.core.agent.interiority;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * Group B (scheduled chronicle synthesis): extracted atomic-write helper
 * for daily chronicle snapshots. Pulled out of CompanionActor so the
 * file-shape + atomic-move semantics are testable without instantiating
 * a full actor. CompanionActor.writeDailyChronicleSnapshot() delegates
 * here.
 */
public final class ChronicleSnapshotWriter {

    private ChronicleSnapshotWriter() {}

    /**
     * Write the chronicle snapshot atomically under
     * {@code <dataDir>/chronicles/<didSlug>.json}. Uses .tmp + ATOMIC_MOVE +
     * REPLACE_EXISTING so steward readers never observe a half-written file.
     *
     * @return the final snapshot path
     */
    public static Path write(Path dataDir, String didSlug,
                              ChronicleService.Chronicle chronicle,
                              Instant writtenAt) throws IOException {
        if (dataDir == null) throw new IllegalArgumentException("dataDir null");
        if (didSlug == null || didSlug.isBlank())
            throw new IllegalArgumentException("didSlug blank");
        if (chronicle == null) throw new IllegalArgumentException("chronicle null");

        var dir = dataDir.resolve("chronicles");
        Files.createDirectories(dir);
        var file = dir.resolve(didSlug + ".json");
        var tmp = dir.resolve(didSlug + ".json.tmp");

        var mapper = new ObjectMapper();
        var node = mapper.createObjectNode();
        node.put("agentDid", chronicle.agentDid());
        node.put("agentName", chronicle.agentName());
        node.put("scale", chronicle.scale().name());
        node.put("windowStart", chronicle.since().toString());
        node.put("windowEnd", chronicle.until().toString());
        node.put("testimony", chronicle.testimony());
        node.put("synthesis", chronicle.synthesis());
        node.put("writtenAt", (writtenAt == null ? Instant.now() : writtenAt).toString());

        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), node);
        Files.move(tmp, file,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
        return file;
    }
}
