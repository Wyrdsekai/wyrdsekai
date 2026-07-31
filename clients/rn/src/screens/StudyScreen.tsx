/**
 * StudyScreen — personal workspace screen (journal-first on phone).
 *
 * Primary view: recent journal entries + write + search.
 * Shell mode toggle: desk commands (same as before).
 * Mirrors KMP's StudyScreen.kt for parity.
 */
import React, { useState, useCallback, useRef, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  TextInput,
  TouchableOpacity,
  ScrollView,
  FlatList,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import type { RootStackParamList } from '../navigation/types';
import type { StudyItem } from '../engine/study/StudyItem';
import type { StudyStore } from '../engine/study/StudyStore';
import { useThemeColors } from '../theme/useTheme';
import { useStrings } from '../i18n/useStrings';
import type { ColorPalette } from '../theme/colors';

type Props = NativeStackScreenProps<RootStackParamList, 'Study'>;

export function StudyScreen({ navigation, route }: Props) {
  const colors = useThemeColors();
  const strings = useStrings();
  // Android 15 edge-to-edge: pad bottom input rows above the gesture-nav bar
  // so taps hit Send/the field instead of firing HOME (task #30).
  const insets = useSafeAreaInsets();
  const [inputText, setInputText] = useState('');
  const [showShell, setShowShell] = useState(false);
  const [shellOutput, setShellOutput] = useState<string[]>([]);
  const [journalEntries, setJournalEntries] = useState<StudyItem[]>([]);
  const [entryCount, setEntryCount] = useState(0);
  const [searchResults, setSearchResults] = useState<StudyItem[] | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [isSearchMode, setIsSearchMode] = useState(false);

  const studyStore: StudyStore | null = route.params?.studyStore ?? null;
  const userDid: string = route.params?.userDid ?? 'local-user';
  const onSay: ((text: string) => void) | undefined = route.params?.onSay;
  const ageBracket: string | null = route.params?.ageBracket ?? null;

  // Load recent entries on mount
  useEffect(() => {
    if (!studyStore) return;
    (async () => {
      const entries = await studyStore.recentJournal(userDid, 50);
      setJournalEntries(entries);
      const count = await studyStore.count(userDid);
      setEntryCount(count);
    })();
  }, [studyStore, userDid]);

  const handleWriteJournal = useCallback(async () => {
    if (!inputText.trim() || !studyStore) return;
    await studyStore.writeJournal(userDid, inputText.trim());
    setInputText('');
    // Refresh
    const entries = await studyStore.recentJournal(userDid, 50);
    setJournalEntries(entries);
    setEntryCount(await studyStore.count(userDid));
  }, [inputText, studyStore, userDid]);

  const handleSearch = useCallback(async () => {
    if (!searchQuery.trim() || !studyStore) return;
    const results = await studyStore.searchJournal(userDid, searchQuery.trim(), 20);
    setSearchResults(results);
  }, [searchQuery, studyStore, userDid]);

  const handleShellCommand = useCallback(
    (cmd: string) => {
      setShellOutput((prev) => [...prev, `> ${cmd}`]);
      onSay?.(`desk:${cmd}`);
    },
    [onSay],
  );

  const title =
    ageBracket === 'seedling' ? 'Playroom'
      : ageBracket === 'sprout' ? 'Treehouse'
        : ageBracket === 'sapling' ? 'Workshop'
          : ageBracket === 'young-tree' ? 'Studio'
            : 'The Study';

  const displayItems = isSearchMode && searchResults ? searchResults : journalEntries;
  const styles = makeStyles(colors);

  return (
    <View style={styles.container}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} testID="study-back">
          <Text style={styles.backButton}>{strings.common.back}</Text>
        </TouchableOpacity>
        <Text style={styles.title}>{title}</Text>
        <View style={styles.headerActions}>
          <TouchableOpacity
            onPress={() => {
              setIsSearchMode(!isSearchMode);
              if (isSearchMode) {
                setSearchQuery('');
                setSearchResults(null);
              }
            }}
            testID="study-search-toggle"
          >
            <Text style={styles.modeButton}>{isSearchMode ? '\u2715' : '\u{1F50D}'}</Text>
          </TouchableOpacity>
          <TouchableOpacity onPress={() => setShowShell(!showShell)} testID="study-mode-toggle">
            <Text style={styles.modeButton}>{showShell ? 'Room' : 'Desk'}</Text>
          </TouchableOpacity>
        </View>
      </View>

      {showShell ? (
        /* Shell mode */
        <View style={styles.shellContainer}>
          <ScrollView style={styles.shellOutput}>
            {shellOutput.map((line, i) => (
              <Text key={i} style={styles.shellLine}>{line}</Text>
            ))}
          </ScrollView>
          <View style={[styles.shellInput, { paddingBottom: insets.bottom }]}>
            <Text style={styles.shellPrompt}>desk: </Text>
            <TextInput
              style={styles.shellTextField}
              value={inputText}
              onChangeText={setInputText}
              onSubmitEditing={() => {
                if (inputText.trim()) {
                  handleShellCommand(inputText.trim());
                  setInputText('');
                }
              }}
              returnKeyType="send"
              autoCapitalize="none"
              autoCorrect={false}
              placeholder="command..."
              placeholderTextColor={colors.textSecondary}
              testID="study-shell-input"
            />
          </View>
        </View>
      ) : (
        <View style={styles.content}>
          {/* Search bar */}
          {isSearchMode && (
            <View style={styles.searchRow}>
              <TextInput
                style={styles.searchInput}
                value={searchQuery}
                onChangeText={setSearchQuery}
                onSubmitEditing={handleSearch}
                returnKeyType="search"
                placeholder="Search journal..."
                placeholderTextColor={colors.placeholder}
                testID="study-search"
              />
              <TouchableOpacity style={styles.searchButton} onPress={handleSearch}>
                <Text style={styles.searchButtonText}>{'\u{1F50D}'}</Text>
              </TouchableOpacity>
            </View>
          )}

          {/* Entry count */}
          <Text style={styles.countLabel}>
            {isSearchMode && searchResults
              ? `${searchResults.length} results`
              : `${entryCount} journal entries`}
          </Text>

          {/* Journal entries list */}
          {displayItems.length === 0 ? (
            <View style={styles.emptyContainer}>
              <Text style={styles.emptyText}>
                {isSearchMode
                  ? 'No results found.'
                  : 'Your journal is empty.\nWrite something below to get started.'}
              </Text>
            </View>
          ) : (
            <FlatList
              data={displayItems}
              keyExtractor={(item) => item.id}
              style={styles.list}
              renderItem={({ item }) => (
                <JournalEntryCard item={item} colors={colors} />
              )}
              ItemSeparatorComponent={() => <View style={{ height: 8 }} />}
              testID="study-entries"
            />
          )}

          {/* Write input */}
          <View style={[styles.inputRow, { paddingBottom: 8 + insets.bottom }]}>
            <TextInput
              style={styles.textInput}
              value={inputText}
              onChangeText={setInputText}
              onSubmitEditing={handleWriteJournal}
              returnKeyType="send"
              placeholder="Write in journal..."
              placeholderTextColor={colors.placeholder}
              testID="study-input"
            />
            <TouchableOpacity style={styles.sendButton} onPress={handleWriteJournal} testID="study-send">
              <Text style={styles.sendButtonText}>{'\u270F\uFE0F'}</Text>
            </TouchableOpacity>
          </View>
        </View>
      )}
    </View>
  );
}

