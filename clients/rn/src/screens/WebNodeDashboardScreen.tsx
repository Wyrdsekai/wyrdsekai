import React, { useState } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  ScrollView,
  TextInput,
  Platform,
} from 'react-native';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/types';
import { useWebNodeStore } from '../state/webNodeStore';
import { WEB_MODEL_CATALOG } from '../web/WebLLMService';
import { useThemeColors } from '../theme/useTheme';
import { useStrings } from '../i18n/useStrings';

/** Minimal shape of the web node hook return value. */
interface WebNodeHookResult {
  ephemeralNode: {
    loadModel(modelId: string, onProgress: (p: { text: string; progress: number }) => void): Promise<void>;
    unloadModel(): Promise<void>;
    connectNats(url: string): Promise<void>;
    natsClient: { disconnect(): Promise<void> };
    startCompanion(): Promise<void>;
    look(): import('../protocol/models').RoomSnapshot | null;
  };
}

// Only import web node on web platform
let useWebNodeHook: (() => WebNodeHookResult) | null = null;
if (Platform.OS === 'web') {
  try {
    const mod = require('../web/WebNodeContext');
    useWebNodeHook = mod.useWebNode;
  } catch {}
}

type Props = NativeStackScreenProps<RootStackParamList, 'WebNodeDashboard'>;

