/**
 * HouseholdScreen — Manage household/Between network connection.
 *
 * Sections:
 * 1. Connection Status — colored dot, status text, household ID, connect/disconnect
 * 2. Connected Nodes — FlatList of online nodes
 * 3. Settings — household URL, relay URL, auto-discover toggle
 */
import React, { useState } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  FlatList,
  StyleSheet,
  ScrollView,
  Switch,
} from 'react-native';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/types';
import { useHouseholdStore, NodePresence } from '../state/householdStore';
import { useThemeColors } from '../theme/useTheme';
import { useStrings } from '../i18n/useStrings';
import type { ConnectivityState } from '../engine/discovery/types';

type Props = NativeStackScreenProps<RootStackParamList, 'Household'>;

/** Map ConnectivityState to a dot color. */
export function connectivityDotColor(state: ConnectivityState): string {
  switch (state) {
    case 'CONNECTED_LAN': return '#4CAF50';
    case 'CONNECTED_RELAY': return '#2196F3';
    case 'DISCOVERING': return '#FF9800';
    case 'RECONNECTING': return '#FF9800';
    case 'OFFLINE': return '#D32F2F';
  }
}

/** Map ConnectivityState to a human-readable label using i18n strings. */
function connectivityLabel(
  state: ConnectivityState,
  t: ReturnType<typeof useStrings>['household'],
): string {
  switch (state) {
    case 'CONNECTED_LAN': return t.connectedLan;
    case 'CONNECTED_RELAY': return t.connectedRelay;
    case 'DISCOVERING': return t.discovering;
    case 'RECONNECTING': return t.reconnecting;
    case 'OFFLINE': return t.offline;
  }
}

