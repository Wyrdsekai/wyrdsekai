/**
 * expo-sqlite + FTS5 implementation of StudyStore for React Native.
 *
 * Schema mirrors KMP's SqliteStudyStore.kt:
 * - `study_items` — main table with all fields
 * - `study_fts` — FTS5 virtual table for full-text search (title + content)
 * - Triggers keep FTS in sync on insert/update/delete
 *
 * Uses `unicode61` tokenizer for CJK support. Requires expo-sqlite >= 14.
 */
import { openDatabaseAsync, type SQLiteDatabase } from 'expo-sqlite';
import type { StudyItem } from './StudyItem';
import type { StudyStore } from './StudyStore';
import { tick, type ClockMap } from './VectorClock';

function generateId(): string {
  return 'si-' + 'xxxx-xxxx-xxxx'.replace(/x/g, () =>
    Math.floor(Math.random() * 16).toString(16),
  );
}

interface StudyRow {
  id: string;
  user_did: string;
  item_type: string;
  title: string;
  content: string;
  collection: string;
  timestamp: number;
  version: number;
  vector_clock: string | null;
  last_modified_by: string | null;
  deleted: number | null;
}

/** Columns every read must select so the CRDT sync fields round-trip. */
const STUDY_COLS =
  'id, user_did, item_type, title, content, collection, timestamp, version, vector_clock, last_modified_by, deleted';

function parseClock(s: string | null | undefined): ClockMap {
  if (!s) return {};
  try {
    const o = JSON.parse(s);
    return o && typeof o === 'object' ? (o as ClockMap) : {};
  } catch {
    return {};
  }
}

function rowToItem(row: StudyRow): StudyItem {
  return {
    id: row.id,
    userDid: row.user_did,
    itemType: row.item_type as StudyItem['itemType'],
    title: row.title,
    content: row.content,
    collection: row.collection,
    timestamp: row.timestamp,
    version: row.version,
    vectorClock: parseClock(row.vector_clock),
    lastModifiedBy: row.last_modified_by ?? undefined,
    deleted: (row.deleted ?? 0) !== 0,
  };
}

export class SqliteStudyStore implements StudyStore {
  private db: SQLiteDatabase | null = null;
  private initPromise: Promise<void> | null = null;

  // Vector-clock slot key for local writes. Set to the
  // Between node id when sync is wired; 'local' until then. Every local write
  // ticks this slot so edits propagate to peers as strictly newer.
  private deviceId = 'local';

  constructor(private readonly dbName: string = 'wyrd-study.db') {}

  /** Set the vector-clock slot key (the Between node id) for local writes. */
  setDeviceId(id: string): void {
    if (id) this.deviceId = id;
  }

  /** Stamp a fresh local item with a first-write clock {deviceId: 1}. */
  private stampNew(base: Omit<StudyItem, 'vectorClock' | 'lastModifiedBy' | 'deleted'>): StudyItem {
    return { ...base, vectorClock: { [this.deviceId]: 1 }, lastModifiedBy: this.deviceId, deleted: false };
  }

