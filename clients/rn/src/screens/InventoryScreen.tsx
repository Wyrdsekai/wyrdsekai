import React from 'react';
import {
  View,
  Text,
  FlatList,
  TouchableOpacity,
  StyleSheet,
} from 'react-native';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/types';
import { useSessionStore } from '../state/sessionStore';
import { useWs } from '../App';
import { newId } from '../protocol/c2s';
import { RoomObject } from '../protocol/models';
import { useThemeColors } from '../theme/useTheme';
import { useStrings } from '../i18n/useStrings';

type Props = NativeStackScreenProps<RootStackParamList, 'Inventory'>;

export function InventoryScreen({ navigation }: Props) {
  const ws = useWs();
  const c = useThemeColors();
  const t = useStrings();
  const inventory = useSessionStore((s) => s.inventory);
  const roomId = useSessionStore((s) => s.roomId);

  const handleDrop = (item: RoomObject) => {
    ws.send({ type: 'drop', id: newId(), roomId, objectName: item.name });
  };

  const handleUse = (item: RoomObject) => {
    ws.send({ type: 'use', id: newId(), roomId, objectName: item.name });
  };

  const renderItem = ({ item }: { item: RoomObject }) => (
    <View style={[styles.itemRow, { backgroundColor: c.surface, borderColor: c.border }]} testID={`item-${item.id}`}>
      <View style={styles.itemInfo}>
        <Text style={[styles.itemName, { color: c.text }]}>{item.name}</Text>
        <Text style={[styles.itemDescription, { color: c.textMuted }]}>{item.description}</Text>
      </View>
      <View style={styles.itemActions}>
        <TouchableOpacity style={[styles.actionButton, { borderColor: c.primary }]} onPress={() => handleDrop(item)} testID={`drop-${item.id}`}>
          <Text style={[styles.actionText, { color: c.primary }]}>{t.inventory.drop}</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[styles.actionButton, { backgroundColor: c.primary, borderColor: c.primary }]}
          onPress={() => handleUse(item)}
          testID={`use-${item.id}`}
        >
          <Text style={[styles.actionText, { color: c.textOnPrimary }]}>{t.inventory.use}</Text>
        </TouchableOpacity>
      </View>
    </View>
  );

  return (
    <View style={[styles.container, { backgroundColor: c.background }]} testID="inventory-screen">
      <View style={[styles.header, { backgroundColor: c.header }]}>
        <TouchableOpacity onPress={() => navigation.goBack()} testID="inventory-close">
          <Text style={[styles.backButton, { color: c.textOnHeader }]}>{t.common.back}</Text>
        </TouchableOpacity>
        <Text style={[styles.title, { color: c.textOnHeader }]}>{t.inventory.title}</Text>
        <View style={styles.headerSpacer} />
      </View>

      {inventory.length === 0 ? (
        <View style={styles.emptyContainer}>
          <Text style={[styles.emptyText, { color: c.textMuted }]} testID="empty-inventory">{t.inventory.empty}</Text>
        </View>
      ) : (
        <FlatList
          data={inventory}
          renderItem={renderItem}
          keyExtractor={(item) => item.id}
          contentContainerStyle={styles.listContent}
        />
      )}
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
  emptyContainer: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  emptyText: { fontSize: 16 },
  listContent: { padding: 16 },
  itemRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 12,
    marginBottom: 8,
    borderRadius: 8,
    borderWidth: 1,
  },
  itemInfo: { flex: 1, marginRight: 12 },
  itemName: { fontSize: 16, fontWeight: 'bold' },
  itemDescription: { fontSize: 14, marginTop: 2 },
  itemActions: { flexDirection: 'row', gap: 8 },
  actionButton: {
    paddingVertical: 6,
    paddingHorizontal: 14,
    borderRadius: 6,
    borderWidth: 1,
  },
  actionText: { fontWeight: '600', fontSize: 14 },
});
