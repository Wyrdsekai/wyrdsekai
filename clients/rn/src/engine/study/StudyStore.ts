/**
 * Local Study persistence — the phone-side equivalent of the server's StudyService.
 * TypeScript port of KMP's StudyStore.kt.
 *
 * Provides journal, notes, pinboard, and full-text search over the player's
 * personal Study items.
 */
import type { StudyItem } from './StudyItem';

export interface StudyStore {
  /** Write a journal entry (shared or private). Returns the created item. */
  writeJournal(userDid: string, content: string, isPrivate?: boolean): Promise<StudyItem>;

  /** Edit an existing item's content. Increments version. Returns updated item, or null. */
  editItem(id: string, newContent: string): Promise<StudyItem | null>;

  /** Full-text search over journal entries for a user. */
  searchJournal(userDid: string, query: string, limit?: number): Promise<StudyItem[]>;

  /** Most recent journal entries for a user, ordered newest-first. */
  recentJournal(userDid: string, limit?: number): Promise<StudyItem[]>;

  /** Add a quick note. */
  addNote(userDid: string, content: string): Promise<StudyItem>;

  /** Pin a reference (e.g. Library bookmark). */
  pin(userDid: string, title: string, snippet: string, sourceUrl?: string): Promise<StudyItem>;

  /** Full-text search across all item types for a user. */
  searchAll(userDid: string, query: string, limit?: number): Promise<StudyItem[]>;

  /** Get a single item by ID, or null. */
  getItem(id: string): Promise<StudyItem | null>;

  /**
   * #5 (2026-07-19 OSS hardening) — upsert a full item verbatim. Used by the
   * sync layer to store an incoming item this device does not yet have. Without
   * it, mergeIncoming counted a new remote item as "merged" but never persisted
   * it, silently dropping synced Study/journal entries (data loss).
   */
  putItem(item: StudyItem): Promise<void>;

  /** Delete an item by ID. Returns true if it existed and was deleted. */
  deleteItem(id: string): Promise<boolean>;

  /** Count of all items for a user. */
  count(userDid: string): Promise<number>;

  /** Set the vector-clock slot key (Between node id) for local writes, if the
   *  implementation persists clocks. No-op for stores that don't. */
  setDeviceId?(id: string): void;

  /** Re-key all items owned by {@code fromUserDid} to {@code toUserDid} — used
   *  once on first home-zone login so pre-account local notes follow the user to
   * their account. Returns the number of rows moved. */
  rekeyUserDid?(fromUserDid: string, toUserDid: string): Promise<number>;
}
