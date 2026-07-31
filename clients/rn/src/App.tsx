import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import { Platform, ActivityIndicator, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { WyrdWebSocket } from './network/websocket';
import { useSessionStore } from './state/sessionStore';
import { usePreferencesStore } from './state/preferencesStore';
import { useAppModeStore } from './state/appModeStore';
import { useZoneBankStore } from './state/zoneBankStore';
import { initSecureStorage, secureStorage } from './state/secureStorage';
import { useInference } from './inference/InferenceContext';
import type { RemoteAuthType } from './inference/InferenceRouter';
import { installPinMismatchListener } from './server/HouseholdTrust';
import { RootStackParamList } from './navigation/types';
import { FirstRunScreen } from './screens/FirstRunScreen';
import { WelcomeScreen } from './screens/WelcomeScreen';
import { ConnectScreen } from './screens/ConnectScreen';
import { ServersScreen } from './screens/ServersScreen';
import { FindZoneScreen } from './screens/FindZoneScreen';
import { addInviteToBank } from './server/addInviteToBank';
import { isPhoneInviteUrl } from './network/phoneInvite';
import { RoomScreen } from './screens/RoomScreen';
import { InventoryScreen } from './screens/InventoryScreen';
import { SettingsScreen } from './screens/SettingsScreen';
import { ModelDownloadScreen } from './screens/ModelDownloadScreen';
import { WebNodeDashboardScreen } from './screens/WebNodeDashboardScreen';
import { HouseholdScreen } from './screens/HouseholdScreen';
import { StudyScreen } from './screens/StudyScreen';
import { StandaloneRoomScreen } from './screens/StandaloneRoomScreen';
import { StandaloneNodeProvider } from './screens/StandaloneNodeContext';
import { BirthScreen } from './screens/BirthScreen';
import { LoginScreen } from './screens/LoginScreen';
import { InferenceProvider } from './inference/InferenceContext';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { newId } from './protocol/c2s';

let WebNodeProviderComponent: React.FC<{ children: React.ReactNode }> | null = null;
if (Platform.OS === 'web') {
  try {
    const mod = require('./web/WebNodeContext');
    WebNodeProviderComponent = mod.WebNodeProvider;
  } catch {}
}

/** React context to pass the WyrdWebSocket instance to all screens. */
const WsContext = createContext<WyrdWebSocket | null>(null);

export function useWs(): WyrdWebSocket {
  const ws = useContext(WsContext);
  if (!ws) {
    throw new Error('useWs must be used within a WsContext.Provider');
  }
  return ws;
}

const Stack = createNativeStackNavigator<RootStackParamList>();

/** Wraps StandaloneRoomScreen in its required context provider. */
function StandaloneScreenWrapper(props: NativeStackScreenProps<RootStackParamList, 'Standalone'>) {
  return (
    <StandaloneNodeProvider>
      <StandaloneRoomScreen {...props} />
    </StandaloneNodeProvider>
  );
}

/** Wraps WelcomeScreen with state-store + navigation glue.
 * Mirrors KMP WyrdApp.kt's WelcomeScreen → appMode="local" transition:
 * any of the three completion paths (use server / API key / standalone)
 * marks first-run complete and sets local mode. The user lands on Birth next,
 * which boots the standalone node. If they provided a server URL, we save it
 * as the inference endpoint so the companion can route through it. */
function WelcomeScreenWrapper(props: NativeStackScreenProps<RootStackParamList, 'Welcome'>) {
  const { setLocalMode, setRemoteMode, setInferenceUrl } = useAppModeStore();
  const { inferenceRouter } = useInference();
  // Home-zone path (Mode 1) — ONE input, the app picks the door:
  //   wyrdphone:// invite → zone bank → Servers (relay login via openZone).
  //     A NAT'd zone is reachable ONLY through the relay, and the relay login
  //     is mcp.login over NATS — never the HTTP fetch.
  //   bare relay URL (wss://) → rejected with guidance: relay transport creds
  //     travel in invites; a bare relay address is not loggable-into.
  //   plain LAN/host URL → direct-WS account login on the Connect screen.
  // Returns an error string for the Welcome screen to show, or null after
  // navigating.
  const handleHomeZone = async (input: string): Promise<string | null> => {
    // Pasted invites can carry stray prefix text/whitespace — find the
    // wyrdphone:// substring wherever it sits (same hardening as
    // ConnectScreen.extractInvite).
    const embedded = input.match(/wyrdphone:\/\/\S+/)?.[0];
    if (embedded) input = embedded;
    if (isPhoneInviteUrl(input)) {
      const added = addInviteToBank(input);
      if (added) {
        // Persist the relay leg NOW (not only after the first successful
        // login): the Mode-1 model-skip gate reads @wyrd_relay_url, and
        // without it a boot that happens before login completes (user
        // abandons at the password prompt, app restart) treats the phone as
        // pure-local and starts the 2.5GB model download she'll never need.
        // Mirrors ConnectScreen.applyPhoneInvite + KMP's invite branch.
        try {
          const { parsePhoneInvite } = await import('./network/phoneInvite');
          const { secureStorage } = await import('./state/secureStorage');
          const invite = parsePhoneInvite(input);
          const relay = invite.relays[0];
          if (relay) {
            await secureStorage.setItem('@wyrd_relay_url', relay.wsUrl);
            await secureStorage.setItem('@wyrd_nats_user', relay.natsUser);
            await secureStorage.setItem('@wyrd_nats_pass', relay.natsPassword);
            if (invite.zoneId) await secureStorage.setItem('@wyrd_zone_id', invite.zoneId);
            if (relay.fp) await secureStorage.setItem('@wyrd_relay_fp', relay.fp);
            // Pin the relay's household CA while we hold the invite's
            // fingerprints (the invite IS the trust decision) — best-effort.
            if (relay.fp || relay.caFp) {
              try {
                const u = new URL(relay.wsUrl.replace(/^wss:/, 'https:').replace(/^ws:/, 'http:'));
                const { trustFromInviteFingerprints } = await import('./server/HouseholdTrust');
                await trustFromInviteFingerprints(
                  u.hostname, u.port ? Number(u.port) : 443, [relay.caFp, relay.fp]);
              } catch { /* pin failure surfaces on first TLS contact */ }
            }
          }
        } catch { /* invite parsed by addInviteToBank already; leg persist is best-effort */ }
        await setLocalMode('Wyrd');
        props.navigation.replace('Servers');
        return null;
      }
      return 'That invite could not be read — ask your node for a fresh one (wyrd phone invite).';
    }
    if (/^wss?:\/\//i.test(input)) {
      return 'That looks like a relay address. Relays need an invite — on your node, run: wyrd phone invite';
    }
    // Plain server URL: direct-WS account login on the Connect screen.
    useSessionStore.getState().setServerUrl(input);
    await setRemoteMode();
    props.navigation.replace('Connect');
    return null;
  };
  const handleComplete = async (
    serverUrl: string | null,
    apiProvider: string | null,
    apiKey: string | null,
    onDeviceModel?: boolean,
  ) => {
    // Picking "on-device model" in the wizard IS the experimental opt-in: it is
    // the one path with nothing else to think with. Persist it before
    // setLocalMode, so the first mode resolution already sees it and the boot
    // path downloads rather than printing the finish-setup notice.
    if (onDeviceModel) {
      await useAppModeStore.getState().setOnDeviceModelOptIn(true);
    }
    // a wyrdphone:// invite populates the zone bank
    // (held relays + a zone entry) and lands on the Servers screen, where one
    // tap auto-attempts the relay login. Distinct from the direct-WS paths.
    if (serverUrl && isPhoneInviteUrl(serverUrl)) {
      const added = addInviteToBank(serverUrl);
      await setLocalMode('Wyrd');
      if (added) {
        props.navigation.replace('Servers');
        return;
      }
    }
    if (serverUrl) {
      setInferenceUrl(serverUrl);
    }
    if (apiProvider && apiKey) {
      // Cloud-API path: persist provider+key as inference endpoint.
      // Map provider → URL like KMP WyrdApp.kt onComplete does.
      const apiUrl =
        apiProvider === 'anthropic' ? 'https://api.anthropic.com'
        : apiProvider === 'openai' ? 'https://api.openai.com'
        : apiProvider === 'openrouter' ? 'https://openrouter.ai/api'
        : serverUrl ?? '';
      if (apiUrl) setInferenceUrl(apiUrl);

      // Persist credentials so the next launch restores them (mirrors KMP
      // system properties, but durable). Read back in StandaloneNodeContext
      // at boot time and re-applied to inferenceRouter before node.start().
      await secureStorage.setItem('@wyrd_api_provider', apiProvider);
      await secureStorage.setItem('@wyrd_api_key', apiKey);
      if (apiUrl) {
        await secureStorage.setItem('@wyrd_inference_url', apiUrl);
      }

      // Apply NOW so Birth → Standalone → companion's first inference call
      // has the header from request #1, not request #2 after a state hydrate.
      const authType: RemoteAuthType =
        apiProvider === 'anthropic' ? 'x-api-key' : 'bearer';
      inferenceRouter.setRemoteAuth(authType, apiKey);
      if (apiUrl) inferenceRouter.setRemoteUrl(apiUrl);
      // Cloud OpenAI-compat endpoints REQUIRE a `model` field (Anthropic 400s
      // with "model: Field required"/invalid-model otherwise). The "apply NOW"
      // path must set it too: on the FIRST session — fresh onboard → say — the
      // companion's first inference fires before any boot-hydrate (wireCredentials)
      // runs, so without this the request ships model:'local-model', the API
      // rejects it, and the companion parks in "considers..." with no reply.
      const apiModel =
        apiProvider === 'anthropic' ? 'claude-sonnet-4-6'
        : apiProvider === 'openai' ? 'gpt-4o-mini'
        : apiProvider === 'openrouter' ? 'anthropic/claude-sonnet-4'
        : null;
      if (apiModel) inferenceRouter.setRemoteModel(apiModel);
    }
    await setLocalMode('Wyrd');
    // setLocalMode flips firstRunComplete=true; React's NavigationContainer
    // re-renders with the new initialRouteName next mount, but for immediate
    // navigation we replace to Birth.
    props.navigation.replace('Birth');
  };
  return <WelcomeScreen onComplete={handleComplete} onHomeZone={handleHomeZone} />;
}

/** Wraps ServersScreen with navigation. On a successful
 *  relay login the connected client is already in standaloneNodeStore; we land
 *  on the Standalone room which routes tell/library/journal through it. */
function ServersScreenWrapper(props: NativeStackScreenProps<RootStackParamList, 'Servers'>) {
  const { setLocalMode } = useAppModeStore();
  return (
    <ServersScreen
      onConnected={async () => {
        await setLocalMode('Wyrd');
        props.navigation.replace('Standalone');
      }}
      onFindZone={() => props.navigation.navigate('FindZone')}
    />
  );
}

export default function App() {
  const wsRef = useRef(new WyrdWebSocket());
  const ws = wsRef.current;

  const { setConnectionState, handleMessage, connectionState } = useSessionStore();
  const { firstRunComplete, mode, loaded, inferenceUrl, pairingToken } = useAppModeStore();

  // Load persisted preferences (locale, theme) and app mode on start.
  // initSecureStorage MUST run before any store reads its credentials —
  // it wipes the legacy plaintext AsyncStorage entries and warms the
  // encrypted MMKV bootstrap.
  useEffect(() => {
    (async () => {
      await initSecureStorage();
      usePreferencesStore.getState().loadFromStorage();
      await useAppModeStore.getState().loadFromStorage();
      // held relays + zone bank (the address book that
      // routing consults). Load alongside app mode so the Servers surface has
      // it on first render.
      await useZoneBankStore.getState().loadFromStorage();
    })();
    // Pin-mismatch recovery: native TLS layer emits an event when an
    // existing pin doesn't validate against the chain (cert rotation).
    // The listener pops an Alert and on accept clears the pin so the
    // next request re-runs TOFU.
    const detach = installPinMismatchListener();
    return () => { detach(); };
  }, []);

  useEffect(() => {
    const unsub1 = ws.onStateChange(setConnectionState);
    const unsub2 = ws.onMessage(handleMessage);
    return () => {
      unsub1();
      unsub2();
    };
  }, []);

  // Track current room for reconnect URL
  const roomId = useSessionStore((s) => s.roomId);
  useEffect(() => {
    if (roomId) ws.setCurrentRoomId(roomId);
  }, [roomId]);

  // Send locale to server whenever connection becomes established
  useEffect(() => {
    if (connectionState === 'connected') {
      const locale = usePreferencesStore.getState().locale;
      ws.send({ type: 'set_preference', id: newId(), key: 'locale', value: locale });
    }
  }, [connectionState]);

  // Wait for app mode to load from storage before rendering navigation
  if (!loaded) {
    return (
      <View style={{ flex: 1, justifyContent: 'center', alignItems: 'center' }}>
        <ActivityIndicator size="large" />
      </View>
    );
  }

  // Determine initial route based on persisted mode.
  // Mirrors KMP WyrdApp.kt routing:
  //   appMode==null    → Welcome      (firstRunComplete=false)
  //   appMode=="setup" → FirstRun     (firstRunComplete=true && mode='unset' — re-pairing)
  //   appMode=="local" → Birth/Standalone
  //   appMode=="remote"→ Connect/Room
  //
  // the legacy LoginScreen branch (HTTP
  // /api/auth/status + /api/mcp/login probe) is gone. NATS-based auth runs
  // inside StandaloneNodeContext.setupServerClient — it probes via
  // wyrd.zone.{zone}.auth.status, reuses saved MCP creds, redeems invites,
  // or registers anonymously, all over wss://relay:4443. If none works the
  // companion stays local-only with prose explaining why.
  let initialRouteName: keyof RootStackParamList = 'Welcome';
  if (firstRunComplete) {
    if (mode === 'local') {
      initialRouteName = 'Birth';
    } else if (mode === 'remote') {
      initialRouteName = 'Connect';
    } else {
      // mode='unset' after resetToFirstRun — show legacy FirstRun for re-pairing.
      initialRouteName = 'FirstRun';
    }
  }

  const content = (
    <InferenceProvider>
      <NavigationContainer>
        <Stack.Navigator
          initialRouteName={initialRouteName}
          screenOptions={{ headerShown: false }}
        >
          <Stack.Screen name="Welcome" component={WelcomeScreenWrapper} />
          <Stack.Screen name="FirstRun" component={FirstRunScreen} />
          <Stack.Screen
            name="Login"
            component={LoginScreen}
            initialParams={{
              serverUrl: inferenceUrl ?? '',
              deviceToken: pairingToken ?? '',
            }}
          />
          <Stack.Screen name="Birth" component={BirthScreen} />
          <Stack.Screen name="Connect" component={ConnectScreen} />
          <Stack.Screen name="Servers" component={ServersScreenWrapper} />
          <Stack.Screen name="FindZone" component={FindZoneScreen} />
          <Stack.Screen name="Room" component={RoomScreen} />
          <Stack.Screen name="Standalone" component={StandaloneScreenWrapper} />
          <Stack.Screen name="Inventory" component={InventoryScreen} />
          <Stack.Screen name="Settings" component={SettingsScreen} />
          <Stack.Screen name="ModelDownload" component={ModelDownloadScreen} />
          <Stack.Screen name="WebNodeDashboard" component={WebNodeDashboardScreen} />
          <Stack.Screen name="Household" component={HouseholdScreen} />
          <Stack.Screen name="Study" component={StudyScreen} />
        </Stack.Navigator>
      </NavigationContainer>
    </InferenceProvider>
  );

  return (
    <SafeAreaProvider>
    <WsContext.Provider value={ws}>
      {WebNodeProviderComponent ? (
        <WebNodeProviderComponent>{content}</WebNodeProviderComponent>
      ) : (
        content
      )}
    </WsContext.Provider>
    </SafeAreaProvider>
  );
}
