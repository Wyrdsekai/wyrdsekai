/**
 * StandaloneRoomScreen — Room UI for the local standalone PhoneNode.
 *
 * Reads from standaloneNodeStore and sends commands directly to PhoneNode
 * instead of through WebSocket. Structurally mirrors RoomScreen.
 */
import React, { useRef, useState, useEffect } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  FlatList,
  ScrollView,
  Switch,
  StyleSheet,
  ActivityIndicator,
  Modal,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/types';
import { useStandaloneNodeStore, StandaloneProseEntry } from '../state/standaloneNodeStore';
import { useStandaloneNode } from './StandaloneNodeContext';
import {
  RelayTunnelHolder, RelayTunnelServerConnection,
  mapSessionInput, renderSessionS2C,
} from '../engine/transit';
import { newId, type C2SMessage } from '../protocol/c2s';
import type { S2CMessage } from '../protocol/s2c';
import { useHouseholdStore } from '../state/householdStore';
import { useAppModeStore } from '../state/appModeStore';
import { useThemeColors } from '../theme/useTheme';
import { useStrings } from '../i18n/useStrings';
import { connectivityDotColor } from './HouseholdScreen';

const PLAYER_ENTITY_ID = 'player-local';

type Props = NativeStackScreenProps<RootStackParamList, 'Standalone'>;

