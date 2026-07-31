/**
 * ServiceWorkerCache — registers a service worker for offline caching.
 *
 * Caches the app shell, model weights (GGUF/WebLLM), and static assets
 * so the web ephemeral node works offline after first load.
 *
 * Strategy:
 * - App shell: cache-first (HTML, JS, CSS)
 * - Model weights: cache-first (large, rarely change)
 * - API calls: network-first (dynamic data)
 */

const SW_PATH = '/service-worker.js';
const CACHE_NAME = 'wyrdsekai-v1';

export type CacheStatus = 'unavailable' | 'installing' | 'active' | 'error';

export class ServiceWorkerCache {
  private _status: CacheStatus = 'unavailable';
  private statusListeners: Array<(status: CacheStatus) => void> = [];

  get status(): CacheStatus {
    return this._status;
  }

  onStatusChange(listener: (status: CacheStatus) => void): () => void {
    this.statusListeners.push(listener);
    return () => {
      this.statusListeners = this.statusListeners.filter(l => l !== listener);
    };
  }

  private setStatus(status: CacheStatus): void {
    this._status = status;
    for (const listener of this.statusListeners) {
      listener(status);
    }
  }

  /** Check if Service Worker is supported in this browser. */
  static isSupported(): boolean {
    return typeof navigator !== 'undefined' && 'serviceWorker' in navigator;
  }

  /**
   * Register the service worker and set up update listeners.
   * No-op if service workers aren't supported.
   */
  async register(): Promise<void> {
    if (!ServiceWorkerCache.isSupported()) {
      this.setStatus('unavailable');
      return;
    }

    try {
      this.setStatus('installing');

      const registration = await navigator.serviceWorker.register(SW_PATH, {
        scope: '/',
      });

      if (registration.active) {
        this.setStatus('active');
      }

      registration.addEventListener('updatefound', () => {
        const newWorker = registration.installing;
        if (newWorker) {
          newWorker.addEventListener('statechange', () => {
            if (newWorker.state === 'activated') {
              this.setStatus('active');
            }
          });
        }
      });

      // Listen for controller changes (new version activated)
      navigator.serviceWorker.addEventListener('controllerchange', () => {
        this.setStatus('active');
      });
    } catch (e) {
      console.warn('[ServiceWorkerCache] Registration failed:', e);
      this.setStatus('error');
    }
  }

  /** Unregister all service workers. */
  async unregister(): Promise<void> {
    if (!ServiceWorkerCache.isSupported()) return;

    const registrations = await navigator.serviceWorker.getRegistrations();
    for (const reg of registrations) {
      await reg.unregister();
    }
    this.setStatus('unavailable');
  }

  /**
   * Pre-cache a list of URLs (app shell, critical assets).
   * Called from the main thread; the SW handles the actual caching.
   */
  async precache(urls: string[]): Promise<void> {
    if (typeof caches === 'undefined') return;

    try {
      const cache = await caches.open(CACHE_NAME);
      await cache.addAll(urls);
    } catch (e) {
      console.warn('[ServiceWorkerCache] Precache failed:', e);
    }
  }

  /**
   * Cache a model file at a given URL.
   * Uses the Cache API directly for large files.
   */
  async cacheModel(url: string): Promise<void> {
    if (typeof caches === 'undefined') return;

    try {
      const cache = await caches.open(CACHE_NAME);
      const existing = await cache.match(url);
      if (existing) return; // Already cached

      const response = await fetch(url);
      if (response.ok) {
        await cache.put(url, response);
      }
    } catch (e) {
      console.warn('[ServiceWorkerCache] Model cache failed:', e);
    }
  }

  /** Check if a URL is already cached. */
  async isCached(url: string): Promise<boolean> {
    if (typeof caches === 'undefined') return false;

    try {
      const cache = await caches.open(CACHE_NAME);
      const match = await cache.match(url);
      return match !== undefined;
    } catch {
      return false;
    }
  }

  /** Clear all cached data. */
  async clearCache(): Promise<void> {
    if (typeof caches === 'undefined') return;

    try {
      await caches.delete(CACHE_NAME);
    } catch {
      // Non-fatal
    }
  }
}

/** Singleton instance. */
export const serviceWorkerCache = new ServiceWorkerCache();
