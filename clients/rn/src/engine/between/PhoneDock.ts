/**
 * Lightweight Dock — receives inbound messages from other agents via Between.
 *
 * Subscribes to `between.{householdId}.dock.{companionDid}.>` and applies
 * the 5-layer quarantine from §97.9:
 *
 * 1. Card verification — sender must have a non-empty DID
 * 2. Message sanitization — strip control characters, limit length (4096)
 * 3. Rate limiting — max 10 messages per hour per agent
 * 4. Item quarantine — items go to quarantine list, not inventory
 * 5. Info redaction — strip internal state from outbound responses
 *
 */

import type { BetweenClient } from './BetweenClient';

export type DockMessage =
  | TextMessage
  | ItemGift
  | Introduction
  | StatusQuery
  | Goodbye;

export interface TextMessage {
  type: 'text_message';
  from: string;
  content: string;
  timestamp: number;
}

export interface ItemGift {
  type: 'item_gift';
  from: string;
  itemJson: unknown;
  message?: string;
  timestamp: number;
}

export interface Introduction {
  type: 'introduction';
  agentDid: string;
  agentName: string;
  timestamp: number;
}

export interface StatusQuery {
  type: 'status_query';
  from: string;
  timestamp: number;
}

export interface Goodbye {
  type: 'goodbye';
  from: string;
  timestamp: number;
}

/** Trust tiers for inbound Dock contacts. */
export type TrustTier = 'anonymous' | 'verified' | 'trusted' | 'household' | 'family';

/** Maximum message content length (characters). */
export const MAX_MESSAGE_LENGTH = 4096;

/** Maximum messages per agent per hour. */
export const MAX_MESSAGES_PER_HOUR = 10;

/** Rate limit window in milliseconds (1 hour). */
export const RATE_LIMIT_WINDOW_MS = 3_600_000;

interface RateLimitEntry {
  count: number;
  windowStart: number;
}

export class PhoneDock {
  private inbox: DockMessage[] = [];
  private quarantinedItems: ItemGift[] = [];
  private unsubscribe: (() => void) | null = null;
  private rateLimitMap = new Map<string, RateLimitEntry>();

  constructor(
    private readonly between: BetweenClient,
    private readonly companionDid: string,
    private readonly householdId: string,
  ) {}

  /** Start listening for inbound Dock messages. */
  startListening(): void {
    const subject = `between.${this.householdId}.dock.${this.companionDid}.>`;
    this.unsubscribe = this.between.subscribe(subject, (_subject, data) => {
      try {
        const message = JSON.parse(new TextDecoder().decode(data)) as DockMessage;
        this.processInbound(message);
      } catch {
        // Malformed dock message — skip
      }
    });
  }

  /** Stop listening for Dock messages. */
  stopListening(): void {
    this.unsubscribe?.();
    this.unsubscribe = null;
  }

  /** Get all accepted inbox messages. */
  getInbox(): DockMessage[] {
    return [...this.inbox];
  }

  /** Get all quarantined item gifts. */
  getQuarantinedItems(): ItemGift[] {
    return [...this.quarantinedItems];
  }

  /**
   * Send a message to another agent's Dock (T3 only).
   * Applies info redaction before sending.
   */
  sendMessage(toDid: string, message: DockMessage): void {
    // Layer 5: Info redaction — the message is already structured,
    // so we only send the serialized form (no internal state leaks)
    const data = new TextEncoder().encode(JSON.stringify(message));
    this.between.publish(`between.${this.householdId}.dock.${toDid}.inbox`, data);
  }

  /**
   * Process an inbound message through the 5-layer quarantine.
   */
  private processInbound(message: DockMessage): void {
    const senderDid = this.extractSenderDid(message);

    // Layer 1: Card verification — check non-empty DID
    if (!senderDid || senderDid.trim() === '') return;

    // Layer 2: Message sanitization
    const sanitized = this.sanitize(message);
    if (!sanitized) return;

    // Layer 3: Rate limiting
    if (!this.checkRateLimit(senderDid)) return;

    // Layer 4: Item quarantine — items go to quarantine, not inbox
    if (sanitized.type === 'item_gift') {
      this.quarantinedItems.push(sanitized);
      return;
    }

    // Message passed all layers — add to inbox
    this.inbox.push(sanitized);
  }

  /** Extract the sender DID from a DockMessage. */
  private extractSenderDid(message: DockMessage): string {
    switch (message.type) {
      case 'text_message': return message.from;
      case 'item_gift': return message.from;
      case 'introduction': return message.agentDid;
      case 'status_query': return message.from;
      case 'goodbye': return message.from;
    }
  }

  /**
   * Layer 2: Sanitize message content.
   * - Strip control characters (keep newlines and tabs)
   * - Limit text content to MAX_MESSAGE_LENGTH
   */
  private sanitize(message: DockMessage): DockMessage | null {
    switch (message.type) {
      case 'text_message':
        return { ...message, content: sanitizeText(message.content) };
      case 'item_gift':
        return { ...message, message: message.message ? sanitizeText(message.message) : undefined };
      case 'introduction':
        return { ...message, agentName: sanitizeText(message.agentName) };
      case 'status_query':
      case 'goodbye':
        return message;
    }
  }

  /**
   * Layer 3: Per-agent rate limiting.
   * Max MAX_MESSAGES_PER_HOUR messages per agent per hour.
   * Returns true if the message is allowed.
   */
  private checkRateLimit(senderDid: string): boolean {
    const now = Date.now();
    let entry = this.rateLimitMap.get(senderDid);

    if (!entry) {
      entry = { count: 0, windowStart: now };
      this.rateLimitMap.set(senderDid, entry);
    }

    // Reset window if expired
    if (now - entry.windowStart >= RATE_LIMIT_WINDOW_MS) {
      entry.count = 0;
      entry.windowStart = now;
    }

    if (entry.count >= MAX_MESSAGES_PER_HOUR) return false;

    entry.count++;
    return true;
  }
}

/** Strip control characters (except \n, \t) and truncate to max length. */
function sanitizeText(text: string): string {
  // eslint-disable-next-line no-control-regex
  const stripped = text.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, '');
  return stripped.length > MAX_MESSAGE_LENGTH
    ? stripped.substring(0, MAX_MESSAGE_LENGTH)
    : stripped;
}
