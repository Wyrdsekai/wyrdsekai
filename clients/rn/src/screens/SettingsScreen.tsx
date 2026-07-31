import React from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  ScrollView,
  Platform,
  I18nManager,
  Alert,
  Modal,
  Share,
  Switch,
} from 'react-native';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/types';
import { useSessionStore } from '../state/sessionStore';
import { usePreferencesStore, ThemeMode } from '../state/preferencesStore';
import { useCredentialStore } from '../state/credentialStore';
import { useInferenceStore } from '../state/inferenceStore';
import { useWebNodeStore } from '../state/webNodeStore';
import { useHouseholdStore } from '../state/householdStore';
import { useAppModeStore } from '../state/appModeStore';
import { useZoneBankStore, zonePasswordKey } from '../state/zoneBankStore';
import { MODEL_CATALOG } from '../inference/ModelManager';
import { useWs } from '../App';
import { newId } from '../protocol/c2s';
import { isRtl } from '../i18n/i18nStrings';
import { useThemeColors } from '../theme/useTheme';
import { useStrings } from '../i18n/useStrings';
import { connectivityDotColor } from './HouseholdScreen';
import { SoulSyncManager } from '../engine/soul/SoulSyncManager';
import { AsyncStorageSoulManifestStore } from '../engine/persistence/AsyncStorageSoulManifestStore';
// Two storage backends in play:
//   • secureStorage — MMKV-backed, encrypted. Houses credentials, API keys,
//     and identity/topology config (companion DID, server URL, between URL).
//   • rawAsyncStorage — RKStorage SQLite. Used ONLY as the backing store for
//     bulk user-data persisters (here: AsyncStorageSoulManifestStore). Anything
//     a paired user wrote pre-migration still lives here for read-only fallback,
//     but new credential writes always go to secureStorage.
import rawAsyncStorage from '@react-native-async-storage/async-storage';
import { secureStorage } from '../state/secureStorage';
import { OpenRouterAuthScreen } from './OpenRouterAuthScreen';
import { useInference } from '../inference/InferenceContext';
import { collectModeInputs, resolvePhoneMode, applyModeToRouter, availableBackings, modeLabel } from '../engine/mode/currentMode';
import { decideMode } from '../engine/mode/PhoneMode';
import type { Backing, PhoneMode } from '../engine/mode/PhoneMode';

type Props = NativeStackScreenProps<RootStackParamList, 'Settings'>;

const LANGUAGES: { code: string; label: string }[] = [
  { code: 'en', label: 'English' },
  { code: 'es', label: 'Espa\u00f1ol' },
  { code: 'ja', label: '\u65e5\u672c\u8a9e' },
];

const API_PROVIDERS: { code: string; label: string }[] = [
  { code: 'openai', label: 'OpenAI' },
  { code: 'anthropic', label: 'Anthropic' },
  { code: 'openrouter', label: 'OpenRouter' },
  { code: 'custom', label: 'Custom' },
];

const KEY_API_KEY = '@wyrd_api_key';
const KEY_API_PROVIDER = '@wyrd_api_provider';
const KEY_API_BASE_URL = '@wyrd_api_base_url';
const KEY_DEBUG_MODE = '@wyrd_debug_mode';

