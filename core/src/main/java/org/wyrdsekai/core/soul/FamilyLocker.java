package org.wyrdsekai.core.soul;

import org.wyrdsekai.core.familiar.Familiar;
import org.wyrdsekai.core.familiar.NamedFamiliar;
import org.wyrdsekai.core.familiar.Provenance;
import org.wyrdsekai.core.familiar.ThoughtForm;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Content-addressed distributed storage for soul lineage (§95.3).
 *
 * Structure (logical):
 *   /{familyId}/
 *     items/          — SoulItems, content-addressed by SHA-256
 *     manifest.soul   — current manifest per bud DID
 *     lineage.json    — bud tree (parent→children)
 *     headlines.json  — latest headline per bud
 *     argot.dat       — family ArgotCodebook (encrypted)
 *
 * Properties:
 * - Content-addressed: items stored by SHA-256 hash
 * - Distributed: replicated across Between nodes (minimum 2 replicas)
 * - Append-mostly: items added, rarely deleted (tombstone pattern)
 * - Signed: every item signed by creating bud's Ed25519 key
 * - Family-scoped: only buds in the same family can read/write
 *
 * The locker is the shared memory of a soul lineage.
 * Each bud contributes items; all buds can read everything.
 * Deletion uses tombstones that propagate during sleep sync.
 */
public class FamilyLocker {

    /** Tombstone marking an item as deleted. */
    public record Tombstone(
        String itemHash,
        String deletedBy,
        Instant deletedAt,
        String reason
    ) {}

    /** Headline: a bud's latest status summary (~200 bytes, Tier 1 sync). */
    public record Headline(
        String budDid,
        String summary,
        double[] vitalitySnapshot,
        Instant timestamp,
        int itemCount
    ) {
        public static Headline create(String budDid, String summary,
                                       double[] vitality, int itemCount) {
            return new Headline(budDid, summary, vitality, Instant.now(), itemCount);
        }
    }

    /** Lineage node in the bud tree. */
    public record LineageNode(
        String budDid,
        String parentDid,
        Instant budTime,
        String status
    ) {}

    private final String familyId;
    private final String lockerAddress;
    private final Map<String, SoulItem> items = new ConcurrentHashMap<>();
    private final Map<String, Tombstone> tombstones = new ConcurrentHashMap<>();
    private final Map<String, SoulManifest> manifests = new ConcurrentHashMap<>();
    private final Map<String, Headline> headlines = new ConcurrentHashMap<>();
    private final Map<String, LineageNode> lineage = new ConcurrentHashMap<>();
    private final Set<String> authorizedDids = ConcurrentHashMap.newKeySet();

    // thought-form namespace.
    // Keyed by form id. Current version only; historical versions live in `thoughtFormHistory`.
    private final Map<String, ThoughtForm> thoughtForms = new ConcurrentHashMap<>();
    private final Map<String, List<ThoughtForm>> thoughtFormHistory = new ConcurrentHashMap<>();
    private final Map<String, RetiredForm> retiredThoughtForms = new ConcurrentHashMap<>();

    /** A retired thought form (§14). Soft-delete; un-retire within window. */
    public record RetiredForm(String formId, String retiredBy, Instant retiredAt, String note) {}

    // named-familiar namespace. Keyed by name, scoped per locker.
    private final Map<String, NamedFamiliar> namedFamiliars = new ConcurrentHashMap<>();

    /** Family cryptophasia codebook (argot.dat, §95.7). Grows as the family accretes shared
     *  significant items; used to compress + privatise Tier-1 headlines. Volatile single-writer
     *  via {@link #updateFamilyArgot} during sleep sync. */
    private volatile ArgotCodebook argotCodebook;

    public FamilyLocker(String familyId, String lockerAddress) {
        this.familyId = familyId;
        this.lockerAddress = lockerAddress;
        this.argotCodebook = ArgotCodebook.initial(familyId);
    }

    /** The family cryptophasia codebook (never null — seeded with meta codes at construction). */
    public ArgotCodebook familyArgot() {
        return argotCodebook;
    }

    /** Replace the family codebook with a grown version (called from sleep sync after learnFromItems). */
    public void updateFamilyArgot(ArgotCodebook codebook) {
        if (codebook != null) this.argotCodebook = codebook;
    }

