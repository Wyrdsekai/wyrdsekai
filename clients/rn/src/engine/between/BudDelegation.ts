/**
 * Bud delegation — phone bud delegates COMPLEX queries to the server
 * companion through Between/NATS, with HTTP fallback.
 *
 * NATS path:
 *   Phone publishes to: between.household.{familyId}.{nodeId}.*.soul.delegate
 *   Phone subscribes:   between.household.{familyId}.*.*.soul.delegate-response
 *
 * HTTP fallback:
 *   POST {serverUrl}/api/companion/ask
 *   Authorization: Bearer {deviceToken}
 *   Body: { message, recentHistory, locale }
 *   Response: { text, requestId, latencyMs }
 *
 * (soul budding) and CompanionAskRoutes.java.
 */

import type { BetweenClient } from './BetweenClient';

export interface DelegationRequest {
  type: 'delegate';
  requestId: string;
  fromBudDid: string;
  message: string;
  recentHistory: string[];
  locale: string;
  timestamp: number;
}

export interface DelegationActionDto {
  type: string;
  data: Record<string, unknown>;
}

export interface DelegationResult {
  text: string;
  actions: DelegationActionDto[];
}

export interface DelegationResponse {
  requestId: string;
  text: string;
  latencyMs?: number;
  actions?: DelegationActionDto[];
}

/** Default timeout for NATS delegation (60 seconds). */
const DEFAULT_TIMEOUT_MS = 60_000;

/** HTTP request timeout (65 seconds — slightly longer than server's 60s ask timeout). */
const HTTP_TIMEOUT_MS = 65_000;

export class BudDelegation {
  private between: BetweenClient | null;
  private nodeId: string;
  private familyId: string;
  private serverUrl: string | null;
  private deviceToken: string | null;
  private pendingCallbacks = new Map<string, (resp: DelegationResponse) => void>();
  private unsubscribe: (() => void) | null = null;

  constructor(params: {
    between: BetweenClient | null;
    nodeId: string;
    familyId: string;
    serverUrl: string | null;
    deviceToken: string | null;
  }) {
    this.between = params.between;
    this.nodeId = params.nodeId;
    this.familyId = params.familyId;
    this.serverUrl = params.serverUrl;
    this.deviceToken = params.deviceToken;
  }

  /**
   * Subscribe to NATS delegation responses.
   * Matches: between.household.{familyId}.*.*.soul.delegate-response
   * (server publishes with its nodeId and wildcard target)
   */
  startListening(): void {
    if (!this.between || !this.between.isConnected) return;

    const subject = `between.household.${this.familyId}.*.*.soul.delegate-response`;
    this.unsubscribe = this.between.subscribe(subject, (_subj, data) => {
      try {
        const resp: DelegationResponse = JSON.parse(new TextDecoder().decode(data));
        const cb = this.pendingCallbacks.get(resp.requestId);
        if (cb) {
          this.pendingCallbacks.delete(resp.requestId);
          cb(resp);
        }
      } catch {
        // Malformed response — skip
      }
    });
  }

  /** Unsubscribe from NATS delegation responses. */
  stopListening(): void {
    this.unsubscribe?.();
    this.unsubscribe = null;
    // Reject all pending callbacks so they don't hang
    for (const [, cb] of this.pendingCallbacks) {
      cb({ requestId: '', text: '' });
    }
    this.pendingCallbacks.clear();
  }

  /** Update the Between client (e.g. after reconnect). */
  updateBetween(between: BetweenClient | null): void {
    this.stopListening();
    this.between = between;
    if (between?.isConnected) {
      this.startListening();
    }
  }

  /** Update credentials (e.g. after re-pairing). */
  updateCredentials(serverUrl: string | null, deviceToken: string | null): void {
    this.serverUrl = serverUrl;
    this.deviceToken = deviceToken;
  }

  /**
   * Delegate a complex query to the server companion.
   * Tries NATS first, falls back to HTTP.
   *
   * @returns Response text, or null if both paths failed.
   */
  async delegate(params: {
    message: string;
    recentHistory?: string[];
    locale?: string;
    timeoutMs?: number;
  }): Promise<DelegationResult | null> {
    const { message, recentHistory = [], locale = 'en', timeoutMs = DEFAULT_TIMEOUT_MS } = params;

    // Try NATS first
    if (this.between?.isConnected) {
      const result = await this.delegateViaNats(message, recentHistory, locale, timeoutMs);
      if (result) return result;
    }

    // HTTP fallback
    return this.delegateViaHttp(message, recentHistory, locale);
  }

  /**
   * Publish a delegation request via NATS and wait for the response.
   * Uses Promise.race with a timeout to avoid hanging indefinitely.
   */
  private delegateViaNats(
    message: string,
    recentHistory: string[],
    locale: string,
    timeoutMs: number,
  ): Promise<DelegationResult | null> {
    const requestId = `${Date.now()}-${Math.floor(Math.random() * 100000)}`;

    const request: DelegationRequest = {
      type: 'delegate',
      requestId,
      fromBudDid: this.nodeId,
      message,
      recentHistory,
      locale,
      timestamp: Date.now(),
    };

    return new Promise<DelegationResult | null>(resolve => {
      let settled = false;
      let timer: ReturnType<typeof setTimeout> | null = null;

      const cleanup = () => {
        if (timer) {
          clearTimeout(timer);
          timer = null;
        }
        this.pendingCallbacks.delete(requestId);
      };

      // Register callback for the response
      this.pendingCallbacks.set(requestId, (resp: DelegationResponse) => {
        if (settled) return;
        settled = true;
        cleanup();
        if (resp.text && resp.text.trim()) {
          resolve({ text: resp.text, actions: resp.actions ?? [] });
        } else {
          resolve(null);
        }
      });

      // Set timeout
      timer = setTimeout(() => {
        if (settled) return;
        settled = true;
        cleanup();
        resolve(null); // Timeout — fall through to HTTP
      }, timeoutMs);

      // Publish the request
      try {
        const data = new TextEncoder().encode(JSON.stringify(request));
        const subject = `between.household.${this.familyId}.${this.nodeId}.*.soul.delegate`;
        this.between!.publish(subject, data);
      } catch {
        // Publish failed — resolve null to fall through to HTTP
        if (!settled) {
          settled = true;
          cleanup();
          resolve(null);
        }
      }
    });
  }

  /**
   * Send a delegation request via HTTP to the server's /api/companion/ask endpoint.
   */
  private async delegateViaHttp(
    message: string,
    recentHistory: string[],
    locale: string,
  ): Promise<DelegationResult | null> {
    if (!this.serverUrl || !this.deviceToken) return null;

    const url = `${this.serverUrl}/api/companion/ask`;
    const body = JSON.stringify({ message, recentHistory, locale });

    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), HTTP_TIMEOUT_MS);

      const response = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${this.deviceToken}`,
        },
        body,
        signal: controller.signal,
      });

      clearTimeout(timer);

      if (!response.ok) return null;

      const data = await response.json();
      if (data.text && data.text.trim()) {
        return { text: data.text, actions: data.actions ?? [] };
      }
      return null;
    } catch {
      // Network error, timeout, or parse error — delegation failed
      return null;
    }
  }
}