export function StandaloneRoomScreen({ navigation }: Props) {
  const { phoneNode } = useStandaloneNode();
  const c = useThemeColors();
  const t = useStrings();
  const insets = useSafeAreaInsets();

  const {
    nodeState,
    nodeError,
    roomName,
    exits,
    entities,
    proseStream,
    companionState,
  } = useStandaloneNodeStore();
  const betweenState = useHouseholdStore((s) => s.connectivityState);

  const [inputText, setInputText] = useState('');
  const [enterToSend, setEnterToSend] = useState(true);
  const [showSettings, setShowSettings] = useState(false);
  const resetToFirstRun = useAppModeStore((s) => s.resetToFirstRun);
  // Home-zone relay leg configured → this screen is the zone TERMINAL, not a
  // standalone mini-zone; the quick-settings modal labels itself honestly.
  // Store value covers app-restart sessions; the secureStorage re-read covers
  // same-session logins (openZone persists @wyrd_relay_url AFTER the store's
  // boot-time load, so the zustand snapshot alone would lag until restart).
  const storeRelayUrl = useAppModeStore((s) => s.relayUrl);
  const [hasHomeZone, setHasHomeZone] = useState(storeRelayUrl != null);
  useEffect(() => {
    if (!showSettings) return;
    (async () => {
      try {
        const { secureStorage } = await import('../state/secureStorage');
        const live = await secureStorage.getItem('@wyrd_relay_url');
        setHasHomeZone(live != null || storeRelayUrl != null);
      } catch {
        setHasHomeZone(storeRelayUrl != null);
      }
    })();
  }, [showSettings, storeRelayUrl]);
  const listRef = useRef<FlatList>(null);

  // when the relay tunnel is up (relay-login mode), the
  // terminal tunnels a FULL session to the real zone: world verbs go out as C2S
  // over wyrd.tunnel.{zone}.*, and the zone's S2C frames render into the store.
  // When absent, every path below drives the offline PhoneNode unchanged.
  const tunnelRef = useRef<RelayTunnelServerConnection | null>(null);
  useEffect(() => {
    let cancelled = false;
    // renderSessionS2C is the EXECUTABLE render contract shared with KMP
    // (clients/parity/parity.json) — the prose rules live there, tested against
    // the same table on both clients (2026-07-25).
    const renderS2C = (msg: S2CMessage) => {
      const st = useStandaloneNodeStore.getState();
      const render = renderSessionS2C(msg);
      if (render.room) st.applyRoomSnapshot(render.room);
      for (const p of render.prose) st.addProse({ speaker: p.speaker, text: p.text });
    };
    const unsub = RelayTunnelHolder.subscribe((bc) => {
      // Tear down any prior tunnel first (relay reconnect / logout).
      tunnelRef.current?.close();
      tunnelRef.current = null;
      if (!bc) return;
      (async () => {
        const { secureStorage } = await import('../state/secureStorage');
        const zone = (await secureStorage.getItem('@wyrd_zone_id')) || '';
        const token = await secureStorage.getItem('@wyrd_mcp_session_token');
        if (cancelled || !zone) return;
        const conn = new RelayTunnelServerConnection(bc, zone, token ?? null);
        conn.onMessage(renderS2C);
        conn.open();
        // Prime the terminal with the zone's current room (initial render).
        conn.send({ type: 'command', id: newId(), command: 'look', args: [], payload: {} });
        tunnelRef.current = conn;
      })();
    });
    return () => {
      cancelled = true;
      unsub();
      tunnelRef.current?.close();
      tunnelRef.current = null;
    };
  }, []);

  /**
   * Forward a raw input line to the zone over the tunnel via the SHARED
   * session mapper — the executable parity contract with KMP
   * (clients/parity/parity.json). Exit chips also route through here
   * ("go <dir>"), so buttons and typed input cannot drift apart.
   */
  const sendOverTunnel = (raw: string): void => {
    const conn = tunnelRef.current;
    if (!conn) return;
    const st = useStandaloneNodeStore.getState();
    const mapped = mapSessionInput(raw, st.hints ?? [], newId);
    if (mapped.kind === 'send') {
      // Terminal-style input echo ("> l") — muted, unmistakably NOT speech.
      // The old "You: l" echo made every command read as the player SAYING it
      // (parity.json echoPolicy, 2026-07-25).
      st.addProse({ speaker: 'system', text: mapped.echo });
      conn.send(mapped.frame);
    } else if (mapped.kind === 'local') {
      st.addProse({ speaker: mapped.speaker, text: mapped.text });
    }
  };

  // Auto-scroll
  useEffect(() => {
    if (proseStream.length > 0) {
      listRef.current?.scrollToEnd({ animated: true });
    }
  }, [proseStream.length]);

  // Direction aliases
  const directionAliases: Record<string, string> = {
    n: 'north', s: 'south', e: 'east', w: 'west',
    ne: 'northeast', nw: 'northwest', se: 'southeast', sw: 'southwest',
    '\u5317': 'north', '\u5357': 'south', '\u6771': 'east', '\u897F': 'west',
    '\u4E0A': 'up', '\u4E0B': 'down',
  };
  const bareDirections = new Set([
    'north', 'south', 'east', 'west', 'up', 'down',
    'northeast', 'northwest', 'southeast', 'southwest',
    'n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw',
    '\u5317', '\u5357', '\u6771', '\u897F', '\u4E0A', '\u4E0B',
    'out', 'back',
  ]);
  const resolveDirection = (raw: string): string => {
    const lower = raw.toLowerCase();
    return directionAliases[lower] ?? lower;
  };

  const sendInput = () => {
    const trimmed = inputText.trim();
    if (!trimmed) return;
    const lower = trimmed.toLowerCase();

    // Server-routed Study commands: journal write + library search hit
    // dedicated REST endpoints rather than going through /api/mcp/do.
    // Earlier versions prepended `say` and relied on a Study onSay
    // handler that doesn't exist — the request reached the server but
    // never fired the right script. Direct endpoints work regardless of
    // which room the player is currently in.
    //   journal <text>        → POST /api/study/journal
    //   /journal <text>       → POST /api/study/journal
    //   library search <q>    → GET  /api/library/search?q=…
    //   search library <q>    → GET  /api/library/search?q=…
    //   search the library for <q> → same
    const studyAction: { kind: 'journal'; content: string; private: boolean }
      | { kind: 'library'; query: string }
      | null = (() => {
      const body = lower.startsWith('/') ? trimmed.substring(1) : trimmed;
      const bl = body.toLowerCase();
      // Journal
      const journalMatch = bl.match(/^journal(?:\s+(entry|private)\b)?\s+(.+)$/);
      if (journalMatch) {
        const isPrivate = journalMatch[1] === 'private';
        const content = body.substring(body.toLowerCase().indexOf(journalMatch[2])).trim();
        return { kind: 'journal', content, private: isPrivate };
      }
      // Library search prefixes
      const libPrefixes = [
        'library search ',
        'search library for ',
        'search the library for ',
        'search library ',
        'use library card ',
        'use library_card ',
      ];
      for (const pfx of libPrefixes) {
        if (bl.startsWith(pfx)) {
          return { kind: 'library', query: body.substring(pfx.length).trim() };
        }
      }
      return null;
    })();
    const sc = useStandaloneNodeStore.getState().serverClient;
    if (studyAction && sc) {
      // Echo the player's command so the prose log reads naturally
      useStandaloneNodeStore.getState().addProse({
        speaker: 'You',
        text: trimmed,
      });

      if (studyAction.kind === 'journal') {
        (async () => {
          // The /api/study/journal endpoint takes a `user` DID, not the MCP
          // session token. Read the persisted @wyrd_user_id from secureStorage
          // (seeded by the setupServerClient register/login flow or by the
          // e2e JSON-seed). Pre-migration this lived in AsyncStorage too —
          // now it's only in MMKV after the StandaloneNodeContext sweep.
          const { secureStorage } = await import('../state/secureStorage');
          const userId = await secureStorage.getItem('@wyrd_user_id');
          if (!userId) {
            useStandaloneNodeStore.getState().addProse({
              speaker: 'system',
              text: 'Journal entry skipped — no server identity. Reconnect to your server.',
            });
            return;
          }
          const res = await sc.writeJournal(userId, studyAction.content, studyAction.private);
          useStandaloneNodeStore.getState().addProse({
            speaker: res.ok ? 'narrator' : 'system',
            text: res.ok ? (res.data ?? 'Journal entry saved.') : `Journal write failed: ${res.error ?? 'unknown'}`,
          });
        })();
      } else {
        sc.searchLibrary(studyAction.query).then((res) => {
          useStandaloneNodeStore.getState().addProse({
            speaker: res.ok ? 'narrator' : 'system',
            text: res.ok ? (res.data ?? 'No results.') : `Library search failed: ${res.error ?? 'unknown'}`,
          });
        });
      }
      setInputText('');
      return;
    }

    // Remote-over-relay: when a tunnel is up, the terminal
    // is a window onto the REAL zone — forward the line and let the zone's world
    // answer. Study journal/library above still route via the server RPC; every
    // other verb tunnels here. No tunnel ⇒ fall through to the offline node.
    if (tunnelRef.current) {
      // LIVE ZONE SESSION → the shared mapper, the EXECUTABLE parity contract
      // with KMP (clients/parity/parity.json). All typed input over the tunnel
      // routes through mapSessionInput so both clients produce byte-identical
      // frames; behavior changes go table-first (2026-07-25).
      sendOverTunnel(trimmed);
      setInputText('');
      return;
    }

    // Slash commands: /<command> [args...] — route locally
    if (lower.startsWith('/')) {
      const parts = trimmed.substring(1).split(/\s+/);
      const cmd = parts[0].toLowerCase();
      if (cmd === 'inventory' || cmd === 'i') {
        // TODO: local inventory display
      } else if (cmd === 'help') {
        useStandaloneNodeStore.getState().addProse({
          speaker: 'system',
          text: 'Commands:\n' +
            '  say <text> or \'<text> or "<text>  -- Say something\n' +
            '  emote <action> or :<action> or ;<action>  -- Perform an action\n' +
            '  tell <name> <text> or ><name> <text>  -- Send a private message\n' +
            '  whisper <name> <text>  -- Whisper to someone nearby\n' +
            '  look or l  -- Look around\n' +
            '  go <direction>  -- Move to another room\n' +
            '  take <object>  -- Pick up an object\n' +
            '  drop <object>  -- Drop an object\n' +
            '  use <object>  -- Use an object\n' +
            '  /inventory or /i  -- Check your inventory\n' +
            '  /socials  -- List social emotes\n' +
            '  /help  -- Show this help',
        });
      } else if (cmd === 'socials') {
        useStandaloneNodeStore.getState().addProse({
          speaker: 'system',
          text: 'Social emotes (type the word to perform):\n' +
            '  nod, smile, laugh, grin, frown, shrug, sigh, gasp, blink, wince,\n' +
            '  wave, bow, clap, dance, stretch, yawn, pace, fidget,\n' +
            '  cry, cheer, groan, blush, ponder, brood, beam, sulk,\n' +
            '  hug, thank, agree, disagree, salute, welcome',
        });
      } else {
        // Send as say — room scripts parse verbs
        phoneNode.say(PLAYER_ENTITY_ID, phoneNode.playerName, trimmed.substring(1));
      }
      setInputText('');
      return;
    }

    // examine <target> / ex <target> / look at <target> — passive observation
    // Check BEFORE the bare `look` so `look at X`
    // doesn't shortcut to a room render.
    const examineMatch =
      lower.match(/^examine\s+(.+)$/) ??
      lower.match(/^ex\s+(.+)$/) ??
      lower.match(/^look\s+at\s+(.+)$/) ??
      lower.match(/^l\s+at\s+(.+)$/);
    if (examineMatch) {
      const target = trimmed.substring(trimmed.length - examineMatch[1].length);
      const result = phoneNode.examine(target);
      if (result) {
        useStandaloneNodeStore.getState().addProse({
          speaker: 'narrator',
          text: result.description ? `${result.name}\n${result.description}` : result.name,
        });
      } else {
        useStandaloneNodeStore.getState().addProse({
          speaker: 'system',
          text: `There's nothing called ${target} here.`,
        });
      }
      setInputText('');
      return;
    }

    // rename me <name> — local-only display name update (SPEC §7.4)
    const renameMatch = lower.match(/^rename\s+me(?:\s+(.+))?$/);
    if (renameMatch) {
      const newName = (renameMatch[1] ?? '').trim();
      const result = phoneNode.rename(newName);
      if (result.ok) {
        useStandaloneNodeStore.getState().addProse({
          speaker: 'system',
          text: `You are now known as ${result.newName}.`,
        });
      } else {
        useStandaloneNodeStore.getState().addProse({
          speaker: 'system',
          text: result.error,
        });
      }
      setInputText('');
      return;
    }

    // drop <object> (SPEC §4 — symmetric with take)
    const dropMatch =
      lower.match(/^drop\s+(.+)$/) ??
      lower.match(/^put\s+down\s+(.+)$/);
    if (dropMatch) {
      phoneNode.drop(PLAYER_ENTITY_ID, dropMatch[1].trim());
      setInputText('');
      return;
    }

    // look
    if (lower === 'look' || lower === 'l') {
      const snapshot = phoneNode.look();
      if (snapshot) {
        useStandaloneNodeStore.getState().applyRoomSnapshot(snapshot);
        useStandaloneNodeStore.getState().addProse({
          speaker: 'narrator',
          text: `${snapshot.name}\n${snapshot.description}`,
        });
      }
      setInputText('');
      return;
    }

    // go <direction>
    const goMatch = lower.match(/^(?:go|move)\s+(.+)$/);
    if (goMatch) {
      phoneNode.go(PLAYER_ENTITY_ID, phoneNode.playerName, resolveDirection(goMatch[1].trim()));
      setInputText('');
      return;
    }

    // Bare direction
    if (bareDirections.has(lower) || bareDirections.has(trimmed)) {
      const raw = bareDirections.has(lower) ? lower : trimmed;
      phoneNode.go(PLAYER_ENTITY_ID, phoneNode.playerName, resolveDirection(raw));
      setInputText('');
      return;
    }

    // take <object>
    const takeMatch = lower.match(/^(?:take|get)\s+(.+)$/) ?? lower.match(/^pick\s+up\s+(.+)$/);
    if (takeMatch) {
      phoneNode.take(PLAYER_ENTITY_ID, takeMatch[1].trim());
      setInputText('');
      return;
    }

    // use <object>
    const useMatch = lower.match(/^use\s+(.+)$/);
    if (useMatch) {
      phoneNode.use(PLAYER_ENTITY_ID, useMatch[1].trim(), null);
      setInputText('');
      return;
    }

    // Say shorthands: ' or "
    if (trimmed.startsWith("'") || trimmed.startsWith('"')) {
      phoneNode.say(PLAYER_ENTITY_ID, phoneNode.playerName, trimmed.substring(1));
      setInputText('');
      return;
    }

    // Emote: : or ; prefix — route to emote
    if (trimmed.startsWith(':') || trimmed.startsWith(';')) {
      const emoteText = trimmed.substring(1).trim();
      if (emoteText) {
        phoneNode.emote(PLAYER_ENTITY_ID, phoneNode.playerName, emoteText);
      }
      setInputText('');
      return;
    }

    // Tell: >name text — route as say for now (companion hears it)
    if (trimmed.startsWith('>')) {
      phoneNode.say(PLAYER_ENTITY_ID, phoneNode.playerName, trimmed);
      setInputText('');
      return;
    }

    // Full word commands
    if (lower.startsWith('emote ')) {
      phoneNode.emote(PLAYER_ENTITY_ID, phoneNode.playerName, trimmed.substring(6).trim());
      setInputText('');
      return;
    }
    if (lower.startsWith('tell ') || lower.startsWith('whisper ')) {
      const verb = lower.startsWith('tell ') ? 'tell' : 'whisper';
      // `tell <target> <message>` — parse target/message. When a
      // ServerClient is connected we route to `/api/mcp/tell`, which
      // delegates to the server's CrossZoneTellService for dotted
      // (`beta.wyrdsekai`) targets and falls back to whisper for plain
      // ones. Without a server, we still emit a local structured echo so
      // the user sees their own message even in offline-only mode.
      const rest = trimmed.substring(verb.length + 1).trim();
      const match = rest.match(/^(\S+)\s+(.+)$/);
      if (match) {
        const target = match[1];
        const message = match[2];
        const verbCap = verb === 'tell' ? 'tell' : 'whisper to';
        useStandaloneNodeStore.getState().addProse({
          speaker: 'narrator',
          text: `You ${verbCap} ${target}: "${message}"`,
        });
        const sc = useStandaloneNodeStore.getState().serverClient;
        if (sc) {
          // Best-effort async server delivery. Errors prose-log themselves.
          sc.tell(target, message).then((res) => {
            if (res.ok) {
              if (res.data) {
                useStandaloneNodeStore.getState().addProse({
                  speaker: 'narrator',
                  text: res.data,
                });
              }
            } else {
              useStandaloneNodeStore.getState().addProse({
                speaker: 'system',
                text: `Tell failed: ${res.error ?? 'unknown error'}`,
              });
            }
          });
        } else if (target.includes('.')) {
          useStandaloneNodeStore.getState().addProse({
            speaker: 'system',
            text: `${target} is in another zone — connect to your server to deliver cross-zone tells.`,
          });
        } else {
          // No server session AND not cross-zone: route to local companion as say
          phoneNode.say(PLAYER_ENTITY_ID, phoneNode.playerName, trimmed);
        }
      } else {
        // Malformed — fall through to legacy say behavior
        phoneNode.say(PLAYER_ENTITY_ID, phoneNode.playerName, trimmed);
      }
      setInputText('');
      return;
    }

    // Explicit say: say <text> or "<text>"
    const sayMatch = trimmed.match(/^say\s+(.+)$/i) ?? trimmed.match(/^"(.+)"$/);
    if (sayMatch) {
      phoneNode.say(PLAYER_ENTITY_ID, phoneNode.playerName, sayMatch[1]);
      setInputText('');
      return;
    }

    // Default: unknown command (standard MUD — no auto-say)
    useStandaloneNodeStore.getState().addProse({
      speaker: 'system',
      text: "Huh? Use 'text to say, :text to emote, or type /help for commands.",
    });
    setInputText('');
  };

  const sendGo = (direction: string) => {
    // Over a live zone session (relay tunnel) route the move to the REAL zone,
    // exactly like the text-input path (sendOverTunnel parses "go <dir>" into a
    // C2S go frame the zone dispatches). Offline → the local node.
    if (tunnelRef.current) {
      sendOverTunnel(`go ${direction}`);
      return;
    }
    phoneNode.go(PLAYER_ENTITY_ID, phoneNode.playerName, direction);
  };

  const handleSwitchToRemote = async () => {
    setShowSettings(false);
    await resetToFirstRun();
    // Re-onboard through Welcome, the current door. This used to reset to
    // FirstRun — and FirstRun's "connect to household" hands off to the legacy
    // Connect screen, so "switch to a server" walked backwards into the
    // pre-Welcome UI. Same defect as KMP's appMode="setup" (both 2026-07-29).
    navigation.reset({ index: 0, routes: [{ name: 'Welcome' }] });
  };

  const renderProseEntry = ({ item }: { item: StandaloneProseEntry }) => {
    // The outer Text becomes an Android TextView with text= populated from
    // the concatenation of its string + nested Text children — so UIAutomator
    // matches against the rendered content. Setting `accessible` + role=text
    // forces inclusion in the a11y tree under RN Fabric, where bare Text
    // nodes are otherwise filtered out and Maestro's `visible: "<substring>"`
    // matcher silently misses companion replies.
    const isPlayer =
      item.speaker !== 'narrator' && item.speaker !== 'system' && item.speaker !== 'emote';
    return (
      <View style={styles.proseEntry} testID="standalone-prose-entry">
        <Text
          selectable
          accessible
          accessibilityRole="text"
          style={[
            styles.proseText,
            { color: c.proseNormal },
            (item.speaker === 'narrator' || item.speaker === 'emote') && styles.italic,
          ]}
        >
          {isPlayer && <Text style={styles.speaker}>{item.speaker}: </Text>}
          {item.text}
        </Text>
      </View>
    );
  };

  // Loading state
  if (nodeState === 'starting') {
    return (
      <View style={[styles.centered, { backgroundColor: c.background }]}>
        <ActivityIndicator size="large" color={c.primary} />
        <Text style={[styles.loadingText, { color: c.textMuted }]}>Starting local node...</Text>
      </View>
    );
  }

  if (nodeState === 'error') {
    return (
      <View style={[styles.centered, { backgroundColor: c.background }]}>
        <Text style={[styles.errorText, { color: c.error }]}>{nodeError ?? 'Unknown error'}</Text>
        <TouchableOpacity style={[styles.button, { backgroundColor: c.primary }]} onPress={handleSwitchToRemote}>
          <Text style={[styles.buttonText, { color: c.textOnPrimary }]}>Switch mode</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <View style={[styles.container, { backgroundColor: c.background }]}>
      {/* Header — paddingTop respects status-bar insets so the gear/dot icons
          sit below the system bar (otherwise the right-side actions overlap
          the safe area and become untappable on devices with cutouts). */}
      <View
        style={[styles.header, { backgroundColor: c.header, paddingTop: insets.top + 16 }]}
        testID="standalone-header"
      >
        <Text style={[styles.roomName, { color: c.textOnHeader }]} testID="standalone-room-name">
          {roomName || 'Starting...'}
        </Text>
        <View style={styles.headerActions}>
          <TouchableOpacity
            onPress={() => navigation.navigate('Household')}
            testID="standalone-between-dot"
            accessibilityLabel="Between status"
          >
            <View
              style={[
                styles.companionDot,
                { backgroundColor: connectivityDotColor(betweenState) },
              ]}
            />
          </TouchableOpacity>
          <View
            testID="standalone-companion-dot"
            style={[
              styles.companionDot,
              {
                backgroundColor:
                  companionState === 'thinking' ? '#FF9800'
                    : companionState === 'idle' ? '#4CAF50'
                    : '#BDBDBD',
              },
            ]}
          />
          <TouchableOpacity onPress={() => setShowSettings(true)} testID="standalone-settings-button">
            <Text style={styles.headerIcon}>{'\u{2699}'}</Text>
          </TouchableOpacity>
        </View>
      </View>

      {/* Prose */}
      <FlatList
        ref={listRef}
        data={proseStream}
        renderItem={renderProseEntry}
        keyExtractor={(_, index) => String(index)}
        style={styles.proseList}
        contentContainerStyle={styles.proseContent}
        testID="standalone-prose-list"
      />

      {/* Entity presence */}
      {entities.filter(e => e.type === 'agent').length > 0 && (
        <Text
          style={[styles.entityPresence, { color: c.textMuted }]}
          testID="standalone-entity-presence"
        >
          Present: {entities.filter(e => e.type === 'agent').map(e => e.name).join(', ')}
        </Text>
      )}

      {/* Exits */}
      {exits.length > 0 && (
        <ScrollView
          horizontal
          style={styles.chipRow}
          contentContainerStyle={styles.chipContent}
        >
          {exits.map((exit) => (
            <TouchableOpacity
              key={exit.direction}
              style={[styles.exitChip, { backgroundColor: c.exitChipBg }]}
              onPress={() => sendGo(exit.direction)}
              testID={`standalone-exit-${exit.direction}`}
            >
              <Text style={[styles.exitText, { color: c.exitChipText }]}>
                {t.room.directionLabels[exit.direction] ?? exit.direction}
              </Text>
            </TouchableOpacity>
          ))}
        </ScrollView>
      )}

      {/* Enter-to-send toggle */}
      <View style={styles.enterToSendRow}>
        <Switch
          value={enterToSend}
          onValueChange={setEnterToSend}
          style={styles.enterToSendSwitch}
          testID="standalone-enter-to-send"
        />
        <Text style={[styles.enterToSendLabel, { color: c.textMuted }]}>
          Enter sends
        </Text>
      </View>

      {/* Settings modal — mirrors KMP NodeSettingsDialog (switch-to-remote + companion info). */}
      <Modal
        visible={showSettings}
        transparent
        animationType="fade"
        onRequestClose={() => setShowSettings(false)}
      >
        <View style={styles.modalBackdrop}>
          <View style={[styles.modalCard, { backgroundColor: c.background }]} testID="standalone-settings-dialog">
            <Text style={[styles.modalTitle, { color: c.text }]}>Node Settings</Text>
            {/* Honest live-where framing (2026-07-22, parity with the KMP
                NodeSettingsDialog): this screen serves BOTH the home-zone
                terminal (relay leg up — "Mode: Local" was a lie there) and
                the true standalone mini-zone. */}
            <Text style={[styles.modalLabel, { color: c.textMuted }]}>Where your companion lives</Text>
            <Text style={[styles.modalValue, { color: c.text }]}>
              {hasHomeZone ? 'Home zone (this phone is her window)' : 'On this phone (standalone)'}
            </Text>
            <TouchableOpacity
              onPress={() => { setShowSettings(false); navigation.navigate('Servers'); }}
              testID="standalone-my-zones"
              style={styles.modalRow}
            >
              <Text style={[styles.modalAction, { color: c.primary }]}>My zones</Text>
            </TouchableOpacity>
            <TouchableOpacity
              onPress={() => { setShowSettings(false); navigation.navigate('Settings'); }}
              testID="standalone-open-settings"
              style={styles.modalRow}
            >
              <Text style={[styles.modalAction, { color: c.primary }]}>All settings…</Text>
            </TouchableOpacity>
            {/* Switch lives in full Settings now, with the honest reversible
                copy — the old resetToFirstRun path (legacy FirstRun re-pair)
                is retired from this modal. testID kept for the web e2e. */}
            <TouchableOpacity
              onPress={() => { setShowSettings(false); navigation.navigate('Settings'); }}
              testID="switch-mode-button"
              style={styles.modalRow}
            >
              <Text style={[styles.modalAction, { color: c.error }]}>
                {hasHomeZone ? 'Switch to standalone…' : 'Connect to a home zone…'}
              </Text>
            </TouchableOpacity>
            <TouchableOpacity onPress={() => setShowSettings(false)} style={styles.modalRow}>
              <Text style={[styles.modalAction, { color: c.primary }]}>Close</Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>

      {/* Input — paddingBottom respects the gesture-nav / navigation-bar inset.
          Android 15 edge-to-edge draws the app under the system nav bar; without
          this the input row + Send button sit beneath the gesture area and taps
          fire HOME instead of Send (task #30). */}
      <View style={[styles.inputRow, { borderTopColor: c.divider, paddingBottom: 8 + insets.bottom }]}>
        <TextInput
          style={[styles.textInput, { borderColor: c.inputBorder, backgroundColor: c.inputBackground, color: c.text }]}
          value={inputText}
          onChangeText={setInputText}
          placeholder={t.room.placeholder}
          placeholderTextColor={c.placeholder}
          onSubmitEditing={enterToSend ? sendInput : undefined}
          returnKeyType={enterToSend ? 'send' : 'default'}
          testID="standalone-input"
        />
        <TouchableOpacity
          style={[styles.sendButton, { backgroundColor: c.primary }]}
          onPress={sendInput}
          disabled={!inputText.trim()}
          testID="standalone-send-button"
        >
          <Text style={[styles.sendText, { color: c.textOnPrimary }]}>{t.room.send}</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  centered: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 32 },
  loadingText: { marginTop: 16, fontSize: 16 },
  errorText: { fontSize: 16, textAlign: 'center', marginBottom: 16 },
  button: { paddingVertical: 12, paddingHorizontal: 24, borderRadius: 8 },
  buttonText: { fontWeight: 'bold', fontSize: 16 },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 16,
  },
  roomName: { fontSize: 20, fontWeight: 'bold' },
  headerActions: { flexDirection: 'row', gap: 12, alignItems: 'center' },
  companionDot: { width: 8, height: 8, borderRadius: 4 },
  headerIcon: { fontSize: 20 },
  proseList: { flex: 1 },
  proseContent: { padding: 16 },
  proseEntry: { marginBottom: 4 },
  proseText: { fontSize: 15, lineHeight: 22 },
  speaker: { fontWeight: 'bold' },
  italic: { fontStyle: 'italic' as const },
  chipRow: { maxHeight: 44 },
  chipContent: { paddingHorizontal: 16, gap: 8, alignItems: 'center' },
  exitChip: {
    paddingVertical: 6,
    paddingHorizontal: 14,
    borderRadius: 16,
  },
  exitText: { fontWeight: '600' },
  entityPresence: {
    fontSize: 12,
    paddingHorizontal: 16,
    paddingVertical: 2,
  },
  enterToSendRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 2,
  },
  enterToSendSwitch: {
    transform: [{ scale: 0.7 }],
  },
  enterToSendLabel: {
    fontSize: 12,
    marginLeft: 4,
  },
  inputRow: {
    flexDirection: 'row',
    padding: 8,
    gap: 8,
    borderTopWidth: 1,
  },
  textInput: {
    flex: 1,
    borderWidth: 1,
    borderRadius: 8,
    padding: 10,
    fontSize: 16,
  },
  sendButton: {
    paddingHorizontal: 20,
    borderRadius: 8,
    justifyContent: 'center',
  },
  sendText: { fontWeight: 'bold' },
  modalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
  },
  modalCard: {
    width: '100%',
    maxWidth: 360,
    borderRadius: 12,
    padding: 20,
  },
  modalTitle: { fontSize: 18, fontWeight: 'bold', marginBottom: 12 },
  modalLabel: { fontSize: 12, textTransform: 'uppercase', marginTop: 4 },
  modalValue: { fontSize: 16, marginBottom: 16 },
  modalRow: { paddingVertical: 12 },
  modalAction: { fontSize: 16, fontWeight: '500' },
});