function JournalEntryCard({ item, colors }: { item: StudyItem; colors: ColorPalette }) {
  const isPrivate = item.itemType === 'journal_private';
  const timeText = new Date(item.timestamp).toLocaleString(undefined, {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  });
  const styles = makeStyles(colors);

  return (
    <View style={styles.card} testID={`study-entry-${item.id}`}>
      <View style={styles.cardHeader}>
        <View style={styles.cardTitleRow}>
          {isPrivate && <Text style={styles.lockIcon}>{'\u{1F512}'}</Text>}
          <Text style={styles.cardTitle} numberOfLines={1}>
            {item.title || item.content.slice(0, 60)}
          </Text>
        </View>
        <Text style={styles.cardTime}>{timeText}</Text>
      </View>
      {item.content.length > (item.title?.length ?? 0) + 5 && (
        <Text style={styles.cardBody} numberOfLines={3}>
          {item.content.slice(0, 200)}
        </Text>
      )}
    </View>
  );
}

function makeStyles(colors: ColorPalette) {
  return StyleSheet.create({
    container: { flex: 1, backgroundColor: colors.background },
    header: {
      flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
      paddingHorizontal: 16, paddingVertical: 12,
      borderBottomWidth: 1, borderBottomColor: colors.border, backgroundColor: colors.header,
    },
    headerActions: { flexDirection: 'row', gap: 12 },
    backButton: { color: colors.textOnHeader, fontSize: 16, fontWeight: 'bold' },
    title: { color: colors.textOnHeader, fontSize: 18, fontWeight: '600' },
    modeButton: { color: colors.textOnHeader, fontSize: 16 },
    content: { flex: 1 },
    searchRow: {
      flexDirection: 'row', paddingHorizontal: 16, paddingVertical: 8, gap: 8,
    },
    searchInput: {
      flex: 1, backgroundColor: colors.inputBackground, borderRadius: 8,
      paddingHorizontal: 12, paddingVertical: 8, color: colors.text, fontSize: 14,
      borderWidth: 1, borderColor: colors.inputBorder,
    },
    searchButton: { justifyContent: 'center', paddingHorizontal: 12 },
    searchButtonText: { fontSize: 18 },
    countLabel: {
      color: colors.textSecondary, fontSize: 12, paddingHorizontal: 16, paddingVertical: 4,
    },
    list: { flex: 1, paddingHorizontal: 16 },
    emptyContainer: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24 },
    emptyText: {
      color: colors.textSecondary, fontSize: 14, fontStyle: 'italic', textAlign: 'center',
    },
    card: {
      backgroundColor: colors.surface, borderRadius: 8, padding: 12,
      borderWidth: 1, borderColor: colors.border,
    },
    cardHeader: {
      flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center',
    },
    cardTitleRow: { flexDirection: 'row', alignItems: 'center', flex: 1, marginRight: 8 },
    lockIcon: { fontSize: 12, marginRight: 4 },
    cardTitle: { color: colors.text, fontSize: 15, fontWeight: '500', flex: 1 },
    cardTime: { color: colors.textSecondary, fontSize: 11 },
    cardBody: { color: colors.textSecondary, fontSize: 13, marginTop: 4 },
    inputRow: {
      flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16, paddingVertical: 8,
      borderTopWidth: 1, borderTopColor: colors.border,
    },
    textInput: {
      flex: 1, backgroundColor: colors.inputBackground, borderRadius: 8,
      paddingHorizontal: 12, paddingVertical: 8, color: colors.text, fontSize: 14,
      borderWidth: 1, borderColor: colors.inputBorder,
    },
    sendButton: {
      marginLeft: 8, paddingHorizontal: 14, paddingVertical: 8,
      backgroundColor: colors.primary, borderRadius: 8,
    },
    sendButtonText: { color: colors.textOnPrimary, fontSize: 16 },
    // Shell mode
    shellContainer: { flex: 1, padding: 8 },
    shellOutput: {
      flex: 1, backgroundColor: colors.surface, borderRadius: 8, padding: 8,
    },
    shellLine: { color: colors.text, fontSize: 13, fontFamily: 'monospace', lineHeight: 18 },
    shellInput: { flexDirection: 'row', alignItems: 'center', paddingTop: 8 },
    shellPrompt: { color: colors.primary, fontSize: 14, fontFamily: 'monospace', fontWeight: '600' },
    shellTextField: {
      flex: 1, backgroundColor: colors.inputBackground, borderRadius: 8,
      paddingHorizontal: 8, paddingVertical: 6, color: colors.text, fontSize: 14,
      fontFamily: 'monospace', borderWidth: 1, borderColor: colors.inputBorder,
    },
  });
}
