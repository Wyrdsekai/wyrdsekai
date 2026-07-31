package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.core.crypto.PrivateJournalCipher;
import org.wyrdsekai.core.home.HomeClient;
import org.wyrdsekai.core.search.SearchCollections;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing a user's private Study content:
 * journal entries, documents, pinboard references, notes, voice memo transcripts.
 *
 * All content is stored in the per-user Study Lucene collection, filtered by user DID.
 * Private journal entries are marked with item_type "journal_private" — the companion
 * respects this by never reading items of that type.
 *
 * <p>Collection-level consent (which companions can read which collections) is
 * delegated to {@link HomeClient} — i.e. backed by the unified Grant model
 * In tests without a running HomeRegistryActor, pass
 * {@code null}; collection access defaults to the shared journal only.</p>
 */
public final class StudyService {

    private static final Logger log = LoggerFactory.getLogger(StudyService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WyrdLuceneStore luceneStore;
    private final HomeClient homeClient;  // nullable — tests may omit

    // this server's vector-clock slot key. Every write on
    // the server ticks THIS slot so phones can tell server-authored items apart
    // from their own. Defaults to "server"; the Between study-sync peer sets it to
    // the node identity so multiple zones stay distinct. See VectorClock.
    private volatile String serverDeviceId = "server";

    public StudyService(WyrdLuceneStore luceneStore) {
        this(luceneStore, null);
    }

    public StudyService(WyrdLuceneStore luceneStore, HomeClient homeClient) {
        this.luceneStore = luceneStore;
        this.homeClient = homeClient;
    }

    /** Set the vector-clock slot key for server-authored writes (node id). */
    public void setServerDeviceId(String id) {
        if (id != null && !id.isBlank()) this.serverDeviceId = id;
    }

    // --- Vector clock (CRDT sync) helpers ---

    /** Parse a stored {@code vector_clock} JSON string into a mutable clock map. */
    private static Map<String, Integer> parseClock(Object vectorClockJson) {
        if (!(vectorClockJson instanceof String s) || s.isBlank()) return new HashMap<>();
        try {
            Map<String, Integer> m = MAPPER.readValue(s,
                MAPPER.getTypeFactory().constructMapType(HashMap.class, String.class, Integer.class));
            return m != null ? m : new HashMap<>();
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /** Read an item's clock from its Lucene metadata (empty if absent). */
    private static Map<String, Integer> clockFromMeta(Map<String, Object> meta) {
        return meta == null ? new HashMap<>() : parseClock(meta.get("vector_clock"));
    }

    private static String clockToJson(Map<String, Integer> clock) {
        try {
            return MAPPER.writeValueAsString(clock);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** The clock a fresh server-authored item gets: {serverDeviceId: 1}. */
    private String newItemClockJson() {
        return clockToJson(VectorClock.tick(new HashMap<>(), serverDeviceId));
    }

    // --- Version Tracking ---

    /**
     * Edit an existing Study item. Creates a versioned copy of the old content
     * and updates the current item with new content and incremented version.
     *
     * @param itemId     ID of the item to edit
     * @param userDid    Owner's DID (must match)
     * @param newContent Updated content
     * @return The new version number, or -1 if item not found
     */
    public int editItem(String itemId, String userDid, String newContent) {
        // Look up by exact ID
        var current = luceneStore.getById(
            SearchCollections.STUDY, itemId);
        if (current == null) {
            log.warn("[Study] Edit failed — item not found: {}", itemId);
            return -1;
        }
        var meta = current.metadata();
        int currentVersion = 1;
        if (meta != null && meta.containsKey("version")) {
            var versionObj = meta.get("version");
            if (versionObj instanceof Number n) currentVersion = n.intValue();
            else if (versionObj instanceof String s) {
                try { currentVersion = Integer.parseInt(s); } catch (NumberFormatException ignored) {}
            }
        }
        String itemType = meta != null ? (String) meta.getOrDefault("item_type", "document") : "document";
        String title = meta != null ? (String) meta.getOrDefault("title", "") : "";
        String collection = meta != null ? (String) meta.getOrDefault("collection", "default") : "default";

        // Archive the old version with a versioned ID
        String archiveId = itemId + "_v" + currentVersion;
        long timestamp = Instant.now().toEpochMilli();
        if (meta != null && meta.containsKey("timestamp")) {
            var tsObj = meta.get("timestamp");
            if (tsObj instanceof Number n) timestamp = n.longValue();
            else if (tsObj instanceof String s) {
                try { timestamp = Long.parseLong(s); } catch (NumberFormatException ignored) {}
            }
        }
        luceneStore.insertStudyItem(archiveId, userDid, itemType + "_archived", title,
            current.content(), collection, timestamp, currentVersion, null);

        // Update the current item with new content and incremented version.
        // Tick THIS server's vector-clock slot so the edit propagates to phones as
        // strictly newer than what they hold (unless they edited concurrently).
        int newVersion = currentVersion + 1;
        var editedClock = clockToJson(VectorClock.tick(clockFromMeta(meta), serverDeviceId));
        luceneStore.insertStudyItem(itemId, userDid, itemType, title,
            newContent, collection, Instant.now().toEpochMilli(), newVersion, null,
            editedClock, serverDeviceId, false);
        luceneStore.commitAll();

        log.info("[Study] Item {} edited: v{} → v{}", itemId, currentVersion, newVersion);
        return newVersion;
    }

    /**
     * Get version history for an item.
     *
     * @param itemId  Base item ID (without version suffix)
     * @param userDid Owner's DID
     * @return List of all versions (current + archived), most recent first
     */
    public List<WyrdLuceneStore.SearchResult> getVersionHistory(String itemId, String userDid, int maxVersions) {
        // Search for the current version and archived versions
        var results = luceneStore.searchStudy(userDid, itemId, maxVersions + 1);
        return results.stream()
            .filter(r -> r.id() != null && (r.id().equals(itemId) || r.id().startsWith(itemId + "_v")))
            .toList();
    }

    // --- Journal ---

    /**
     * Write a shared journal entry (companion can read).
     */
    public String writeJournalEntry(String userDid, String content) {
        var id = "journal:" + userDid + ":" + System.currentTimeMillis();
        var title = content.length() > 60 ? content.substring(0, 60) + "..." : content;
        luceneStore.insertStudyItem(id, userDid, "journal", title, content,
            "journal", Instant.now().toEpochMilli(), 1, null,
            newItemClockJson(), serverDeviceId, false);
        luceneStore.commitAll();
        log.info("[Study] Journal entry written for {} ({} chars, shared)", userDid, content.length());
        return id;
    }

    /**
     * Write a private journal entry (companion CANNOT read).
     * Content is stored with item_type "journal_private", AES-256-GCM
     * encrypted at rest under the owner's zone-derived key (0.5a) — a copied
     * database/index/backup no longer exposes private entries. Fail-closed:
     * if the zone master is unavailable the write throws rather than storing
     * plaintext silently. Trade-off, accepted: private entries are no longer
     * full-text searchable (the index holds ciphertext); they still list and
     * decrypt on the owner's own read paths.
     */
    public String writePrivateJournalEntry(String userDid, String content) {
        var id = "journal_private:" + userDid + ":" + System.currentTimeMillis();
        var title = "(private entry)";  // Don't reveal content in title
        var sealed = PrivateJournalCipher.encrypt(userDid, content);
        luceneStore.insertStudyItem(id, userDid, "journal_private", title, sealed,
            "journal", Instant.now().toEpochMilli(), 1, null,
            newItemClockJson(), serverDeviceId, false);
        luceneStore.commitAll();
        log.info("[Study] Private journal entry written for {} ({} chars, encrypted at rest)",
            userDid, content.length());
        return id;
    }

    /**
     * Search journal entries (shared only — private entries excluded for companion access).
     */
    public List<WyrdLuceneStore.SearchResult> searchJournal(String userDid, String query, int limit) {
        return luceneStore.searchStudyByType(userDid, "journal", query, limit);
    }

    /**
     * Search all journal entries including private (for the user themselves).
     */
    public List<WyrdLuceneStore.SearchResult> searchAllJournal(String userDid, String query, int limit) {
        // Search both journal and journal_private. Private entries are
        // encrypted at rest (0.5a): the text query cannot match their
        // ciphertext, so ALSO list recent private entries and match on the
        // decrypted content here — the owner's own search keeps working.
        var shared = luceneStore.searchStudyByType(userDid, "journal", query, limit);
        var combined = new ArrayList<>(shared);
        for (var r : luceneStore.searchStudyByType(userDid, "journal_private", query, limit)) {
            combined.add(decryptResult(userDid, r));
        }
        var tokens = query == null ? new String[0] : query.toLowerCase().split("\\s+");
        for (var r : luceneStore.listStudyByTypeRecent(userDid, "journal_private", Math.max(limit, 20))) {
            var open = decryptResult(userDid, r);
            var haystack = open.content() == null ? "" : open.content().toLowerCase();
            boolean allTokensHit = tokens.length > 0;
            for (var token : tokens) {
                if (!haystack.contains(token)) { allTokensHit = false; break; }
            }
            if (allTokensHit && combined.stream().noneMatch(c -> c.id().equals(open.id()))) {
                combined.add(open);
            }
        }
        combined.sort(Comparator.comparing(WyrdLuceneStore.SearchResult::score).reversed());
        return combined.size() > limit ? combined.subList(0, limit) : combined;
    }

    /** Decrypt a journal_private result's content for the OWNER's read paths. */
    private static WyrdLuceneStore.SearchResult decryptResult(String userDid,
                                                              WyrdLuceneStore.SearchResult r) {
        if (r == null || !PrivateJournalCipher.isEncrypted(r.content())) return r;
        return new WyrdLuceneStore.SearchResult(r.id(),
            PrivateJournalCipher.decryptIfNeeded(userDid, r.content()),
            r.source(), r.metadata(), r.score());
    }

    /**
     * List recent journal entries (shared only).
     */
    public List<WyrdLuceneStore.SearchResult> recentJournal(String userDid, int limit) {
        return luceneStore.listJournal(userDid, limit);
    }

    // --- Documents ---

    /**
     * Index a document chunk into the user's Study. The ID is derived from
     * the content hash — deterministic, so re-indexing the same text upserts
     * instead of duplicating. Prefer {@link #indexDocumentChunk} when a
     * source file + chunk index are known (file-level idempotency even when
     * the text changes).
     */
    public void indexDocument(String userDid, String collection, String title,
                               String content, String sourceFile) {
        var discriminator = sourceFile != null && !sourceFile.isBlank()
            ? sourceFile + "#h" + Integer.toHexString(content == null ? 0 : content.hashCode())
            : "h" + Integer.toHexString(content == null ? 0 : content.hashCode());
        insertChunk(userDid, collection, title, content, discriminator);
    }

    /**
     * Index one chunk of a source file with a DETERMINISTIC id derived from
     * (user, collection, sourceFile, chunkIndex). Re-running the same ingest
     * upserts in place — no duplicates, crash-safe resume. (The previous
     * timestamp-based ids collided within a millisecond and silently
     * overwrote sibling chunks.)
     */
    public void indexDocumentChunk(String userDid, String collection, String title,
                                    String content, String sourceFile, int chunkIndex) {
        insertChunk(userDid, collection, title, content, sourceFile + "#" + chunkIndex);
    }

    /** True when the chunk for (sourceFile, chunkIndex) is already indexed. */
    public boolean hasDocumentChunk(String userDid, String collection,
                                     String sourceFile, int chunkIndex) {
        return luceneStore.getById(SearchCollections.STUDY,
            documentChunkId(userDid, collection, sourceFile + "#" + chunkIndex)) != null;
    }

    private void insertChunk(String userDid, String collection, String title,
                              String content, String discriminator) {
        var id = documentChunkId(userDid, collection, discriminator);
        luceneStore.insertStudyItem(id, userDid, "document", title, content,
            collection, Instant.now().toEpochMilli(), 1, null);
    }

    /** Stable chunk id: doc:&lt;user&gt;:&lt;collection&gt;:&lt;sha256(discriminator)[:16]&gt;. */
    static String documentChunkId(String userDid, String collection, String discriminator) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                .digest(discriminator.getBytes(StandardCharsets.UTF_8));
            var hex = HexFormat.of().formatHex(digest, 0, 8);
            return "doc:" + userDid + ":" + collection + ":" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Commit after bulk document indexing.
     */
    public void commitDocuments() {
        luceneStore.commitAll();
    }

    /**
     * Search documents in a specific collection.
     */
    public List<WyrdLuceneStore.SearchResult> searchDocuments(String userDid, String collection,
                                                                String query, int limit) {
        return luceneStore.searchStudyByCollection(userDid, collection, query, limit);
    }

    /**
     * Search all documents across all collections.
     */
    public List<WyrdLuceneStore.SearchResult> searchAllDocuments(String userDid, String query, int limit) {
        return luceneStore.searchStudyByType(userDid, "document", query, limit);
    }

    // --- Pinboard ---

    /**
     * Pin a reference from the public Library.
     */
    public String pin(String userDid, String title, String knowledgeChunkId, String snippet) {
        var id = "pin:" + userDid + ":" + System.currentTimeMillis();
        var content = "PIN: " + title + "\nRef: " + knowledgeChunkId + "\n" + snippet;
        luceneStore.insertStudyItem(id, userDid, "pinboard", title, content,
            "pinboard", Instant.now().toEpochMilli(), 1, null,
            newItemClockJson(), serverDeviceId, false);
        luceneStore.commitAll();
        log.info("[Study] Pinned '{}' for {}", title, userDid);
        return id;
    }

    /**
     * List pinned items.
     */
    public List<WyrdLuceneStore.SearchResult> listPins(String userDid, int limit) {
        return luceneStore.searchStudyByType(userDid, "pinboard", "PIN", limit);
    }

    // --- Notes / Voice Memos ---

    /**
     * Add a quick note (from phone or in-world).
     */
    public String addNote(String userDid, String content) {
        var id = "note:" + userDid + ":" + System.currentTimeMillis();
        var title = content.length() > 60 ? content.substring(0, 60) + "..." : content;
        luceneStore.insertStudyItem(id, userDid, "note", title, content,
            "notes", Instant.now().toEpochMilli(), 1, null,
            newItemClockJson(), serverDeviceId, false);
        luceneStore.commitAll();
        return id;
    }

    /**
     * author-only note deletion.
     * Returns {@code {ok:true,id}} on success,
     * {@code {ok:false, reason:"not_owner"}} if the requester isn't the
     * note's owner, or {@code {ok:false, reason:"not_found"}} when the id
     * doesn't exist.
     */
    public Map<String, Object> deleteNote(String noteId, String requesterDid) {
        if (noteId == null || noteId.isBlank() || requesterDid == null) {
            return Map.of("ok", false, "reason", "invalid_args");
        }
        var existing = luceneStore.getById(
            SearchCollections.STUDY, noteId);
        if (existing == null) {
            return Map.of("ok", false, "reason", "not_found");
        }
        var meta = existing.metadata();
        var owner = meta != null ? String.valueOf(meta.getOrDefault("user_did", "")) : "";
        if (!requesterDid.equals(owner)) {
            log.info("[Study] deleteNote denied: {} is not owner ({}) of {}",
                requesterDid, owner, noteId);
            return Map.of("ok", false, "reason", "not_owner");
        }
        var deleted = luceneStore.deletePublicById(
            SearchCollections.STUDY, noteId);
        log.info("[Study] note {} deleted ({} docs)", noteId, deleted);
        return deleted > 0
            ? Map.of("ok", true, "id", noteId)
            : Map.of("ok", false, "reason", "not_found");
    }

    /**
     * author-only pin removal.
     * Same shape as {@link #deleteNote}.
     */
    public Map<String, Object> unpin(String pinId, String requesterDid) {
        if (pinId == null || pinId.isBlank() || requesterDid == null) {
            return Map.of("ok", false, "reason", "invalid_args");
        }
        var existing = luceneStore.getById(
            SearchCollections.STUDY, pinId);
        if (existing == null) {
            return Map.of("ok", false, "reason", "not_found");
        }
        var meta = existing.metadata();
        var owner = meta != null ? String.valueOf(meta.getOrDefault("user_did", "")) : "";
        if (!requesterDid.equals(owner)) {
            log.info("[Study] unpin denied: {} is not owner ({}) of {}",
                requesterDid, owner, pinId);
            return Map.of("ok", false, "reason", "not_owner");
        }
        var deleted = luceneStore.deletePublicById(
            SearchCollections.STUDY, pinId);
        log.info("[Study] pin {} removed ({} docs)", pinId, deleted);
        return deleted > 0
            ? Map.of("ok", true, "id", pinId)
            : Map.of("ok", false, "reason", "not_found");
    }

    /**
     * Add a voice memo transcript.
     */
    public String addVoiceMemo(String userDid, String transcript, String audioFile) {
        var id = "voice:" + userDid + ":" + System.currentTimeMillis();
        var title = "Voice memo" + (audioFile != null ? " (" + audioFile + ")" : "");
        luceneStore.insertStudyItem(id, userDid, "voice_memo", title, transcript,
            "voice_memos", Instant.now().toEpochMilli(), 1, null);
        luceneStore.commitAll();
        log.info("[Study] Voice memo indexed for {} ({} chars)", userDid, transcript.length());
        return id;
    }

    // --- Search across all Study content ---

    /**
     * Search everything in the user's Study.
     */
    public List<WyrdLuceneStore.SearchResult> searchAll(String userDid, String query, int limit) {
        return luceneStore.searchStudy(userDid, query, limit);
    }

    // --- Shared Shelves ---

    /**
     * Shared shelf grants: collections shared with specific users.
     * Key: "ownerDid:collection:grantedDid" → granted timestamp.
     */
    private final Map<String, Instant> shelfShares = new ConcurrentHashMap<>();

    /**
     * Share a collection with another user.
     */
    public void shareCollection(String ownerDid, String collection, String targetDid) {
        var key = ownerDid + ":" + collection + ":" + targetDid;
        shelfShares.put(key, Instant.now());
        log.info("[Study] Shared shelf: {} shared '{}' with {}", ownerDid, collection, targetDid);
    }

    /**
     * Unshare a collection.
     */
    public void unshareCollection(String ownerDid, String collection, String targetDid) {
        var key = ownerDid + ":" + collection + ":" + targetDid;
        shelfShares.remove(key);
        log.info("[Study] Unshared shelf: {} unshared '{}' from {}", ownerDid, collection, targetDid);
    }

    /**
     * Check if a user has access to another user's collection.
     */
    public boolean hasSharedAccess(String ownerDid, String collection, String requesterDid) {
        if (ownerDid.equals(requesterDid)) return true; // Owner always has access
        var key = ownerDid + ":" + collection + ":" + requesterDid;
        return shelfShares.containsKey(key);
    }

    /**
     * Search a shared collection as another user.
     */
    public List<WyrdLuceneStore.SearchResult> searchSharedCollection(String ownerDid, String collection,
                                                                        String requesterDid, String query, int limit) {
        if (!hasSharedAccess(ownerDid, collection, requesterDid)) {
            log.warn("[Study] Access denied: {} tried to search {}/{}", requesterDid, ownerDid, collection);
            return List.of();
        }
        return searchDocuments(ownerDid, collection, query, limit);
    }

    /**
     * List all shared collections for a user (what they've shared with others).
     */
    public Map<String, String> listShares(String ownerDid) {
        var shares = new LinkedHashMap<String, String>();
        shelfShares.forEach((key, time) -> {
            if (key.startsWith(ownerDid + ":")) {
                var rest = key.substring(ownerDid.length() + 1);
                shares.put(rest, time.toString());
            }
        });
        return shares;
    }

    // --- Agent Consent (per-collection access control via Grant model, ) ---

    /**
     * Resource URI for a user's collection. A companion's {@code read} on this
     * URI is the single authorization fact for "can this companion search this
     * collection?". Default grants are not stored — the shared journal always
     * reads via {@link #hasAccess} short-circuit.
     */
    private static ResourceUri collectionResource(String userDid, String collection) {
        return ResourceUri.of(userDid, ResourceTypeRegistry.COLLECTION, collection);
    }

    /**
     * Grant a companion {@code read} access to a specific collection.
     * Issues a Grant via {@link HomeClient}; replaces any prior identical
     * active grant so idempotent re-grants don't accumulate rows.
     */
    public void grantAccess(String userDid, String companionId, String collection) {
        if (homeClient == null) {
            throw new IllegalStateException(
                "StudyService has no HomeClient — cannot issue collection grant");
        }
        var resource = collectionResource(userDid, collection);
        homeClient.issueOrReplace(userDid, companionId, resource, Capability.read,
            Map.of(), null, "collection consent");
        log.info("[Study] Access granted: {} can read {}/{}", companionId, userDid, collection);
    }

    /**
     * Revoke a companion's access to a collection.
     */
    public void revokeAccess(String userDid, String companionId, String collection) {
        if (homeClient == null) return;  // nothing to revoke
        var resource = collectionResource(userDid, collection);
        var revoked = homeClient.revokeByKey(userDid, companionId, resource, Capability.read);
        if (revoked) {
            log.info("[Study] Access revoked: {} can no longer read {}/{}", companionId, userDid, collection);
        }
    }

    /**
     * Check if a companion has access to a collection.
     * Default grants: shared journal entries are always readable (built-in).
     */
    public boolean hasAccess(String userDid, String companionId, String collection) {
        // Shared journal is always accessible to the user's companion.
        if ("journal".equals(collection)) return true;
        if (homeClient == null) return false;
        var resource = collectionResource(userDid, collection);
        return homeClient.check(companionId, resource, Capability.read, Map.of());
    }

    /**
     * List all collection grants for a user, keyed as "companion:collection" for
     * API-shape compatibility with the prior in-memory implementation.
     */
    public Map<String, Instant> listGrants(String userDid) {
        var result = new LinkedHashMap<String, Instant>();
        if (homeClient == null) return result;
        var now = Instant.now();
        for (var g : homeClient.listIssuedBy(userDid)) {
            if (!g.isActive(now)) continue;
            if (g.capability() != Capability.read) continue;
            if (!ResourceTypeRegistry.COLLECTION.equals(g.resource().type())) continue;
            var key = g.subject() + ":" + g.resource().id();
            result.put(key, g.issuedAt());
        }
        return result;
    }

    /**
     * Search documents but only in collections the companion has access to.
     */
    public List<WyrdLuceneStore.SearchResult> searchAsCompanion(String userDid, String companionId,
                                                                   String query, int limit) {
        var allResults = searchAll(userDid, query, limit * 2);
        return allResults.stream()
            .filter(r -> {
                var meta = r.metadata();
                if (meta == null) return false;
                var type = (String) meta.getOrDefault("item_type", "");
                var collection = (String) meta.getOrDefault("collection", "default");
                // Never show private journal entries to companion
                if ("journal_private".equals(type)) return false;
                // Check collection-level access
                return hasAccess(userDid, companionId, collection);
            })
            .limit(limit)
            .toList();
    }

    // --- Import / Export ---

    /**
     * Export a Study collection as a knowledge pack (OPDS-K format).
     * Creates a directory with pack.json + chunks/data.jsonl.
     *
     * @param userDid    Owner's DID
     * @param collection Collection to export
     * @param outputDir  Target directory
     * @return Number of items exported
     */
    public int exportCollection(String userDid, String collection, Path outputDir) throws IOException {
        // Search with a broad query to get all items in this collection
        var items = searchDocuments(userDid, collection, "content", 10000);
        if (items.isEmpty()) return 0;

        Files.createDirectories(outputDir.resolve("chunks"));
        var mapper = new ObjectMapper();

        // Write pack.json
        var pack = new KnowledgePack(
            "study-export-" + collection, collection + " (exported)",
            userDid, List.of(), "Exported from Study", null, null,
            "en", "private", "private", "general",
            Map.of(), null, Map.of("items", String.valueOf(items.size())),
            null, null, List.of("knowledge"), List.of(), "study-export"
        );
        mapper.writerWithDefaultPrettyPrinter()
            .writeValue(outputDir.resolve("pack.json").toFile(), pack);

        // Write chunks
        try (var writer = new BufferedWriter(
                new FileWriter(outputDir.resolve("chunks/data.jsonl").toFile()))) {
            int count = 0;
            for (var item : items) {
                var meta = item.metadata();
                var chunk = new KnowledgeChunk(
                    item.id(), "study-export-" + collection,
                    meta != null ? (String) meta.getOrDefault("title", "") : "",
                    item.content(),
                    "Study export", null, "private", null, null);
                writer.write(mapper.writeValueAsString(chunk));
                writer.newLine();
                count++;
            }
            log.info("[Study] Exported collection '{}' for {}: {} items", collection, userDid, count);
            return count;
        }
    }

    /**
     * Import a knowledge pack into a Study collection.
     * Uses KnowledgePackIndexer to read the pack, then re-indexes as Study items.
     *
     * @param userDid    Owner's DID
     * @param collection Target collection name
     * @param packDir    Directory containing pack.json + chunks/
     * @return Number of items imported
     */
    public int importCollection(String userDid, String collection, Path packDir) throws IOException {
        var chunksDir = packDir.resolve("chunks");
        if (!Files.isDirectory(chunksDir)) return 0;

        var mapper = new ObjectMapper();
        int count = 0;

        try (var files = Files.list(chunksDir)) {
            for (var jsonlFile : files.filter(f -> f.toString().endsWith(".jsonl")).toList()) {
                try (var reader = new BufferedReader(new FileReader(jsonlFile.toFile()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        try {
                            var chunk = mapper.readValue(line, KnowledgeChunk.class);
                            indexDocument(userDid, collection,
                                chunk.title() != null ? chunk.title() : "",
                                chunk.content(), chunk.source());
                            count++;
                        } catch (Exception e) {
                            // Skip malformed lines
                        }
                    }
                }
            }
        }

        commitDocuments();
        log.info("[Study] Imported {} items into collection '{}' for {}", count, collection, userDid);
        return count;
    }

    // --- Storage Monitoring ---

    /**
     * Get disk usage estimate for a user's Study content.
     * Approximate: count items × average chunk size.
     */
    public Map<String, Object> getDiskUsage(String userDid) {
        long totalItems = luceneStore.countStudyItems(userDid);
        // Rough estimate: 500 bytes per indexed item (text + metadata)
        long estimatedBytes = totalItems * 500;
        return Map.of(
            "totalItems", totalItems,
            "estimatedBytes", estimatedBytes,
            "estimatedMB", estimatedBytes / (1024 * 1024),
            "userDid", userDid
        );
    }

    // --- Management ---

    /**
     * Get stats for a user's Study.
     */
    public Map<String, Object> getStats(String userDid) {
        long total = luceneStore.countStudyItems(userDid);
        return Map.of(
            "totalItems", total,
            "userDid", userDid
        );
    }

    /**
     * Delete a specific collection from the user's Study.
     */
    public long deleteCollection(String userDid, String collection) {
        long deleted = luceneStore.deleteStudyCollection(userDid, collection);
        log.info("[Study] Deleted collection '{}' for {}: {} items", collection, userDid, deleted);
        return deleted;
    }

    /**
     * Delete ALL Study content for a user (right to be forgotten / GDPR).
     */
    public long purgeAll(String userDid) {
        long deleted = luceneStore.deleteStudyByUser(userDid);
        log.info("[Study] Purged all Study content for {}: {} items", userDid, deleted);
        return deleted;
    }

    // --- Phone Sync ---

    // --- Vector-clock CRDT sync (the Between study-sync peer's data plane) ---
    // (The legacy timestamp-LWW getDeltaSince + /api/study/sync HTTP route were
    //  deleted pre-OSS: zero callers, and the POST merged by body-supplied userDid.)

    /**
     * Per-slot max of every item's vector clock — this server's {@code study_state}
     * advertisement. A phone whose summary is behind ours requests a delta.
     */
    public Map<String, Integer> buildClockSummary(String userDid) {
        var summary = new HashMap<String, Integer>();
        for (var r : luceneStore.listAllStudy(userDid, 100_000)) {
            for (var e : clockFromMeta(r.metadata()).entrySet()) {
                summary.merge(e.getKey(), e.getValue(), Math::max);
            }
        }
        return summary;
    }

    /**
     * Items to send a peer whose clock summary is {@code peerSummary}: those whose
     * own clock is NOT dominated by (nor equal to) the peer's — i.e. the peer is
     * missing them or has an older/concurrent copy. Mirrors the clients'
     * {@code StudySyncLayer.sendDelta} filter (dominates || concurrent).
     */
    public List<StudyMergeItem> getDeltaForPeer(String userDid, Map<String, Integer> peerSummary) {
        var out = new ArrayList<StudyMergeItem>();
        var summary = peerSummary != null ? peerSummary : Map.<String, Integer>of();
        for (var r : luceneStore.listAllStudy(userDid, 100_000)) {
            var meta = r.metadata();
            var clock = clockFromMeta(meta);
            var rel = VectorClock.compare(clock, summary);
            if (rel == VectorClock.Relation.DOMINATES || rel == VectorClock.Relation.CONCURRENT) {
                // 0.5a — the sync channel is authenticated per-owner (the
                // study-sync peer refuses cross-user sessions), so the OWNER's
                // device receives private entries decrypted; at-rest stays
                // ciphertext on this node. Phone-local at-rest protection is
                // the device OS's (EncryptedSharedPrefs / Keychain) job.
                var content = "journal_private".equals(str(meta, "item_type", ""))
                    ? PrivateJournalCipher.decryptIfNeeded(userDid, r.content())
                    : r.content();
                out.add(toMergeItem(r.id(), userDid, content, meta, clock));
            }
        }
        return out;
    }

    /**
     * Merge items from a peer using vector-clock CRDT rules — the exact mirror of
     * the clients' {@code StudySyncLayer.mergeIncoming}:
     * <ul>
     *   <li>unknown id → insert (skip a tombstone we never had)</li>
     *   <li>remote DOMINATES local → fast-forward (apply content/clock, or delete on tombstone)</li>
     *   <li>CONCURRENT → keep local, count as an unresolved conflict</li>
     *   <li>DOMINATED / EQUAL → no-op</li>
     * </ul>
     * @return number of items applied (inserted or fast-forwarded)
     */
    public int mergeFromPeer(String userDid, List<StudyMergeItem> items) {
        if (items == null) return 0;
        int merged = 0;
        int conflicts = 0;
        for (var remote : items) {
            if (remote == null || remote.id() == null) continue;
            var local = luceneStore.getById(SearchCollections.STUDY, remote.id());
            if (local == null) {
                // A tombstone for an item we never had is a no-op.
                if (!remote.deleted()) { applyRemote(userDid, remote); merged++; }
                continue;
            }
            var rel = VectorClock.compare(remote.vectorClock(), clockFromMeta(local.metadata()));
            switch (rel) {
                case DOMINATES -> {
                    if (remote.deleted()) luceneStore.deleteStudyItem(remote.id());
                    else applyRemote(userDid, remote);
                    merged++;
                }
                case CONCURRENT -> conflicts++;   // keep local; surfaced as a conflict
                case DOMINATED, EQUAL -> { /* local already current */ }
            }
        }
        luceneStore.commitAll();
        log.info("[Study] Merged {} item(s) from peer for {} ({} conflict(s) kept local)",
            merged, userDid, conflicts);
        return merged;
    }

    /** Upsert a peer item verbatim — its own clock/version/tombstone, no server tick. */
    private void applyRemote(String userDid, StudyMergeItem remote) {
        // 0.5a — a private entry arriving from a device in plaintext is sealed
        // before it touches the index; already-sealed content passes through.
        var content = "journal_private".equals(remote.itemType())
            ? PrivateJournalCipher.encrypt(userDid, remote.content())
            : remote.content();
        luceneStore.insertStudyItem(
            remote.id(), userDid, remote.itemType(), remote.title(),
            content, remote.collection(), remote.timestamp(),
            Math.max(1, remote.version()), null,
            clockToJson(remote.vectorClock() != null ? remote.vectorClock() : new HashMap<>()),
            remote.lastModifiedBy(), remote.deleted());
    }

    /** Build a wire item from a stored Study SearchResult + its parsed clock. */
    private static StudyMergeItem toMergeItem(String id, String userDid, String content,
                                              Map<String, Object> meta, Map<String, Integer> clock) {
        String itemType = str(meta, "item_type", "document");
        String title = str(meta, "title", "");
        String collection = str(meta, "collection", "default");
        long ts = 0L;
        var tsObj = meta.get("timestamp");
        if (tsObj instanceof Number n) ts = n.longValue();
        else if (tsObj instanceof String s) { try { ts = Long.parseLong(s); } catch (NumberFormatException ignored) {} }
        int version = 1;
        var vObj = meta.get("version");
        if (vObj instanceof Number n) version = n.intValue();
        else if (vObj instanceof String s) { try { version = Integer.parseInt(s); } catch (NumberFormatException ignored) {} }
        boolean deleted = false;
        var dObj = meta.get("deleted");
        if (dObj instanceof Number n) deleted = n.intValue() != 0;
        else if (dObj instanceof String s) deleted = "1".equals(s) || "true".equalsIgnoreCase(s);
        String lastBy = str(meta, "last_modified_by", null);
        return new StudyMergeItem(id, userDid, itemType, title, content, collection,
            ts, version, clock, lastBy, deleted);
    }

    private static String str(Map<String, Object> meta, String key, String dflt) {
        var v = meta == null ? null : meta.get(key);
        return v instanceof String s ? s : dflt;
    }

    /**
     * A Study item on the sync wire. Field names match the clients' StudyItem
     * (camelCase) so Jackson (de)serialization round-trips through the Between
     * study-sync peer without a translation layer.
     */
    public record StudyMergeItem(
        String id,
        String userDid,
        String itemType,
        String title,
        String content,
        String collection,
        long timestamp,
        int version,
        Map<String, Integer> vectorClock,
        String lastModifiedBy,
        boolean deleted
    ) {}
}
