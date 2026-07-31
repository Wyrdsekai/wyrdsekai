/**
 * ServersScreen — your zone bank ( UI).
 *
 * The everyday surface: a list of the servers you have access to. Tap one →
 * it auto-attempts login across the relays that reach it (openZone). If this
 * device has no remembered password for that server yet, an inline field
 * appears; enter it once and it's remembered. On success the connected client
 * is handed to the session and onConnected fires (navigation).
 *
 * No relays, no zones, no URLs are shown — relays are invisible plumbing.
 */
import React, { useState } from 'react';
import {
  View, Text, TextInput, TouchableOpacity, ScrollView, ActivityIndicator, StyleSheet, Alert,
} from 'react-native';
import { useThemeColors } from '../theme/useTheme';
import type { ColorPalette } from '../theme/colors';
import { useZoneBankStore } from '../state/zoneBankStore';
import { useStandaloneNodeStore } from '../state/standaloneNodeStore';
import { openZone, createZoneAccount, forgetZonePassword } from '../server/openZone';
import { secureStorage } from '../state/secureStorage';
import { RelayTunnelHolder } from '../engine/transit';

interface Props {
  /** Fired after a successful connect+login; caller navigates into the world. */
  onConnected: (zoneId: string) => void;
  /** Optional: open the "Find a zone" discovery flow (SPEC §5, P4). */
  onFindZone?: () => void;
}