export function SettingsScreen({ navigation }: Props) {
  const ws = useWs();
  const c = useThemeColors();
  const t = useStrings();
  const serverUrl = useSessionStore((s) => s.serverUrl);
  const connectionState = useSessionStore((s) => s.connectionState);
  const savedUsername = useCredentialStore((s) => s.savedUsername);
  const clearCredentials = useCredentialStore((s) => s.clearCredentials);
  const { locale, setLocale, theme, setTheme } = usePreferencesStore();
  const setConnectionState = useSessionStore((s) => s.setConnectionState);
  const setToken = useSessionStore((s) => s.setToken);
  const activeModelId = useInferenceStore((s) => s.activeModelId);
  const activeBackend = useInferenceStore((s) => s.activeBackend);

  // Companion / Soul sync state
  const appMode = useAppModeStore((s) => s.mode);
  const companionName = useAppModeStore((s) => s.companionName);
  const homeName = useAppModeStore((s) => s.homeName);
  const setHomeName = useAppModeStore((s) => s.setHomeName);
  const lastSoulSyncTime = useAppModeStore((s) => s.lastSoulSyncTime);
  const soulManifestVersion = useAppModeStore((s) => s.soulManifestVersion);
  const savedInferenceUrl = useAppModeStore((s) => s.inferenceUrl);
  const setInferenceUrl = useAppModeStore((s) => s.setInferenceUrl);
  const relayUrl = useAppModeStore((s) => s.relayUrl);
  const householdName = useAppModeStore((s) => s.householdName);

  const { inferenceRouter } = useInference();
  const onDeviceOptIn = useAppModeStore((s) => s.onDeviceModelOptIn);

  // Whether a home-zone leg exists at all. This drives the connect/disconnect
  // ACTION only — it is NOT the mode. Modes 1 and 4 both have a zone leg and
  // are different products, so reading this as a mode is the §0b defect.
  const hasHomeZoneLeg = Boolean(relayUrl);

  // The mode the phone is actually in, from both axes. Resolved async because
  // some inputs live in secure storage; null until it settles, and null is
  // also the honest answer for a half-configured phone.
  const [resolvedMode, setResolvedMode] = React.useState<PhoneMode | null>(null);
  React.useEffect(() => {
    let alive = true;
    resolvePhoneMode({ hasOnDeviceModel: inferenceRouter.canInferLocally() })
      .then((d) => { if (alive) setResolvedMode(d.mode); })
      .catch(() => {});
    return () => { alive = false; };
  }, [inferenceRouter, appMode, relayUrl, savedInferenceUrl, onDeviceOptIn]);

  const effectiveModeLabel =
    resolvedMode === 1
      ? `Remote terminal${householdName ? ` · ${householdName}` : ''}`
      : resolvedMode === 4
        ? `On this phone · ${householdName ?? 'home zone'} behind it`
        : resolvedMode === 5
          ? 'On this phone · cloud API behind it'
          : resolvedMode === 2
            ? 'On this phone · cloud inference'
            : resolvedMode === 3
              ? 'On this phone · on-device model'
              : 'Setup incomplete';
  const effectiveModeHint =
    resolvedMode === 1
      ? 'Rooms render live from your zone; your Study syncs with it. Nothing runs here.'
      : resolvedMode === 4
        ? 'Your companion lives on this phone and borrows the household for planning and skills.'
        : resolvedMode === 5
          ? 'Your companion lives on this phone and uses your cloud API for planning and skills.'
          : resolvedMode === 2
            ? 'A local Study on this phone, thinking via your cloud API key.'
            : resolvedMode === 3
              ? 'A local Study on this phone, thinking on the on-device model.'
              : 'Finish setup: this phone has no home zone, no API key, and no on-device model.';
  const setLastSoulSync = useAppModeStore((s) => s.setLastSoulSync);

  const [editingName, setEditingName] = React.useState(companionName);
  const [editingHomeName, setEditingHomeName] = React.useState(homeName);
  const [manualInferenceUrl, setManualInferenceUrl] = React.useState(savedInferenceUrl ?? '');
  const [inferenceTestResult, setInferenceTestResult] = React.useState<string | null>(null);
  const [syncing, setSyncing] = React.useState(false);

  // API key / provider state
  const [apiProvider, setApiProvider] = React.useState('openai');
  const [apiKey, setApiKey] = React.useState('');
  const [apiKeyVisible, setApiKeyVisible] = React.useState(false);
  const [apiBaseUrl, setApiBaseUrl] = React.useState('');
  const [debugMode, setDebugMode] = React.useState(false);

  // OpenRouter OAuth dialog state.
  const [showOpenRouterAuth, setShowOpenRouterAuth] = React.useState(false);
  const [openRouterAuthError, setOpenRouterAuthError] = React.useState<string | null>(null);

  // Load persisted API settings
  React.useEffect(() => {
    (async () => {
      const [p, k, u, d] = await Promise.all([
        secureStorage.getItem(KEY_API_PROVIDER),
        secureStorage.getItem(KEY_API_KEY),
        secureStorage.getItem(KEY_API_BASE_URL),
        secureStorage.getItem(KEY_DEBUG_MODE),
      ]);
      if (p) setApiProvider(p);
      if (k) setApiKey(k);
      if (u) setApiBaseUrl(u);
      if (d === 'true') setDebugMode(true);
    })();
  }, []);

  const handleNameChange = async (newName: string) => {
    const trimmed = newName.trim() || 'Wyrd';
    setEditingName(trimmed);
    await secureStorage.setItem('@wyrd_companion_name', trimmed);
    useAppModeStore.getState().setLocalMode(trimmed).catch(() => {});
  };

  const handleHomeNameChange = (newName: string) => {
    const trimmed = newName.trim() || 'Home';
    setEditingHomeName(trimmed);
    setHomeName(trimmed);
  };

  const handleTestInferenceUrl = async () => {
    const url = manualInferenceUrl.trim();
    if (!url) return;
    setInferenceTestResult(null);
    try {
      const res = await fetch(`${url}/v1/models`, { method: 'GET' });
      if (res.ok) {
        setInferenceTestResult('OK');
        setInferenceUrl(url);
      } else {
        setInferenceTestResult(`Failed: HTTP ${res.status}`);
      }
    } catch (e: any) {
      setInferenceTestResult(`Failed: ${e.message ?? 'unreachable'}`);
    }
  };

  const handleForceSync = async () => {
    setSyncing(true);
    try {
      const serverUrl = await secureStorage.getItem('@wyrd_server_url');
      const betweenUrl = await secureStorage.getItem('@wyrd_between_url');
      let resolvedUrl = serverUrl;
      if (!resolvedUrl && betweenUrl) {
        try {
          const parsed = new URL(betweenUrl);
          resolvedUrl = `http://${parsed.hostname}:8080`;
        } catch { /* skip */ }
      }
      if (!resolvedUrl) {
        Alert.alert('No Server', 'No household server URL configured.');
        return;
      }

      // Soul manifest is bulk user data — keeps its AsyncStorage-backed store.
      // Token + DID are credentials/identity — go through secureStorage.
      const store = new AsyncStorageSoulManifestStore(rawAsyncStorage as any);
      const token = (await secureStorage.getItem('@wyrd_token')) ?? undefined;
      const syncManager = new SoulSyncManager(store, resolvedUrl, token);
      const did = (await secureStorage.getItem('@wyrd_companion_did')) ?? 'did:key:bootstrap-ma';
      const pulled = await syncManager.tryPullFromServer(did, companionName);
      if (pulled) {
        const syncTime = syncManager.getLastSyncTime();
        const syncVersion = syncManager.getLastSyncVersion();
        if (syncTime != null && syncVersion != null) {
          setLastSoulSync(syncTime, syncVersion);
        }
        Alert.alert('Synced', `Soul updated to version ${pulled.manifestVersion}.`);
      } else {
        Alert.alert('Up to date', 'Local manifest is already current.');
      }
    } catch (e: any) {
      Alert.alert('Sync failed', e.message ?? 'Unknown error');
    } finally {
      setSyncing(false);
    }
  };

  // Zone bank — the address book Settings surfaces.
  const bankZones = useZoneBankStore((s) => s.zones);
  const confirmForgetZone = (zoneId: string, displayName: string) => {
    Alert.alert(
      `Forget ${displayName}?`,
      'Removes this zone and its saved login from this phone. The zone itself '
        + 'is untouched — a fresh invite adds it back.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Forget',
          style: 'destructive',
          onPress: async () => {
            useZoneBankStore.getState().removeZone(zoneId);
            await secureStorage.removeItem(zonePasswordKey(zoneId)).catch(() => {});
          },
        },
      ],
    );
  };

  const handleSwitchMode = () => {
    if (hasHomeZoneLeg) {
      // Switch to standalone. HONEST COPY: the home-zone companion stays on
      // the home machines — the phone stops being her terminal and runs its
      // OWN companion. Reversible: the zone bank keeps the zone + relays +
      // username (per-zone password stays in secureStorage), so returning is
      // one tap on the Servers screen — switching never burns the invite.
      Alert.alert(
        'Switch to standalone?',
        `Your companion on ${householdName ?? 'your home zone'} stays home — this phone stops `
          + 'being her window and runs its own companion instead. The zone stays '
          + 'saved in My zones; switch back any time with one tap.',
        [
          { text: 'Cancel', style: 'cancel' },
          {
            text: 'Switch',
            style: 'destructive',
            onPress: async () => {
              // Drop the LIVE tunnel too — clearing storage alone left the
              // running relay session (and study sync) alive until app restart.
              try {
                const { RelayTunnelHolder } = await import('../engine/transit');
                RelayTunnelHolder.clear();
              } catch { /* holder optional */ }
              await useAppModeStore.getState().disconnectHomeZone(companionName);
              // Your servers, not the legacy Connect screen. Landing on Connect
              // put people back on the pre-Welcome UI ("Connect without account",
              // "Switch to local companion instead") — a screen we replaced.
              navigation.reset({ index: 0, routes: [{ name: 'Servers' }] });
            },
          },
        ],
      );
    } else {
      // Local → offer to connect to a home zone (paste/scan an invite).
      Alert.alert(
        'Connect to a home zone',
        'Point this phone at a household zone via its invite. Your local Study will sync with it.',
        [
          { text: 'Cancel', style: 'cancel' },
          { text: 'Connect', onPress: () => navigation.reset({ index: 0, routes: [{ name: 'Servers' }] }) },
        ],
      );
    }
  };

  const formatSyncTime = (ts: number | null): string => {
    if (ts == null) return 'Never';
    const d = new Date(ts);
    return d.toLocaleString();
  };

  const THEME_OPTIONS: { value: ThemeMode; label: string }[] = [
    { value: 'system', label: t.settings.themeSystem },
    { value: 'light', label: t.settings.themeLight },
    { value: 'dark', label: t.settings.themeDark },
  ];

  const activeModelName = activeModelId
    ? MODEL_CATALOG.find((m) => m.id === activeModelId)?.name ?? activeModelId
    : t.settings.noModelLoaded;

  const backendDotColor =
    activeBackend === 'local' ? '#4CAF50' : activeBackend === 'server' ? '#2196F3' : '#BDBDBD';
  const backendLabel =
    activeBackend === 'local' ? t.settings.backendLocal : activeBackend === 'server' ? t.settings.backendServer : t.settings.backendNone;

  const handleLanguageSelect = (code: string) => {
    setLocale(code);
    ws.setLocale(code);
    ws.send({ type: 'set_preference', id: newId(), key: 'locale', value: code });
    const shouldBeRtl = isRtl(code);
    if (I18nManager.isRTL !== shouldBeRtl) {
      I18nManager.forceRTL(shouldBeRtl);
    }
  };

  const handleLogout = () => {
    ws.disconnect();
    setToken(null);
    setConnectionState('disconnected');
    clearCredentials();
    // Log out lands on Your servers — the zone stays in the bank, so getting
    // back in is one tap. Parity with KMP, whose logout sets appMode="servers".
    // This used to reset to the legacy Connect screen.
    navigation.reset({
      index: 0,
      routes: [{ name: 'Servers' }],
    });
  };

  const isConnected = connectionState === 'connected';
  const connDotColor = isConnected ? '#4CAF50' : '#D32F2F';

  return (
    <View style={[styles.container, { backgroundColor: c.background }]} testID="settings-screen">
      <View style={[styles.header, { backgroundColor: c.header }]}>
        <TouchableOpacity onPress={() => navigation.goBack()}>
          <Text style={[styles.backButton, { color: c.textOnHeader }]}>{t.common.back}</Text>
        </TouchableOpacity>
        <Text style={[styles.title, { color: c.textOnHeader }]}>{t.settings.title}</Text>
        <View style={styles.headerSpacer} />
      </View>

      <ScrollView contentContainerStyle={styles.content}>
        {/* ---------------------------------------------------------------
            CONNECTION section
            --------------------------------------------------------------- */}
        <SectionHeader label={t.settings.connection} color={c.textMuted} />

        <View style={[styles.readOnlyField, { backgroundColor: c.surface, borderColor: c.border }]}>
          <Text style={[styles.readOnlyLabel, { color: c.placeholder }]}>{t.settings.serverUrl}</Text>
          <Text style={[styles.readOnlyValue, { color: c.text }]}>{serverUrl}</Text>
        </View>

        <View style={[styles.statusRow, { marginTop: 8 }]}>
          <View style={[styles.statusDot, { backgroundColor: connDotColor }]} />
          <Text style={[styles.statusLabel, { color: c.textSecondary }]}>
            {t.settings.connectionStatus}: {isConnected ? t.settings.connected : t.settings.disconnected}
          </Text>
        </View>

        <View style={[styles.readOnlyField, { backgroundColor: c.surface, borderColor: c.border, marginTop: 8 }]}>
          <Text style={[styles.readOnlyLabel, { color: c.placeholder }]}>{t.settings.username}</Text>
          <Text style={[styles.readOnlyValue, { color: c.text }]}>{savedUsername ?? t.settings.anonymous}</Text>
        </View>

        <View style={styles.divider} />

        {/* ---------------------------------------------------------------
            INFERENCE section
            --------------------------------------------------------------- */}
        <SectionHeader label={t.settings.inference} color={c.textMuted} />

        {/* EXPERIMENTAL: run the model on this device. Off by default and
            placed FIRST, because it is what unlocks modes 3/4/5 below. */}
        <OnDeviceModelToggle />

        {/* Mode 4 vs 5 — what stands behind this phone for heavy work.
            Renders only when the choice is real (see BackingPicker). */}
        <BackingPicker />

        {/* Provider selector */}
        <Text style={[styles.fieldLabel, { color: c.textSecondary }]}>{t.settings.apiProvider}</Text>
        <View style={styles.chipGrid}>
          {API_PROVIDERS.map((prov) => (
            <TouchableOpacity
              key={prov.code}
              style={[
                styles.chip,
                { borderColor: c.primary, backgroundColor: c.surface },
                apiProvider === prov.code && { backgroundColor: c.primary },
              ]}
              onPress={() => {
                setApiProvider(prov.code);
                secureStorage.setItem(KEY_API_PROVIDER, prov.code);
              }}
              testID={`provider-${prov.code}`}
            >
              <Text
                style={[
                  styles.chipText,
                  { color: c.primary },
                  apiProvider === prov.code && { color: c.textOnPrimary },
                ]}
              >
                {prov.label}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        {/* API key field */}
        <View style={[styles.readOnlyField, { backgroundColor: c.surface, borderColor: c.border, marginTop: 12 }]}>
          <Text style={[styles.readOnlyLabel, { color: c.placeholder }]}>{t.settings.apiKey}</Text>
          <View style={{ flexDirection: 'row', alignItems: 'center' }}>
            <TextInput
              style={[styles.editableValue, { color: c.text, flex: 1 }]}
              value={apiKey}
              onChangeText={(v) => {
                setApiKey(v);
                secureStorage.setItem(KEY_API_KEY, v);
              }}
              placeholder={t.settings.apiKeyPlaceholder}
              placeholderTextColor={c.placeholder}
              secureTextEntry={!apiKeyVisible}
              autoCapitalize="none"
              autoCorrect={false}
              testID="api-key-input"
            />
            <TouchableOpacity onPress={() => setApiKeyVisible(!apiKeyVisible)}>
              <Text style={[styles.chipText, { color: c.primary }]}>{apiKeyVisible ? 'Hide' : 'Show'}</Text>
            </TouchableOpacity>
          </View>
        </View>

        {/* OpenRouter OAuth sign-in. Loopback PKCE flow: opens an in-app
            WebView pointed at openrouter.ai/auth and intercepts the
            http://localhost:3000/callback redirect before it goes out. */}
        {apiProvider === 'openrouter' && (
          <>
            <TouchableOpacity
              style={[styles.actionButton, { backgroundColor: c.primary, marginTop: 12 }]}
              onPress={() => {
                setOpenRouterAuthError(null);
                setShowOpenRouterAuth(true);
              }}
              testID="openrouter-signin-button"
            >
              <Text style={[styles.actionButtonText, { color: c.textOnPrimary }]}>
                Sign in with OpenRouter
              </Text>
            </TouchableOpacity>
            {openRouterAuthError && (
              <Text
                style={[styles.testResultText, { color: c.error ?? '#D32F2F' }]}
                testID="openrouter-signin-error"
              >
                {openRouterAuthError}
              </Text>
            )}
          </>
        )}

        {/* Base URL for custom provider */}
        {apiProvider === 'custom' && (
          <View style={[styles.readOnlyField, { backgroundColor: c.surface, borderColor: c.border, marginTop: 8 }]}>
            <Text style={[styles.readOnlyLabel, { color: c.placeholder }]}>{t.settings.apiBaseUrl}</Text>
            <TextInput
              style={[styles.editableValue, { color: c.text }]}
              value={apiBaseUrl}
              onChangeText={(v) => {
                setApiBaseUrl(v);
                secureStorage.setItem(KEY_API_BASE_URL, v);
              }}
              placeholder={t.settings.apiBaseUrlPlaceholder}
              placeholderTextColor={c.placeholder}
              autoCapitalize="none"
              autoCorrect={false}
              testID="api-base-url-input"
            />
          </View>
        )}

        {/* Local inference model info */}
        <View style={[styles.readOnlyField, { backgroundColor: c.surface, borderColor: c.border, marginTop: 12 }]}>
          <Text style={[styles.readOnlyLabel, { color: c.placeholder }]}>{t.settings.activeModel}</Text>
          <Text style={[styles.readOnlyValue, { color: c.text }]}>{activeModelName}</Text>
        </View>
        <View style={styles.statusRow}>
          <View style={[styles.statusDot, { backgroundColor: backendDotColor }]} />
          <Text style={[styles.statusLabel, { color: c.textSecondary }]}>{backendLabel}</Text>
        </View>
        <TouchableOpacity
          style={[styles.actionButton, { backgroundColor: c.primary, marginTop: 8 }]}
          onPress={() => navigation.navigate('ModelDownload')}
          testID="manage-models-button"
        >
          <Text style={[styles.actionButtonText, { color: c.textOnPrimary }]}>{t.settings.manageModels}</Text>
        </TouchableOpacity>

        <View style={styles.divider} />

        {/* ---------------------------------------------------------------
            COMPANION section
            --------------------------------------------------------------- */}
        <SectionHeader label={t.settings.companion} color={c.textMuted} />

        <View style={[styles.readOnlyField, { backgroundColor: c.surface, borderColor: c.border }]}>
          <Text style={[styles.readOnlyLabel, { color: c.placeholder }]}>{t.settings.companionName}</Text>
          <TextInput
            style={[styles.editableValue, { color: c.text }]}
            value={editingName}
            onChangeText={setEditingName}
            onBlur={() => handleNameChange(editingName)}
            onSubmitEditing={() => handleNameChange(editingName)}
            returnKeyType="done"
            testID="companion-name-input"
          />
        </View>
        <View style={[styles.readOnlyField, { backgroundColor: c.surface, borderColor: c.border, marginTop: 8 }]}>
          <Text style={[styles.readOnlyLabel, { color: c.placeholder }]}>Room Name</Text>
          <TextInput
            style={[styles.editableValue, { color: c.text }]}
            value={editingHomeName}
            onChangeText={setEditingHomeName}
            onBlur={() => handleHomeNameChange(editingHomeName)}
            onSubmitEditing={() => handleHomeNameChange(editingHomeName)}
            returnKeyType="done"
            testID="home-name-input"
          />
          <Text style={[styles.readOnlyLabel, { color: c.placeholder, marginTop: 4 }]}>
            Takes effect on restart.
          </Text>
        </View>
        <View style={[styles.readOnlyField, { backgroundColor: c.surface, borderColor: c.border, marginTop: 8 }]}>
          <Text style={[styles.readOnlyLabel, { color: c.placeholder }]}>Soul Version</Text>
          <Text style={[styles.readOnlyValue, { color: c.text }]} testID="soul-version">
            {soulManifestVersion != null ? `v${soulManifestVersion}` : 'Bootstrap'}
          </Text>
        </View>
        <View style={[styles.readOnlyField, { backgroundColor: c.surface, borderColor: c.border, marginTop: 8 }]}>
          <Text style={[styles.readOnlyLabel, { color: c.placeholder }]}>Last Sync</Text>
          <Text style={[styles.readOnlyValue, { color: c.text }]} testID="last-sync-time">
            {formatSyncTime(lastSoulSyncTime)}
          </Text>
        </View>
        <TouchableOpacity
          style={[styles.actionButton, { backgroundColor: c.primary, marginTop: 12, opacity: syncing ? 0.5 : 1 }]}
          onPress={handleForceSync}
          disabled={syncing}
          testID="force-sync-button"
        >
          <Text style={[styles.actionButtonText, { color: c.textOnPrimary }]}>
            {syncing ? 'Syncing...' : 'Force Sync'}
          </Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.actionButton, { backgroundColor: c.primary, marginTop: 8 }]}
          onPress={() => {
            /* TODO: soul seed import flow */
            Alert.alert('Coming Soon', 'Soul seed import is not yet implemented.');
          }}
          testID="soul-seed-import-button"
        >
          <Text style={[styles.actionButtonText, { color: c.textOnPrimary }]}>{t.settings.soulSeedImport}</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.actionButton, { backgroundColor: c.secondary ?? c.primary, marginTop: 8 }]}
          onPress={async () => {
            try {
              const { exportToJson } = await import('../engine/soul/SoulSeedImporter');
              const dids = await new AsyncStorageSoulManifestStore(rawAsyncStorage as any).listDids();
              const did = dids.length > 0 ? dids[0] : null;
              if (!did) {
                Alert.alert('No Soul', 'No soul manifest found to export.');
                return;
              }
              const manifest = await new AsyncStorageSoulManifestStore(rawAsyncStorage as any).load(did);
              if (!manifest) {
                Alert.alert('No Soul', 'Could not load soul manifest.');
                return;
              }
              const json = exportToJson(manifest);
              await Share.share({
                message: json,
                title: `${manifest.agentName ?? 'Companion'}.soul.json`,
              });
            } catch (e: any) {
              Alert.alert('Export Failed', e?.message ?? 'Could not export soul.');
            }
          }}
          testID="export-soul-button"
        >
          <Text style={[styles.actionButtonText, { color: c.textOnPrimary }]}>{t.settings.exportSoul}</Text>
        </TouchableOpacity>

        <View style={styles.divider} />

        {/* ---------------------------------------------------------------
            LANGUAGE section
            --------------------------------------------------------------- */}
        <SectionHeader label={t.settings.language} color={c.textMuted} />

        {/* Theme Picker */}
        <Text style={[styles.fieldLabel, { color: c.textSecondary }]}>{t.settings.theme}</Text>
        <View style={styles.chipGrid}>
          {THEME_OPTIONS.map((opt) => (
            <TouchableOpacity
              key={opt.value}
              style={[
                styles.chip,
                { borderColor: c.primary, backgroundColor: c.surface },
                theme === opt.value && { backgroundColor: c.primary },
              ]}
              onPress={() => setTheme(opt.value)}
              testID={`theme-${opt.value}`}
            >
              <Text
                style={[
                  styles.chipText,
                  { color: c.primary },
                  theme === opt.value && { color: c.textOnPrimary },
                ]}
              >
                {opt.label}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        {/* Language Picker */}
        <View style={[styles.chipGrid, { marginTop: 12 }]}>
          {LANGUAGES.map((lang) => (
            <TouchableOpacity
              key={lang.code}
              style={[
                styles.chip,
                { borderColor: c.primary, backgroundColor: c.surface },
                locale === lang.code && { backgroundColor: c.primary },
              ]}
              onPress={() => handleLanguageSelect(lang.code)}
              testID={`lang-${lang.code}`}
            >
              <Text
                style={[
                  styles.chipText,
                  { color: c.primary },
                  locale === lang.code && { color: c.textOnPrimary },
                ]}
              >
                {lang.label}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        <View style={styles.divider} />

        {/* ---------------------------------------------------------------
            ADVANCED section
            --------------------------------------------------------------- */}
        <SectionHeader label={t.settings.advanced} color={c.textMuted} />

        {/* Inference URL override */}
        <Text style={[styles.fieldLabel, { color: c.textSecondary }]}>{t.settings.inferenceUrlOverride}</Text>
        <View style={{ flexDirection: 'row', alignItems: 'center', marginTop: 4 }}>
          <TextInput
            style={[styles.textInput, { flex: 1, borderColor: c.inputBorder, backgroundColor: c.inputBackground, color: c.text }]}
            value={manualInferenceUrl}
            onChangeText={setManualInferenceUrl}
            placeholder="http://198.51.100.10:11434"
            placeholderTextColor={c.placeholder}
            autoCapitalize="none"
            autoCorrect={false}
            testID="inference-url-input"
          />
          <TouchableOpacity
            style={[styles.testButton, { backgroundColor: c.primary, marginLeft: 8 }]}
            onPress={handleTestInferenceUrl}
            testID="test-inference-button"
          >
            <Text style={[styles.actionButtonText, { color: c.textOnPrimary }]}>Test</Text>
          </TouchableOpacity>
        </View>
        {inferenceTestResult && (
          <Text
            style={[
              styles.testResultText,
              { color: inferenceTestResult === 'OK' ? '#4CAF50' : c.error ?? '#D32F2F' },
            ]}
            testID="inference-test-result"
          >
            {inferenceTestResult}
          </Text>
        )}

        {/* Debug mode toggle */}
        <View style={[styles.toggleRow, { marginTop: 16 }]}>
          <Text style={[styles.toggleLabel, { color: c.text }]}>{t.settings.debugMode}</Text>
          <Switch
            value={debugMode}
            onValueChange={(v) => {
              setDebugMode(v);
              secureStorage.setItem(KEY_DEBUG_MODE, v ? 'true' : 'false');
            }}
            testID="debug-mode-toggle"
          />
        </View>

        {/* Household / Between */}
        <View style={[styles.section, { marginTop: 16 }]}>
          <Text style={[styles.fieldLabel, { color: c.textSecondary }]}>{t.household.title}</Text>
          <HouseholdSection navigation={navigation} />
        </View>

        {/* Web Node (web platform only) */}
        {Platform.OS === 'web' && (
          <View style={styles.section}>
            <Text style={[styles.fieldLabel, { color: c.textSecondary }]}>{t.settings.webNode}</Text>
            <WebNodeSection navigation={navigation} />
          </View>
        )}

        {/* Where your companion lives — current mode + the zone address book */}
        <View style={[styles.readOnlyField, { backgroundColor: c.surface, borderColor: c.border, marginTop: 16 }]}>
          <Text style={[styles.readOnlyLabel, { color: c.placeholder }]}>Where your companion lives</Text>
          <View style={{ flexDirection: 'row', alignItems: 'center' }}>
            <View style={[styles.statusDot, { backgroundColor: hasHomeZoneLeg ? '#2196F3' : '#4CAF50' }]} />
            <Text style={[styles.readOnlyValue, { color: c.text }]} testID="current-mode">
              {effectiveModeLabel}
            </Text>
          </View>
          <Text style={[styles.readOnlyLabel, { color: c.placeholder, marginTop: 4 }]}>{effectiveModeHint}</Text>
        </View>

        {/* My zones — the bank is an ADDRESS BOOK: every
            zone stays saved with its relays + username; switching is one tap
            (relay creds live in the bank, per-zone password in secureStorage).
            Long-press = explicit forget, the ONLY way an entry leaves. */}
        {bankZones.length > 0 && (
          <View style={{ marginTop: 12 }}>
            <Text style={[styles.fieldLabel, { color: c.textSecondary }]}>My zones</Text>
            {bankZones.map((z) => (
              <TouchableOpacity
                key={z.zoneId}
                style={[styles.actionButton, { backgroundColor: c.surface, borderColor: c.border, borderWidth: 1, marginTop: 6 }]}
                onPress={() => navigation.navigate('Servers')}
                onLongPress={() => confirmForgetZone(z.zoneId, z.displayName)}
                testID={`settings-zone-${z.zoneId}`}
              >
                <Text style={[styles.actionButtonText, { color: c.text }]}>
                  ⌂ {z.displayName}{z.homeZone ? ' · home' : ''}
                </Text>
              </TouchableOpacity>
            ))}
            <Text style={[styles.readOnlyLabel, { color: c.placeholder, marginTop: 4 }]}>
              Tap to open · long-press to forget
            </Text>
          </View>
        )}
        <TouchableOpacity
          style={[styles.actionButton, { backgroundColor: c.surface, borderColor: c.border, borderWidth: 1, marginTop: 12 }]}
          onPress={() => navigation.navigate('Servers')}
          testID="settings-add-zone"
        >
          <Text style={[styles.actionButtonText, { color: c.text }]}>Add a zone (paste or scan an invite)</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.actionButton, { backgroundColor: c.surface, borderColor: c.border, borderWidth: 1, marginTop: 12 }]}
          onPress={handleSwitchMode}
          testID="switch-mode-button"
        >
          <Text style={[styles.actionButtonText, { color: c.text }]}>
            {hasHomeZoneLeg ? 'Switch to standalone…' : 'Connect to a home zone'}
          </Text>
        </TouchableOpacity>

        <View style={styles.divider} />

        {/* Logout */}
        <View style={styles.section}>
          <TouchableOpacity style={[styles.logoutButton, { backgroundColor: c.error }]} onPress={handleLogout} testID="logout-button">
            <Text style={styles.logoutText}>{t.settings.logout}</Text>
          </TouchableOpacity>
        </View>
      </ScrollView>

      {/* OpenRouter OAuth modal — fullscreen WebView that intercepts the
          loopback callback and exchanges the code for an API key. */}
      <Modal
        visible={showOpenRouterAuth}
        animationType="slide"
        presentationStyle="fullScreen"
        onRequestClose={() => setShowOpenRouterAuth(false)}
      >
        <OpenRouterAuthScreen
          onApiKey={(key) => {
            setApiKey(key);
            secureStorage.setItem(KEY_API_KEY, key);
            // Force provider=openrouter even if the user toggled it during
            // the flow, and re-apply the bearer header so the next inference
            // call sees the new key.
            if (apiProvider !== 'openrouter') {
              setApiProvider('openrouter');
              secureStorage.setItem(KEY_API_PROVIDER, 'openrouter');
            }
            inferenceRouter.setRemoteAuth('bearer', key);
            inferenceRouter.setRemoteUrl('https://openrouter.ai/api');
            secureStorage.setItem('@wyrd_inference_url', 'https://openrouter.ai/api');
            setShowOpenRouterAuth(false);
          }}
          onCancel={() => setShowOpenRouterAuth(false)}
          onError={(msg) => {
            setOpenRouterAuthError(msg);
            setShowOpenRouterAuth(false);
          }}
        />
      </Modal>
    </View>
  );
}

function SectionHeader({ label, color }: { label: string; color: string }) {
  return (
    <Text style={[styles.sectionTitle, { color }]}>{label}</Text>
  );
}

/**
 * The EXPERIMENTAL on-device model switch.
 *
 * Shown to everyone rather than hidden behind a device check, because the point
 * is that the user gets to decide: someone with a heavily specced tablet may
 * well want this, and it is their hardware. What we owe them is an honest
 * label — the hint says plainly that most phones are not fast enough, so the
 * choice is informed rather than discovered later as "why is this so slow".
 */
function OnDeviceModelToggle() {
  const c = useThemeColors();
  const t = useStrings();
  const enabled = useAppModeStore((s) => s.onDeviceModelOptIn);
  const setEnabled = useAppModeStore((s) => s.setOnDeviceModelOptIn);

  return (
    <View style={{ marginBottom: 16 }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
        <View style={{ flex: 1, paddingRight: 12 }}>
          <Text style={[styles.fieldLabel, { color: c.textSecondary }]}>
            {t.settings.onDeviceModel}
            {'  '}
            <Text style={{ color: c.primary, fontSize: 11, fontWeight: '700' }}>
              {t.settings.experimental}
            </Text>
          </Text>
        </View>
        <Switch
          value={enabled}
          onValueChange={(v) => { setEnabled(v).catch(() => {}); }}
          testID="on-device-model-toggle"
        />
      </View>
      <Text style={[styles.fieldLabel, { color: c.placeholder, marginTop: 4 }]}>
        {t.settings.onDeviceModelHint}
      </Text>
    </View>
  );
}

/**
 * The mode-4-vs-5 fork, as a control.
 *
 * Deliberately renders NOTHING unless both backings are actually available:
 * offering "cloud" with no API key configured would let the user select a mode
 * that cannot answer, and offering "home zone" to a phone with no household is
 * meaningless. When only one is possible there is no choice to present.
 *
 */
function BackingPicker() {
  const c = useThemeColors();
  const t = useStrings();
  const { inferenceRouter } = useInference();
  const preferredBacking = useAppModeStore((s) => s.preferredBacking);
  const setPreferredBacking = useAppModeStore((s) => s.setPreferredBacking);
  const onDeviceModelOptIn = useAppModeStore((s) => s.onDeviceModelOptIn);
  const [choices, setChoices] = React.useState<Backing[]>([]);
  const [mode, setMode] = React.useState<PhoneMode | null>(null);

  // Re-resolve whenever the stored preference changes, so the mode line below
  // the chips reflects the tap that just happened.
  React.useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const inputs = await collectModeInputs({
          hasOnDeviceModel: inferenceRouter.canInferLocally(),
        });
        if (!alive) return;
        setChoices(availableBackings(inputs));
        const decision = decideMode(inputs);
        setMode(decision.mode);
        applyModeToRouter(decision.mode, inferenceRouter);
      } catch {
        // Leave the picker hidden rather than showing a choice we can't honour.
      }
    })();
    return () => { alive = false; };
  }, [preferredBacking, onDeviceModelOptIn, inferenceRouter]);

  if (choices.length < 2) return null;

  const LABELS: Record<Backing, string> = {
    home: t.settings.heavyThinkingHome,
    cloud: t.settings.heavyThinkingCloud,
  };

  return (
    <View style={{ marginBottom: 16 }}>
      <Text style={[styles.fieldLabel, { color: c.textSecondary }]}>
        {t.settings.heavyThinking}
      </Text>
      <View style={styles.chipGrid}>
        {choices.map((backing) => (
          <TouchableOpacity
            key={backing}
            style={[
              styles.chip,
              { borderColor: c.primary, backgroundColor: c.surface },
              preferredBacking === backing && { backgroundColor: c.primary },
            ]}
            onPress={() => { setPreferredBacking(backing).catch(() => {}); }}
            testID={`backing-${backing}`}
          >
            <Text
              style={[
                styles.chipText,
                { color: c.primary },
                preferredBacking === backing && { color: c.textOnPrimary },
              ]}
            >
              {LABELS[backing]}
            </Text>
          </TouchableOpacity>
        ))}
      </View>
      <Text style={[styles.fieldLabel, { color: c.placeholder, marginTop: 6 }]}>
        {t.settings.heavyThinkingHint}
      </Text>
      <Text style={[styles.fieldLabel, { color: c.placeholder }]} testID="current-mode">
        {t.settings.currentMode}: {modeLabel(mode)}
      </Text>
    </View>
  );
}

