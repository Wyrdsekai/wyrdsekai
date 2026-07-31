/**
 * FirstRunScreen -- step-by-step setup wizard.
 *
 * Steps (local mode):
 *   1. Mode selection -- "My companion lives here" vs "Connect to household"
 *   2. Name your companion
 *   3. Find household server (network scan + manual URL)
 *   4. Pair with server (6-digit code entry)
 *   5. (Invisible) Triggers navigation to BirthScreen
 *
 * Remote mode: Step 1 immediately navigates to Connect screen.
 *
 * Model download starts in the background as soon as the user picks local mode
 * (step 1), running through steps 2-4 so it may be done by step 5.
 */
import React, { useState, useCallback, useRef, useEffect } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  ActivityIndicator,
} from 'react-native';
import {
  discoverWyrdsekaiServers,
  bestEndpoint,
  type DiscoveredInference,
} from '../engine/discovery/InferenceDiscovery';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/types';
import { useAppModeStore } from '../state/appModeStore';
import { useThemeColors } from '../theme/useTheme';
import { useStrings } from '../i18n/useStrings';
import { ModelManager } from '../inference/ModelManager';
import {
  requestPairing,
  verifyCode,
  type PairingCredentials,
} from '../network/PairingClient';

type Props = NativeStackScreenProps<RootStackParamList, 'FirstRun'>;

