/**
 * Theme hook — follows system dark/light preference by default,
 * with manual override via preferencesStore.
 */

import { useColorScheme } from 'react-native';
import { usePreferencesStore } from '../state/preferencesStore';
import { LightColors, DarkColors, ColorPalette } from './colors';

export function useThemeColors(): ColorPalette {
  const systemScheme = useColorScheme();
  const themeOverride = usePreferencesStore((s) => s.theme);

  const isDark = themeOverride === 'system'
    ? systemScheme === 'dark'
    : themeOverride === 'dark';

  return isDark ? DarkColors : LightColors;
}

export function useIsDark(): boolean {
  const systemScheme = useColorScheme();
  const themeOverride = usePreferencesStore((s) => s.theme);

  return themeOverride === 'system'
    ? systemScheme === 'dark'
    : themeOverride === 'dark';
}
