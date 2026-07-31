package org.wyrdsekai.core.soul;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Three-tier sync orchestration for soul buds (§95.6).
 *
 * Tier 1: Headlines — continuous, ~200 bytes via Between
 *   "I'm awake, energy 0.7, talked to Alice about gardening, 42 items"
 *   Posted to family locker, all buds read all headlines.
 *   Purpose: family awareness without bandwidth cost.
 *
 * Tier 2: Warm Handoff — on device switch, <2 seconds
 *   Full active context (inventory, open conversations, current room)
 *   transferred to the receiving bud. Human sees seamless transition.
 *   Like picking up a phone call on a different device.
 *
 * Tier 3: Sleep Sync — full Forge consolidation during sleep
 *   Complete soul item exchange between all family buds.
 *   Tombstone propagation, codebook updates, manifest merge.
 *   Heaviest operation — only during sleep cycles.
 *
 * Human sees one companion; buds coordinate behind the scenes.
 */
public class BudSyncService {

    /** Tier 1 headline notification (~200 bytes). */
    public record HeadlineNotification(
        String fromDid,
        String summary,
        double[] vitalitySnapshot,
        Instant timestamp,
        int totalItems,
        int newItemsSinceLastSync
    ) {
        public static HeadlineNotification create(String fromDid, String summary,
                                                    double[] vitality, int totalItems,
                                                    int newItems) {
            return new HeadlineNotification(fromDid, summary, vitality,
                Instant.now(), totalItems, newItems);
        }

        /** Estimated size in bytes. */
        public int estimatedBytes() {
            return (summary != null ? summary.length() : 0) + 80; // metadata overhead
        }
    }

    /** Tier 2 warm handoff context. */
    public record WarmHandoffContext(
        String fromDid,
        String toDid,
        String activeRoomId,
        List<String> openConversationDids,
        List<SoulItem> activeInventory,
        Map<String, Double> currentVitality,
        Instant handoffTime,
        String currentTask
    ) {
        public static WarmHandoffContext create(String fromDid, String toDid,
                                                  String activeRoom,
                                                  List<String> conversations,
                                                  List<SoulItem> inventory,
                                                  Map<String, Double> vitality,
                                                  String task) {
            return new WarmHandoffContext(fromDid, toDid, activeRoom,
                conversations, inventory, vitality, Instant.now(), task);
        }
    }

    /** Tier 3 sleep sync result. */
    public record SleepSyncResult(
        String budDid,
        int itemsMerged,
        int tombstonesApplied,
        int codebookUpdates,
        boolean manifestUpdated,
        Duration duration,
        Instant completedAt
    ) {}

    /** Sync state tracking per bud. */
    public record BudSyncState(
        String budDid,
        Instant lastHeadline,
        Instant lastWarmHandoff,
        Instant lastSleepSync,
        int pendingItems
    ) {
        public BudSyncState withHeadline(Instant time) {
            return new BudSyncState(budDid, time, lastWarmHandoff, lastSleepSync, pendingItems);
        }
        public BudSyncState withHandoff(Instant time) {
            return new BudSyncState(budDid, lastHeadline, time, lastSleepSync, pendingItems);
        }
        public BudSyncState withSleepSync(Instant time) {
            return new BudSyncState(budDid, lastHeadline, lastWarmHandoff, time, 0);
        }
    }

    private final FamilyLocker locker;
    private final Map<String, BudSyncState> syncStates = new ConcurrentHashMap<>();

    public BudSyncService(FamilyLocker locker) {
        this.locker = locker;
    }

    // --- Tier 1: Headlines ---

    /** Post a headline to the family locker. */
    public HeadlineNotification postHeadline(String budDid, String summary,
                                              double[] vitality, int totalItems) {
        var state = getOrCreateState(budDid);
        int newItems = totalItems - state.pendingItems();

        var notification = HeadlineNotification.create(
            budDid, summary, vitality, totalItems, newItems);

        // §95.7 cryptophasia — compress + privatise the summary via the family codebook before it
        // rests in the locker. Family buds decode it on read; non-family see only codes. Early on the
        // codebook holds few codes (little compresses); it grows from shared items during sleep sync.
        var familyArgot = locker.familyArgot();
        var wireSummary = familyArgot != null ? familyArgot.encodeText(summary) : summary;
        locker.postHeadline(FamilyLocker.Headline.create(
            budDid, wireSummary, vitality, totalItems));

        syncStates.put(budDid, state.withHeadline(Instant.now()));
        return notification;
    }

    /** Read all current headlines from family. */
    public Map<String, HeadlineNotification> readHeadlines(String requesterDid) {
        var lockerHeadlines = locker.allHeadlines();
        var result = new LinkedHashMap<String, HeadlineNotification>();

        var familyArgot = locker.familyArgot();
        for (var entry : lockerHeadlines.entrySet()) {
            var h = entry.getValue();
            // §95.7 — decode the family-private summary back to plain text for the reading bud.
            var summary = familyArgot != null ? familyArgot.decodeText(h.summary()) : h.summary();
            result.put(entry.getKey(), new HeadlineNotification(
                h.budDid(), summary, h.vitalitySnapshot(),
                h.timestamp(), h.itemCount(), 0));
        }
        return result;
    }

    // --- Tier 2: Warm Handoff ---

