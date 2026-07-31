/**
 * Color palettes for light and dark modes.
 * Mirrors KMP WyrdTheme.kt colors for visual consistency.
 */

export interface ColorPalette {
  // Backgrounds
  background: string;
  surface: string;
  header: string;

  // Text
  text: string;
  textSecondary: string;
  textMuted: string;
  textOnHeader: string;
  textOnPrimary: string;

  // Primary / accent
  primary: string;
  primaryLight: string;
  secondary: string;
  secondaryLight: string;

  // Input
  inputBorder: string;
  inputBackground: string;
  placeholder: string;

  // Borders
  border: string;
  divider: string;

  // Status
  error: string;
  errorLight: string;
  success: string;
  successLight: string;

  // Prose
  proseNormal: string;
  proseAmbient: string;
  proseCritical: string;
  proseStreaming: string;

  // Chips
  exitChipBg: string;
  exitChipText: string;
  hintChipBg: string;
  hintChipText: string;
}

export const LightColors: ColorPalette = {
  background: '#FAFAFA',
  surface: '#FFFFFF',
  header: '#00796B',

  text: '#000000',
  textSecondary: '#424242',
  textMuted: '#666666',
  textOnHeader: '#FFFFFF',
  textOnPrimary: '#FFFFFF',

  primary: '#00796B',
  primaryLight: '#E0F2F1',
  secondary: '#F57C00',
  secondaryLight: '#FFF3E0',

  inputBorder: '#CCCCCC',
  inputBackground: '#FFFFFF',
  placeholder: '#999999',

  border: '#E0E0E0',
  divider: '#E0E0E0',

  error: '#D32F2F',
  errorLight: '#FFEBEE',
  success: '#4CAF50',
  successLight: '#E8F5E9',

  proseNormal: '#000000',
  proseAmbient: '#999999',
  proseCritical: '#D32F2F',
  proseStreaming: '#666666',

  exitChipBg: '#E0F2F1',
  exitChipText: '#00796B',
  hintChipBg: '#FFF3E0',
  hintChipText: '#F57C00',
};

export const DarkColors: ColorPalette = {
  background: '#121212',
  surface: '#1E1E1E',
  header: '#004D40',

  text: '#E0E0E0',
  textSecondary: '#BDBDBD',
  textMuted: '#888888',
  textOnHeader: '#E0E0E0',
  textOnPrimary: '#FFFFFF',

  primary: '#80CBC4',
  primaryLight: '#1A3A36',
  secondary: '#FFB74D',
  secondaryLight: '#3D2800',

  inputBorder: '#424242',
  inputBackground: '#2C2C2C',
  placeholder: '#666666',

  border: '#333333',
  divider: '#333333',

  error: '#EF5350',
  errorLight: '#3D1111',
  success: '#66BB6A',
  successLight: '#1B3D1B',

  proseNormal: '#E0E0E0',
  proseAmbient: '#666666',
  proseCritical: '#EF5350',
  proseStreaming: '#888888',

  exitChipBg: '#1A3A36',
  exitChipText: '#80CBC4',
  hintChipBg: '#3D2800',
  hintChipText: '#FFB74D',
};
