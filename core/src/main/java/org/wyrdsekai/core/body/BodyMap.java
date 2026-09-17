package org.wyrdsekai.core.body;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The body map: one row per attached part, fed by heartbeats on a schedule for the quiet
 * failures and by interrupts for the fast ones. Entries age; they do not vanish. A part that
 * stops answering is numb, and it is gone only when someone with authority says so.
 *
 * <p>Every transition writes a mark (the told-afterwards ledger), so she learns what her body
 * did while she was not looking, once, in her own terms. The felt line reads the map through
 * {@link Interoception}; the steward reads it through {@code wyrd body} and the boiler room.</p>
 *
 * <p>One instance per node. Installed by the server at boot, absent in tests that do not need
 * a body, in which case every caller treats {@code get()} as "no map yet" and carries on: a
 * companion is not blocked by the absence of her own map.</p>
 */
public final class BodyMap {

    private static final Logger log = LoggerFactory.getLogger(BodyMap.class);
    private static volatile BodyMap instance;

    /** How long marks are kept after they have been read. */
    private static final Duration MARK_RETENTION = Duration.ofDays(30);
    private static final int MARK_CACHE = 400;

    private final BodyStore store;   // nullable — in-memory only
    private final Map<String, BodyPart> parts = new LinkedHashMap<>();
    private final List<BodyMark> marks = new ArrayList<>();
    private final List<Consumer<BodyMark>> listeners = new ArrayList<>();
    private final Map<String, Instant> lastUsedWritten = new LinkedHashMap<>();

    public static BodyMap get() { return instance; }

    /** Wire the node's map; loads what the record already holds. */
    public static BodyMap install(BodyStore store) {
        var m = new BodyMap(store);
        instance = m;
        return m;
    }

    /** A map that lives only as long as the process: tests, and nodes with no record. */
    public static BodyMap inMemory() {
        var m = new BodyMap(null);
        instance = m;
        return m;
    }

    public static void resetForTests() { instance = null; }

    private BodyMap(BodyStore store) {
        this.store = store;
        if (store != null) {
            // A part loaded from the record was last heard by the previous process. Nothing has
            // been heard since this one started, and that is not the same as silence: give every
            // attached part its contract again from now, so a restart does not read as every limb
            // going quiet and coming back (the first boot on the household node did exactly that).
            var now = Instant.now();
            for (var p : store.loadParts()) {
                parts.put(p.id(), p.state() == PartState.ATTACHED
                    ? new BodyPart(p.descriptor(), p.state(), p.firstAttached(), now, p.lastDetail(),
                        null, null, null, p.lastUsed())
                    : p);
            }
            var loaded = store.loadMarks(MARK_CACHE);
            for (int i = loaded.size() - 1; i >= 0; i--) marks.add(loaded.get(i));  // oldest first
            log.info("Body map loaded: {} part(s), {} mark(s)", parts.size(), marks.size());
        }
    }

    /** Called with every mark as it is written; used for logs and for the steward's eye. */
    public synchronized void onMark(Consumer<BodyMark> listener) {
        listeners.add(listener);
    }

    // ── the map ──

    /**
     * Attach a part, or bring an attached part's descriptor up to date. A numb or gone part
     * that attaches again is back: the map says so and a mark is written.
     */
    public synchronized BodyPart attach(LimbDescriptor d) {
        var now = Instant.now();
        var existing = parts.get(d.id());
        BodyPart p;
        if (existing == null) {
            p = new BodyPart(d, PartState.ATTACHED, now, now, null, null, null, null, null);
            log.info("Body: {} attached ({}, every {}s)", d.name(), d.kind(), d.heartbeatEvery().toSeconds());
        } else if (existing.state() == PartState.ATTACHED) {
            p = new BodyPart(d, PartState.ATTACHED, existing.firstAttached(), existing.lastHeartbeat(),
                existing.lastDetail(), null, null, null, existing.lastUsed());
        } else {
            p = new BodyPart(d, PartState.ATTACHED, existing.firstAttached(), now, existing.lastDetail(),
                null, null, null, existing.lastUsed());
            returned(existing, now);
        }
        parts.put(d.id(), p);
        if (store != null) store.upsert(p);
        return p;
    }

