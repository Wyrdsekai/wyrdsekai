/**
 * Welcome / onboarding screen — shown on first launch only.
 * RN port of KMP's WelcomeScreen.kt.
 *
 * 2026-07-22 redesign: the first question is WHERE
 * THE COMPANION LIVES, not what infrastructure the user has.
 *
 *   ⌂ On my home machines → Mode 1 (remote terminal). One input takes an
 *     invite OR a LAN address; the app picks the transport (invite → relay
 *     via the zone bank, plain address → direct WS). No "Use my server" /
 *     "Log in to my account" ambiguity, no dead-end HTTP login on relay URLs.
 *
 *   ◎ On this phone → standalone mini-zone (Modes 2/3): cloud API key,
 *     on-device model, or (advanced, preserved capability) a home server
 *     used purely as the inference endpoint.
 */
import React, { useCallback, useState } from 'react';
import { View, Text, TextInput, TouchableOpacity, ScrollView, StyleSheet } from 'react-native';
import { useThemeColors } from '../theme/useTheme';
import type { ColorPalette } from '../theme/colors';

type Step = 'live-where' | 'home-connect' | 'standalone-think' | 'api-key';

interface Props {
  onComplete: (
    serverUrl: string | null,
    apiProvider: string | null,
    apiKey: string | null,
    /**
     * The user explicitly chose to run the model on this device.
     *
     * That choice IS the experimental opt-in — it is the one path where there
     * is nothing else to think with, so the phone must be allowed to try. Every
     * other path (home zone, cloud key, server endpoint) leaves this false and
     * lands on mode 1 or 2.
     */
    onDeviceModel?: boolean,
  ) => void;
  /** Home-zone path: user supplied an invite or a server address. The wrapper
   * inspects it and routes — invite → zone bank → Servers (relay login);
   * plain LAN/host URL → direct-WS account login on Connect. Returns an
   * error string to display, or null on successful navigation. */
  onHomeZone?: (input: string) => Promise<string | null>;
}

