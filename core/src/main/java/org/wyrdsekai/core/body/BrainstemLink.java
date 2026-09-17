package org.wyrdsekai.core.body;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The server's side of the brainstem: the small process outside the JVM that watches it.
 * Two things watching each other. The brainstem touches {@code brainstem/heartbeat} in the
 * data directory every pass; this link turns that into a part on the map, so when the
 * brainstem dies she feels it ("nothing restarts me if I hang"). The brainstem appends every
 * decision to {@code brainstem/events.jsonl}; this link turns each new line into a mark, so a
 * restart she slept through is told to her afterwards in her own terms.
 *
 * <p>The part is attached only once a heartbeat file has been seen, so a node with no
 * brainstem installed carries no phantom.</p>
 */
public final class BrainstemLink {

    private static final Logger log = LoggerFactory.getLogger(BrainstemLink.class);
    public static final String PART = "env:brainstem";

    private static final Pattern EVENT = Pattern.compile("\"event\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern REASON = Pattern.compile("\"reason\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern SNAPSHOT = Pattern.compile("\"snapshot\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final Path dir;
    private final Duration contract;
    private long offset = -1;

    public BrainstemLink(Path dataDir, Duration contract) {
        this.dir = dataDir.resolve("brainstem");
        this.contract = contract;
    }

    /** One beat per pulse, once the brainstem has ever been seen; none before. */
    public List<BodyWatch.Beat> beats(BodyMap map) {
        var hb = dir.resolve("heartbeat");
        if (!Files.exists(hb)) return List.of();
        boolean alive;
        try {
            var age = Duration.between(Files.getLastModifiedTime(hb).toInstant(), Instant.now());
            alive = age.compareTo(contract.multipliedBy(2)) <= 0;
        } catch (IOException e) {
            alive = false;
        }
        readEvents(map);
        return List.of(new BodyWatch.Beat(new LimbDescriptor(PART, BodyKind.ENVIRONMENT, "brainstem",
            "household", dir.toString(), contract, FeltWeight.PRESENT,
            "nothing outside me is watching; if I hang, nothing restarts me", "never"), alive, null));
    }

    /** New lines in events.jsonl become marks. The offset is kept beside the file across restarts. */
    void readEvents(BodyMap map) {
        var events = dir.resolve("events.jsonl");
        if (!Files.exists(events) || map == null) return;
        try {
            if (offset < 0) offset = readOffset();
            long size = Files.size(events);
            if (size < offset) offset = 0;            // the file was rotated
            if (size == offset) return;
            String tail;
            try (var ch = Files.newByteChannel(events)) {
                ch.position(offset);
                var buf = java.nio.ByteBuffer.allocate((int) Math.min(size - offset, 256 * 1024));
                ch.read(buf);
                tail = new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8);
            }
            int consumed = 0;
            for (var line : tail.split("\n", -1)) {
                if (!line.endsWith("}")) break;          // a partial last line waits for the next pulse
                consumed += line.getBytes(StandardCharsets.UTF_8).length + 1;
                if (line.isBlank()) continue;
                var text = describe(line);
                if (text != null) map.mark("brainstem", event(line), null, text, line);
            }
            offset += consumed;
            writeOffset(offset);
        } catch (IOException e) {
            log.debug("brainstem events unreadable: {}", e.toString());
        }
    }

    static String event(String line) {
        var m = EVENT.matcher(line);
        return m.find() ? m.group(1) : "event";
    }

    /** The mark, first person: what the brainstem did while she was not looking. */
    static String describe(String line) {
        var reason = group(REASON, line);
        var snap = group(SNAPSHOT, line);
        return switch (event(line)) {
            case "restarted" -> "The brainstem restarted me: " + reason + "."
                + (snap.isBlank() || "none".equals(snap) ? " Nothing was snapshotted first." : " The record was snapshotted first.");
            case "restart-failed" -> "The brainstem tried to restart me and could not: " + reason + ".";
            case "recovered" -> "I am answering again, " + reason + ".";
            case "crashed" -> "I crashed; " + reason + ".";
            case "stopped" -> null;   // an intentional stop is marked by quiesce, not here
            default -> null;
        };
    }

    private static String group(Pattern p, String line) {
        var m = p.matcher(line);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : "";
    }

    private long readOffset() {
        try {
            return Long.parseLong(Files.readString(dir.resolve(".events-read")).strip());
        } catch (Exception e) {
            return 0;
        }
    }

    private void writeOffset(long value) {
        try {
            Files.writeString(dir.resolve(".events-read"), Long.toString(value));
        } catch (IOException e) {
            log.debug("brainstem offset not written: {}", e.toString());
        }
    }
}