    /** Initiate a warm handoff (device switch). */
    public WarmHandoffContext initiateHandoff(String fromDid, String toDid,
                                                String activeRoom,
                                                List<String> openConversations,
                                                List<SoulItem> activeInventory,
                                                Map<String, Double> vitality,
                                                String currentTask) {
        var context = WarmHandoffContext.create(fromDid, toDid, activeRoom,
            openConversations, activeInventory, vitality, currentTask);

        // Update sync state for both buds
        var fromState = getOrCreateState(fromDid);
        syncStates.put(fromDid, fromState.withHandoff(Instant.now()));
        var toState = getOrCreateState(toDid);
        syncStates.put(toDid, toState.withHandoff(Instant.now()));

        return context;
    }

    /** Accept a warm handoff — receiving bud acknowledges. */
    public boolean acceptHandoff(WarmHandoffContext context) {
        // Store any inventory items in the locker so receiving bud can access
        for (var item : context.activeInventory()) {
            try {
                locker.store(item, context.fromDid());
            } catch (SecurityException e) {
                return false; // Sender not authorized
            }
        }
        return true;
    }

    // --- Tier 3: Sleep Sync ---

    /**
     * Perform full sleep sync for a bud.
     * This is the heaviest operation — exchanges all items, tombstones,
     * and codebook updates with the family locker.
     */
    public SleepSyncResult sleepSync(String budDid, List<SoulItem> localItems,
                                       Collection<FamilyLocker.Tombstone> localTombstones,
                                       ArgotCodebook localCodebook) {
        var start = Instant.now();
        int itemsMerged = 0;
        int tombstonesApplied = 0;
        int codebookUpdates = 0;

        // 1. Push local items to locker
        for (var item : localItems) {
            try {
                locker.store(item, budDid);
                itemsMerged++;
            } catch (Exception e) {
                // Skip items that fail integrity or auth checks
            }
        }

        // 2. Push local tombstones to locker
        tombstonesApplied += locker.applyTombstones(localTombstones);

        // 3. Pull items from locker that this bud doesn't have
        var state = getOrCreateState(budDid);
        var since = state.lastSleepSync() != null ? state.lastSleepSync() : Instant.EPOCH;
        var newItems = locker.itemsSince(since, budDid);
        itemsMerged += newItems.size();

        // 4. Pull tombstones from locker
        var newTombstones = locker.tombstonesSince(since);
        tombstonesApplied += newTombstones.size();

        // 5. §95.7 — grow the family cryptophasia codebook from the significant items shared this
        // sync (item codes), then persist it to the locker so every bud compresses against the same
        // map. codebookUpdates = how many new codes were minted this sync.
        var baseCodebook = localCodebook != null ? localCodebook : locker.familyArgot();
        if (baseCodebook != null) {
            var grown = baseCodebook.learnFromItems(localItems);
            codebookUpdates = grown.totalCodes() - baseCodebook.totalCodes();
            if (codebookUpdates > 0) locker.updateFamilyArgot(grown);
        }

        var duration = Duration.between(start, Instant.now());
        syncStates.put(budDid, state.withSleepSync(Instant.now()));

        return new SleepSyncResult(budDid, itemsMerged, tombstonesApplied,
            codebookUpdates, true, duration, Instant.now());
    }

    // --- State Queries ---

    /** Get sync state for a bud. */
    public Optional<BudSyncState> syncState(String budDid) {
        return Optional.ofNullable(syncStates.get(budDid));
    }

    /** Check if a bud needs sleep sync (hasn't synced in given duration). */
    public boolean needsSleepSync(String budDid, Duration maxAge) {
        var state = syncStates.get(budDid);
        if (state == null || state.lastSleepSync() == null) return true;
        return Duration.between(state.lastSleepSync(), Instant.now()).compareTo(maxAge) > 0;
    }

    /** Number of tracked buds. */
    public int trackedBudCount() {
        return syncStates.size();
    }

    // --- Capability Requests ---

    /**
     * A request from one bud to peers for capability creation.
     * Phone companion escalation tier 3: "ask peer to create skill".
     * Posted to family locker; any peer can pick it up.
     */
    public record CapabilityRequest(
        String requesterId,
        String skillName,
        String skillDescription,
        String runtime,
        String codeHint,
        Instant requestedAt
    ) {
        public static CapabilityRequest create(String requesterId, String skillName,
                                                  String description, String runtime,
                                                  String codeHint) {
            return new CapabilityRequest(requesterId, skillName, description,
                runtime, codeHint, Instant.now());
        }
    }

    /** Post a capability request to the family locker. */
    public void postCapabilityRequest(CapabilityRequest request) {
        // Store as a special SoulItem so peers can discover it
        var item = SoulItem.create(
            "capability_request",
            "cap-request:" + request.skillName(),
            "{\"skillName\":\"" + request.skillName()
                + "\",\"description\":\"" + request.skillDescription()
                + "\",\"runtime\":\"" + request.runtime()
                + "\",\"codeHint\":\"" + (request.codeHint() != null ? request.codeHint() : "")
                + "\"}",
            request.requesterId(),
            0.5
        );
        try {
            locker.store(item, request.requesterId());
        } catch (Exception e) {
            // Best-effort — peer may not pick it up
        }
    }

    /** Read pending capability requests from the family locker. */
    public List<CapabilityRequest> pendingCapabilityRequests(String readerDid) {
        var items = locker.byCategory("capability_request", readerDid);
        if (items == null) return List.of();
        var requests = new ArrayList<CapabilityRequest>();
        for (var item : items) {
            // Basic extraction — full JSON parsing deferred to when we have tests
            requests.add(new CapabilityRequest(
                item.creatorDid(), item.label(), "", "graaljs", null, item.created()));
        }
        return requests;
    }

    private BudSyncState getOrCreateState(String budDid) {
        return syncStates.computeIfAbsent(budDid,
            did -> new BudSyncState(did, null, null, null, 0));
    }
}