  /** INSERT a fully-formed item (with its sync fields) into study_items. */
  private async insertItem(item: StudyItem): Promise<void> {
    const db = await this.ensureDb();
    await db.runAsync(
      `INSERT INTO study_items (${STUDY_COLS})
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [item.id, item.userDid, item.itemType, item.title, item.content, item.collection ?? '',
        item.timestamp, item.version, JSON.stringify(item.vectorClock ?? {}),
        item.lastModifiedBy ?? this.deviceId, item.deleted ? 1 : 0],
    );
  }

  private async ensureDb(): Promise<SQLiteDatabase> {
    if (this.db) return this.db;
    if (!this.initPromise) {
      this.initPromise = this.initialize();
    }
    await this.initPromise;
    return this.db!;
  }

  private async initialize(): Promise<void> {
    this.db = await openDatabaseAsync(this.dbName);

    await this.db.execAsync(`
      CREATE TABLE IF NOT EXISTS study_items (
        _rowid INTEGER PRIMARY KEY AUTOINCREMENT,
        id TEXT UNIQUE NOT NULL,
        user_did TEXT NOT NULL,
        item_type TEXT NOT NULL,
        title TEXT NOT NULL DEFAULT '',
        content TEXT NOT NULL,
        collection TEXT NOT NULL DEFAULT '',
        timestamp INTEGER NOT NULL,
        version INTEGER NOT NULL DEFAULT 1
      );

      CREATE INDEX IF NOT EXISTS idx_study_user_type
        ON study_items(user_did, item_type);

      CREATE INDEX IF NOT EXISTS idx_study_user_ts
        ON study_items(user_did, timestamp DESC);

      CREATE VIRTUAL TABLE IF NOT EXISTS study_fts USING fts5(
        title, content,
        content='study_items',
        content_rowid='_rowid',
        tokenize='unicode61'
      );

      CREATE TRIGGER IF NOT EXISTS study_ai AFTER INSERT ON study_items BEGIN
        INSERT INTO study_fts(rowid, title, content)
        VALUES (new._rowid, new.title, new.content);
      END;

      CREATE TRIGGER IF NOT EXISTS study_ad AFTER DELETE ON study_items BEGIN
        INSERT INTO study_fts(study_fts, rowid, title, content)
        VALUES ('delete', old._rowid, old.title, old.content);
      END;

      CREATE TRIGGER IF NOT EXISTS study_au AFTER UPDATE ON study_items BEGIN
        INSERT INTO study_fts(study_fts, rowid, title, content)
        VALUES ('delete', old._rowid, old.title, old.content);
        INSERT INTO study_fts(rowid, title, content)
        VALUES (new._rowid, new.title, new.content);
      END;
    `);
    await this.ensureSyncColumns();
  }

  /**
   * Add the CRDT sync columns to an existing study_items table.
   * CREATE TABLE IF NOT EXISTS never adds columns, so upgrade in place — guarded by
   * PRAGMA table_info so it's a no-op on a DB that already has them.
   */
  private async ensureSyncColumns(): Promise<void> {
    const db = this.db!;
    const cols = await db.getAllAsync<{ name: string }>(`PRAGMA table_info(study_items)`);
    const have = new Set(cols.map((c) => c.name));
    if (!have.has('vector_clock')) {
      await db.execAsync(`ALTER TABLE study_items ADD COLUMN vector_clock TEXT`);
    }
    if (!have.has('last_modified_by')) {
      await db.execAsync(`ALTER TABLE study_items ADD COLUMN last_modified_by TEXT`);
    }
    if (!have.has('deleted')) {
      await db.execAsync(`ALTER TABLE study_items ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0`);
    }
  }

  // ── StudyStore implementation ──────────────────────────────────────

  async writeJournal(userDid: string, content: string, isPrivate = false): Promise<StudyItem> {
    const item = this.stampNew({
      id: generateId(),
      userDid,
      itemType: isPrivate ? 'journal_private' : 'journal',
      title: (content.split('\n')[0] ?? '').slice(0, 120),
      content,
      collection: '',
      timestamp: Date.now(),
      version: 1,
    });
    await this.insertItem(item);
    return item;
  }

  async editItem(id: string, newContent: string): Promise<StudyItem | null> {
    const existing = await this.getItem(id);
    if (!existing) return null;
    const db = await this.ensureDb();
    // Tick THIS device's clock slot so the edit propagates as strictly newer.
    const clock = tick(existing.vectorClock ?? {}, this.deviceId);
    const updated: StudyItem = {
      ...existing,
      content: newContent,
      title: (newContent.split('\n')[0] ?? existing.title).slice(0, 120),
      version: existing.version + 1,
      timestamp: Date.now(),
      vectorClock: clock,
      lastModifiedBy: this.deviceId,
    };
    await db.runAsync(
      `UPDATE study_items
         SET content = ?, title = ?, version = ?, timestamp = ?, vector_clock = ?, last_modified_by = ?
       WHERE id = ?`,
      [updated.content, updated.title, updated.version, updated.timestamp,
        JSON.stringify(clock), this.deviceId, id],
    );
    return updated;
  }

  async searchJournal(userDid: string, query: string, limit = 20): Promise<StudyItem[]> {
    return this.ftsSearch(userDid, query, limit, true);
  }

  async recentJournal(userDid: string, limit = 20): Promise<StudyItem[]> {
    const db = await this.ensureDb();
    const rows = await db.getAllAsync<StudyRow>(
      `SELECT ${STUDY_COLS}
       FROM study_items
       WHERE user_did = ? AND item_type IN ('journal', 'journal_private')
       ORDER BY timestamp DESC LIMIT ?`,
      [userDid, limit],
    );
    return rows.map(rowToItem);
  }

  async addNote(userDid: string, content: string): Promise<StudyItem> {
    const item = this.stampNew({
      id: generateId(),
      userDid,
      itemType: 'note',
      title: (content.split('\n')[0] ?? '').slice(0, 120),
      content,
      collection: '',
      timestamp: Date.now(),
      version: 1,
    });
    await this.insertItem(item);
    return item;
  }

  async pin(userDid: string, title: string, snippet: string, sourceUrl = ''): Promise<StudyItem> {
    const item = this.stampNew({
      id: generateId(),
      userDid,
      itemType: 'pinboard',
      title,
      content: sourceUrl ? `${snippet}\n\nSource: ${sourceUrl}` : snippet,
      collection: '',
      timestamp: Date.now(),
      version: 1,
    });
    await this.insertItem(item);
    return item;
  }

  async searchAll(userDid: string, query: string, limit = 20): Promise<StudyItem[]> {
    return this.ftsSearch(userDid, query, limit, false);
  }

  async getItem(id: string): Promise<StudyItem | null> {
    const db = await this.ensureDb();
    const row = await db.getFirstAsync<StudyRow>(
      `SELECT ${STUDY_COLS} FROM study_items WHERE id = ?`,
      [id],
    );
    return row ? rowToItem(row) : null;
  }

  async deleteItem(id: string): Promise<boolean> {
    const db = await this.ensureDb();
    const result = await db.runAsync(`DELETE FROM study_items WHERE id = ?`, [id]);
    return result.changes > 0;
  }

  async putItem(item: StudyItem): Promise<void> {
    // Upsert a synced-in item VERBATIM — its own clock/lastModifiedBy/deleted are
    // authoritative (this is a merge fast-forward, not a local edit). The clock is
    // what makes the next comparison correct; dropping it (the old gap) made every
    // item read as {} → EQUAL → sync no-op.
    const db = await this.ensureDb();
    await db.runAsync(
      `INSERT OR REPLACE INTO study_items (${STUDY_COLS})
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [item.id, item.userDid, item.itemType, item.title ?? '', item.content,
        item.collection ?? '', item.timestamp, item.version ?? 1,
        JSON.stringify(item.vectorClock ?? {}), item.lastModifiedBy ?? null, item.deleted ? 1 : 0],
    );
  }

