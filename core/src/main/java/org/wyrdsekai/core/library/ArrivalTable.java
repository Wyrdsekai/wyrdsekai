package org.wyrdsekai.core.library;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Library's arrival table — pending pack-acquisition proposals
 *
 * <p>Discovery (companion-driven, federation tip, gap signal) drops a
 * {@link ProposedPack} here. Steward review (or auto-approve for high-tier)
 * consumes it. Ingest pipeline turns approved proposals into pack chunks
 * with full provenance.</p>
 *
 * <p>Zone-scoped, JSON-on-disk under the zone's library data dir.
 * Concurrent-safe; persistent across restarts.</p>
 */
public final class ArrivalTable {

    private static final Logger log = LoggerFactory.getLogger(ArrivalTable.class);
    private static final String FILENAME = "arrival-table.json";

    private static final ObjectMapper MAPPER = buildMapper();

    private static ObjectMapper buildMapper() {
        var m = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .enable(SerializationFeature.INDENT_OUTPUT);
        m.findAndRegisterModules();
        return m;
    }

    private final Path file;
    private final ConcurrentHashMap<String, ProposedPack> proposals = new ConcurrentHashMap<>();

    public ArrivalTable(Path root) {
        this.file = root.resolve(FILENAME);
        load();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            var bytes = Files.readAllBytes(file);
            if (bytes.length == 0) return;
            List<ProposedPack> loaded = MAPPER.readValue(
                bytes, new TypeReference<List<ProposedPack>>() {});
            for (var p : loaded) {
                if (p != null && p.id() != null) proposals.put(p.id(), p);
            }
            log.info("Loaded {} arrival-table proposals", proposals.size());
        } catch (IOException e) {
            log.warn("Failed to load arrival table: {}", e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writeValueAsString(list()));
        } catch (IOException e) {
            log.warn("Failed to save arrival table: {}", e.getMessage());
        }
    }

    /**
     * Lay a fresh proposal on the table. If the proposal qualifies for
     * auto-approval (high-tier source) the returned record is already in
     * {@link ProposedPack.Status#APPROVED}; otherwise it is
     * {@link ProposedPack.Status#PENDING}.
     */
    public ProposedPack propose(ProposedPack proposal) {
        var stored = proposal.autoApproveEligible()
            ? proposal.approve("auto:high-tier")
            : proposal;
        proposals.put(stored.id(), stored);
        save();
        log.info("Arrival table: proposed pack '{}' tier={} status={}",
            stored.topic(), stored.trustTier(), stored.status());
        return stored;
    }

    public Optional<ProposedPack> approve(String id, String reviewer) {
        var existing = proposals.get(id);
        if (existing == null) return Optional.empty();
        var updated = existing.approve(reviewer);
        proposals.put(id, updated);
        save();
        return Optional.of(updated);
    }

    public Optional<ProposedPack> reject(String id, String reviewer, String reason) {
        var existing = proposals.get(id);
        if (existing == null) return Optional.empty();
        var updated = existing.reject(reviewer, reason);
        proposals.put(id, updated);
        save();
        return Optional.of(updated);
    }

    public Optional<ProposedPack> markIngested(String id) {
        var existing = proposals.get(id);
        if (existing == null) return Optional.empty();
        var updated = existing.ingested();
        proposals.put(id, updated);
        save();
        return Optional.of(updated);
    }

    /**
     * Apply enrichment from an async discovery scout (refined summary + extra
     * sources). No-op if the proposal has already been rejected or ingested —
     * enrichment is a best-effort backfill, not a status mutation.
     */
    public Optional<ProposedPack> enrich(String id, String enrichedSummary,
                                            List<Provenance.Source> enrichedSources) {
        var existing = proposals.get(id);
        if (existing == null) return Optional.empty();
        if (existing.status() == ProposedPack.Status.REJECTED
                || existing.status() == ProposedPack.Status.INGESTED) {
            return Optional.of(existing);
        }
        var updated = existing.withEnrichment(enrichedSummary, enrichedSources);
        proposals.put(id, updated);
        save();
        return Optional.of(updated);
    }

    public Optional<ProposedPack> get(String id) {
        return Optional.ofNullable(proposals.get(id));
    }

    public List<ProposedPack> list() {
        var out = new ArrayList<>(proposals.values());
        out.sort(Comparator.comparing(ProposedPack::proposedAt).reversed());
        return List.copyOf(out);
    }

    public List<ProposedPack> pending() {
        return list().stream()
            .filter(p -> p.status() == ProposedPack.Status.PENDING)
            .toList();
    }

    public List<ProposedPack> approved() {
        return list().stream()
            .filter(p -> p.status() == ProposedPack.Status.APPROVED)
            .toList();
    }

    public boolean remove(String id) {
        var removed = proposals.remove(id) != null;
        if (removed) save();
        return removed;
    }

    public int size() {
        return proposals.size();
    }
}
