import React, { useEffect, useState, useCallback } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  ActivityIndicator,
} from 'react-native';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/types';
import { login, register } from '../network/auth';
import { useSessionStore } from '../state/sessionStore';
import { useCredentialStore } from '../state/credentialStore';
import { usePreferencesStore } from '../state/preferencesStore';
import { useWs } from '../App';
import { useThemeColors } from '../theme/useTheme';
import { useStrings } from '../i18n/useStrings';
import {
  discoverWyrdsekaiServers,
  type DiscoveredInference,
} from '../engine/discovery/InferenceDiscovery';
import {isPhoneInviteUrl, parsePhoneInvite} from '../network/phoneInvite';
import {secureStorage} from '../state/secureStorage';

type Props = NativeStackScreenProps<RootStackParamList, 'Connect'>;

export function ConnectScreen({ navigation }: Props) {
  const ws = useWs();
  const c = useThemeColors();
  const t = useStrings();
  const { serverUrl, setServerUrl, setToken, connectionState } = useSessionStore();
  const { savedServerUrl, savedUsername, savedToken, saveCredentials } =
    useCredentialStore();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  // Network scan state
  const [discoveredServers, setDiscoveredServers] = useState<DiscoveredInference[]>([]);
  const [scanning, setScanning] = useState(false);

  // camera QR scanner for wyrdphone:// invites.
  // expo-camera is lazy-required so the JS bundle (and jest) never touch
  // the native module until the user actually opens the scanner.
  const [scannerOpen, setScannerOpen] = useState(false);
  const [cameraMod, setCameraMod] = useState<any>(null);

  const openScanner = useCallback(async () => {
    try {
      // eslint-disable-next-line @typescript-eslint/no-var-requires
      const mod = require('expo-camera');
      const perm = await mod.Camera.requestCameraPermissionsAsync();
      if (!perm?.granted) {
        setError(t.connect.cameraDenied ?? 'Camera permission denied');
        return;
      }
      setCameraMod(mod);
      setScannerOpen(true);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Camera unavailable');
    }
  }, [t]);

  const onQrScanned = useCallback(({ data }: { data: string }) => {
    setScannerOpen(false);
    if (!data) return;
    setServerUrl(data);
    if (isPhoneInviteUrl(data)) {
      // Same path as pasting the invite and tapping Log in.
      void applyPhoneInvite(data);
    }
    // Non-invite QR (e.g. a plain server URL): leave it in the field for
    // the user to log in against.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [setServerUrl]);

  // Load saved credentials on mount
  useEffect(() => {
    if (savedServerUrl) {
      setServerUrl(savedServerUrl);
    }
    if (savedUsername) {
      setUsername(savedUsername);
    }
  }, []);

  // Navigate to Room when connection is established
  useEffect(() => {
    if (connectionState === 'connected') {
      navigation.replace('Room');
    }
  }, [connectionState]);

  // Scan network for Wyrdsekai servers
  const handleScan = useCallback(async () => {
    setScanning(true);
    try {
      const servers = await discoverWyrdsekaiServers();
      setDiscoveredServers(servers);
      // Auto-select the first server found
      if (servers.length > 0) {
        setServerUrl(servers[0].url);
      }
    } catch {
      // Scan failure is non-fatal
    }
    setScanning(false);
  }, [setServerUrl]);

  // a pasted `wyrd phone invite` URL in the server
  // field configures the relay path instead of attempting an HTTP login.
  // Persists the relay material the standalone NATS path reads on its
  // next connect; TOFU pinning happens on first TLS contact (#705), with
  // the invite's fingerprint stored for the trust layer to verify against.
  const applyPhoneInvite = async (url: string): Promise<boolean> => {
    setLoading(true);
    setError(null);
    try {
      const invite = parsePhoneInvite(url);
      const relay = invite.relays[0];
      // Tier-1 phone guard: a zone-less invite is unroutable — the standalone NATS
      // path can't reach a home zone with no id and would SILENTLY fall to local
      // mode. Refuse it here (defense in depth; `wyrd phone invite` now refuses to
      // mint one too) so the user sees a real error, not a dead local Study.
      if (!invite.zoneId) {
        setError(t.connect.inviteNoZone
          ?? 'This invite has no home zone — ask for a fresh invite.');
        return false;
      }
      if (!relay) {
        setError(t.connect.inviteNoZone
          ?? 'This invite has no relay — ask for a fresh invite.');
        return false;
      }
      await secureStorage.setItem('@wyrd_relay_url', relay.wsUrl);
      await secureStorage.setItem('@wyrd_nats_user', relay.natsUser);
      await secureStorage.setItem('@wyrd_nats_pass', relay.natsPassword);
      if (invite.zoneId) {
        await secureStorage.setItem('@wyrd_zone_id', invite.zoneId);
      }
      if (relay.fp) {
        await secureStorage.setItem('@wyrd_relay_fp', relay.fp);
      }
      // Pin the relay's household CA NOW, while we hold the invite's
      // fingerprints — single-port relays have no other TOFU bootstrap
      // Best-effort: a failed pin leaves the
      // credentials in place and the connect attempt surfaces the TLS
      // error through the normal trust-not-established path.
      if (relay.fp || relay.caFp) {
        try {
          const u = new URL(relay.wsUrl.replace(/^wss:/, 'https:').replace(/^ws:/, 'http:'));
          const port = u.port ? Number(u.port) : 443;
          const { trustFromInviteFingerprints } = await import('../server/HouseholdTrust');
          const pinned = await trustFromInviteFingerprints(
            u.hostname, port, [relay.caFp, relay.fp]);
          if (!pinned) {
            // eslint-disable-next-line no-console
            console.warn('[ConnectScreen] invite-fingerprint pin did not take for', relay.wsUrl);
          }
        } catch (e) {
          // eslint-disable-next-line no-console
          console.warn('[ConnectScreen] invite-fingerprint pin failed:', e);
        }
      }
      setServerUrl(relay.wsUrl);
      // 2026-07-22: an accepted invite must land on the ZONE-BANK path, not
      // leave the user here with HTTP Login/Register buttons pointed at a wss
      // relay URL (the historical dead-end: fetch against wss → "Network
      // request failed"). Bank the invite (zone entry + held relay) and go to
      // Servers, where one tap runs the real relay login (mcp.login over NATS).
      const { addInviteToBank } = await import('../server/addInviteToBank');
      if (addInviteToBank(url)) {
        navigation.replace('Servers');
        return true;
      }
      setError(t.connect.inviteAccepted ?? 'Relay invite accepted — connecting…');
      return true;
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Invalid invite URL');
      return false;
    } finally {
      setLoading(false);
    }
  };

  // A pasted invite often lands AFTER pre-filled text (saved server URL,
  // stray whitespace) — "localhost:7070wyrdphone://…" must still read as an
  // invite, not fall through to an HTTP login against garbage. Extract the
  // wyrdphone:// substring wherever it sits.
  const extractInvite = (raw: string): string | null =>
    raw.match(/wyrdphone:\/\/\S+/)?.[0] ?? null;

  const doLogin = async () => {
    const invite = extractInvite(serverUrl);
    if (invite) {
      await applyPhoneInvite(invite);
      return;
    }
    // Relay wss URLs are not HTTP-loggable — route to the zone bank instead of
    // letting fetch() fail with an unhelpful network error.
    if (/^wss?:\/\//i.test(serverUrl)) {
      navigation.replace('Servers');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const auth = await login(serverUrl, username, password);
      setToken(auth.token);
      saveCredentials(serverUrl, username, auth.token);
      ws.connect(serverUrl, auth.token, usePreferencesStore.getState().locale);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : t.connect.loginFailed);
    }
    setLoading(false);
  };

  const doRegister = async () => {
    const invite = extractInvite(serverUrl);
    if (invite) {
      await applyPhoneInvite(invite);
      return;
    }
    // Same guard as doLogin: no HTTP against a relay wss URL.
    if (/^wss?:\/\//i.test(serverUrl)) {
      navigation.replace('Servers');
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const auth = await register(serverUrl, username, password, username);
      setToken(auth.token);
      saveCredentials(serverUrl, username, auth.token);
      ws.connect(serverUrl, auth.token, usePreferencesStore.getState().locale);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : t.connect.registrationFailed);
    }
    setLoading(false);
  };

  // This is a LOGIN FORM for one already-chosen server — not an onboarding
  // screen. WelcomeScreen owns onboarding (home zone / this phone / API key)
  // and ServersScreen owns the zone bank; the only way here is pasting a plain
  // host:port on Welcome, which needs a username and password.
  //
  // What used to be here and is deliberately gone (2026-07-29): "Connect
  // without account" (an auth bypass a logged-out person could tap), "Switch to
  // local companion instead" (the last door into the legacy FirstRunScreen),
  // and the Scan Network / Scan invite QR buttons, which Welcome already
  // offers. Together they made this look like a second, older front door —
  // reachable by walking backwards out of logout. Do not re-add them.
  return (
    <View style={[styles.container, { backgroundColor: c.background }]} testID="connect-screen">
      <Text style={[styles.title, { color: c.primary }]}>{t.connect.title}</Text>

      {/* Network scan button */}
      <TouchableOpacity
        style={[
          styles.scanButton,
          { borderColor: c.primary },
        ]}
        onPress={handleScan}
        disabled={scanning}
        testID="network-scan-button"
      >
        {scanning ? (
          <View style={styles.scanRow}>
            <ActivityIndicator color={c.primary} size="small" />
            <Text style={[styles.scanButtonText, { color: c.primary, marginLeft: 8 }]}>
              Scanning network...
            </Text>
          </View>
        ) : (
          <Text style={[styles.scanButtonText, { color: c.primary }]}>
            Scan Network
          </Text>
        )}
      </TouchableOpacity>

      {/* Invite QR scanner — wyrdphone:// invites from `wyrd phone invite` */}
      <TouchableOpacity
        style={[styles.scanButton, { borderColor: c.primary }]}
        onPress={() => (scannerOpen ? setScannerOpen(false) : void openScanner())}
        testID="qr-scan-button"
      >
        <Text style={[styles.scanButtonText, { color: c.primary }]}>
          {scannerOpen
            ? (t.connect.cancelScan ?? 'Cancel scan')
            : (t.connect.scanInvite ?? 'Scan invite QR')}
        </Text>
      </TouchableOpacity>
      {scannerOpen && cameraMod && (
        <cameraMod.CameraView
          style={styles.qrCamera}
          facing="back"
          barcodeScannerSettings={{ barcodeTypes: ['qr'] }}
          onBarcodeScanned={onQrScanned}
          testID="qr-scanner"
        />
      )}

      {/* Discovered servers */}
      {discoveredServers.map((server, idx) => {
        const isSelected = serverUrl === server.url;
        return (
          <TouchableOpacity
            key={`${server.url}-${idx}`}
            style={[
              styles.serverCard,
              {
                borderColor: isSelected ? c.primary : c.border,
                borderWidth: isSelected ? 2 : 1,
                backgroundColor: isSelected ? c.primaryLight : c.surface,
              },
            ]}
            onPress={() => setServerUrl(server.url)}
            testID={`server-card-${idx}`}
          >
            <Text style={{ color: c.text, fontWeight: '600' }}>
              {server.label}
            </Text>
            <Text style={{ color: c.textSecondary, fontSize: 12 }}>
              {server.url}
            </Text>
          </TouchableOpacity>
        );
      })}

      <TextInput
        style={[styles.input, { borderColor: c.inputBorder, backgroundColor: c.inputBackground, color: c.text }]}
        value={serverUrl}
        onChangeText={setServerUrl}
        placeholder={t.connect.serverUrl}
        placeholderTextColor={c.placeholder}
        autoCapitalize="none"
        testID="server-url-input"
      />
      <TextInput
        style={[styles.input, { borderColor: c.inputBorder, backgroundColor: c.inputBackground, color: c.text }]}
        value={username}
        onChangeText={setUsername}
        placeholder={t.connect.username}
        placeholderTextColor={c.placeholder}
        autoCapitalize="none"
        testID="username-input"
      />
      <TextInput
        style={[styles.input, { borderColor: c.inputBorder, backgroundColor: c.inputBackground, color: c.text }]}
        value={password}
        onChangeText={setPassword}
        placeholder={t.connect.password}
        placeholderTextColor={c.placeholder}
        secureTextEntry
        testID="password-input"
      />

      {error && <Text style={[styles.error, { color: c.error }]} testID="error-text">{error}</Text>}

      <View style={styles.buttons}>
        <TouchableOpacity style={[styles.button, { backgroundColor: c.primary }]} onPress={doLogin} disabled={loading} testID="login-button">
          <Text style={[styles.buttonText, { color: c.textOnPrimary }]}>{t.connect.login}</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.button, styles.outlineButton, { borderColor: c.primary }]}
          onPress={doRegister}
          disabled={loading}
          testID="register-button"
        >
          <Text style={[styles.outlineButtonText, { color: c.primary }]}>{t.connect.register}</Text>
        </TouchableOpacity>
      </View>

      {loading && <ActivityIndicator style={styles.spinner} color={c.primary} />}

    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', padding: 32 },
  title: {
    fontSize: 32,
    fontWeight: 'bold',
    textAlign: 'center',
    marginBottom: 24,
  },
  scanButton: {
    borderWidth: 1,
    borderRadius: 8,
    paddingVertical: 12,
    paddingHorizontal: 20,
    alignItems: 'center',
    marginBottom: 12,
  },
  scanButtonText: {
    fontWeight: '600',
    fontSize: 15,
  },
  qrCamera: {
    height: 280,
    borderRadius: 8,
    overflow: 'hidden',
    marginBottom: 12,
  },
  scanRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  serverCard: {
    borderRadius: 8,
    padding: 12,
    marginBottom: 8,
  },
  input: {
    borderWidth: 1,
    borderRadius: 8,
    padding: 12,
    marginBottom: 12,
    fontSize: 16,
  },
  error: { marginBottom: 12, textAlign: 'center' },
  buttons: {
    flexDirection: 'row',
    justifyContent: 'center',
    gap: 12,
    marginBottom: 16,
  },
  button: {
    paddingVertical: 12,
    paddingHorizontal: 24,
    borderRadius: 8,
  },
  buttonText: { fontWeight: 'bold', fontSize: 16 },
  outlineButton: {
    backgroundColor: 'transparent',
    borderWidth: 1,
  },
  outlineButtonText: { fontWeight: 'bold', fontSize: 16 },
  link: { textAlign: 'center', textDecorationLine: 'underline' },
  spinner: { marginTop: 16 },
});
