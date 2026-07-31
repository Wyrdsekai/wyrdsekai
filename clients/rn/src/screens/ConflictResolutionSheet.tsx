/**
 * Bottom sheet for resolving Study sync conflicts.
 * Shows local vs remote version with Keep Mine / Keep Theirs / Keep Both.
 * TypeScript port of KMP's ConflictResolutionSheet.kt.
 */
import React from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  FlatList,
  Modal,
} from 'react-native';
import type { StudyItem } from '../engine/study/StudyItem';
import { useThemeColors } from '../theme/useTheme';
import type { ColorPalette } from '../theme/colors';

export interface ConflictPair {
  local: StudyItem;
  remote: StudyItem;
}

export type ConflictResolution = 'keep_mine' | 'keep_theirs' | 'keep_both';

interface Props {
  conflicts: ConflictPair[];
  onResolve: (itemId: string, resolution: ConflictResolution) => void;
  onDismiss: () => void;
}

export function ConflictResolutionSheet({ conflicts, onResolve, onDismiss }: Props) {
  const colors = useThemeColors();
  const styles = makeStyles(colors);

  if (conflicts.length === 0) return null;

  return (
    <Modal visible transparent animationType="slide" onRequestClose={onDismiss}>
      <View style={styles.overlay}>
        <View style={styles.sheet}>
          <View style={styles.header}>
            <Text style={styles.title}>{conflicts.length} sync conflicts</Text>
            <TouchableOpacity onPress={onDismiss} testID="conflict-dismiss">
              <Text style={styles.dismiss}>{'\u2715'}</Text>
            </TouchableOpacity>
          </View>

          <FlatList
            data={conflicts}
            keyExtractor={(pair) => pair.local.id}
            renderItem={({ item: pair }) => (
              <ConflictCard
                pair={pair}
                onResolve={(r) => onResolve(pair.local.id, r)}
                colors={colors}
              />
            )}
            ItemSeparatorComponent={() => <View style={{ height: 12 }} />}
            style={styles.list}
          />
        </View>
      </View>
    </Modal>
  );
}

function ConflictCard({
  pair,
  onResolve,
  colors,
}: {
  pair: ConflictPair;
  onResolve: (r: ConflictResolution) => void;
  colors: ColorPalette;
}) {
  const styles = makeStyles(colors);

  return (
    <View style={styles.card} testID={`conflict-${pair.local.id}`}>
      <Text style={styles.cardTitle} numberOfLines={1}>
        {pair.local.title || pair.local.content.slice(0, 60)}
      </Text>

      <Text style={styles.versionLabel}>
        Mine ({(pair.local.lastModifiedBy ?? 'unknown').slice(-8)})
      </Text>
      <Text style={styles.versionContent} numberOfLines={3}>
        {pair.local.content.slice(0, 200)}
      </Text>

      <Text style={styles.versionLabel}>
        Theirs ({(pair.remote.lastModifiedBy ?? 'unknown').slice(-8)})
      </Text>
      <Text style={styles.versionContent} numberOfLines={3}>
        {pair.remote.content.slice(0, 200)}
      </Text>

      <View style={styles.buttonRow}>
        <TouchableOpacity
          style={styles.outlineButton}
          onPress={() => onResolve('keep_mine')}
          testID="conflict-keep-mine"
        >
          <Text style={styles.outlineButtonText}>Keep Mine</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={styles.outlineButton}
          onPress={() => onResolve('keep_theirs')}
          testID="conflict-keep-theirs"
        >
          <Text style={styles.outlineButtonText}>Keep Theirs</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={styles.filledButton}
          onPress={() => onResolve('keep_both')}
          testID="conflict-keep-both"
        >
          <Text style={styles.filledButtonText}>Keep Both</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

function makeStyles(colors: ColorPalette) {
  return StyleSheet.create({
    overlay: {
      flex: 1, backgroundColor: 'rgba(0,0,0,0.4)', justifyContent: 'flex-end',
    },
    sheet: {
      backgroundColor: colors.background, borderTopLeftRadius: 16, borderTopRightRadius: 16,
      maxHeight: '80%', padding: 16,
    },
    header: {
      flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center',
      marginBottom: 12,
    },
    title: { color: colors.text, fontSize: 18, fontWeight: '600' },
    dismiss: { color: colors.textSecondary, fontSize: 20 },
    list: { flex: 1 },
    card: {
      backgroundColor: colors.surface, borderRadius: 8, padding: 12,
      borderWidth: 1, borderColor: colors.border,
    },
    cardTitle: { color: colors.text, fontSize: 15, fontWeight: '600', marginBottom: 8 },
    versionLabel: { color: colors.textSecondary, fontSize: 11, marginTop: 4 },
    versionContent: { color: colors.text, fontSize: 13, marginBottom: 4 },
    buttonRow: { flexDirection: 'row', gap: 8, marginTop: 8 },
    outlineButton: {
      flex: 1, paddingVertical: 8, borderRadius: 8,
      borderWidth: 1, borderColor: colors.border, alignItems: 'center',
    },
    outlineButtonText: { color: colors.text, fontSize: 13 },
    filledButton: {
      flex: 1, paddingVertical: 8, borderRadius: 8,
      backgroundColor: colors.primary, alignItems: 'center',
    },
    filledButtonText: { color: colors.textOnPrimary, fontSize: 13, fontWeight: '500' },
  });
}
