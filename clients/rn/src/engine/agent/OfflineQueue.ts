/**
 * Queues complex inference requests when the household is unreachable.
 * Persists to AsyncStorage so requests survive app restarts.
 * Max 50 requests (oldest dropped if exceeded).
 *
 * Key: `@wyrd_offline_queue`
 */

const STORAGE_KEY = '@wyrd_offline_queue';
const MAX_QUEUE_SIZE = 50;

export interface PendingRequest {
  triggerId: string;
  triggerText: string;
  triggerEntityName: string;
  roomId: string;
  timestamp: number;
  retryCount: number;
}

/** Minimal AsyncStorage interface — avoids hard import dependency. */
interface AsyncStorageLike {
  getItem(key: string): Promise<string | null>;
  setItem(key: string, value: string): Promise<void>;
  removeItem(key: string): Promise<void>;
}

export class OfflineQueue {
  private cache: PendingRequest[] | null = null;

  constructor(private readonly storage: AsyncStorageLike) {}

  async enqueue(
    triggerText: string,
    triggerEntityName: string,
    roomId: string,
  ): Promise<void> {
    const list = await this.loadOrInit();
    const request: PendingRequest = {
      triggerId: `${Date.now()}-${Math.floor(Math.random() * 10000)}`,
      triggerText,
      triggerEntityName,
      roomId,
      timestamp: Date.now(),
      retryCount: 0,
    };
    list.push(request);
    // Cap at 50 — drop oldest
    while (list.length > MAX_QUEUE_SIZE) {
      list.shift();
    }
    await this.save(list);
  }

  async pending(): Promise<PendingRequest[]> {
    const list = await this.loadOrInit();
    return [...list];
  }

  async complete(triggerId: string): Promise<void> {
    const list = await this.loadOrInit();
    const idx = list.findIndex(r => r.triggerId === triggerId);
    if (idx >= 0) {
      list.splice(idx, 1);
      await this.save(list);
    }
  }

  async size(): Promise<number> {
    const list = await this.loadOrInit();
    return list.length;
  }

  async clear(): Promise<void> {
    this.cache = [];
    await this.save([]);
  }

  // --- Internal ---

  private async loadOrInit(): Promise<PendingRequest[]> {
    if (this.cache !== null) return this.cache;

    try {
      const data = await this.storage.getItem(STORAGE_KEY);
      if (data) {
        const parsed = JSON.parse(data) as PendingRequest[];
        this.cache = parsed;
        return parsed;
      }
    } catch {
      // Corrupted data — start fresh
    }

    this.cache = [];
    return this.cache;
  }

  private async save(list: PendingRequest[]): Promise<void> {
    this.cache = list;
    try {
      await this.storage.setItem(STORAGE_KEY, JSON.stringify(list));
    } catch {
      // Non-fatal — queue is also in memory
    }
  }
}