    /** Create a locker and authorize the original bud. */
    public static FamilyLocker create(String familyId, String lockerAddress,
                                       SoulBud originalBud) {
        var locker = new FamilyLocker(familyId, lockerAddress);
        locker.authorizedDids.add(originalBud.did());
        locker.lineage.put(originalBud.did(), new LineageNode(
            originalBud.did(), null, originalBud.budTime(), originalBud.status()));
        return locker;
    }

    // --- Authorization ---

    /** Authorize a bud to access this locker (called when a child bud is created). */
    public void authorize(SoulBud bud) {
        if (!familyId.equals(bud.familyId())) {
            throw new IllegalArgumentException("Bud family " + bud.familyId() +
                " does not match locker family " + familyId);
        }
        authorizedDids.add(bud.did());
        lineage.put(bud.did(), new LineageNode(
            bud.did(), bud.parentDid(), bud.budTime(), bud.status()));
        // W5 Family Locker sync: an authorized locker is a live replica —
        // register it so remote items from household peers merge into it.
        // (Weak registration; no-op until the Between wires the transport.)
        LockerSyncHub.get().registerLocker(this, bud.did());
    }

    /** Revoke access (called during independence declaration). */
    public void revoke(String budDid) {
        authorizedDids.remove(budDid);
    }

    /** Check if a DID is authorized. */
    public boolean isAuthorized(String did) {
        return authorizedDids.contains(did);
    }

    // --- Item Operations ---

    /** Store a soul item. Verifies content integrity and authorization. */
    public void store(SoulItem item, String requesterDid) {
        requireAuthorized(requesterDid);
        if (!item.verifyIntegrity()) {
            throw new IllegalArgumentException("Item integrity check failed: " + item.hash());
        }
        if (tombstones.containsKey(item.hash())) {
            return; // Don't store tombstoned items
        }
        boolean isNew = items.put(item.hash(), item) == null;
        // W5 Family Locker sync: replicate new local writes across household
        // nodes. No-op until the Between installs the hub transport, and
        // mergeItems (the remote-apply path) bypasses store() — no loop.
        if (isNew) {
            LockerSyncHub.get().onLocalStore(familyId, item);
        }
    }

    /** Retrieve an item by hash. */
    public Optional<SoulItem> get(String hash, String requesterDid) {
        requireAuthorized(requesterDid);
        if (tombstones.containsKey(hash)) {
            return Optional.empty();
        }
        return Optional.ofNullable(items.get(hash));
    }

    /** List all items (excluding tombstoned). */
    public List<SoulItem> allItems(String requesterDid) {
        requireAuthorized(requesterDid);
        return items.values().stream()
            .filter(item -> !tombstones.containsKey(item.hash()))
            .toList();
    }

    /** List items by category. */
    public List<SoulItem> byCategory(String category, String requesterDid) {
        requireAuthorized(requesterDid);
        return items.values().stream()
            .filter(item -> !tombstones.containsKey(item.hash()))
            .filter(item -> category.equals(item.category()))
            .toList();
    }

    /** List items by creator. */
    public List<SoulItem> byCreator(String creatorDid, String requesterDid) {
        requireAuthorized(requesterDid);
        return items.values().stream()
            .filter(item -> !tombstones.containsKey(item.hash()))
            .filter(item -> creatorDid.equals(item.creatorDid()))
            .toList();
    }

    /** Items sorted by significance (highest first). */
    public List<SoulItem> bySignificance(String requesterDid, int limit) {
        requireAuthorized(requesterDid);
        return items.values().stream()
            .filter(item -> !tombstones.containsKey(item.hash()))
            .sorted(Comparator.comparingDouble(SoulItem::significance).reversed())
            .limit(limit)
            .toList();
    }

    // --- Tombstone Operations ---

    /** Delete an item via tombstone. Only the creator or any family member can tombstone. */
    public Tombstone tombstone(String itemHash, String requesterDid, String reason) {
        requireAuthorized(requesterDid);
        var ts = new Tombstone(itemHash, requesterDid, Instant.now(), reason);
        boolean isNew = tombstones.put(itemHash, ts) == null;
        // W5 Family Locker sync — mirror of store(): applyTombstones (the
        // remote-apply path) bypasses this method, so no gossip loop.
        if (isNew) {
            LockerSyncHub.get().onLocalTombstone(familyId, ts);
        }
        return ts;
    }