export function ServersScreen({ onConnected, onFindZone }: Props) {
  const colors = useThemeColors();
  const styles = makeStyles(colors);
  const zones = useZoneBankStore((s) => s.zones);
  const addOrUpdateZone = useZoneBankStore((s) => s.addOrUpdateZone);
  const setServerClient = useStandaloneNodeStore((s) => s.setServerClient);

  // Per-zone transient UI state.
  const [busyZone, setBusyZone] = useState<string | null>(null);
  const [pwPromptZone, setPwPromptZone] = useState<string | null>(null);
  const [pwInput, setPwInput] = useState('');
  const [userInput, setUserInput] = useState('');
  const [errorByZone, setErrorByZone] = useState<Record<string, string>>({});
  // Registration mode (2026-07-23, phone-first onboarding): create a NAMED
  // account over the relay instead of logging into an existing one. The
  // invite-code field appears only when the household reports itself
  // invite-only (registration_closed).
  const [registerZone, setRegisterZone] = useState<string | null>(null);
  const [inviteCodeInput, setInviteCodeInput] = useState('');
  const [needsInviteCode, setNeedsInviteCode] = useState(false);

  const resetPrompts = () => {
    setPwPromptZone(null);
    setRegisterZone(null);
    setPwInput('');
    setUserInput('');
    setInviteCodeInput('');
    setNeedsInviteCode(false);
  };

  // Create the account, then hand the connected client to the session —
  // identical adoption path to a successful login. A fresh household's first
  // registrant becomes the steward and gets a ONE-TIME recovery key; show it
  // in a blocking alert before entering the world (it will never be shown
  // again — it is the only password-reset credential).
  const submitRegister = async (zoneId: string) => {
    const u = userInput.trim();
    if (!u || !pwInput) return;
    setBusyZone(zoneId);
    setErrorByZone((e) => ({ ...e, [zoneId]: '' }));
    try {
      const r = await createZoneAccount(zoneId, {
        username: u,
        password: pwInput,
        inviteCode: needsInviteCode ? inviteCodeInput.trim() || undefined : undefined,
      });
      if (r.ok) {
        setServerClient(r.client);
        // Register must adopt the live relay tunnel exactly like login does
        // (attempt() below). Without this a fresh registrant landed on the
        // OFFLINE local node — "Present: Wyrd", every verb handled locally, the
        // zone companion unreachable ("considers…" forever) — because
        // StandaloneRoomScreen's tunnelRef only populates when RelayTunnelHolder
        // is set. Stand up the tunnel from the authenticated client and persist
        // the session token the zone's loopback /ws leg requires (else it
        // rejects `4002 Authentication required`). openZone already persisted
        // @wyrd_relay_url / @wyrd_zone_id via persistZoneSession. (2026-07-24)
        const tunnelBc = r.client.asBetweenClient();
        if (tunnelBc) {
          RelayTunnelHolder.set(tunnelBc);
        }
        const sessTok = r.client.getToken();
        if (sessTok) {
          await secureStorage.setItem('@wyrd_mcp_session_token', sessTok);
        }
        const enter = () => {
          resetPrompts();
          onConnected(zoneId);
        };
        if (r.recoveryKey) {
          Alert.alert(
            r.role === 'steward' ? 'You are the steward of this household' : 'Account created',
            `Save your recovery key — it is shown ONCE and is the only way to `
              + `reset your password:\n\n${r.recoveryKey}`,
            [{ text: "I've saved it", onPress: enter }],
          );
        } else {
          enter();
        }
        return;
      }
      if (r.reason === 'registration-closed') {
        setNeedsInviteCode(true);
        setErrorByZone((e) => ({ ...e, [zoneId]: r.error }));
        return;
      }
      setErrorByZone((e) => ({ ...e, [zoneId]: r.error }));
    } finally {
      setBusyZone(null);
    }
  };

  const attempt = async (zoneId: string, password?: string) => {
    setBusyZone(zoneId);
    setErrorByZone((e) => ({ ...e, [zoneId]: '' }));
    try {
      const r = await openZone(zoneId, password ? { password } : undefined);
      if (r.ok) {
        setServerClient(r.client);
        // Mode 1 (remote terminal): stand up the relay tunnel from the
        // authenticated client so StandaloneRoomScreen tunnels verbs to the REAL
        // zone (not the offline local node), and persist the relay URL under the
        // key the local-node bootstrap + model-skip gate read (@wyrd_relay_url).
        // openZone only recorded the relay in the zone bank, so on this login
        // path the tunnel never came up and the local Study model still
        // downloaded — the live-verify symptom. asBetweenClient() reuses the
        // already-authenticated NATS conn (nats.ws auto-reconnect).
        const tunnelBc = r.client.asBetweenClient();
        if (tunnelBc) {
          RelayTunnelHolder.set(tunnelBc);
        }
        await secureStorage.setItem('@wyrd_relay_url', r.relayUrl);
        // Persist the known zone id so the local-node bootstrap
        // (StandaloneNodeContext.setupServerClient) does NOT fall to
        // `_unknown` → `nc.discoverZone()`. The shared `relay_phone` NATS
        // account can't SUBSCRIBE wyrd.discover.>/wyrd.zone.> (only request/
        // reply via _INBOX), so that discovery raises a NATS Authorization
        // Violation that tears down the connection BEFORE the relay tunnel's
        // `.down` subscription lands — leaving the phone on the local node.
        // The invite already carries the zone, so discovery is redundant here.
        await secureStorage.setItem('@wyrd_zone_id', zoneId);
        // Persist the authenticated session token so the relay tunnel
        // (StandaloneRoomScreen → RelayTunnelServerConnection) hands it to the
        // zone's loopback /ws. Without it the tunnel OPENS but the loopback
        // leg is rejected `4002 Authentication required` and no frames render.
        const sessTok = r.client.getToken();
        if (sessTok) {
          await secureStorage.setItem('@wyrd_mcp_session_token', sessTok);
        }
        resetPrompts();
        onConnected(zoneId);
        return;
      }
      if (r.reason === 'needs-password') {
        setPwPromptZone(zoneId);
        return;
      }
      if (r.reason === 'auth-rejected') {
        // Wrong/stale password — forget it and re-prompt.
        await forgetZonePassword(zoneId);
        setPwPromptZone(zoneId);
        setErrorByZone((e) => ({ ...e, [zoneId]: r.error }));
        return;
      }
      setErrorByZone((e) => ({ ...e, [zoneId]: r.error })); // unreachable
    } finally {
      setBusyZone(null);
    }
  };

  // Submit the inline prompt: capture a username first if the zone has none
  // (invite-seeded zones don't know your account name), then attempt.
  const submitPrompt = async (zoneId: string) => {
    const zone = useZoneBankStore.getState().getZone(zoneId);
    if (zone && !zone.username) {
      const u = userInput.trim();
      if (!u) return;
      addOrUpdateZone({ ...zone, username: u });
    }
    await attempt(zoneId, pwInput);
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content} testID="servers-screen">
      <Text style={styles.title} testID="servers-title">Your servers</Text>

      {zones.length === 0 && (
        <Text style={styles.empty}>
          No servers yet. Add one by scanning or pasting an invite, or find a public zone below.
        </Text>
      )}

      {zones.map((z) => {
        const busy = busyZone === z.zoneId;
        const prompting = pwPromptZone === z.zoneId;
        const err = errorByZone[z.zoneId];
        return (
          <View key={z.zoneId} style={styles.card} testID={`server-card-${z.zoneId}`}>
            <TouchableOpacity
              disabled={busy}
              onPress={() => attempt(z.zoneId)}
              testID={`server-open-${z.zoneId}`}
            >
              <View style={styles.cardRow}>
                <View style={{ flex: 1 }}>
                  <Text style={styles.cardTitle}>{z.displayName}</Text>
                  <Text style={styles.cardSub}>
                    {z.username}{z.homeZone ? ' · home' : ''}
                  </Text>
                </View>
                {busy ? <ActivityIndicator /> : <Text style={styles.chevron}>›</Text>}
              </View>
            </TouchableOpacity>

            {prompting && registerZone !== z.zoneId && (
              <View style={styles.pwBlock}>
                {!z.username && (
                  <TextInput
                    style={styles.input}
                    value={userInput}
                    onChangeText={setUserInput}
                    placeholder={`Your username on ${z.displayName}`}
                    placeholderTextColor={colors.placeholder}
                    autoCapitalize="none"
                    autoCorrect={false}
                    testID={`server-user-${z.zoneId}`}
                  />
                )}
                <TextInput
                  style={styles.input}
                  value={pwInput}
                  onChangeText={setPwInput}
                  placeholder={`Password for ${z.displayName}`}
                  placeholderTextColor={colors.placeholder}
                  secureTextEntry
                  autoCapitalize="none"
                  autoCorrect={false}
                  testID={`server-pw-${z.zoneId}`}
                />
                <TouchableOpacity
                  style={[styles.primaryButton, (!pwInput || (!z.username && !userInput) || busy) && styles.disabled]}
                  disabled={!pwInput || (!z.username && !userInput) || busy}
                  onPress={() => submitPrompt(z.zoneId)}
                  testID={`server-pw-submit-${z.zoneId}`}
                >
                  <Text style={styles.primaryButtonText}>Log in</Text>
                </TouchableOpacity>
                <TouchableOpacity
                  onPress={() => {
                    setRegisterZone(z.zoneId);
                    setErrorByZone((e) => ({ ...e, [z.zoneId]: '' }));
                  }}
                  testID={`server-register-link-${z.zoneId}`}
                >
                  <Text style={styles.linkText}>
                    New here? Create your account on {z.displayName}
                  </Text>
                </TouchableOpacity>
              </View>
            )}

            {prompting && registerZone === z.zoneId && (
              <View style={styles.pwBlock}>
                <Text style={styles.registerHint}>
                  Choose the name and password you'll use on {z.displayName}.
                  On a brand-new household, the first account becomes the steward
                  — the keeper of the keys.
                </Text>
                <TextInput
                  style={styles.input}
                  value={userInput}
                  onChangeText={setUserInput}
                  placeholder="Choose a username"
                  placeholderTextColor={colors.placeholder}
                  autoCapitalize="none"
                  autoCorrect={false}
                  testID={`server-reg-user-${z.zoneId}`}
                />
                <TextInput
                  style={styles.input}
                  value={pwInput}
                  onChangeText={setPwInput}
                  placeholder="Choose a password (4+ characters)"
                  placeholderTextColor={colors.placeholder}
                  secureTextEntry
                  autoCapitalize="none"
                  autoCorrect={false}
                  testID={`server-reg-pw-${z.zoneId}`}
                />
                {needsInviteCode && (
                  <TextInput
                    style={styles.input}
                    value={inviteCodeInput}
                    onChangeText={setInviteCodeInput}
                    placeholder="Invite code from the steward"
                    placeholderTextColor={colors.placeholder}
                    autoCapitalize="none"
                    autoCorrect={false}
                    testID={`server-reg-code-${z.zoneId}`}
                  />
                )}
                <TouchableOpacity
                  style={[styles.primaryButton,
                    (!pwInput || pwInput.length < 4 || !userInput.trim()
                      || (needsInviteCode && !inviteCodeInput.trim()) || busy) && styles.disabled]}
                  disabled={!pwInput || pwInput.length < 4 || !userInput.trim()
                    || (needsInviteCode && !inviteCodeInput.trim()) || busy}
                  onPress={() => submitRegister(z.zoneId)}
                  testID={`server-reg-submit-${z.zoneId}`}
                >
                  <Text style={styles.primaryButtonText}>Create account</Text>
                </TouchableOpacity>
                <TouchableOpacity
                  onPress={() => {
                    setRegisterZone(null);
                    setNeedsInviteCode(false);
                    setErrorByZone((e) => ({ ...e, [z.zoneId]: '' }));
                  }}
                  testID={`server-reg-back-${z.zoneId}`}
                >
                  <Text style={styles.linkText}>I already have an account — log in</Text>
                </TouchableOpacity>
              </View>
            )}

            {!!err && <Text style={styles.error} testID={`server-error-${z.zoneId}`}>{err}</Text>}
          </View>
        );
      })}

      {onFindZone && (
        <TouchableOpacity style={styles.secondaryButton} onPress={onFindZone} testID="servers-find-zone">
          <Text style={styles.secondaryButtonText}>Find a zone…</Text>
        </TouchableOpacity>
      )}
    </ScrollView>
  );
}