const WEB_MODEL_CATALOG_NAMES: Record<string, string> = {
  'qwen2.5-0.5b-instruct-q4f16_1-MLC': 'Qwen2.5 0.5B',
  'Llama-3.2-1B-Instruct-q4f16_1-MLC': 'Llama 3.2 1B',
  'Qwen2.5-3B-Instruct-q4f16_1-MLC': 'Qwen2.5 3B',
};

function WebNodeSection({ navigation }: { navigation: Props['navigation'] }) {
  const c = useThemeColors();
  const t = useStrings();
  const nodeState = useWebNodeStore((s) => s.nodeState);
  const webLLMModelId = useWebNodeStore((s) => s.webLLMModelId);
  const capabilities = useWebNodeStore((s) => s.capabilities);

  const modelName = webLLMModelId ? (WEB_MODEL_CATALOG_NAMES[webLLMModelId] ?? webLLMModelId) : t.settings.noModelLoaded;
  const nodeDotColor = nodeState === 'running' ? '#4CAF50' : nodeState === 'error' ? '#D32F2F' : '#BDBDBD';

  return (
    <>
      <View style={[styles.readOnlyField, { backgroundColor: c.surface, borderColor: c.border }]}>
        <Text style={[styles.readOnlyLabel, { color: c.placeholder }]}>{t.settings.nodeStatus}</Text>
        <View style={{ flexDirection: 'row', alignItems: 'center' }}>
          <View style={[styles.statusDot, { backgroundColor: nodeDotColor }]} />
          <Text style={[styles.readOnlyValue, { color: c.text }]}>{nodeState}</Text>
        </View>
      </View>
      {capabilities.webgpu && (
        <View style={[styles.readOnlyField, { backgroundColor: c.surface, borderColor: c.border, marginTop: 8 }]}>
          <Text style={[styles.readOnlyLabel, { color: c.placeholder }]}>{t.settings.browserModel}</Text>
          <Text style={[styles.readOnlyValue, { color: c.text }]}>{modelName}</Text>
        </View>
      )}
      <TouchableOpacity
        style={[styles.actionButton, { backgroundColor: c.primary, marginTop: 12 }]}
        onPress={() => navigation.navigate('WebNodeDashboard')}
        testID="web-node-dashboard-button"
      >
        <Text style={[styles.actionButtonText, { color: c.textOnPrimary }]}>{t.settings.webNodeDashboard}</Text>
      </TouchableOpacity>
    </>
  );
}