export function FirstRunScreen({ navigation }: Props) {
  const c = useThemeColors();
  const t = useStrings();
  const {
    setLocalMode,
    setRemoteMode,
    setInferenceUrl: persistInferenceUrl,
    setPairingCredentials,
  } = useAppModeStore();

  const [step, setStep] = useState(1);
  const [companionName, setCompanionName] = useState('Wyrd');
  const [inferenceUrl, setInferenceUrl] = useState('');
  const [selectedServerUrl, setSelectedServerUrl] = useState('');
  const [discoveredEndpoints, setDiscoveredEndpoints] = useState<
    DiscoveredInference[]
  >([]);
  const [scanning, setScanning] = useState(false);
  const [downloadProgress, setDownloadProgress] = useState(0);

  // Pairing step state
  const [pairingCode, setPairingCode] = useState('');
  const [verifying, setVerifying] = useState(false);
  const [pairingError, setPairingError] = useState<string | null>(null);
  const [challengeId, setChallengeId] = useState<string | null>(null);
  const [requesting, setRequesting] = useState(false);

  // ModelManager ref -- created lazily when download starts
  const modelManagerRef = useRef<ModelManager | null>(null);

  // Start model download in background
  const startModelDownload = useCallback(async () => {
    try {
      const RNFS = require('react-native-fs');
      const modelsDir = `${RNFS.DocumentDirectoryPath}/models`;
      const mm = new ModelManager(modelsDir);
      modelManagerRef.current = mm;

      const preferredModel = 'qwen3.5-2b-q4';
      const existing = await mm.getModelPath(preferredModel);
      if (existing) {
        setDownloadProgress(100);
        return;
      }

      await mm.downloadModel(preferredModel, (percent: number) => {
        setDownloadProgress(percent);
      });
      setDownloadProgress(100);
    } catch {
      // Download failure is non-fatal -- user can still proceed
    }
  }, []);

  // Navigate to Connect screen
  const handleRemote = useCallback(async () => {
    await setRemoteMode();
    navigation.replace('Connect');
  }, [navigation, setRemoteMode]);

  // Finish wizard: save local mode and go to Login (if paired) or Birth (if skipped)
  const finishLocal = useCallback(
    async (url: string | null, deviceToken?: string) => {
      const name = companionName.trim() || 'Wyrd';
      if (url) {
        persistInferenceUrl(url);
      }
      await setLocalMode(name);

      // If we have a server URL (from pairing), go through Login first
      if (url && deviceToken) {
        navigation.replace('Login', { serverUrl: url, deviceToken });
      } else {
        navigation.replace('Birth');
      }
    },
    [companionName, navigation, persistInferenceUrl, setLocalMode],
  );

  // Scan for household endpoints
  const handleScan = useCallback(async () => {
    setScanning(true);
    const endpoints = await discoverWyrdsekaiServers();
    setDiscoveredEndpoints(endpoints);
    setScanning(false);
    if (endpoints.length > 0) {
      const best = bestEndpoint(endpoints);
      if (best) {
        setInferenceUrl(best.url);
      }
    }
  }, []);

  // Request pairing challenge when entering step 4 — generates code on server
  useEffect(() => {
    if (step !== 4 || !selectedServerUrl) return;
    setRequesting(true);
    setPairingError(null);
    (async () => {
      const challenge = await requestPairing(
        selectedServerUrl,
        companionName.trim() || 'Wyrd',
        'phone',
      );
      if (challenge) {
        setChallengeId(challenge.challengeId);
      } else {
        setPairingError('Could not reach server at ' + selectedServerUrl);
      }
      setRequesting(false);
    })();
  }, [step, selectedServerUrl, companionName]);

  // Handle pairing verification
  const handleVerify = useCallback(async () => {
    if (!challengeId) {
      setPairingError('No pairing session. Go back and try again.');
      return;
    }
    setVerifying(true);
    setPairingError(null);

    const credentials = await verifyCode(
      selectedServerUrl,
      challengeId,
      pairingCode,
    );
    if (!credentials) {
      setPairingError('Invalid code. Check the code and try again.');
      setVerifying(false);
      return;
    }

    // Save pairing credentials
    setPairingCredentials(credentials);
    setVerifying(false);
    await finishLocal(credentials.serverUrl, credentials.token);
  }, [selectedServerUrl, companionName, pairingCode, setPairingCredentials, finishLocal]);

  // -------------------------------------------------------------------------
  // Step 1 -- Mode Selection
  // -------------------------------------------------------------------------
  if (step === 1) {
    return (
      <View
        style={[styles.container, { backgroundColor: c.background }]}
        testID="first-run-screen"
      >
        <Text style={[styles.title, { color: c.primary }]}>
          {t.connect.title}
        </Text>
        <Text style={[styles.subtitle, { color: c.textSecondary }]}>
          {t.firstRun.welcome}
        </Text>

        <TouchableOpacity
          style={[styles.card, { borderColor: c.border, backgroundColor: c.surface }]}
          onPress={() => {
            startModelDownload();
            setStep(2);
          }}
          activeOpacity={0.7}
          testID="local-mode-card"
        >
          <Text style={[styles.cardTitle, { color: c.text }]}>
            {t.firstRun.localTitle}
          </Text>
          <Text style={[styles.cardDescription, { color: c.textSecondary }]}>
            {t.firstRun.localDescription}
          </Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[styles.card, { borderColor: c.border, backgroundColor: c.surface }]}
          onPress={handleRemote}
          activeOpacity={0.7}
          testID="remote-mode-card"
        >
          <Text style={[styles.cardTitle, { color: c.text }]}>
            {t.firstRun.remoteTitle}
          </Text>
          <Text style={[styles.cardDescription, { color: c.textSecondary }]}>
            {t.firstRun.remoteDescription}
          </Text>
        </TouchableOpacity>
      </View>
    );
  }

  // -------------------------------------------------------------------------
  // Step 2 -- Name Your Companion
  // -------------------------------------------------------------------------
  if (step === 2) {
    return (
      <View
        style={[styles.container, { backgroundColor: c.background }]}
        testID="wizard-step-2"
      >
        <Text style={[styles.stepTitle, { color: c.text }]}>
          {t.firstRun.companionNameLabel}
        </Text>

        <TextInput
          style={[
            styles.input,
            {
              borderColor: c.inputBorder,
              backgroundColor: c.inputBackground,
              color: c.text,
            },
          ]}
          value={companionName}
          onChangeText={setCompanionName}
          placeholder="Wyrd"
          placeholderTextColor={c.placeholder}
          autoCapitalize="words"
          testID="companion-name-input"
        />

        <View style={styles.navRow}>
          <TouchableOpacity
            style={[styles.outlineButton, { borderColor: c.primary }]}
            onPress={() => setStep(1)}
          >
            <Text style={[styles.outlineButtonText, { color: c.primary }]}>
              Back
            </Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[
              styles.actionButton,
              {
                backgroundColor: companionName.trim()
                  ? c.primary
                  : c.border,
              },
            ]}
            onPress={() => setStep(3)}
            disabled={!companionName.trim()}
            testID="next-button"
          >
            <Text style={[styles.actionButtonText, { color: c.textOnPrimary }]}>
              Next
            </Text>
          </TouchableOpacity>
        </View>

        {downloadProgress > 0 && downloadProgress < 100 && (
          <DownloadProgress progress={downloadProgress} c={c} />
        )}
      </View>
    );
  }

  // -------------------------------------------------------------------------
  // Step 3 -- Find Server (network scan + manual URL)
  // -------------------------------------------------------------------------
  if (step === 3) {
    return (
      <View
        style={[styles.container, { backgroundColor: c.background }]}
        testID="wizard-step-3"
      >
        <Text style={[styles.stepTitle, { color: c.text }]}>
          Do you have a household server on your network?
        </Text>
        <Text style={[styles.stepHint, { color: c.textSecondary }]}>
          A household server lets your companion think more deeply.
        </Text>

        {/* Scan button */}
        <TouchableOpacity
          style={[
            styles.actionButton,
            { backgroundColor: scanning ? c.border : c.primary },
          ]}
          onPress={handleScan}
          disabled={scanning}
          testID="household-scan-button"
        >
          {scanning ? (
            <View style={styles.scanRow}>
              <ActivityIndicator color={c.textOnPrimary} size="small" />
              <Text style={[styles.actionButtonText, { color: c.textOnPrimary, marginLeft: 8 }]}>
                Scanning...
              </Text>
            </View>
          ) : (
            <Text style={[styles.actionButtonText, { color: c.textOnPrimary }]}>
              Scan Network
            </Text>
          )}
        </TouchableOpacity>

        {/* Discovered endpoints */}
        {discoveredEndpoints.map((endpoint, idx) => {
          const isSelected = inferenceUrl === endpoint.url;
          return (
            <TouchableOpacity
              key={`${endpoint.url}-${idx}`}
              style={[
                styles.endpointCard,
                {
                  borderColor: isSelected ? c.primary : c.border,
                  borderWidth: isSelected ? 2 : 1,
                  backgroundColor: isSelected ? c.primaryLight : c.surface,
                },
              ]}
              onPress={() => setInferenceUrl(endpoint.url)}
            >
              <Text style={{ color: c.text, fontWeight: '600' }}>
                {endpoint.label}
              </Text>
              <Text style={{ color: c.textSecondary, fontSize: 12 }}>
                {endpoint.url}
              </Text>
            </TouchableOpacity>
          );
        })}

        {/* Manual URL entry */}
        <TextInput
          style={[
            styles.input,
            {
              borderColor: c.inputBorder,
              backgroundColor: c.inputBackground,
              color: c.text,
            },
          ]}
          value={inferenceUrl}
          onChangeText={setInferenceUrl}
          placeholder="http://192.168.1.x:7070"
          placeholderTextColor={c.placeholder}
          autoCapitalize="none"
          autoCorrect={false}
          testID="inference-url-input"
        />

        {/* Navigation: Back / Skip / Next */}
        <View style={styles.navRow}>
          <TouchableOpacity
            style={[styles.outlineButton, { borderColor: c.primary }]}
            onPress={() => setStep(2)}
          >
            <Text style={[styles.outlineButtonText, { color: c.primary }]}>
              Back
            </Text>
          </TouchableOpacity>

          <View style={styles.rightNav}>
            <TouchableOpacity
              onPress={() => finishLocal(null)}
              testID="skip-button"
            >
              <Text style={[styles.skipText, { color: c.primary }]}>Skip</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={[
                styles.actionButton,
                {
                  backgroundColor: inferenceUrl.trim()
                    ? c.primary
                    : c.border,
                },
              ]}
              onPress={() => {
                setSelectedServerUrl(inferenceUrl.trim());
                setStep(4);
              }}
              disabled={!inferenceUrl.trim()}
              testID="next-button"
            >
              <Text style={[styles.actionButtonText, { color: c.textOnPrimary }]}>
                Next
              </Text>
            </TouchableOpacity>
          </View>
        </View>

        {downloadProgress > 0 && downloadProgress < 100 && (
          <DownloadProgress progress={downloadProgress} c={c} />
        )}
      </View>
    );
  }

  // -------------------------------------------------------------------------
  // Step 4 -- Pair with Server (6-digit code entry)
  // -------------------------------------------------------------------------
  return (
    <View
      style={[styles.container, { backgroundColor: c.background }]}
      testID="wizard-step-4"
    >
      <Text style={[styles.stepTitle, { color: c.text }]}>
        Pair with Server
      </Text>

      {requesting ? (
        <>
          <ActivityIndicator color={c.primary} />
          <Text style={[styles.stepHint, { color: c.textSecondary }]}>
            Requesting pairing code from server...
          </Text>
        </>
      ) : (
        <>
          <Text style={[styles.stepHint, { color: c.textSecondary }]}>
            A pairing code has been sent to your server.{'\n'}
            It will appear on any connected device.
          </Text>
          <Text style={[styles.stepHintSmall, { color: c.textSecondary }]}>
            Connect to your server first if you haven't:{'\n'}
            {'• telnet ' + selectedServerUrl.replace('http://', '').split(':')[0] + ' 7071'}{'\n'}
            {'• Browser: ' + selectedServerUrl}{'\n'}
            {'• CLI: wyrdsekai pair-code'}
          </Text>
        </>
      )}

      {/* 6-digit code input */}
      <TextInput
        style={[
          styles.codeInput,
          {
            borderColor: pairingError ? c.error : c.inputBorder,
            backgroundColor: c.inputBackground,
            color: c.text,
          },
        ]}
        value={pairingCode}
        onChangeText={(text) => {
          // Only allow digits, max 6
          const filtered = text.replace(/\D/g, '').slice(0, 6);
          setPairingCode(filtered);
          setPairingError(null);
        }}
        placeholder="000000"
        placeholderTextColor={c.placeholder}
        keyboardType="number-pad"
        maxLength={6}
        autoFocus
        testID="pairing-code-input"
      />

      {/* Error message */}
      {pairingError && (
        <Text style={[styles.errorText, { color: c.error }]}>
          {pairingError}
        </Text>
      )}

      {/* Navigation: Back / Verify */}
      <View style={styles.navRow}>
        <TouchableOpacity
          style={[styles.outlineButton, { borderColor: c.primary }]}
          onPress={() => setStep(3)}
          disabled={verifying}
        >
          <Text style={[styles.outlineButtonText, { color: c.primary }]}>
            Back
          </Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={[
            styles.actionButton,
            {
              backgroundColor:
                pairingCode.length === 6 && !verifying && !requesting && challengeId
                  ? c.primary
                  : c.border,
            },
          ]}
          onPress={handleVerify}
          disabled={pairingCode.length !== 6 || verifying}
          testID="verify-button"
        >
          {verifying ? (
            <View style={styles.scanRow}>
              <ActivityIndicator color={c.textOnPrimary} size="small" />
              <Text
                style={[
                  styles.actionButtonText,
                  { color: c.textOnPrimary, marginLeft: 8 },
                ]}
              >
                Verifying...
              </Text>
            </View>
          ) : (
            <Text style={[styles.actionButtonText, { color: c.textOnPrimary }]}>
              Verify
            </Text>
          )}
        </TouchableOpacity>
      </View>

      {downloadProgress > 0 && downloadProgress < 100 && (
        <DownloadProgress progress={downloadProgress} c={c} />
      )}
    </View>
  );
}