  async rekeyUserDid(fromUserDid: string, toUserDid: string): Promise<number> {
    if (!fromUserDid || fromUserDid === toUserDid) return 0;
    const db = await this.ensureDb();
    const res = await db.runAsync(
      `UPDATE study_items SET user_did = ? WHERE user_did = ?`,
      [toUserDid, fromUserDid],
    );
    return res.changes ?? 0;
  }

  async count(userDid: string): Promise<number> {
    const db = await this.ensureDb();
    const row = await db.getFirstAsync<{ cnt: number }>(
      `SELECT COUNT(*) as cnt FROM study_items WHERE user_did = ?`,
      [userDid],
    );
    return row?.cnt ?? 0;
  }

  // ── Internal ───────────────────────────────────────────────────────

  private async ftsSearch(userDid: string, query: string, limit: number, journalOnly: boolean): Promise<StudyItem[]> {
    const sanitized = query.replace(/"/g, '').trim();
    if (!sanitized) return [];

    const db = await this.ensureDb();
    const typeFilter = journalOnly
      ? `AND s.item_type IN ('journal', 'journal_private')`
      : '';

    const rows = await db.getAllAsync<StudyRow>(
      `SELECT s.id, s.user_did, s.item_type, s.title, s.content, s.collection, s.timestamp, s.version,
              s.vector_clock, s.last_modified_by, s.deleted
       FROM study_items s
       INNER JOIN study_fts f ON s._rowid = f.rowid
       WHERE study_fts MATCH ? AND s.user_did = ? ${typeFilter}
       ORDER BY rank LIMIT ?`,
      [sanitized, userDid, limit],
    );
    return rows.map(rowToItem);
  }

  async close(): Promise<void> {
    await this.db?.closeAsync();
    this.db = null;
    this.initPromise = null;
  }
}