export function WelcomeScreen({ onComplete, onHomeZone }: Props) {
  const colors = useThemeColors();
  const [step, setStep] = useState<Step>('live-where');
  const [homeInput, setHomeInput] = useState('');
  const [homeError, setHomeError] = useState<string | null>(null);
  const [homeBusy, setHomeBusy] = useState(false);
  const [serverUrl, setServerUrl] = useState('');
  const [showServerInference, setShowServerInference] = useState(false);
  const [apiProvider, setApiProvider] = useState('');
  const [apiKey, setApiKey] = useState('');

  // Camera QR scanner for wyrdphone:// invites — parity with ConnectScreen +
  // KMP Welcome (2026-07-24: the home-connect step could paste but not SCAN).
  // expo-camera is lazy-required so the JS bundle (and jest) never touch the
  // native module until the user opens the scanner.
  const [scannerOpen, setScannerOpen] = useState(false);
  const [cameraMod, setCameraMod] = useState<any>(null);

  const styles = makeStyles(colors);

  const submitHomeZone = async (input?: string) => {
    const value = (input ?? homeInput).trim();
    if (!onHomeZone || !value) return;
    setHomeBusy(true);
    setHomeError(null);
    const err = await onHomeZone(value);
    setHomeBusy(false);
    if (err) setHomeError(err);
  };

  const openScanner = useCallback(async () => {
    try {
      // eslint-disable-next-line @typescript-eslint/no-var-requires
      const mod = require('expo-camera');
      const perm = await mod.Camera.requestCameraPermissionsAsync();
      if (!perm?.granted) {
        setHomeError('Camera permission denied');
        return;
      }
      setCameraMod(mod);
      setScannerOpen(true);
    } catch (e: unknown) {
      setHomeError(e instanceof Error ? e.message : 'Camera unavailable');
    }
  }, []);

  const onQrScanned = useCallback(({ data }: { data: string }) => {
    setScannerOpen(false);
    if (!data) return;
    setHomeInput(data);
    // A scanned invite/URL is a complete input — submit it straight away, same
    // as scanning on the Connect screen.
    void submitHomeZone(data);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content} testID="welcome-screen">
      <Text style={styles.title} testID="welcome-title">Wyrdsekai</Text>
      <Text style={styles.subtitle}>
        Your personal Study — a private space for{'\n'}your thoughts, notes, and a companion{'\n'}who learns your patterns.
      </Text>

      {step === 'live-where' && (
        <View style={styles.section}>
          <Text style={styles.heading}>Where does your companion live?</Text>

          <TouchableOpacity
            style={styles.choiceCard}
            onPress={() => setStep('home-connect')}
            testID="welcome-home-zone"
          >
            <Text style={styles.choiceTitle}>⌂  On my home machines</Text>
            <Text style={styles.choiceBody}>
              I have a zone — connect this phone to it.{'\n'}
              The phone becomes a window to the companion{'\n'}who lives there.
            </Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.choiceCard}
            onPress={() => setStep('standalone-think')}
            testID="welcome-standalone-mode"
          >
            <Text style={styles.choiceTitle}>◎  On this phone</Text>
            <Text style={styles.choiceBody}>
              A standalone companion, born here.{'\n'}
              No home zone needed.
            </Text>
          </TouchableOpacity>
        </View>
      )}

      {step === 'home-connect' && (
        <View style={styles.section}>
          <Text style={styles.heading}>Connect to your zone</Text>
          <Text style={styles.body}>
            Paste an invite, or type your server's address{'\n'}if you're on the same network.
          </Text>

          <TextInput
            style={styles.input}
            value={homeInput}
            onChangeText={setHomeInput}
            placeholder="wyrdphone://…  or  http://192.168.1.x:7070"
            placeholderTextColor={colors.placeholder}
            autoCapitalize="none"
            autoCorrect={false}
            testID="welcome-home-input"
          />

          {/* Scan the QR that `wyrd phone invite` prints — parity with the
              Connect screen + KMP Welcome. */}
          <TouchableOpacity
            style={styles.secondaryButton}
            onPress={() => (scannerOpen ? setScannerOpen(false) : void openScanner())}
            testID="welcome-scan-invite"
          >
            <Text style={styles.secondaryButtonText}>
              {scannerOpen ? 'Cancel scan' : 'Scan invite QR'}
            </Text>
          </TouchableOpacity>
          {scannerOpen && cameraMod && (
            <cameraMod.CameraView
              style={styles.qrCamera}
              facing="back"
              barcodeScannerSettings={{ barcodeTypes: ['qr'] }}
              onBarcodeScanned={onQrScanned}
              testID="welcome-qr-scanner"
            />
          )}

          {homeError && <Text style={styles.error} testID="welcome-home-error">{homeError}</Text>}

          <TouchableOpacity
            style={[styles.primaryButton, (!homeInput.trim() || homeBusy) && styles.disabled]}
            onPress={() => submitHomeZone()}
            disabled={!homeInput.trim() || homeBusy}
            testID="welcome-connect"
          >
            <Text style={styles.primaryButtonText}>Connect</Text>
          </TouchableOpacity>

          <Text style={styles.hint}>
            Don't have an invite? On your node, run:{'\n'}
            <Text style={styles.mono}>wyrd phone invite</Text>
          </Text>

          <TouchableOpacity style={styles.textButton} onPress={() => { setHomeError(null); setStep('live-where'); }}>
            <Text style={styles.textButtonText}>Back</Text>
          </TouchableOpacity>
        </View>
      )}

      {step === 'standalone-think' && (
        <View style={styles.section}>
          <Text style={styles.heading}>How should your companion think?</Text>
          <Text style={styles.body}>
            A standalone companion needs a way to reason.
          </Text>

          <TouchableOpacity style={styles.secondaryButton} onPress={() => setStep('api-key')} testID="welcome-use-api">
            <Text style={styles.secondaryButtonText}>Cloud API key</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.secondaryButton}
            onPress={() => onComplete(null, null, null, true)}
            testID="welcome-standalone"
          >
            <Text style={styles.secondaryButtonText}>
              On-device model (works offline) · EXPERIMENTAL
            </Text>
            <Text style={styles.choiceBody}>
              Most phones are not fast enough yet — replies come slower than you can
              read, and the phone gets warm. A home zone or a cloud API key is far
              better if you have one.
            </Text>
          </TouchableOpacity>

          {/* Preserved capability: a home server used purely as the inference
              endpoint for the phone-local companion (NOT a home-zone login). */}
          <TouchableOpacity
            style={styles.textButton}
            onPress={() => setShowServerInference((v) => !v)}
            testID="welcome-server-inference-toggle"
          >
            <Text style={styles.textButtonText}>
              {showServerInference ? 'Hide' : 'Advanced: my server provides inference'}
            </Text>
          </TouchableOpacity>

          {showServerInference && (
            <>
              <TextInput
                style={styles.input}
                value={serverUrl}
                onChangeText={setServerUrl}
                placeholder="http://192.168.1.x:7070"
                placeholderTextColor={colors.placeholder}
                autoCapitalize="none"
                autoCorrect={false}
                testID="welcome-server-url"
              />
              <TouchableOpacity
                style={[styles.primaryButton, !serverUrl && styles.disabled]}
                onPress={() => onComplete(serverUrl || null, null, null)}
                disabled={!serverUrl}
                testID="welcome-use-server"
              >
                <Text style={styles.primaryButtonText}>Use my server for thinking</Text>
              </TouchableOpacity>
            </>
          )}

          <TouchableOpacity style={styles.textButton} onPress={() => setStep('live-where')}>
            <Text style={styles.textButtonText}>Back</Text>
          </TouchableOpacity>
        </View>
      )}

      {step === 'api-key' && (
        <View style={styles.section}>
          <Text style={styles.heading}>Which service?</Text>

          {[
            ['openrouter', 'OpenRouter (recommended)\nAccess Claude, GPT, Llama — one account'],
            ['anthropic', 'Anthropic (Claude)\nconsole.anthropic.com/settings/keys'],
            ['openai', 'OpenAI\nplatform.openai.com/api-keys'],
            ['custom', 'Custom endpoint\nAny OpenAI-compatible API'],
          ].map(([id, label]) => (
            <TouchableOpacity
              key={id}
              style={[styles.providerButton, apiProvider === id && styles.providerSelected]}
              onPress={() => setApiProvider(id)}
              testID={`welcome-provider-${id}`}
            >
              <Text style={styles.providerText}>{label}</Text>
            </TouchableOpacity>
          ))}

          {apiProvider !== '' && (
            <>
              {apiProvider === 'custom' && (
                <TextInput
                  style={styles.input}
                  value={serverUrl}
                  onChangeText={setServerUrl}
                  placeholder="http://localhost:8080"
                  placeholderTextColor={colors.placeholder}
                  autoCapitalize="none"
                />
              )}

              <TextInput
                style={styles.input}
                value={apiKey}
                onChangeText={setApiKey}
                placeholder="sk-..."
                placeholderTextColor={colors.placeholder}
                autoCapitalize="none"
                autoCorrect={false}
                secureTextEntry
                testID="welcome-api-key"
              />

              <TouchableOpacity
                style={[styles.primaryButton, !apiKey && styles.disabled]}
                onPress={() => onComplete(serverUrl || null, apiProvider, apiKey || null)}
                disabled={!apiKey}
                testID="welcome-api-done"
              >
                <Text style={styles.primaryButtonText}>Continue</Text>
              </TouchableOpacity>
            </>
          )}

          <TouchableOpacity style={styles.textButton} onPress={() => setStep('standalone-think')}>
            <Text style={styles.textButtonText}>Back</Text>
          </TouchableOpacity>
        </View>
      )}
    </ScrollView>
  );
}