    /** Get all tombstones (for sync propagation). */
    public Collection<Tombstone> tombstones() {
        return Collections.unmodifiableCollection(tombstones.values());
    }

    /** Apply tombstones received from another bud's sync. */
    public int applyTombstones(Collection<Tombstone> incoming) {
        int applied = 0;
        for (var ts : incoming) {
            if (!tombstones.containsKey(ts.itemHash())) {
                tombstones.put(ts.itemHash(), ts);
                applied++;
            }
        }
        return applied;
    }

    // --- Manifest Operations ---

    /** Store a bud's manifest. */
    public void storeManifest(SoulManifest manifest, String requesterDid) {
        requireAuthorized(requesterDid);
        manifests.put(manifest.did(), manifest);
    }

    /** Get a bud's manifest. */
    public Optional<SoulManifest> manifest(String budDid, String requesterDid) {
        requireAuthorized(requesterDid);
        return Optional.ofNullable(manifests.get(budDid));
    }

    // --- Headline Operations ---

    /** Post a headline (Tier 1 sync — continuous, ~200 bytes). */
    public void postHeadline(Headline headline) {
        if (!isAuthorized(headline.budDid())) return;
        headlines.put(headline.budDid(), headline);
    }

    /** Get the latest headline for a bud. */
    public Optional<Headline> headline(String budDid) {
        return Optional.ofNullable(headlines.get(budDid));
    }

    /** Get all current headlines. */
    public Map<String, Headline> allHeadlines() {
        return Collections.unmodifiableMap(headlines);
    }

    // --- Lineage ---

    /** Get the full lineage tree. */
    public Map<String, LineageNode> lineage() {
        return Collections.unmodifiableMap(lineage);
    }

    /** Get children of a bud. */
    public List<LineageNode> childrenOf(String parentDid) {
        return lineage.values().stream()
            .filter(n -> parentDid.equals(n.parentDid()))
            .toList();
    }

    /** Get the root (original) bud. */
    public Optional<LineageNode> root() {
        return lineage.values().stream()
            .filter(n -> n.parentDid() == null)
            .findFirst();
    }

    // --- Sync Support ---

    /** Items created after a given timestamp (for incremental sync). */
    public List<SoulItem> itemsSince(Instant since, String requesterDid) {
        requireAuthorized(requesterDid);
        return items.values().stream()
            .filter(item -> !tombstones.containsKey(item.hash()))
            .filter(item -> item.created().isAfter(since))
            .toList();
    }

    /** Tombstones created after a given timestamp. */
    public List<Tombstone> tombstonesSince(Instant since) {
        return tombstones.values().stream()
            .filter(ts -> ts.deletedAt().isAfter(since))
            .toList();
    }

    /** Merge items from another locker replica (idempotent via content addressing). */
    public int mergeItems(Collection<SoulItem> incoming, String sourceDid) {
        requireAuthorized(sourceDid);
        int merged = 0;
        for (var item : incoming) {
            if (!items.containsKey(item.hash()) && !tombstones.containsKey(item.hash())) {
                if (item.verifyIntegrity()) {
                    items.put(item.hash(), item);
                    merged++;
                }
            }
        }
        return merged;
    }

    // --- Thought Forms ---

    /**
     * Store a freshly authored thought form. Requester must be the form's
     * original author (provenance root). The store is the place where
     * provenance-strip is rejected (§7.4 structural guarantee).
     */
    public void shapeThoughtForm(ThoughtForm form, String requesterDid) {
        requireAuthorized(requesterDid);
        if (form == null) throw new IllegalArgumentException("form required");
        if (!requesterDid.equals(form.provenance().originalAuthor())) {
            throw new SecurityException("requesterDid must match form's originalAuthor");
        }
        if (thoughtForms.containsKey(form.id())) {
            throw new IllegalStateException("form " + form.id() + " already exists; use reviseThoughtForm");
        }
        if (retiredThoughtForms.containsKey(form.id())) {
            throw new IllegalStateException("form " + form.id() + " is retired; use unretireThoughtForm first");
        }
        thoughtForms.put(form.id(), form);
        thoughtFormHistory.put(form.id(), List.of(form));
    }

