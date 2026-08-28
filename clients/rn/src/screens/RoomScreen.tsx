import React, { useRef, useState, useEffect } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  FlatList,
  ScrollView,
  StyleSheet,
  Platform,
  ActivityIndicator,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/types';
import { useSessionStore, ProseEntry } from '../state/sessionStore';
import { useInferenceStore } from '../state/inferenceStore';
import { useWebNodeStore } from '../state/webNodeStore';
import { useWs } from '../App';
import { newId } from '../protocol/c2s';
import { ContentBlockRegistry } from '../rendering/ContentBlockRenderer';
import { usePreferencesStore } from '../state/preferencesStore';
import { useHouseholdStore } from '../state/householdStore';
import { resolveLabel } from '../i18n/i18nStrings';
import { useThemeColors } from '../theme/useTheme';
import { useStrings } from '../i18n/useStrings';
import { connectivityDotColor } from './HouseholdScreen';

type Props = NativeStackScreenProps<RootStackParamList, 'Room'>;

export function RoomScreen({ navigation }: Props) {
  const ws = useWs();
  const c = useThemeColors();
  const t = useStrings();
  const {
    roomName,
    exits,
    hints,
    proseStream,
    streamingText,
    roomId,
    inventory,
    connectionState,
    setToken,
  } = useSessionStore();

  const activeBackend = useInferenceStore((s) => s.activeBackend);
  const locale = usePreferencesStore((s) => s.locale);
  const webLLMModelId = Platform.OS === 'web' ? useWebNodeStore((s) => s.webLLMModelId) : null;
  const betweenState = useHouseholdStore((s) => s.connectivityState);

  const [inputText, setInputText] = useState('');
  const listRef = useRef<FlatList>(null);

  // Auto-scroll on new entries
  useEffect(() => {
    if (proseStream.length > 0) {
      listRef.current?.scrollToEnd({ animated: true });
    }
  }, [proseStream.length]);

  // On explicit disconnect (not reconnecting) go to Your servers, so the zone
  // is one tap away. This used to reset to the legacy Connect screen, which is
  // how an ordinary disconnect dropped people into the pre-Welcome UI.
  useEffect(() => {
    if (connectionState === 'disconnected') {
      navigation.reset({
        index: 0,
        routes: [{ name: 'Servers' }],
      });
    }
  }, [connectionState]);

  const isReconnecting = connectionState === 'reconnecting';

  // Remote mode gate: show loading overlay until room has loaded
  const roomNotReady = !roomName || roomName === '?';
  if (roomNotReady) {
    return (
      <View style={[styles.container, styles.loadingOverlay, { backgroundColor: c.background }]}>
        <ActivityIndicator size="large" color={c.primary} />
        <Text style={[styles.loadingText, { color: c.text }]}>
          {t.birth.remoteEntering}
        </Text>
      </View>
    );
  }

  // Direction aliases: short forms + Japanese kanji
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
  ]);

  const resolveDirection = (raw: string): string => {
    const lower = raw.toLowerCase();
    return directionAliases[lower] ?? lower;
  };

  const sendInput = () => {
    const trimmed = inputText.trim();
    if (!trimmed) return;

    const lower = trimmed.toLowerCase();

    // Slash commands: /<command> [args...] → send as Command
    if (lower.startsWith('/')) {
      const parts = trimmed.substring(1).split(/\s+/);
      const cmd = parts[0].toLowerCase();
      const args = parts.slice(1);
      ws.send({ type: 'command', id: newId(), command: cmd, args, payload: {} });
      setInputText('');
      return;
    }

    // look or l
    if (lower === 'look' || lower === 'l') {
      ws.send({ type: 'look', id: newId(), roomId });
      setInputText('');
      return;
    }

    // go <direction> or move <direction>
    const goMatch = lower.match(/^(?:go|move)\s+(.+)$/);
    if (goMatch) {
      ws.send({ type: 'go', id: newId(), roomId, direction: resolveDirection(goMatch[1].trim()) });
      setInputText('');
      return;
    }

    // Bare direction word (check original trimmed for Japanese chars)
    if (bareDirections.has(lower) || bareDirections.has(trimmed)) {
      const raw = bareDirections.has(lower) ? lower : trimmed;
      ws.send({ type: 'go', id: newId(), roomId, direction: resolveDirection(raw) });
      setInputText('');
      return;
    }

    // take/get <object> or pick up <object>
    const takeMatch = lower.match(/^(?:take|get)\s+(.+)$/) ?? lower.match(/^pick\s+up\s+(.+)$/);
    if (takeMatch) {
      ws.send({ type: 'take', id: newId(), roomId, objectName: takeMatch[1].trim() });
      setInputText('');
      return;
    }

    // drop <object>
    const dropMatch = lower.match(/^drop\s+(.+)$/);
    if (dropMatch) {
      ws.send({ type: 'drop', id: newId(), roomId, objectName: dropMatch[1].trim() });
      setInputText('');
      return;
    }

    // retire <object> — the counterpart to drop. Without this the phone would send the
    // line as speech, so a person trying to get rid of something says it out loud instead.
    const retireMatch = lower.match(/^(?:retire|destroy|discard)\s+(.+)$/);
    if (retireMatch) {
      ws.send({ type: 'retire', id: newId(), roomId, objectName: retireMatch[1].trim() });
      setInputText('');
      return;
    }

    // use <object>
    const useMatch = lower.match(/^use\s+(.+)$/);
    if (useMatch) {
      ws.send({ type: 'use', id: newId(), roomId, objectName: useMatch[1].trim() });
      setInputText('');
      return;
    }

    // Say shorthands: ' or "
    if (trimmed.startsWith("'") || trimmed.startsWith('"')) {
      ws.send({ type: 'say', id: newId(), roomId, text: trimmed.substring(1) });
      setInputText('');
      return;
    }

    // Emote: : or ;
    if (trimmed.startsWith(':') || trimmed.startsWith(';')) {
      ws.send({ type: 'say', id: newId(), roomId, text: trimmed });
      setInputText('');
      return;
    }

    // Tell: >name text
    if (trimmed.startsWith('>')) {
      ws.send({ type: 'say', id: newId(), roomId, text: trimmed });
      setInputText('');
      return;
    }

    // Full word commands
    if (lower.startsWith('emote ')) {
      ws.send({ type: 'say', id: newId(), roomId, text: ':' + trimmed.substring(6) });
      setInputText('');
      return;
    }
    if (lower.startsWith('tell ') || lower.startsWith('whisper ')) {
      ws.send({ type: 'say', id: newId(), roomId, text: trimmed });
      setInputText('');
      return;
    }

    // Explicit say: say <text> or "<text>"
    const sayMatch = trimmed.match(/^say\s+(.+)$/i) ?? trimmed.match(/^"(.+)"$/);
    if (sayMatch) {
      ws.send({ type: 'say', id: newId(), roomId, text: sayMatch[1] });
      setInputText('');
      return;
    }

    // Default: send as Say — room scripts parse verbs from speech
    // (equip, doff, consume, craft, assess, collaborate, etc.)
    ws.send({ type: 'say', id: newId(), roomId, text: trimmed });
    setInputText('');
  };

  const sendGo = (direction: string) => {
    ws.send({ type: 'go', id: newId(), roomId, direction });
  };

  const sendHint = (index: number) => {
    ws.send({ type: 'hint_select', id: newId(), roomId, index });
  };

  const sendLook = () => {
    ws.send({ type: 'look', id: newId(), roomId });
  };

  const handleDisconnect = () => {
    ws.disconnect();
    setToken(null);
  };

  const renderProseEntry = ({ item }: { item: ProseEntry }) => {
    const color =
      item.priority === 'critical'
        ? c.proseCritical
        : item.priority === 'ambient'
          ? c.proseAmbient
          : c.proseNormal;

    return (
      <View style={styles.proseEntry}>
        {/* selectable: long-press to select and copy. A transcript you cannot copy out
            of is a transcript you cannot quote, paste into a bug report, or keep. */}
        <Text
          selectable
          style={[styles.proseText, { color }, (item.speaker === 'narrator' || item.speaker === 'emote') && styles.italic]}>
          {item.speaker !== 'narrator' && item.speaker !== 'system' && item.speaker !== 'emote' && (
            <Text style={styles.speaker}>{item.speaker}: </Text>
          )}
          {item.text}
        </Text>
        {item.blocks?.map((block, i) => {
          if (ContentBlockRegistry.canRenderRich(block.format)) {
            const renderer = ContentBlockRegistry.findRenderer(block.format);
            return (
              <React.Fragment key={i}>{renderer.render(block)}</React.Fragment>
            );
          }
          return block.fallback ? (
            <Text key={i} selectable style={[styles.fallback, { color: c.textMuted }]}>
              {'  '}
              {block.fallback}
            </Text>
          ) : null;
        })}
      </View>
    );
  };

  const streamingEntries = Object.entries(streamingText);
  const inventoryCount = inventory.length;

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: c.background }]}>
      {/* Header */}
      <View style={[styles.header, { backgroundColor: c.header }]} testID="room-header">
        <Text style={[styles.roomName, { color: c.textOnHeader }]} testID="room-name">{roomName}</Text>
        <View style={styles.headerActions}>
          <TouchableOpacity
            onPress={() => navigation.navigate('Household')}
            testID="between-status-dot"
            accessibilityLabel="Between status"
          >
            <View
              style={[
                styles.inferenceDot,
                { backgroundColor: connectivityDotColor(betweenState) },
              ]}
            />
          </TouchableOpacity>
          <View
            testID="inference-dot"
            style={[
              styles.inferenceDot,
              {
                backgroundColor:
                  activeBackend === 'local'
                    ? '#4CAF50'
                    : webLLMModelId
                      ? '#8BC34A'
                      : activeBackend === 'server'
                        ? '#2196F3'
                        : '#BDBDBD',
              },
            ]}
          />
          <TouchableOpacity onPress={sendLook} testID="look-button" accessibilityLabel={t.room.lookLabel}>
            <Text style={styles.headerIcon}>{'\u{1F441}'}</Text>
          </TouchableOpacity>
          <TouchableOpacity onPress={() => navigation.navigate('Inventory')} testID="bag-button" accessibilityLabel={t.room.inventoryLabel}>
            <View style={styles.inventoryButton}>
              <Text style={styles.headerIcon}>{'\u{1F392}'}</Text>
              {inventoryCount > 0 && (
                <View style={styles.badge}>
                  <Text style={styles.badgeText}>{inventoryCount}</Text>
                </View>
              )}
            </View>
          </TouchableOpacity>
          <TouchableOpacity onPress={() => navigation.navigate('Settings')} testID="settings-button" accessibilityLabel={t.room.settingsLabel}>
            <Text style={styles.headerIcon}>{'\u2699\uFE0F'}</Text>
          </TouchableOpacity>
          <TouchableOpacity onPress={handleDisconnect} testID="disconnect-button" accessibilityLabel={t.room.disconnectLabel}>
            <Text style={styles.headerIcon}>{'\u{1F6AA}'}</Text>
          </TouchableOpacity>
        </View>
      </View>

      {/* Prose stream */}
      <FlatList
        ref={listRef}
        data={proseStream}
        renderItem={renderProseEntry}
        keyExtractor={(_, index) => String(index)}
        style={styles.proseList}
        contentContainerStyle={styles.proseContent}
        testID="prose-list"
        ListFooterComponent={
          streamingEntries.length > 0 ? (
            <View>
              {streamingEntries.map(([source, text]) => (
                <Text key={source} selectable style={[styles.streaming, { color: c.proseStreaming }]}>
                  <Text style={styles.speaker}>{source}: </Text>
                  {text}
                  {'\u2588'}
                </Text>
              ))}
            </View>
          ) : null
        }
      />

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
              testID={`exit-${exit.direction}`}
              accessibilityLabel={exit.label}
              accessibilityRole="button"
              accessibilityHint={t.room.navigateHint(t.room.directionLabels[exit.direction] ?? exit.direction)}
            >
              <Text style={[styles.exitText, { color: c.exitChipText }]}>
                {t.room.directionLabels[exit.direction] ?? exit.direction}
              </Text>
            </TouchableOpacity>
          ))}
        </ScrollView>
      )}

      {/* Hints */}
      {hints.length > 0 && (
        <ScrollView
          horizontal
          style={styles.chipRow}
          contentContainerStyle={styles.chipContent}
        >
          {hints.map((hint, i) => (
            <TouchableOpacity
              key={i}
              style={[styles.hintChip, { backgroundColor: c.hintChipBg }]}
              onPress={() => sendHint(i)}
              testID={`hint-${i}`}
            >
              <Text style={[styles.hintText, { color: c.hintChipText }]}>{resolveLabel(hint.labelKey, hint.label, locale)}</Text>
            </TouchableOpacity>
          ))}
        </ScrollView>
      )}

      {/* Input */}
      <View style={[styles.inputRow, { borderTopColor: c.divider }]}>
        <TextInput
          style={[styles.textInput, { borderColor: c.inputBorder, backgroundColor: c.inputBackground, color: c.text }]}
          value={inputText}
          onChangeText={setInputText}
          placeholder={t.room.placeholder}
          placeholderTextColor={c.placeholder}
          onSubmitEditing={sendInput}
          returnKeyType="send"
          testID="room-input"
        />
        <TouchableOpacity
          style={[styles.sendButton, { backgroundColor: c.primary }]}
          onPress={sendInput}
          disabled={!inputText.trim()}
          testID="send-button"
        >
          <Text style={[styles.sendText, { color: c.textOnPrimary }]}>{t.room.send}</Text>
        </TouchableOpacity>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  loadingOverlay: {
    justifyContent: 'center',
    alignItems: 'center',
  },
  loadingText: {
    marginTop: 16,
    fontSize: 16,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 16,
  },
  roomName: { fontSize: 20, fontWeight: 'bold' },
  headerActions: { flexDirection: 'row', gap: 12, alignItems: 'center' },
  inferenceDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  headerButton: { fontWeight: 'bold' },
  headerIcon: { fontSize: 20 },
  inventoryButton: { flexDirection: 'row', alignItems: 'center' },
  badge: {
    backgroundColor: '#FF5722',
    borderRadius: 10,
    minWidth: 20,
    height: 20,
    justifyContent: 'center',
    alignItems: 'center',
    marginLeft: 4,
    paddingHorizontal: 4,
  },
  badgeText: {
    color: '#fff',
    fontSize: 12,
    fontWeight: 'bold',
  },
  proseList: { flex: 1 },
  proseContent: { padding: 16 },
  proseEntry: { marginBottom: 4 },
  proseText: { fontSize: 15, lineHeight: 22 },
  speaker: { fontWeight: 'bold' },
  fallback: { fontSize: 13, marginLeft: 8 },
  italic: { fontStyle: 'italic' as const },
  streaming: { fontSize: 15 },
  chipRow: { maxHeight: 44 },
  chipContent: { paddingHorizontal: 16, gap: 8, alignItems: 'center' },
  exitChip: {
    paddingVertical: 6,
    paddingHorizontal: 14,
    borderRadius: 16,
  },
  exitText: { fontWeight: '600' },
  hintChip: {
    paddingVertical: 6,
    paddingHorizontal: 14,
    borderRadius: 16,
  },
  hintText: { fontWeight: '600' },
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
});
