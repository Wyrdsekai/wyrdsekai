/**
 * addInviteToBank — turn a wyrdphone:// invite into bank entries
 * ( onboarding). Adding an invite is how relays get
 * held and a zone joins your bank.
 *
 * The invite carries the relay(s) + the zone id, but NOT your account name —
 * so the zone entry's `username` starts empty and the Servers screen prompts
 * for it (once) on first open. The TLS pin is installed TOFU on first connect
 * via the native HouseholdTrust layer; we carry `caFp` on the held relay for
 * that path.
 */
import { isPhoneInviteUrl, parsePhoneInvite } from '../network/phoneInvite';
import { useZoneBankStore } from '../state/zoneBankStore';

export interface AddInviteResult {
  zoneId: string;
  relayCount: number;
  /** true if we already had an account name for this zone. */
  hasUsername: boolean;
}

/**
 * Returns the banked zone, or null if the text isn't a phone invite / carries
 * no zone id (a zone with no id can't be addressed on the relay).
 */
export function addInviteToBank(
  inviteUrl: string,
  opts?: { displayName?: string; username?: string },
): AddInviteResult | null {
  if (!isPhoneInviteUrl(inviteUrl)) return null;
  const invite = parsePhoneInvite(inviteUrl);
  const zoneId = invite.zoneId?.trim();
  if (!zoneId) return null;

  const bank = useZoneBankStore.getState();

  // Hold each relay (dedupe by wsUrl handled in the store).
  for (const r of invite.relays) {
    bank.addRelay({
      wsUrl: r.wsUrl,
      caFp: r.caFp,
      fp: r.fp,
      natsUser: r.natsUser,
      natsPass: r.natsPassword,
    });
  }

  // Keep an existing username if this zone is already banked (re-scanning an
  // invite shouldn't wipe your account name); else use the provided one (or
  // empty → Servers will prompt).
  const existing = bank.getZone(zoneId);
  const username = opts?.username ?? existing?.username ?? '';

  bank.addOrUpdateZone({
    zoneId,
    displayName: opts?.displayName ?? existing?.displayName ?? zoneId,
    relayUrls: invite.relays.map((r) => r.wsUrl),
    username,
  });

  return { zoneId, relayCount: invite.relays.length, hasUsername: !!username };
}