    /**
     * Accept a form copied from another agent.
     *
     * <p>Distinct from {@link #shapeThoughtForm} which rejects forms whose
     * originalAuthor differs from the requester. Accept-copy is the sanctioned
     * variant for forks: requesterDid must be the most recent editor in the
     * provenance chain, and that edit must be a {@code COPIED_FROM} action.</p>
     */
    public void acceptCopy(ThoughtForm form, String requesterDid) {
        requireAuthorized(requesterDid);
        if (form == null) throw new IllegalArgumentException("form required");
        var lineage = form.provenance().lineage();
        if (lineage.isEmpty()) {
            throw new SecurityException("copy has empty provenance — cannot accept");
        }
        var lastEdit = lineage.get(lineage.size() - 1);
        if (lastEdit.action() != Provenance.Action.COPIED_FROM) {
            throw new SecurityException(
                "acceptCopy requires last provenance edit to be COPIED_FROM, got "
                    + lastEdit.action());
        }
        if (!requesterDid.equals(lastEdit.agent())) {
            throw new SecurityException(
                "acceptCopy requires last-edit agent to match requester");
        }
        if (thoughtForms.containsKey(form.id())) {
            throw new IllegalStateException("form " + form.id() + " already in this locker");
        }
        thoughtForms.put(form.id(), form);
        thoughtFormHistory.put(form.id(), List.of(form));
    }

    /**
     * Replace a form's current version with a revision, archiving the prior
     * version in history. The revision must preserve the original author and
     * append at least one REVISED edit, authored by the requester.
     */
    public ThoughtForm reviseThoughtForm(String formId, ThoughtForm revised, String requesterDid) {
        requireAuthorized(requesterDid);
        if (revised == null) throw new IllegalArgumentException("revised required");
        if (!formId.equals(revised.id())) {
            throw new IllegalArgumentException("revised.id must match formId");
        }
        var existing = thoughtForms.get(formId);
        if (existing == null) {
            throw new NoSuchElementException("no form " + formId);
        }
        if (retiredThoughtForms.containsKey(formId)) {
            throw new IllegalStateException("form " + formId + " is retired");
        }
        if (!existing.provenance().originalAuthor().equals(revised.provenance().originalAuthor())) {
            throw new SecurityException("revised form changed originalAuthor");
        }
        if (revised.provenance().lineage().size() <= existing.provenance().lineage().size()) {
            throw new SecurityException("revised form must append to provenance lineage");
        }
        var lastEdit = revised.provenance().lineage().get(revised.provenance().lineage().size() - 1);
        if (!requesterDid.equals(lastEdit.agent())) {
            throw new SecurityException("last provenance edit must be authored by requester");
        }
        thoughtForms.put(formId, revised);
        var priorHist = thoughtFormHistory.getOrDefault(formId, List.of());
        var nextHist = new ArrayList<>(priorHist);
        nextHist.add(revised);
        thoughtFormHistory.put(formId, List.copyOf(nextHist));
        return revised;
    }

    /**
     * Retire a form (§14). Soft-delete: form remains in history and is un-retirable
     * within the agent's chosen window. Appends a RETIRED edit to provenance.
     */
    public RetiredForm retireThoughtForm(String formId, String requesterDid, String note) {
        requireAuthorized(requesterDid);
        var existing = thoughtForms.get(formId);
        if (existing == null) throw new NoSuchElementException("no form " + formId);
        if (retiredThoughtForms.containsKey(formId)) {
            throw new IllegalStateException("form " + formId + " already retired");
        }
        var retiredProv = existing.provenance().append(
            new Provenance.Edit(requesterDid, Provenance.Action.RETIRED, Instant.now(), note));
        var retiredForm = new ThoughtForm(existing.id(), existing.name(), existing.version(),
            retiredProv, existing.systemPrompt(), existing.toolSurface(),
            existing.defaultTanks(), existing.maxTanks(), existing.maxTrials(),
            existing.maxNestDepth(), existing.evalCriteria(),
            existing.createdAt(), Instant.now(),
            existing.summonCount(), existing.successCount(), existing.failureCount(),
            existing.bondCharge());
        thoughtForms.put(formId, retiredForm);
        var priorHist = thoughtFormHistory.getOrDefault(formId, List.of());
        var nextHist = new ArrayList<>(priorHist);
        nextHist.add(retiredForm);
        thoughtFormHistory.put(formId, List.copyOf(nextHist));
        var ts = new RetiredForm(formId, requesterDid, Instant.now(), note);
        retiredThoughtForms.put(formId, ts);
        return ts;
    }

