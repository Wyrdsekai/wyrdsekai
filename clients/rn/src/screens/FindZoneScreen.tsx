/**
 * FindZoneScreen — "Find a zone" discovery.
 *
 * A separate, deliberate surface from your bank: search the opt-in ZoneDirectory
 * for zones that publish themselves. Only advertised zones appear; a relay's
 * roster is never enumerated and hidden zones never show. Reachable only once
 * you're connected to at least one zone (the query rides that zone's directory
 * over NATS) — that's expected; discovery needs a live relay leg.
 *
 * From a discovered zone you request access via the zone's steward (a per-zone
 * knock): "Request access" records a real access request that the target zone's
 * steward reviews and approves out-of-band (they mint an invite). It is not
 * theater — the knock rides the connected NATS leg to the target zone's own
 * directory subject. Zones already in your bank skip the knock.
 */
import React, { useState } from 'react';
import {
  View, Text, TextInput, TouchableOpacity, ScrollView, ActivityIndicator, StyleSheet,
} from 'react-native';
import { useThemeColors } from '../theme/useTheme';
import type { ColorPalette } from '../theme/colors';
import { useStandaloneNodeStore } from '../state/standaloneNodeStore';
import { useZoneBankStore } from '../state/zoneBankStore';
import { discoverZones, type DiscoveredZone } from '../server/discoverZones';
import type { DirectorySearchClient } from '../server/discoverZones';

