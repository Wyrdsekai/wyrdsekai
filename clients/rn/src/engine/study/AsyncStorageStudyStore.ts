/**
 * AsyncStorage-backed StudyStore with in-memory full-text search.
 *
 * Stores each StudyItem as a JSON string keyed by `@wyrd-study:{id}`.
 * Maintains an index at `@wyrd-study:_index` for bulk loading.
 * All items are loaded into memory on first access; searches use
 * case-insensitive substring matching (adequate for phone scale <1000 items).
 *
 * Upgrade path: replace with expo-sqlite + FTS5 when search quality
 * or scale demands it (matches KMP's SqliteStudyStore).
 */
import type { StudyItem, StudyItemType } from './StudyItem';
import type { StudyStore } from './StudyStore';
import { requireOwner } from './StudyOwner';

const KEY_PREFIX = '@wyrd-study:';
const INDEX_KEY = `${KEY_PREFIX}_index`;

/** Minimal AsyncStorage interface — avoids hard import dependency. */
interface AsyncStorageLike {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
  removeItem(key: string): Promise<void>;
  multiGet?(keys: string[]): Promise<readonly [string, string | null][]>;
}

function generateId(): string {
  // Simple UUID v4 (no crypto dependency)
  return 'si-' + 'xxxx-xxxx-xxxx'.replace(/x/g, () =>
    Math.floor(Math.random() * 16).toString(16),
  );
}

export class AsyncStorageStudyStore implements StudyStore {
  private cache: Map<string, StudyItem> | null = null;

  constructor(private readonly storage: AsyncStorageLike) {}

  async writeJournal(userDid: string, content: string, isPrivate = false): Promise<StudyItem> {
    // No placeholder identities. See StudyOwner.ts — this client writes
    // journal entries, and an owner that refers to nobody makes them
    // unrecoverable rather than merely misfiled.
    userDid = requireOwner(userDid);
    const item: StudyItem = {
      id: generateId(),
      userDid,
      itemType: isPrivate ? 'journal_private' : 'journal',
      title: (content.split('\n')[0] ?? '').slice(0, 120),
      content,
      collection: '',
      timestamp: Date.now(),
      version: 1,
    };
    await this.storeItem(item);
    return item;
  }

  async editItem(id: string, newContent: string): Promise<StudyItem | null> {
    const existing = await this.getItem(id);
    if (!existing) return null;
    const updated: StudyItem = {
      ...existing,
      content: newContent,
      title: (newContent.split('\n')[0] ?? existing.title).slice(0, 120),
      version: existing.version + 1,
      timestamp: Date.now(),
    };
    await this.storeItem(updated);
    return updated;
  }

  async searchJournal(userDid: string, query: string, limit = 20): Promise<StudyItem[]> {
    const items = await this.ensureLoaded();
    const lower = query.toLowerCase();
    return Array.from(items.values())
      .filter(
        (item) =>
          item.userDid === userDid &&
          (item.itemType === 'journal' || item.itemType === 'journal_private') &&
          (item.content.toLowerCase().includes(lower) ||
            item.title.toLowerCase().includes(lower)),
      )
      .sort((a, b) => b.timestamp - a.timestamp)
      .slice(0, limit);
  }

  async recentJournal(userDid: string, limit = 20): Promise<StudyItem[]> {
    const items = await this.ensureLoaded();
    return Array.from(items.values())
      .filter(
        (item) =>
          item.userDid === userDid &&
          (item.itemType === 'journal' || item.itemType === 'journal_private'),
      )
      .sort((a, b) => b.timestamp - a.timestamp)
      .slice(0, limit);
  }

  async addNote(userDid: string, content: string): Promise<StudyItem> {
    // No placeholder identities. See StudyOwner.ts — this client writes
    // journal entries, and an owner that refers to nobody makes them
    // unrecoverable rather than merely misfiled.
    userDid = requireOwner(userDid);
    const item: StudyItem = {
      id: generateId(),
      userDid,
      itemType: 'note',
      title: (content.split('\n')[0] ?? '').slice(0, 120),
      content,
      collection: '',
      timestamp: Date.now(),
      version: 1,
    };
    await this.storeItem(item);
    return item;
  }

