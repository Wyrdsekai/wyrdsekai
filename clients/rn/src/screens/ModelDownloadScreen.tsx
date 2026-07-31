import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  FlatList,
  TouchableOpacity,
  StyleSheet,
  Alert,
  Platform,
} from 'react-native';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/types';
import { MODEL_CATALOG } from '../inference/ModelManager';
import { ModelInfo } from '../inference/types';
import { useInference } from '../inference/InferenceContext';
import { useInferenceStore } from '../state/inferenceStore';
import { useThemeColors } from '../theme/useTheme';
import { useStrings } from '../i18n/useStrings';

type Props = NativeStackScreenProps<RootStackParamList, 'ModelDownload'>;

const TIER_COLORS: Record<string, string> = {
  tiny: '#4CAF50',
  small: '#2196F3',
  medium: '#FF9800',
};

export function ModelDownloadScreen({ navigation }: Props) {
  const c = useThemeColors();
  const t = useStrings();
  const { llamaService, modelManager } = useInference();
  const activeModelId = useInferenceStore((s) => s.activeModelId);
  const downloadProgress = useInferenceStore((s) => s.downloadProgress);
  const setActiveModelId = useInferenceStore((s) => s.setActiveModelId);
  const setModelLoading = useInferenceStore((s) => s.setModelLoading);
  const updateDownloadProgress = useInferenceStore((s) => s.updateDownloadProgress);
  const clearDownloadProgress = useInferenceStore((s) => s.clearDownloadProgress);
  const setActiveBackend = useInferenceStore((s) => s.setActiveBackend);

  const [downloadedIds, setDownloadedIds] = useState<Set<string>>(new Set());
  const [smokeTestRunning, setSmokeTestRunning] = useState(false);
  const [diagInfo, setDiagInfo] = useState<string | null>(null);

  const refreshDownloaded = useCallback(async () => {
    try {
      const downloaded = await modelManager.getDownloadedModels();
      setDownloadedIds(new Set(downloaded.map((m) => m.id)));
    } catch (e: unknown) {
      console.warn('Failed to check downloaded models:', e instanceof Error ? e.message : e);
    }
  }, [modelManager]);

  useEffect(() => {
    refreshDownloaded();
    // Diagnostic: check RNFS and models dir
    (async () => {
      try {
        const RNFS = require('react-native-fs');
        const dir = modelManager.getModelsDir();
        const dirExists = await RNFS.exists(dir);
        if (!dirExists) {
          await RNFS.mkdir(dir);
        }
        const files = await RNFS.readDir(dir).catch(() => []);
        const fileList = files.map((f: { name: string; size: number | string }) => `${f.name} (${f.size}B)`).join(', ') || 'empty';
        setDiagInfo(`RNFS: OK | Dir: ${dir} | Files: ${fileList}`);
      } catch (e: unknown) {
        setDiagInfo(`RNFS ERROR: ${e instanceof Error ? e.message : String(e)}`);
      }
    })();
  }, [refreshDownloaded, modelManager]);

  const handleDownload = async (modelId: string) => {
    try {
      updateDownloadProgress(modelId, 0);
      await modelManager.downloadModel(modelId, (percent) => {
        updateDownloadProgress(modelId, percent);
      });
      clearDownloadProgress(modelId);
      await refreshDownloaded();
      const path = await modelManager.getModelPath(modelId);
      Alert.alert(t.models.downloadComplete, t.models.downloadCompleteBody(path ?? 'unknown'));
    } catch (e: unknown) {
      clearDownloadProgress(modelId);
      const msg = e instanceof Error ? e.message : String(e);
      Alert.alert(t.models.downloadFailed, t.models.downloadFailedBody(msg, modelManager.getModelsDir()));
    }
  };

  const handleLoad = async (model: ModelInfo) => {
    try {
      setModelLoading(true);
      const path = await modelManager.getModelPath(model.id);
      if (!path) {
        Alert.alert(t.models.error, t.models.modelNotFound);
        return;
      }
      await llamaService.loadModel(path);
      modelManager.setActiveModel(model.id);
      setActiveModelId(model.id);
      setActiveBackend('local');
    } catch (e: unknown) {
      Alert.alert(t.models.loadFailed, e instanceof Error ? e.message : t.models.unknownError);
    } finally {
      setModelLoading(false);
    }
  };

  const handleSmokeTest = async () => {
    if (!llamaService.isLoaded()) {
      Alert.alert(t.models.error, t.models.noModelLoaded);
      return;
    }
    setSmokeTestRunning(true);
    try {
      const response = await llamaService.complete(
        [
          { role: 'system', content: 'You are a helpful assistant. Respond briefly. /no_think' },
          { role: 'user', content: 'Hello! Say one sentence about yourself.' },
        ],
        { maxTokens: 128, temperature: 0.7 },
      );
      // Strip Qwen3 <think>...</think> tags if present
      const clean = response.content
        .replace(/<think>[\s\S]*?<\/think>/g, '')
        .replace(/<think>[\s\S]*/g, '')
        .trim();
      Alert.alert(
        t.models.inferenceTest,
        `${clean}\n\n(${response.promptTokens} prompt + ${response.completionTokens} completion tokens)`,
      );
    } catch (e: unknown) {
      Alert.alert(t.models.inferenceError, e instanceof Error ? e.message : t.models.unknownError);
    } finally {
      setSmokeTestRunning(false);
    }
  };

  const handleDelete = async (model: ModelInfo) => {
    Alert.alert(
      t.models.deleteModel,
      t.models.deleteModelBody(model.name, modelManager.formatSize(model.size)),
      [
        { text: t.models.cancel, style: 'cancel' },
        {
          text: t.models.delete,
          style: 'destructive',
          onPress: async () => {
            if (activeModelId === model.id) {
              await llamaService.unloadModel();
              setActiveModelId(null);
              setActiveBackend('none');
            }
            await modelManager.deleteModel(model.id);
            await refreshDownloaded();
          },
        },
      ],
    );
  };

  const renderModel = ({ item }: { item: ModelInfo }) => {
    const isDownloaded = downloadedIds.has(item.id);
    const isActive = activeModelId === item.id;
    const progress = downloadProgress[item.id];
    const isDownloading = progress !== undefined;
    const tierColor = TIER_COLORS[item.tier] ?? '#999';
    const tierLabels: Record<string, string> = {
      tiny: t.models.tierTiny,
      small: t.models.tierSmall,
      medium: t.models.tierMedium,
    };
    const tierLabel = tierLabels[item.tier] ?? item.tier;

    return (
      <View style={[styles.card, { backgroundColor: c.surface, borderColor: c.border }]} testID={`model-${item.id}`}>
        <View style={styles.cardHeader}>
          <Text style={[styles.modelName, { color: c.text }]}>{item.name}</Text>
          <View style={[styles.tierBadge, { backgroundColor: tierColor }]}>
            <Text style={styles.tierText}>{tierLabel}</Text>
          </View>
        </View>

        <Text style={[styles.modelSize, { color: c.textMuted }]}>{modelManager.formatSize(item.size)}</Text>
        <Text style={[styles.modelDesc, { color: c.textSecondary }]}>{item.description}</Text>

        <View style={styles.cardActions}>
          {isDownloaded ? (
            <>
              {isActive ? (
                <>
                  <View style={[styles.activeBadge, { backgroundColor: c.successLight, borderColor: c.success }]}>
                    <Text style={[styles.activeBadgeText, { color: c.success }]}>{t.models.active}</Text>
                  </View>
                  <TouchableOpacity
                    style={[styles.loadButton, { backgroundColor: smokeTestRunning ? '#999' : c.primary }]}
                    onPress={handleSmokeTest}
                    disabled={smokeTestRunning}
                    testID={`test-${item.id}`}
                  >
                    <Text style={[styles.loadButtonText, { color: c.textOnPrimary }]}>
                      {smokeTestRunning ? t.models.testing : t.models.test}
                    </Text>
                  </TouchableOpacity>
                </>
              ) : (
                <TouchableOpacity
                  style={[styles.loadButton, { backgroundColor: c.primary }]}
                  onPress={() => handleLoad(item)}
                  testID={`load-${item.id}`}
                >
                  <Text style={[styles.loadButtonText, { color: c.textOnPrimary }]}>{t.models.load}</Text>
                </TouchableOpacity>
              )}
              <TouchableOpacity
                style={[styles.deleteButton, { borderColor: c.error }]}
                onPress={() => handleDelete(item)}
              >
                <Text style={[styles.deleteButtonText, { color: c.error }]}>{t.models.delete}</Text>
              </TouchableOpacity>
            </>
          ) : isDownloading ? (
            <View style={styles.progressContainer}>
              <View style={[styles.progressTrack, { backgroundColor: c.border }]}>
                <View
                  style={[styles.progressFill, { backgroundColor: c.primary, width: `${progress}%` }]}
                />
              </View>
              <Text style={[styles.progressText, { color: c.textMuted }]}>{progress}%</Text>
            </View>
          ) : (
            <TouchableOpacity
              style={[styles.downloadButton, { backgroundColor: c.primary }]}
              onPress={() => handleDownload(item.id)}
              testID={`download-${item.id}`}
            >
              <Text style={[styles.downloadButtonText, { color: c.textOnPrimary }]}>{t.models.download}</Text>
            </TouchableOpacity>
          )}
        </View>
      </View>
    );
  };

  return (
    <View style={[styles.container, { backgroundColor: c.background }]} testID="model-download-screen">
      <View style={[styles.header, { backgroundColor: c.header }]}>
        <TouchableOpacity onPress={() => navigation.goBack()}>
          <Text style={[styles.backButton, { color: c.textOnHeader }]}>{t.common.back}</Text>
        </TouchableOpacity>
        <Text style={[styles.title, { color: c.textOnHeader }]}>{t.models.title}</Text>
        <View style={styles.headerSpacer} />
      </View>

      <FlatList
        data={MODEL_CATALOG}
        renderItem={renderModel}
        keyExtractor={(item) => item.id}
        contentContainerStyle={styles.listContent}
        extraData={{ downloadedIds, activeModelId, downloadProgress }}
      />

      <Text style={[styles.diagText, { color: c.textMuted }]}>{diagInfo ?? t.models.checking}</Text>
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
  listContent: { padding: 16 },
  card: {
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
    borderWidth: 1,
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 4,
  },
  modelName: { fontSize: 16, fontWeight: 'bold', flex: 1 },
  tierBadge: {
    paddingHorizontal: 10,
    paddingVertical: 3,
    borderRadius: 12,
    marginLeft: 8,
  },
  tierText: { color: '#fff', fontSize: 12, fontWeight: '600' },
  modelSize: { fontSize: 13, marginBottom: 4 },
  modelDesc: { fontSize: 14, lineHeight: 20, marginBottom: 12 },
  cardActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  activeBadge: {
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 8,
    borderWidth: 1,
  },
  activeBadgeText: { fontWeight: '600', fontSize: 14 },
  loadButton: {
    paddingHorizontal: 20,
    paddingVertical: 8,
    borderRadius: 8,
  },
  loadButtonText: { fontWeight: '600', fontSize: 14 },
  deleteButton: {
    borderWidth: 1,
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 8,
  },
  deleteButtonText: { fontWeight: '600', fontSize: 14 },
  downloadButton: {
    paddingHorizontal: 20,
    paddingVertical: 8,
    borderRadius: 8,
  },
  downloadButtonText: { fontWeight: '600', fontSize: 14 },
  progressContainer: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  progressTrack: {
    flex: 1,
    height: 8,
    borderRadius: 4,
    overflow: 'hidden',
  },
  progressFill: {
    height: '100%',
    borderRadius: 4,
  },
  progressText: {
    fontSize: 13,
    fontWeight: '600',
    minWidth: 36,
    textAlign: 'right',
  },
  diagText: {
    fontSize: 10,
    padding: 12,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
  },
});