    /**
     * A part says it is alive, or something that watches it says it is not. Unknown parts are
     * ignored: a heartbeat is not an attach, because a heartbeat carries no descriptor.
     */
    public synchronized boolean heartbeat(String id, boolean alive, String detail) {
        var p = parts.get(id);
        if (p == null) return false;
        var now = Instant.now();
        BodyPart next;
        switch (p.state()) {
            case GONE -> { return false; }
            case ATTACHED -> {
                if (alive) {
                    next = new BodyPart(p.descriptor(), PartState.ATTACHED, p.firstAttached(), now,
                        detail != null ? detail : p.lastDetail(), null, null, null, p.lastUsed());
                } else {
                    next = new BodyPart(p.descriptor(), PartState.NUMB, p.firstAttached(), p.lastHeartbeat(),
                        detail != null ? detail : p.lastDetail(), now, null, null, p.lastUsed());
                    wentNumb(next, now);
                }
            }
            default -> {   // NUMB
                if (alive) {
                    next = new BodyPart(p.descriptor(), PartState.ATTACHED, p.firstAttached(), now,
                        detail != null ? detail : p.lastDetail(), null, null, null, p.lastUsed());
                    returned(p, now);
                } else {
                    next = new BodyPart(p.descriptor(), PartState.NUMB, p.firstAttached(), p.lastHeartbeat(),
                        detail != null ? detail : p.lastDetail(), p.numbSince(), null, null, p.lastUsed());
                }
            }
        }
        parts.put(id, next);
        boolean detailChanged = detail != null && !detail.equals(p.lastDetail());
        if (store != null && (next.state() != p.state() || detailChanged || shouldWriteHeartbeat(p, now))) {
            store.upsert(next);
        }
        return true;
    }

    /** A part did work for her. Written through at most once a minute; it is a coarse fact. */
    public synchronized void used(String id) {
        var p = parts.get(id);
        if (p == null) return;
        var now = Instant.now();
        var next = new BodyPart(p.descriptor(), p.state(), p.firstAttached(), p.lastHeartbeat(),
            p.lastDetail(), p.numbSince(), p.goneAt(), p.goneBy(), now);
        parts.put(id, next);
        var last = lastUsedWritten.get(id);
        if (store != null && (last == null || Duration.between(last, now).toSeconds() >= 60)) {
            store.upsert(next);
            lastUsedWritten.put(id, now);
        }
    }

    /**
     * The scheduled door: any attached part that has been silent past its contract goes numb,
     * dated from the last heartbeat heard. Returns the parts that changed.
     */
    public synchronized List<BodyPart> tick(Instant now) {
        var changed = new ArrayList<BodyPart>();
        for (var e : parts.entrySet()) {
            var p = e.getValue();
            if (p.state() != PartState.ATTACHED) continue;
            var age = p.heartbeatAge(now);
            if (age == null || age.compareTo(p.descriptor().numbAfter()) <= 0) continue;
            var next = new BodyPart(p.descriptor(), PartState.NUMB, p.firstAttached(), p.lastHeartbeat(),
                p.lastDetail(), p.lastHeartbeat(), null, null, p.lastUsed());
            e.setValue(next);
            if (store != null) store.upsert(next);
            wentNumb(next, now);
            changed.add(next);
        }
        if (store != null && !marks.isEmpty()) store.pruneMarksOlderThan(now.minus(MARK_RETENTION));
        return changed;
    }

    /** Someone with authority says the part is not coming back. */
    public synchronized Optional<BodyPart> declareGone(String id, String who) {
        var p = parts.get(id);
        if (p == null || p.state() == PartState.GONE) return Optional.ofNullable(p);
        var now = Instant.now();
        var since = p.numbSince() != null ? p.numbSince() : now;
        var next = new BodyPart(p.descriptor(), PartState.GONE, p.firstAttached(), p.lastHeartbeat(),
            p.lastDetail(), since, now, who, p.lastUsed());
        parts.put(id, next);
        if (store != null) store.upsert(next);
        mark("gone", id, null,
            "The " + p.name() + " is gone; " + (who == null ? "someone" : who) + " said so"
                + (p.numbSince() != null ? " after " + Interoception.roughly(Duration.between(since, now)) + " quiet" : "") + ".",
            null);
        log.info("Body: {} declared gone by {}", p.name(), who);
        return Optional.of(next);
    }