function makeStyles(colors: ColorPalette) {
  return StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.background },
    content: { padding: 24, alignItems: 'center' },
    title: { fontSize: 28, fontWeight: '700', color: colors.text, marginTop: 40 },
    subtitle: { fontSize: 16, color: colors.textSecondary, textAlign: 'center', marginTop: 8, lineHeight: 22 },
    section: { width: '100%', marginTop: 32 },
    heading: { fontSize: 18, fontWeight: '600', color: colors.text, textAlign: 'center' },
    body: { fontSize: 14, color: colors.textSecondary, textAlign: 'center', marginTop: 8, lineHeight: 20 },
    choiceCard: {
      width: '100%', borderWidth: 1, borderColor: colors.border, borderRadius: 12,
      padding: 16, marginTop: 16, backgroundColor: colors.surface,
    },
    choiceTitle: { fontSize: 17, fontWeight: '600', color: colors.text },
    choiceBody: { fontSize: 13, color: colors.textSecondary, marginTop: 6, lineHeight: 19 },
    input: {
      width: '100%', borderWidth: 1, borderColor: colors.border, borderRadius: 8,
      padding: 12, marginTop: 16, color: colors.text, fontSize: 14,
      backgroundColor: colors.inputBackground,
    },
    primaryButton: {
      width: '100%', backgroundColor: colors.primary, borderRadius: 8,
      padding: 14, marginTop: 12, alignItems: 'center',
    },
    primaryButtonText: { color: colors.textOnPrimary, fontSize: 16, fontWeight: '600' },
    secondaryButton: {
      width: '100%', borderWidth: 1, borderColor: colors.border, borderRadius: 8,
      padding: 14, marginTop: 8, alignItems: 'center',
    },
    secondaryButtonText: { color: colors.text, fontSize: 16 },
    textButton: { marginTop: 12, padding: 8, alignItems: 'center' },
    textButtonText: { color: colors.textSecondary, fontSize: 14 },
    providerButton: {
      width: '100%', borderWidth: 1, borderColor: colors.border, borderRadius: 8,
      padding: 12, marginTop: 8,
    },
    providerSelected: { borderColor: colors.primary, backgroundColor: colors.surface },
    providerText: { color: colors.text, fontSize: 14, lineHeight: 20 },
    hint: { fontSize: 13, color: colors.textSecondary, textAlign: 'center', marginTop: 16, lineHeight: 20 },
    mono: { fontFamily: 'monospace', color: colors.text },
    qrCamera: { width: '100%', height: 280, borderRadius: 8, overflow: 'hidden', marginTop: 8 },
    error: { color: colors.error, marginTop: 10, textAlign: 'center' },
    disabled: { opacity: 0.5 },
  });
}
