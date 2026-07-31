/**
 * A single item in the player's Study — journal entry, note, pin, or document.
 * TypeScript port of KMP's StudyItem.kt.
 */
export interface StudyItem {
  /** Unique identifier (e.g. "si-{uuid}"). */
  id: string;
  /** Owner's DID. */
  userDid: string;
  /** Item type: journal, journal_private, note, pinboard, document, voice_memo. */
  itemType: StudyItemType;
  /** Title or first line of content. */
  title: string;
  /** Full text content. */
  content: string;
  /** Sub-collection name (e.g. "taxes-2025"). Empty string = default. */
  collection: string;
  /** Creation/last-modified timestamp (epoch millis). */
  timestamp: number;
  /** Edit version number. Increments on each edit; old versions archived. */
  version: number;

  // ── Sync fields ──────────────────────────────────────────────────
  /** Vector clock: {deviceId → version}. Each device increments its own slot on write. */
  vectorClock?: Record<string, number>;
  /** Device ID that last modified this item. */
  lastModifiedBy?: string;
  /** Non-empty = unresolved conflict. Contains competing versions. */
  conflictVersions?: StudyItem[];
  /** Soft-delete tombstone. True = deleted, replicated then purged. */
  deleted?: boolean;
}

export type StudyItemType =
  | 'journal'
  | 'journal_private'
  | 'note'
  | 'pinboard'
  | 'document'
  | 'voice_memo';