export function WebNodeDashboardScreen({ navigation }: Props) {
  const c = useThemeColors();
  const t = useStrings();
  const {
    nodeState,
    nodeError,
    webLLMModelId,
    webLLMLoading,
    webLLMProgress,
    capabilities,
    natsConnected,
    roomSnapshot,
    companionState,
    swCacheStatus,
    setWebLLMLoading,
    setWebLLMProgress,
    setWebLLMModelId,
    setNatsConnected,
    setRoomSnapshot,
    setCompanionState,
  } = useWebNodeStore();

  const [natsUrl, setNatsUrl] = useState('ws://localhost:9222');
  const webNode = useWebNodeHook?.();

  const handleLoadModel = async (modelId: string) => {
    if (!webNode) return;
    setWebLLMLoading(true);
    setWebLLMProgress(null);
    try {
      await webNode.ephemeralNode.loadModel(modelId, (progress: { text: string; progress: number }) => {
        setWebLLMProgress(progress);
      });
      setWebLLMModelId(modelId);
    } catch (err: unknown) {
      console.error('[WebLLM] Model load failed:', err instanceof Error ? err.message : err);
      setWebLLMModelId(null);
    } finally {
      setWebLLMLoading(false);
      setWebLLMProgress(null);
    }
  };

  const handleUnloadModel = async () => {
    if (!webNode) return;
    await webNode.ephemeralNode.unloadModel();
    setWebLLMModelId(null);
  };

  const handleConnectNats = async () => {
    if (!webNode) return;
    try {
      await webNode.ephemeralNode.connectNats(natsUrl);
      setNatsConnected(true);
    } catch {
      setNatsConnected(false);
    }
  };

  const handleDisconnectNats = async () => {
    if (!webNode) return;
    await webNode.ephemeralNode.natsClient.disconnect();
    setNatsConnected(false);
  };

  const handleStartCompanion = async () => {
    if (!webNode) return;
    setCompanionState('idle');
    await webNode.ephemeralNode.startCompanion();
  };

  const handleLook = () => {
    if (!webNode) return;
    const snapshot = webNode.ephemeralNode.look();
    if (snapshot) setRoomSnapshot(snapshot);
  };

  const activeModelName = webLLMModelId
    ? WEB_MODEL_CATALOG.find((m) => m.id === webLLMModelId)?.name ?? webLLMModelId
    : null;

  return (
    <View style={[styles.container, { backgroundColor: c.background }]} testID="web-node-dashboard">
      <View style={[styles.header, { backgroundColor: c.header }]}>
        <TouchableOpacity onPress={() => navigation.goBack()}>
          <Text style={[styles.backButton, { color: c.textOnHeader }]}>{t.common.back}</Text>
        </TouchableOpacity>
        <Text style={[styles.title, { color: c.textOnHeader }]}>{t.webNode.title}</Text>
        <View style={styles.headerSpacer} />
      </View>

      <ScrollView contentContainerStyle={styles.content}>
        {/* Node Status */}
        <View style={styles.section}>
          <Text style={[styles.sectionTitle, { color: c.textMuted }]}>{t.webNode.nodeStatus}</Text>
          <View style={styles.statusRow}>
            <View testID="node-status-dot" style={[styles.statusDot, { backgroundColor: statusColor(nodeState) }]} />
            <Text style={[styles.statusText, { color: c.text }]}>{nodeState.charAt(0).toUpperCase() + nodeState.slice(1)}</Text>
          </View>
          {nodeError && <Text style={[styles.errorText, { color: c.error }]}>{nodeError}</Text>}
        </View>

        {/* Capabilities */}
        <View style={styles.section}>
          <Text style={[styles.sectionTitle, { color: c.textMuted }]}>{t.webNode.browserCapabilities}</Text>
          <CapRow label="WebGPU" ok={capabilities.webgpu} />
          <CapRow label="Web Crypto (Ed25519)" ok={capabilities.webCrypto} />
          <CapRow label="IndexedDB" ok={capabilities.indexedDB} />
          <CapRow label="Service Worker" ok={capabilities.serviceWorker} />
        </View>

        {/* NATS Connection */}
        <View style={styles.section}>
          <Text style={[styles.sectionTitle, { color: c.textMuted }]}>{t.webNode.betweenNats}</Text>
          <View style={styles.statusRow}>
            <View style={[styles.statusDot, { backgroundColor: natsConnected ? '#4CAF50' : '#BDBDBD' }]} />
            <Text style={[styles.statusText, { color: c.text }]}>{natsConnected ? t.webNode.connected : t.webNode.disconnected}</Text>
          </View>
          {!natsConnected ? (
            <View style={styles.natsRow}>
              <TextInput
                style={[styles.natsInput, { borderColor: c.inputBorder, color: c.text, backgroundColor: c.inputBackground }]}
                value={natsUrl}
                onChangeText={setNatsUrl}
                placeholder="ws://198.51.100.100:9222"
                placeholderTextColor={c.placeholder}
                testID="nats-url-input"
              />
              <TouchableOpacity style={[styles.natsButton, { backgroundColor: c.primary }]} onPress={handleConnectNats} testID="nats-connect-button">
                <Text style={[styles.natsButtonText, { color: c.textOnPrimary }]}>{t.webNode.connect}</Text>
              </TouchableOpacity>
            </View>
          ) : (
            <TouchableOpacity style={styles.disconnectButton} onPress={handleDisconnectNats}>
              <Text style={styles.disconnectText}>{t.webNode.disconnect}</Text>
            </TouchableOpacity>
          )}
        </View>

        {/* Room State */}
        {nodeState === 'running' && (
          <View style={styles.section}>
            <Text style={[styles.sectionTitle, { color: c.textMuted }]}>{t.webNode.roomState}</Text>
            <TouchableOpacity style={[styles.lookButton, { backgroundColor: c.primary }]} onPress={handleLook} testID="look-around-button">
              <Text style={[styles.lookButtonText, { color: c.textOnPrimary }]}>{t.webNode.lookAround}</Text>
            </TouchableOpacity>
            {roomSnapshot && (
              <View style={[styles.roomInfo, { backgroundColor: c.surface, borderColor: c.border }]}>
                <Text style={[styles.roomName, { color: c.text }]}>{roomSnapshot.name}</Text>
                <Text style={[styles.roomDesc, { color: c.textSecondary }]}>{roomSnapshot.description}</Text>
                {roomSnapshot.entities.length > 0 && (
                  <Text style={[styles.roomDetail, { color: c.textMuted }]}>
                    {t.webNode.present(roomSnapshot.entities.map(e => e.name).join(', '))}
                  </Text>
                )}
                {roomSnapshot.exits.length > 0 && (
                  <Text style={[styles.roomDetail, { color: c.textMuted }]}>
                    {t.webNode.exits(roomSnapshot.exits.map(e => `${t.room.directionLabels[e.direction] ?? e.direction} — ${e.label}`).join('; '))}
                  </Text>
                )}
              </View>
            )}
          </View>
        )}

        {/* Companion */}
        {nodeState === 'running' && (
          <View style={styles.section}>
            <Text style={[styles.sectionTitle, { color: c.textMuted }]}>{t.webNode.companion}</Text>
            <View style={styles.statusRow}>
              <View style={[styles.statusDot, {
                backgroundColor: companionState === 'thinking' ? '#FFC107' : companionState === 'idle' ? '#4CAF50' : '#BDBDBD',
              }]} />
              <Text style={[styles.statusText, { color: c.text }]}>
                {companionState === 'off' ? t.webNode.notStarted : companionState.charAt(0).toUpperCase() + companionState.slice(1)}
              </Text>
            </View>
            {companionState === 'off' && webLLMModelId && (
              <TouchableOpacity style={[styles.actionButton, { backgroundColor: c.primary }]} onPress={handleStartCompanion} testID="start-companion-button">
                <Text style={[styles.actionButtonText, { color: c.textOnPrimary }]}>{t.webNode.startCompanion}</Text>
              </TouchableOpacity>
            )}
          </View>
        )}

        {/* Service Worker Cache */}
        <View style={styles.section}>
          <Text style={[styles.sectionTitle, { color: c.textMuted }]}>{t.webNode.offlineCache}</Text>
          <View style={styles.statusRow}>
            <View style={[styles.statusDot, { backgroundColor: statusColor(swCacheStatus) }]} />
            <Text style={[styles.statusText, { color: c.text }]}>
              {swCacheStatus.charAt(0).toUpperCase() + swCacheStatus.slice(1)}
            </Text>
          </View>
        </View>

        {/* WebLLM Models */}
        {capabilities.webgpu && (
          <View style={styles.section}>
            <Text style={[styles.sectionTitle, { color: c.textMuted }]}>{t.webNode.browserInferenceWebLLM}</Text>
            {activeModelName && (
              <View style={[styles.activeModelRow, { backgroundColor: c.successLight }]}>
                <Text style={[styles.activeModelLabel, { color: c.success }]}>{t.webNode.activePrefix(activeModelName!)}</Text>
                <TouchableOpacity style={styles.unloadButton} onPress={handleUnloadModel}>
                  <Text style={styles.unloadText}>{t.webNode.unload}</Text>
                </TouchableOpacity>
              </View>
            )}

            {webLLMProgress && (
              <View style={styles.progressSection}>
                <Text style={[styles.progressText, { color: c.textMuted }]}>{webLLMProgress.text}</Text>
                <View style={[styles.progressBar, { backgroundColor: c.border }]}>
                  <View style={[styles.progressFill, { backgroundColor: c.primary, width: `${Math.round(webLLMProgress.progress * 100)}%` }]} />
                </View>
                <Text style={[styles.progressPercent, { color: c.textMuted }]}>{Math.round(webLLMProgress.progress * 100)}%</Text>
              </View>
            )}

            {WEB_MODEL_CATALOG.map((model) => (
              <View key={model.id} style={[styles.modelCard, { backgroundColor: c.surface, borderColor: c.border }]}>
                <View style={styles.modelHeader}>
                  <Text style={[styles.modelName, { color: c.text }]}>{model.name}</Text>
                  <Text style={[styles.modelSize, { color: c.textMuted }]}>
                    {model.size >= 1_000_000_000
                      ? `${(model.size / 1_000_000_000).toFixed(1)} GB`
                      : `${Math.round(model.size / 1_000_000)} MB`}
                  </Text>
                </View>
                <Text style={[styles.modelDescription, { color: c.textMuted }]}>{model.description}</Text>
                {webLLMModelId === model.id ? (
                  <View style={[styles.activeBadge, { backgroundColor: c.successLight }]}>
                    <Text style={[styles.activeBadgeText, { color: c.success }]}>{t.models.active}</Text>
                  </View>
                ) : (
                  <TouchableOpacity
                    style={[styles.actionButton, { backgroundColor: c.primary }, webLLMLoading && styles.actionButtonDisabled]}
                    onPress={() => handleLoadModel(model.id)}
                    disabled={webLLMLoading}
                    testID={`webllm-load-${model.id}`}
                  >
                    <Text style={[styles.actionButtonText, { color: c.textOnPrimary }]}>{webLLMLoading ? t.webNode.loading : t.webNode.loadModel}</Text>
                  </TouchableOpacity>
                )}
              </View>
            ))}
          </View>
        )}

        {!capabilities.webgpu && (
          <View style={styles.section}>
            <Text style={[styles.sectionTitle, { color: c.textMuted }]}>{t.webNode.browserInference}</Text>
            <Text style={[styles.warningText, { color: c.secondary }]}>
              {t.webNode.webgpuNotAvailable}
            </Text>
          </View>
        )}
      </ScrollView>
    </View>
  );
}