function HouseholdSection({ navigation }: { navigation: Props['navigation'] }) {
  const c = useThemeColors();
  const t = useStrings();
  const connectivityState = useHouseholdStore((s) => s.connectivityState);
  const connectedNodes = useHouseholdStore((s) => s.connectedNodes);

  const dotColor = connectivityDotColor(connectivityState);
  const statusLabel =
    connectivityState === 'CONNECTED_LAN' ? t.household.connectedLan
    : connectivityState === 'CONNECTED_RELAY' ? t.household.connectedRelay
    : connectivityState === 'DISCOVERING' ? t.household.discovering
    : connectivityState === 'RECONNECTING' ? t.household.reconnecting
    : t.household.offline;

  const nodeCount = connectedNodes.length;
  const summaryText = connectivityState === 'OFFLINE'
    ? t.household.offline
    : `${statusLabel} — ${nodeCount} node${nodeCount !== 1 ? 's' : ''}`;

  return (
    <>
      <View style={[styles.readOnlyField, { backgroundColor: c.surface, borderColor: c.border, marginTop: 8 }]}>
        <Text style={[styles.readOnlyLabel, { color: c.placeholder }]}>{t.household.connectionStatus}</Text>
        <View style={{ flexDirection: 'row', alignItems: 'center' }}>
          <View style={[styles.statusDot, { backgroundColor: dotColor }]} />
          <Text style={[styles.readOnlyValue, { color: c.text }]} testID="settings-household-status">{summaryText}</Text>
        </View>
      </View>

      <TouchableOpacity
        style={[styles.actionButton, { backgroundColor: c.primary, marginTop: 12 }]}
        onPress={() => navigation.navigate('Household')}
        testID="manage-household-button"
      >
        <Text style={[styles.actionButtonText, { color: c.textOnPrimary }]}>{t.household.manageHousehold}</Text>
      </TouchableOpacity>
    </>
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
  content: { padding: 16, paddingBottom: 32 },
  section: { marginBottom: 8 },
  sectionTitle: {
    fontSize: 14,
    fontWeight: '600',
    textTransform: 'uppercase',
    marginBottom: 8,
    marginTop: 4,
  },
  fieldLabel: {
    fontSize: 13,
    fontWeight: '500',
    marginBottom: 6,
  },
  readOnlyField: {
    padding: 12,
    borderRadius: 8,
    borderWidth: 1,
  },
  readOnlyLabel: { fontSize: 12, marginBottom: 2 },
  readOnlyValue: { fontSize: 16 },
  editableValue: {
    fontSize: 16,
    padding: 0,
    margin: 0,
  },
  chipGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  chip: {
    paddingVertical: 8,
    paddingHorizontal: 16,
    borderRadius: 20,
    borderWidth: 1,
  },
  chipText: { fontWeight: '600', fontSize: 14 },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 8,
  },
  statusDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
    marginRight: 8,
  },
  statusLabel: { fontSize: 14, fontWeight: '500' },
  toggleRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  toggleLabel: { fontSize: 16 },
  actionButton: {
    paddingVertical: 12,
    borderRadius: 8,
    alignItems: 'center',
  },
  actionButtonText: { fontWeight: 'bold', fontSize: 15 },
  textInput: {
    borderWidth: 1,
    borderRadius: 8,
    padding: 12,
    fontSize: 15,
    marginTop: 4,
  },
  testButton: {
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderRadius: 8,
    alignItems: 'center',
  },
  testResultText: {
    fontSize: 13,
    marginTop: 4,
    fontWeight: '500',
  },
  divider: {
    height: 1,
    backgroundColor: '#E0E0E0',
    marginVertical: 16,
  },
  logoutButton: {
    paddingVertical: 14,
    borderRadius: 8,
    alignItems: 'center',
  },
  logoutText: { color: '#fff', fontWeight: 'bold', fontSize: 16 },
});