  async pin(userDid: string, title: string, snippet: string, sourceUrl = ''): Promise<StudyItem> {
    const item: StudyItem = {
      id: generateId(),
      userDid,
      itemType: 'pinboard',
      title,
      content: sourceUrl ? `${snippet}\n\nSource: ${sourceUrl}` : snippet,
      collection: '',
      timestamp: Date.now(),
      version: 1,
    };
    await this.storeItem(item);
    return item;
  }

  async searchAll(userDid: string, query: string, limit = 20): Promise<StudyItem[]> {
    const items = await this.ensureLoaded();
    const lower = query.toLowerCase();
    return Array.from(items.values())
      .filter(
        (item) =>
          item.userDid === userDid &&
          (item.content.toLowerCase().includes(lower) ||
            item.title.toLowerCase().includes(lower)),
      )
      .sort((a, b) => b.timestamp - a.timestamp)
      .slice(0, limit);
  }

  async getItem(id: string): Promise<StudyItem | null> {
    const items = await this.ensureLoaded();
    return items.get(id) ?? null;
  }

  async deleteItem(id: string): Promise<boolean> {
    const items = await this.ensureLoaded();
    if (!items.has(id)) return false;
    items.delete(id);
    try {
      await this.storage.removeItem(KEY_PREFIX + id);
      await this.saveIndex(Array.from(items.keys()));
    } catch {
      // best effort
    }
    return true;
  }

  async count(userDid: string): Promise<number> {
    const items = await this.ensureLoaded();
    let n = 0;
    for (const item of items.values()) {
      if (item.userDid === userDid) n++;
    }
    return n;
  }

  async putItem(item: StudyItem): Promise<void> {
    // #5 (2026-07-19) — persist a synced-in item verbatim (full JSON, so the
    // vector clock and all sync fields survive). Previously mergeIncoming
    // counted a new remote item but never stored it.
    await this.storeItem(item);
  }

  // ── Internal ───────────────────────────────────────────────────────

  private async storeItem(item: StudyItem): Promise<void> {
    const items = await this.ensureLoaded();
    items.set(item.id, item);
    try {
      await this.storage.setItem(KEY_PREFIX + item.id, JSON.stringify(item));
      await this.saveIndex(Array.from(items.keys()));
    } catch {
      // best effort
    }
  }

  private async ensureLoaded(): Promise<Map<string, StudyItem>> {
    if (this.cache) return this.cache;
    const loaded = new Map<string, StudyItem>();
    try {
      const indexRaw = await this.storage.getItem(INDEX_KEY);
      if (indexRaw) {
        const ids: string[] = JSON.parse(indexRaw);
        if (ids.length > 0) {
          if (this.storage.multiGet) {
            // Batch load (preferred)
            const keys = ids.map((id) => KEY_PREFIX + id);
            const entries = await this.storage.multiGet(keys);
            for (const [, value] of entries) {
              if (value) {
                try {
                  loaded.set((JSON.parse(value) as StudyItem).id, JSON.parse(value) as StudyItem);
                } catch { /* skip corrupted */ }
              }
            }
          } else {
            // Fallback: load one-by-one
            for (const id of ids) {
              try {
                const raw = await this.storage.getItem(KEY_PREFIX + id);
                if (raw) {
                  const item = JSON.parse(raw) as StudyItem;
                  loaded.set(item.id, item);
                }
              } catch { /* skip corrupted */ }
            }
          }
        }
      }
    } catch {
      // empty on error
    }
    this.cache = loaded;
    return loaded;
  }

  private async saveIndex(ids: string[]): Promise<void> {
    await this.storage.setItem(INDEX_KEY, JSON.stringify(ids));
  }
}