function makeStyles(c: ColorPalette) {
  return StyleSheet.create({
    container: { flex: 1, backgroundColor: c.background },
    content: { padding: 24 },
    title: { fontSize: 28, fontWeight: '700', color: c.text, marginBottom: 16 },
    empty: { color: c.placeholder, marginBottom: 20, lineHeight: 20 },
    card: {
      borderWidth: 1, borderColor: c.border, borderRadius: 12,
      padding: 16, marginBottom: 12, backgroundColor: c.surface,
    },
    cardRow: { flexDirection: 'row', alignItems: 'center' },
    cardTitle: { fontSize: 17, fontWeight: '600', color: c.text },
    cardSub: { fontSize: 13, color: c.placeholder, marginTop: 2 },
    chevron: { fontSize: 24, color: c.placeholder },
    pwBlock: { marginTop: 12 },
    input: {
      borderWidth: 1, borderColor: c.border, borderRadius: 8,
      padding: 12, color: c.text, marginBottom: 8,
    },
    primaryButton: { backgroundColor: c.primary, borderRadius: 8, padding: 12, alignItems: 'center' },
    primaryButtonText: { color: c.textOnPrimary, fontWeight: '600' },
    disabled: { opacity: 0.5 },
    secondaryButton: {
      borderWidth: 1, borderColor: c.border, borderRadius: 8,
      padding: 12, alignItems: 'center', marginTop: 8,
    },
    secondaryButtonText: { color: c.text },
    error: { color: c.error, marginTop: 8 },
    linkText: { color: c.primary, marginTop: 10, textAlign: 'center', fontSize: 14 },
    registerHint: { color: c.placeholder, marginBottom: 8, lineHeight: 19, fontSize: 13 },
  });
}