    /** Un-retire a previously retired form (§14 restoration window). */
    public ThoughtForm unretireThoughtForm(String formId, String requesterDid) {
        requireAuthorized(requesterDid);
        if (!retiredThoughtForms.containsKey(formId)) {
            throw new IllegalStateException("form " + formId + " is not retired");
        }
        retiredThoughtForms.remove(formId);
        return thoughtForms.get(formId);
    }

    /** Look up a form by id. Returns empty for retired forms unless includeRetired. */
    public Optional<ThoughtForm> thoughtForm(String formId, String requesterDid, boolean includeRetired) {
        requireAuthorized(requesterDid);
        if (!includeRetired && retiredThoughtForms.containsKey(formId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(thoughtForms.get(formId));
    }

    /**
     * Look up a form by agent-chosen name. If multiple forms share a name,
     * returns the most recently revised non-retired one.
     */
    public Optional<ThoughtForm> thoughtFormByName(String name, String requesterDid) {
        requireAuthorized(requesterDid);
        return thoughtForms.values().stream()
            .filter(f -> !retiredThoughtForms.containsKey(f.id()))
            .filter(f -> name.equals(f.name()))
            .max(Comparator.comparing(ThoughtForm::revisedAt));
    }

    /** List all thought forms visible to the requester. */
    public List<ThoughtForm> listThoughtForms(String requesterDid, boolean includeRetired) {
        requireAuthorized(requesterDid);
        return thoughtForms.values().stream()
            .filter(f -> includeRetired || !retiredThoughtForms.containsKey(f.id()))
            .sorted(Comparator.comparing(ThoughtForm::revisedAt).reversed())
            .toList();
    }

    /** Full version history for a form, oldest first. */
    public List<ThoughtForm> thoughtFormHistory(String formId, String requesterDid) {
        requireAuthorized(requesterDid);
        return thoughtFormHistory.getOrDefault(formId, List.of());
    }

    /**
     * Record a summon — usage statistic used by Forge (§12.2). Increments the
     * form's summonCount; outcomeSucceeded/Failed counters are bumped via
     * recordFormOutcome when the familiar terminates.
     */
    public ThoughtForm recordFormSummon(String formId, String requesterDid) {
        requireAuthorized(requesterDid);
        var existing = thoughtForms.get(formId);
        if (existing == null) throw new NoSuchElementException("no form " + formId);
        var updated = existing.incrementSummon();
        thoughtForms.put(formId, updated);
        return updated;
    }

    /** Record a success/failure on a form (called by FamiliarActor parent on return). */
    public ThoughtForm recordFormOutcome(String formId, boolean success, String requesterDid) {
        requireAuthorized(requesterDid);
        var existing = thoughtForms.get(formId);
        if (existing == null) throw new NoSuchElementException("no form " + formId);
        var updated = success ? existing.recordSuccess() : existing.recordFailure();
        thoughtForms.put(formId, updated);
        return updated;
    }

    /** Retired forms — for journal/study surfacing. */
    public Collection<RetiredForm> retiredThoughtForms() {
        return Collections.unmodifiableCollection(retiredThoughtForms.values());
    }

    // --- Disk-hydration entry points (package-visible) ---
    // Used only by persistence helpers to restore state on startup.
    // These bypass authorization and validation because the on-disk state
    // is trusted (it was written by a previously-authorized session).

    public void loadThoughtForm(ThoughtForm form, List<ThoughtForm> history) {
        if (form == null) return;
        thoughtForms.put(form.id(), form);
        if (history != null && !history.isEmpty()) {
            thoughtFormHistory.put(form.id(), List.copyOf(history));
        } else {
            thoughtFormHistory.put(form.id(), List.of(form));
        }
    }

    public void loadRetiredForm(RetiredForm retired) {
        if (retired == null) return;
        retiredThoughtForms.put(retired.formId(), retired);
    }

    public void loadNamedFamiliar(NamedFamiliar named) {
        if (named == null) return;
        namedFamiliars.put(named.name(), named);
    }

    /** All thought-form history for serialization. */
    public Map<String, List<ThoughtForm>> thoughtFormHistorySnapshot() {
        return Map.copyOf(thoughtFormHistory);
    }

    /** Raw snapshot of all thought forms (including retired) — for persistence. */
    public Collection<ThoughtForm> thoughtFormsSnapshot() {
        return List.copyOf(thoughtForms.values());
    }

    /** Raw snapshot of named familiars — for persistence. */
    public Collection<NamedFamiliar> namedFamiliarsSnapshot() {
        return List.copyOf(namedFamiliars.values());
    }

    // --- Named Familiars ---

    /**
     * Name an ephemeral familiar so it persists across summonings. Rejects if
     * a named familiar already exists with this name for the same parent DID.
     */
    public NamedFamiliar nameFamiliar(String name, String parentDid, String formId,
                                       String openingContext, String requesterDid) {
        requireAuthorized(requesterDid);
        if (!requesterDid.equals(parentDid)) {
            throw new SecurityException("requesterDid must match parentAgentDid");
        }
        if (namedFamiliars.containsKey(name)) {
            throw new IllegalStateException("named familiar '" + name + "' already exists");
        }
        var named = NamedFamiliar.named(name, parentDid, formId, openingContext);
        namedFamiliars.put(name, named);
        return named;
    }

    /** Look up a named familiar by name. */
    public Optional<NamedFamiliar> namedFamiliar(String name, String requesterDid) {
        requireAuthorized(requesterDid);
        return Optional.ofNullable(namedFamiliars.get(name));
    }

    /** List all named familiars visible to the requester. */
    public List<NamedFamiliar> listNamedFamiliars(String requesterDid) {
        requireAuthorized(requesterDid);
        return namedFamiliars.values().stream()
            .sorted(Comparator.comparing(NamedFamiliar::name))
            .toList();
    }

    /** Record a fresh summoning of a named familiar — returns updated record. */
    public NamedFamiliar recordNamedSummon(String name, String task, String requesterDid) {
        requireAuthorized(requesterDid);
        var existing = namedFamiliars.get(name);
        if (existing == null) throw new NoSuchElementException("no named familiar '" + name + "'");
        var updated = existing.withSummoned(task);
        namedFamiliars.put(name, updated);
        return updated;
    }

    /** Record a termination outcome for a named familiar — returns updated record. */
    public NamedFamiliar recordNamedOutcome(String name, Familiar.Status status,
                                             int turns, String narrativeNote,
                                             String requesterDid) {
        requireAuthorized(requesterDid);
        var existing = namedFamiliars.get(name);
        if (existing == null) throw new NoSuchElementException("no named familiar '" + name + "'");
        var updated = existing.withOutcome(status, turns, narrativeNote);
        namedFamiliars.put(name, updated);
        return updated;
    }

    /** Explicit bond nudge — parent/user affirmed the relationship. */
    public NamedFamiliar nudgeNamedBond(String name, float delta, String requesterDid) {
        requireAuthorized(requesterDid);
        var existing = namedFamiliars.get(name);
        if (existing == null) throw new NoSuchElementException("no named familiar '" + name + "'");
        var updated = existing.nudgeBond(delta);
        namedFamiliars.put(name, updated);
        return updated;
    }

    /** Release / farewell a named familiar. Returns true if it existed. */
    public boolean releaseNamedFamiliar(String name, String requesterDid) {
        requireAuthorized(requesterDid);
        return namedFamiliars.remove(name) != null;
    }

    // --- Stats ---

    public String familyId() { return familyId; }
    public String lockerAddress() { return lockerAddress; }
    public int itemCount() { return (int) items.values().stream()
        .filter(i -> !tombstones.containsKey(i.hash())).count(); }
    public int tombstoneCount() { return tombstones.size(); }
    public int budCount() { return authorizedDids.size(); }
    public int thoughtFormCount() { return (int) thoughtForms.values().stream()
        .filter(f -> !retiredThoughtForms.containsKey(f.id())).count(); }
    public int namedFamiliarCount() { return namedFamiliars.size(); }

    private void requireAuthorized(String did) {
        if (!isAuthorized(did)) {
            throw new SecurityException("DID " + did + " not authorized for family " + familyId);
        }
    }
}