export function FindZoneScreen() {
  const colors = useThemeColors();
  const styles = makeStyles(colors);
  const serverClient = useStandaloneNodeStore((s) => s.serverClient);

  const [query, setQuery] = useState('');
  const [busy, setBusy] = useState(false);
  const [searched, setSearched] = useState(false);
  const [results, setResults] = useState<DiscoveredZone[]>([]);
  const [error, setError] = useState('');
  // Per-zone knock state: 'asking' while in-flight, 'sent' once recorded.
  const [knockState, setKnockState] = useState<Record<string, 'asking' | 'sent'>>({});

  // The connected client doubles as the directory search transport. Only the
  // NATS client exposes searchDirectory; if we're not on a relay leg, say so.
  const canSearch =
    !!serverClient && typeof (serverClient as Partial<DirectorySearchClient>).searchDirectory === 'function';

  // knock on a discovered zone's door. Real: it
  // records an access request the zone's steward sees; they approve out-of-band
  // (mint an invite). Uses the connected NATS client's requestAccess, sent to
  // the TARGET zone's own subject.
  const requestAccess = async (zone: DiscoveredZone) => {
    const client = serverClient as { requestAccess?: (
      target: string, name: string, contact?: string, reason?: string,
    ) => Promise<{ ok: boolean; error?: string }> } | null;
    if (!client?.requestAccess) return;
    const me = useZoneBankStore.getState().homeZone()?.username || 'a wyrdsekai user';
    setKnockState((s) => ({ ...s, [zone.zoneLabel]: 'asking' }));
    const r = await client.requestAccess(zone.zoneLabel, me);
    if (r.ok) {
      setKnockState((s) => ({ ...s, [zone.zoneLabel]: 'sent' }));
    } else {
      setKnockState((s) => { const n = { ...s }; delete n[zone.zoneLabel]; return n; });
      setError(r.error ?? 'Could not reach that zone’s steward.');
    }
  };

  const run = async () => {
    if (!canSearch) {
      setError('Connect to one of your servers first — discovery rides that connection.');
      setSearched(true);
      return;
    }
    setBusy(true);
    setError('');
    try {
      const r = await discoverZones(serverClient as unknown as DirectorySearchClient, query.trim());
      setResults(r.zones);
      setError(r.error ?? '');
      setSearched(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content} testID="find-zone-screen">
      <Text style={styles.title} testID="find-zone-title">Find a zone</Text>
      <Text style={styles.blurb}>
        Search zones that have chosen to list themselves. To join one, ask its
        steward for an invite — public relays are never enumerated.
      </Text>

      <View style={styles.searchRow}>
        <TextInput
          style={styles.input}
          value={query}
          onChangeText={setQuery}
          placeholder="Name, tag: or capability:"
          placeholderTextColor={colors.placeholder}
          autoCapitalize="none"
          autoCorrect={false}
          onSubmitEditing={run}
          testID="find-zone-query"
        />
        <TouchableOpacity
          style={[styles.searchButton, busy && styles.disabled]}
          disabled={busy}
          onPress={run}
          testID="find-zone-search"
        >
          {busy ? <ActivityIndicator color={colors.textOnPrimary} /> : <Text style={styles.searchButtonText}>Search</Text>}
        </TouchableOpacity>
      </View>

      {!!error && <Text style={styles.error} testID="find-zone-error">{error}</Text>}

      {searched && !busy && !error && results.length === 0 && (
        <Text style={styles.empty} testID="find-zone-empty">
          No listed zones found. Most zones are private — ask the steward for an invite.
        </Text>
      )}

      {results.map((z) => {
        const knock = knockState[z.zoneLabel];
        return (
          <View key={z.zoneLabel} style={styles.card} testID={`find-zone-card-${z.zoneLabel}`}>
            <Text style={styles.cardTitle}>{z.displayName ?? z.zoneLabel}</Text>
            {!!z.tagline && <Text style={styles.cardSub}>{z.tagline}</Text>}
            {z.tags.length > 0 && <Text style={styles.cardTags}>{z.tags.join(' · ')}</Text>}
            {z.inBank ? (
              <Text style={styles.cardHint}>Already in your servers.</Text>
            ) : knock === 'sent' ? (
              <Text style={styles.cardHint} testID={`find-zone-sent-${z.zoneLabel}`}>
                Request sent — the steward will review it and send you an invite.
              </Text>
            ) : (
              <TouchableOpacity
                style={[styles.knockButton, knock === 'asking' && styles.disabled]}
                disabled={knock === 'asking'}
                onPress={() => requestAccess(z)}
                testID={`find-zone-knock-${z.zoneLabel}`}
              >
                {knock === 'asking'
                  ? <ActivityIndicator color={colors.textOnPrimary} />
                  : <Text style={styles.knockButtonText}>Request access</Text>}
              </TouchableOpacity>
            )}
          </View>
        );
      })}
    </ScrollView>
  );
}

function makeStyles(c: ColorPalette) {
  return StyleSheet.create({
    container: { flex: 1, backgroundColor: c.background },
    content: { padding: 24 },
    title: { fontSize: 28, fontWeight: '700', color: c.text, marginBottom: 8 },
    blurb: { color: c.placeholder, lineHeight: 20, marginBottom: 20 },
    searchRow: { flexDirection: 'row', alignItems: 'center', marginBottom: 12 },
    input: {
      flex: 1, borderWidth: 1, borderColor: c.border, borderRadius: 8,
      padding: 12, color: c.text, marginRight: 8,
    },
    searchButton: { backgroundColor: c.primary, borderRadius: 8, paddingVertical: 12, paddingHorizontal: 16 },
    searchButtonText: { color: c.textOnPrimary, fontWeight: '600' },
    disabled: { opacity: 0.5 },
    empty: { color: c.placeholder, marginTop: 16, lineHeight: 20 },
    error: { color: c.error, marginTop: 8 },
    card: {
      borderWidth: 1, borderColor: c.border, borderRadius: 12,
      padding: 16, marginTop: 12, backgroundColor: c.surface,
    },
    cardTitle: { fontSize: 17, fontWeight: '600', color: c.text },
    cardSub: { fontSize: 14, color: c.text, marginTop: 4 },
    cardTags: { fontSize: 12, color: c.placeholder, marginTop: 6 },
    cardHint: { fontSize: 13, color: c.placeholder, marginTop: 10, fontStyle: 'italic' },
    knockButton: {
      backgroundColor: c.primary, borderRadius: 8, paddingVertical: 10,
      alignItems: 'center', marginTop: 12,
    },
    knockButtonText: { color: c.textOnPrimary, fontWeight: '600' },
  });
}
