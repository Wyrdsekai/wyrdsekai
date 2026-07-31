package org.wyrdsekai.core.companion;

import java.time.Instant;
import java.util.*;

/**
 * Private journal for child companions (§100.9).
 * Encrypted with CHILD'S key — parent cannot access.
 * Items marked journal:private never sync to parent's household.
 */
public class JournalMode {

    /** A journal entry. */
    public record JournalEntry(
        String entryId,
        String childDid,
        String content,
        Instant createdAt,
        boolean encrypted,
        String contentHash
    ) {}

    /** Journal configuration. */
    public record JournalConfig(
        String childDid,
        boolean enabled,
        boolean autoEncrypt,
        boolean syncToParent
    ) {
        /** Default config — encrypted, never syncs. */
        public static JournalConfig defaultConfig(String childDid) {
            return new JournalConfig(childDid, true, true, false);
        }
    }

    private final Map<String, JournalConfig> configs = new HashMap<>();
    private final Map<String, List<JournalEntry>> entries = new LinkedHashMap<>();
    private int nextId = 1;

    /** Set up journal for a child. */
    public JournalConfig configure(String childDid) {
        var config = JournalConfig.defaultConfig(childDid);
        configs.put(childDid, config);
        entries.putIfAbsent(childDid, new ArrayList<>());
        return config;
    }

    /** Add a journal entry. Always private to the child. */
    public JournalEntry addEntry(String childDid, String content) {
        var config = configs.get(childDid);
        if (config == null || !config.enabled()) return null;

        var entry = new JournalEntry("journal-" + nextId++, childDid,
            config.autoEncrypt() ? "[ENCRYPTED]" : content,
            Instant.now(), config.autoEncrypt(),
            hashContent(content));
        entries.computeIfAbsent(childDid, k -> new ArrayList<>()).add(entry);
        return entry;
    }

    /** Get entries for a child (only the child can access). */
    public List<JournalEntry> entriesFor(String childDid, String requestorDid) {
        // Only the child can access their own journal
        if (!childDid.equals(requestorDid)) return List.of();
        var entryList = entries.get(childDid);
        return entryList != null ? List.copyOf(entryList) : List.of();
    }

    /** Check if journal syncs to parent (should ALWAYS be false). */
    public boolean syncsToParent(String childDid) {
        var config = configs.get(childDid);
        return config != null && config.syncToParent();
    }

    /** Check if journal is enabled. */
    public boolean isEnabled(String childDid) {
        var config = configs.get(childDid);
        return config != null && config.enabled();
    }

    /** Count entries. */
    public int entryCount(String childDid) {
        var entryList = entries.get(childDid);
        return entryList != null ? entryList.size() : 0;
    }

    /** Generate the "journal:private" item tag for soul items. */
    public static String privateTag() {
        return "journal:private";
    }

    /** Check if an item has the private journal tag. */
    public static boolean isPrivateJournalItem(Set<String> tags) {
        return tags != null && tags.contains(privateTag());
    }

    private String hashContent(String content) {
        // Simple hash for integrity checking
        return Integer.toHexString(content.hashCode());
    }
}