export function HouseholdScreen({ navigation }: Props) {
  const c = useThemeColors();
  const t = useStrings();

  const {
    connectivityState,
    connectedNodes,
    householdId,
    householdUrl,
    relayUrl,
    autoDiscover,
    setHouseholdUrl,
    setRelayUrl,
    setAutoDiscover,
    connect,
    disconnect,
  } = useHouseholdStore();

  const [urlInput, setUrlInput] = useState(householdUrl);
  const [relayInput, setRelayInput] = useState(relayUrl);

  const isConnected = connectivityState === 'CONNECTED_LAN' || connectivityState === 'CONNECTED_RELAY';
  const dotColor = connectivityDotColor(connectivityState);

  const handleConnect = () => {
    setHouseholdUrl(urlInput);
    setRelayUrl(relayInput);
    connect();
  };

  const handleDisconnect = () => {
    disconnect();
  };

  const renderNode = ({ item }: { item: NodePresence }) => {
    const nodeDotColor = item.status === 'online' ? '#4CAF50'
      : item.status === 'away' ? '#FF9800'
      : item.status === 'sleeping' ? '#9C27B0'
      : '#BDBDBD';

    return (
      <View style={[styles.nodeRow, { backgroundColor: c.surface, borderColor: c.border }]} testID={`node-${item.nodeId}`}>
        <View style={[styles.nodeDot, { backgroundColor: nodeDotColor }]} />
        <View style={styles.nodeInfo}>
          <Text style={[styles.nodeName, { color: c.text }]}>{item.nodeId}</Text>
          {item.tier && (
            <Text style={[styles.nodeTier, { color: c.textMuted }]}>{item.tier}</Text>
          )}
        </View>
        <Text style={[styles.nodeStatus, { color: c.textSecondary }]}>{item.status}</Text>
      </View>
    );
  };

  return (
    <View style={[styles.container, { backgroundColor: c.background }]} testID="household-screen">
      {/* Header */}
      <View style={[styles.header, { backgroundColor: c.header }]}>
        <TouchableOpacity onPress={() => navigation.goBack()} testID="household-back">
          <Text style={[styles.backButton, { color: c.textOnHeader }]}>{t.common.back}</Text>
        </TouchableOpacity>
        <Text style={[styles.title, { color: c.textOnHeader }]}>{t.household.title}</Text>
        <View style={styles.headerSpacer} />
      </View>

      <ScrollView contentContainerStyle={styles.content}>
        {/* Section 1: Connection Status */}
        <View style={styles.section}>
          <Text style={[styles.sectionTitle, { color: c.textMuted }]}>{t.household.connectionStatus}</Text>

          <View style={[styles.statusCard, { backgroundColor: c.surface, borderColor: c.border }]}>
            <View style={styles.statusRow}>
              <View style={[styles.statusDot, { backgroundColor: dotColor }]} testID="household-status-dot" />
              <Text style={[styles.statusText, { color: c.text }]} testID="household-status-text">
                {connectivityLabel(connectivityState, t.household)}
              </Text>
            </View>

            <View style={styles.idRow}>
              <Text style={[styles.idLabel, { color: c.placeholder }]}>{t.household.householdId}</Text>
              <Text style={[styles.idValue, { color: c.text }]} testID="household-id">
                {householdId ?? t.household.noHousehold}
              </Text>
            </View>

            <TouchableOpacity
              style={[
                styles.connectButton,
                { backgroundColor: isConnected ? c.error : c.primary },
              ]}
              onPress={isConnected ? handleDisconnect : handleConnect}
              testID="household-connect-button"
            >
              <Text style={[styles.connectButtonText, { color: c.textOnPrimary }]}>
                {isConnected ? t.household.disconnect : t.household.connect}
              </Text>
            </TouchableOpacity>
          </View>
        </View>

        {/* Section 2: Connected Nodes */}
        <View style={styles.section}>
          <Text style={[styles.sectionTitle, { color: c.textMuted }]}>{t.household.connectedNodes}</Text>

          {connectedNodes.length === 0 ? (
            <Text style={[styles.emptyText, { color: c.textMuted }]}>{t.household.noNodes}</Text>
          ) : (
            <FlatList
              data={connectedNodes}
              renderItem={renderNode}
              keyExtractor={(item) => item.nodeId}
              scrollEnabled={false}
              testID="household-nodes-list"
            />
          )}
        </View>

        {/* Section 3: Settings */}
        <View style={styles.section}>
          <Text style={[styles.sectionTitle, { color: c.textMuted }]}>{t.household.settingsSection}</Text>

          <Text style={[styles.fieldLabel, { color: c.textSecondary }]}>{t.household.householdUrl}</Text>
          <TextInput
            style={[styles.input, { borderColor: c.inputBorder, backgroundColor: c.inputBackground, color: c.text }]}
            value={urlInput}
            onChangeText={setUrlInput}
            onBlur={() => setHouseholdUrl(urlInput)}
            placeholder={t.household.householdUrlHint}
            placeholderTextColor={c.placeholder}
            autoCapitalize="none"
            autoCorrect={false}
            testID="household-url-input"
          />

          <Text style={[styles.fieldLabel, { color: c.textSecondary }]}>{t.household.relayUrl}</Text>
          <TextInput
            style={[styles.input, { borderColor: c.inputBorder, backgroundColor: c.inputBackground, color: c.text }]}
            value={relayInput}
            onChangeText={setRelayInput}
            onBlur={() => setRelayUrl(relayInput)}
            placeholder={t.household.relayUrlHint}
            placeholderTextColor={c.placeholder}
            autoCapitalize="none"
            autoCorrect={false}
            testID="household-relay-input"
          />

          <View style={styles.toggleRow}>
            <View style={styles.toggleTextCol}>
              <Text style={[styles.toggleLabel, { color: c.text }]}>{t.household.autoDiscover}</Text>
              <Text style={[styles.toggleHint, { color: c.textMuted }]}>{t.household.autoDiscoverHint}</Text>
            </View>
            <Switch
              value={autoDiscover}
              onValueChange={setAutoDiscover}
              trackColor={{ false: c.border, true: c.primaryLight }}
              thumbColor={autoDiscover ? c.primary : c.textMuted}
              testID="household-auto-discover-toggle"
            />
          </View>
        </View>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 16,
  },
  backButton: { fontWeight: 'bold', fontSize: 16 },
  title: { fontSize: 20, fontWeight: 'bold' },
  headerSpacer: { width: 40 },
  content: { padding: 16 },
  section: { marginBottom: 24 },
  sectionTitle: {
    fontSize: 14,
    fontWeight: '600',
    textTransform: 'uppercase',
    marginBottom: 8,
  },
  statusCard: {
    padding: 16,
    borderRadius: 8,
    borderWidth: 1,
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
  },
  statusDot: {
    width: 12,
    height: 12,
    borderRadius: 6,
    marginRight: 10,
  },
  statusText: {
    fontSize: 16,
    fontWeight: '600',
  },
  idRow: {
    marginBottom: 16,
  },
  idLabel: {
    fontSize: 12,
    marginBottom: 2,
  },
  idValue: {
    fontSize: 14,
  },
  connectButton: {
    paddingVertical: 12,
    borderRadius: 8,
    alignItems: 'center',
  },
  connectButtonText: {
    fontWeight: 'bold',
    fontSize: 15,
  },
  nodeRow: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 12,
    borderRadius: 8,
    borderWidth: 1,
    marginBottom: 8,
  },
  nodeDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
    marginRight: 10,
  },
  nodeInfo: {
    flex: 1,
  },
  nodeName: {
    fontSize: 15,
    fontWeight: '600',
  },
  nodeTier: {
    fontSize: 12,
    marginTop: 2,
  },
  nodeStatus: {
    fontSize: 13,
    fontWeight: '500',
  },
  emptyText: {
    fontSize: 14,
    fontStyle: 'italic',
    textAlign: 'center',
    paddingVertical: 16,
  },
  fieldLabel: {
    fontSize: 13,
    fontWeight: '500',
    marginBottom: 4,
    marginTop: 8,
  },
  input: {
    borderWidth: 1,
    borderRadius: 8,
    padding: 12,
    fontSize: 15,
    marginBottom: 8,
  },
  toggleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: 12,
    paddingVertical: 8,
  },
  toggleTextCol: {
    flex: 1,
    marginRight: 12,
  },
  toggleLabel: {
    fontSize: 15,
    fontWeight: '500',
  },
  toggleHint: {
    fontSize: 12,
    marginTop: 2,
  },
});
