/**
 * @format
 * React Native entry point — registers the app component.
 */

// Polyfill crypto.getRandomValues BEFORE any module that needs cryptographic
// randomness (notably secureStorage, which derives the MMKV encryption key
// from 32 bytes of randomness). RN 0.83 doesn't ship crypto.getRandomValues
// natively on Android; without this polyfill we silently fall back to
// Math.random and the encryption key is predictable.
import 'react-native-get-random-values';

// TEMP iOS diagnostic — dump stack of any unhandled JS exception before RN
// strips it. Triggered by Invariant Violation `new NativeEventEmitter()` on
// iOS New Arch when seed-import path runs. Remove once root cause found.
if (typeof global !== 'undefined' && global.ErrorUtils) {
  const _prev = global.ErrorUtils.getGlobalHandler && global.ErrorUtils.getGlobalHandler();
  global.ErrorUtils.setGlobalHandler((err, isFatal) => {
    // eslint-disable-next-line no-console
    console.warn('[WYRD-DIAG] unhandled', isFatal ? 'FATAL' : '', String(err && err.message), '\n', err && err.stack);
    if (_prev) _prev(err, isFatal);
  });
}

import { AppRegistry, Platform } from 'react-native';
import App from './src/App';

// Expo prebuild registers the native module as 'main'
const appName = 'main';

AppRegistry.registerComponent(appName, () => App);

// On web, also run the application into the #root div
if (Platform.OS === 'web') {
  AppRegistry.runApplication(appName, {
    rootTag: document.getElementById('root'),
  });
}