function CapRow({ label, ok }: { label: string; ok: boolean }) {
  const c = useThemeColors();
  const t = useStrings();
  return (
    <View style={[styles.capRow, { borderBottomColor: c.divider }]}>
      <Text style={[styles.capLabel, { color: c.textSecondary }]}>{label}</Text>
      <Text style={[styles.capValue, { color: ok ? '#4CAF50' : '#D32F2F' }]}>
        {ok ? t.webNode.supported : t.webNode.notAvailable}
      </Text>
    </View>
  );
}

function statusColor(state: string): string {
  switch (state) {
    case 'running': case 'active': return '#4CAF50';
    case 'starting': case 'installing': return '#FFC107';
    case 'error': return '#D32F2F';
    default: return '#BDBDBD';
  }
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  header: {
    flexDirection: 'row', justifyContent: 'space-between',
    alignItems: 'center', padding: 16,
  },
  backButton: { fontWeight: 'bold', fontSize: 16 },
  title: { fontSize: 20, fontWeight: 'bold' },
  headerSpacer: { width: 40 },
  content: { padding: 16 },
  section: { marginBottom: 24 },
  sectionTitle: {
    fontSize: 14, fontWeight: '600',
    textTransform: 'uppercase', marginBottom: 8,
  },
  statusRow: { flexDirection: 'row', alignItems: 'center', marginBottom: 4 },
  statusDot: { width: 12, height: 12, borderRadius: 6, marginRight: 8 },
  statusText: { fontSize: 16, fontWeight: '500' },
  errorText: { fontSize: 13, marginTop: 4 },
  capRow: {
    flexDirection: 'row', justifyContent: 'space-between',
    paddingVertical: 8, borderBottomWidth: 1,
  },
  capLabel: { fontSize: 14 },
  capValue: { fontSize: 14, fontWeight: '600' },
  natsRow: { flexDirection: 'row', marginTop: 8, gap: 8 },
  natsInput: {
    flex: 1, borderWidth: 1, borderRadius: 6,
    paddingHorizontal: 10, paddingVertical: 6, fontSize: 14,
  },
  natsButton: {
    paddingVertical: 8, paddingHorizontal: 16,
    borderRadius: 6, justifyContent: 'center',
  },
  natsButtonText: { fontWeight: 'bold', fontSize: 14 },
  disconnectButton: {
    backgroundColor: '#FF5722', paddingVertical: 8, borderRadius: 6,
    alignItems: 'center', marginTop: 8,
  },
  disconnectText: { color: '#fff', fontWeight: 'bold', fontSize: 14 },
  lookButton: {
    paddingVertical: 8, borderRadius: 6,
    alignItems: 'center', marginBottom: 8,
  },
  lookButtonText: { fontWeight: 'bold', fontSize: 14 },
  roomInfo: {
    padding: 12, borderRadius: 8, borderWidth: 1,
  },
  roomName: { fontSize: 16, fontWeight: '600', marginBottom: 4 },
  roomDesc: { fontSize: 14, marginBottom: 6 },
  roomDetail: { fontSize: 13, marginBottom: 2 },
  activeModelRow: {
    flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center',
    padding: 12, borderRadius: 8, marginBottom: 12,
  },
  activeModelLabel: { fontSize: 14, fontWeight: '600' },
  unloadButton: {
    backgroundColor: '#FF5722', paddingVertical: 6, paddingHorizontal: 14, borderRadius: 6,
  },
  unloadText: { color: '#fff', fontWeight: 'bold', fontSize: 13 },
  progressSection: { marginBottom: 12 },
  progressText: { fontSize: 13, marginBottom: 4 },
  progressBar: {
    height: 6, borderRadius: 3, overflow: 'hidden',
  },
  progressFill: { height: 6, borderRadius: 3 },
  progressPercent: { fontSize: 12, marginTop: 2, textAlign: 'right' },
  modelCard: {
    padding: 14, borderRadius: 8, borderWidth: 1, marginBottom: 10,
  },
  modelHeader: {
    flexDirection: 'row', justifyContent: 'space-between',
    alignItems: 'center', marginBottom: 4,
  },
  modelName: { fontSize: 15, fontWeight: '600' },
  modelSize: { fontSize: 13 },
  modelDescription: { fontSize: 13, marginBottom: 8 },
  actionButton: {
    paddingVertical: 8, borderRadius: 6, alignItems: 'center',
  },
  actionButtonDisabled: { opacity: 0.5 },
  actionButtonText: { fontWeight: 'bold', fontSize: 14 },
  activeBadge: {
    paddingVertical: 6, borderRadius: 6, alignItems: 'center',
  },
  activeBadgeText: { fontWeight: '600', fontSize: 14 },
  warningText: { fontSize: 14, lineHeight: 20 },
});