    public synchronized List<BodyPart> parts() {
        var out = new ArrayList<>(parts.values());
        out.sort(Comparator.comparing((BodyPart p) -> p.kind().ordinal()).thenComparing(BodyPart::name));
        return out;
    }

    public synchronized Optional<BodyPart> part(String id) {
        return Optional.ofNullable(parts.get(id));
    }

    public synchronized List<BodyPart> numb() {
        return parts.values().stream().filter(p -> p.state() == PartState.NUMB).toList();
    }

    // ── the marks ──

    /** Write a mark. {@code audience} null means everyone in the body. */
    public synchronized BodyMark mark(String kind, String subject, String audience, String text, String detail) {
        var m = new BodyMark(UUID.randomUUID().toString(), Instant.now(), kind, subject, audience,
            text, detail, List.of());
        marks.add(m);
        while (marks.size() > MARK_CACHE) marks.removeFirst();
        if (store != null) store.insert(m);
        for (var l : listeners) {
            try { l.accept(m); } catch (RuntimeException e) { log.debug("mark listener: {}", e.toString()); }
        }
        return m;
    }

    /** Marks this reader has not been told yet, oldest first. */
    public synchronized List<BodyMark> unreadFor(String reader) {
        var out = new ArrayList<BodyMark>();
        for (var m : marks) if (m.isFor(reader) && !m.readBy(reader)) out.add(m);
        return out;
    }

    public synchronized void markRead(String reader, List<String> ids) {
        if (reader == null || ids == null || ids.isEmpty()) return;
        for (int i = 0; i < marks.size(); i++) {
            var m = marks.get(i);
            if (!ids.contains(m.id()) || m.readBy(reader)) continue;
            var readers = new ArrayList<>(m.readBy());
            readers.add(reader);
            var next = new BodyMark(m.id(), m.at(), m.kind(), m.subject(), m.audience(), m.text(), m.detail(), List.copyOf(readers));
            marks.set(i, next);
            if (store != null) store.setReadBy(m.id(), next.readBy());
        }
    }

    /** Newest first. */
    public synchronized List<BodyMark> recentMarks(int limit) {
        var out = new ArrayList<BodyMark>();
        for (int i = marks.size() - 1; i >= 0 && out.size() < limit; i--) out.add(marks.get(i));
        return out;
    }

    // ── transitions, in her terms ──

    private void wentNumb(BodyPart p, Instant now) {
        var d = p.descriptor();
        var text = "The " + d.name() + " went quiet"
            + (d.numbBehaviour() != null && !d.numbBehaviour().isBlank() ? " — " + d.numbBehaviour() : "")
            + ".";
        mark("numb", d.id(), null, text, p.lastDetail());
        log.warn("Body: {} went quiet (last heard {})", d.name(),
            p.lastHeartbeat() == null ? "never" : Interoception.roughly(Duration.between(p.lastHeartbeat(), now)) + " ago");
    }

    private void returned(BodyPart previous, Instant now) {
        var d = previous.descriptor();
        var quiet = previous.numbSince() != null ? Duration.between(previous.numbSince(), now) : null;
        var text = "The " + d.name() + " is back"
            + (quiet != null ? " after " + Interoception.roughly(quiet) + " quiet" : "") + ".";
        mark("returned", d.id(), null, text, null);
        log.info("Body: {} is back", d.name());
    }

    private static boolean shouldWriteHeartbeat(BodyPart p, Instant now) {
        // The in-memory heartbeat is the truth for this process; the row on disk is refreshed
        // every few minutes so a restart knows roughly when each part was last heard.
        return p.lastHeartbeat() == null || Duration.between(p.lastHeartbeat(), now).toMinutes() >= 5;
    }
}