// ---------------------------------------------------------------------------
// Download progress indicator
// ---------------------------------------------------------------------------

function DownloadProgress({ progress, c }: { progress: number; c: ReturnType<typeof useThemeColors> }) {
  return (
    <View style={styles.progressContainer}>
      <Text style={[styles.progressText, { color: c.textSecondary }]}>
        Downloading model... {progress}%
      </Text>
      <View style={[styles.progressTrack, { backgroundColor: c.border }]}>
        <View
          style={[
            styles.progressFill,
            { backgroundColor: c.primary, width: `${progress}%` },
          ]}
        />
      </View>
    </View>
  );
}

// ---------------------------------------------------------------------------
// Styles
// ---------------------------------------------------------------------------

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    padding: 32,
  },
  title: {
    fontSize: 32,
    fontWeight: 'bold',
    textAlign: 'center',
    marginBottom: 8,
  },
  subtitle: {
    fontSize: 16,
    textAlign: 'center',
    marginBottom: 40,
  },
  stepTitle: {
    fontSize: 22,
    fontWeight: '600',
    textAlign: 'center',
    marginBottom: 24,
  },
  stepHint: {
    fontSize: 14,
    textAlign: 'center',
    marginBottom: 24,
    lineHeight: 20,
  },
  stepHintSmall: {
    fontSize: 12,
    textAlign: 'center',
    marginBottom: 24,
    lineHeight: 18,
  },
  card: {
    borderRadius: 12,
    borderWidth: 1,
    padding: 24,
    marginBottom: 16,
  },
  cardTitle: {
    fontSize: 18,
    fontWeight: '600',
    marginBottom: 4,
  },
  cardDescription: {
    fontSize: 14,
    lineHeight: 20,
  },
  input: {
    borderWidth: 1,
    borderRadius: 8,
    padding: 12,
    marginBottom: 16,
    fontSize: 16,
  },
  codeInput: {
    borderWidth: 1,
    borderRadius: 8,
    padding: 16,
    marginBottom: 16,
    fontSize: 32,
    textAlign: 'center',
    letterSpacing: 12,
    fontWeight: 'bold',
  },
  navRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: 8,
  },
  rightNav: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 16,
  },
  outlineButton: {
    borderWidth: 1,
    borderRadius: 8,
    paddingVertical: 12,
    paddingHorizontal: 20,
    alignItems: 'center',
  },
  outlineButtonText: {
    fontSize: 15,
    fontWeight: '600',
  },
  actionButton: {
    paddingVertical: 12,
    paddingHorizontal: 24,
    borderRadius: 8,
    alignItems: 'center',
  },
  actionButtonText: {
    fontWeight: 'bold',
    fontSize: 16,
  },
  skipText: {
    fontSize: 15,
    fontWeight: '600',
  },
  scanRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  endpointCard: {
    borderRadius: 8,
    padding: 12,
    marginBottom: 8,
  },
  errorText: {
    fontSize: 13,
    textAlign: 'center',
    marginBottom: 8,
  },
  progressContainer: {
    marginTop: 32,
    alignItems: 'center',
  },
  progressText: {
    fontSize: 13,
    marginBottom: 6,
  },
  progressTrack: {
    width: '100%',
    height: 4,
    borderRadius: 2,
    overflow: 'hidden',
  },
  progressFill: {
    height: '100%',
    borderRadius: 2,
  },
});
