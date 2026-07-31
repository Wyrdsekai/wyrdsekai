/**
 * process-wide handle to a connected relay BetweenClient
 * (raw NATS pub/sub over the household relay).
 *
 * Set by StandaloneNodeContext when the relay leg comes up in relay-login mode.
 * StandaloneRoomScreen reads it to build a RelayTunnelServerConnection and route
 * the terminal's session to the real zone instead of driving the in-process
 * PhoneNode. Empty (null) ⇒ no relay tunnel ⇒ the terminal falls back to the
 * offline PhoneNode path. Mirrors the KMP `RelayTunnelHolder` object.
 */
import type { BetweenClient } from '../between/BetweenClient';

type Listener = (bc: BetweenClient | null) => void;

let current: BetweenClient | null = null;
const listeners = new Set<Listener>();

export const RelayTunnelHolder = {
  set(bc: BetweenClient | null): void {
    current = bc;
    for (const l of [...listeners]) l(bc);
  },
  get(): BetweenClient | null {
    return current;
  },
  clear(): void {
    current = null;
    for (const l of [...listeners]) l(null);
  },
  /** Subscribe to changes. Fires immediately with the current value. Returns unsubscribe. */
  subscribe(listener: Listener): () => void {
    listeners.add(listener);
    listener(current);
    return () => {
      listeners.delete(listener);
    };
  },
};
