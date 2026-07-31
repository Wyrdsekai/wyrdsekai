import React from 'react';
import {
  ActivityIndicator,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { WebView } from 'react-native-webview';
import type { WebViewNavigation } from 'react-native-webview';
import { useThemeColors } from '../theme/useTheme';
import {
  LOOPBACK_CALLBACK,
  buildAuthUrl,
  exchangeCode,
  parseCodeFromCallbackUrl,
  type PkceState,
} from '../inference/OpenRouterOAuth';

/**
 * OpenRouter OAuth screen.
 *
 * Hosts a WebView pointed at openrouter.ai/auth. When the page tries to
 * navigate to {@link LOOPBACK_CALLBACK} we return false from
 * onShouldStartLoadWithRequest — the localhost URL is unreachable but
 * the WebView aborts the load before any network request goes out.
 *
 * On success: invokes {@code onApiKey}. Caller persists via secureStorage
 * + applies via InferenceRouter.setRemoteAuth('bearer', key).
 */
export interface OpenRouterAuthScreenProps {
  onApiKey: (key: string) => void;
  onCancel: () => void;
  onError: (message: string) => void;
}

export function OpenRouterAuthScreen({
  onApiKey,
  onCancel,
  onError,
}: OpenRouterAuthScreenProps) {
  const c = useThemeColors();
  // Build once. useRef keeps the PkceState alive across renders so the
  // exchange call has the matching verifier.
  const authRef = React.useRef<{ authUrl: string; pkce: PkceState } | null>(null);
  if (!authRef.current) {
    authRef.current = buildAuthUrl(LOOPBACK_CALLBACK);
  }
  const { authUrl, pkce } = authRef.current;

  const [exchanging, setExchanging] = React.useState(false);
  // Latch to ensure we exchange exactly once even if WebView fires both
  // shouldStartLoad + onError for the doomed localhost request.
  const handledRef = React.useRef(false);

  const handleCallbackUrl = React.useCallback(
    (url: string) => {
      if (handledRef.current) return;
      handledRef.current = true;
      const code = parseCodeFromCallbackUrl(url);
      if (!code) {
        onError('OpenRouter returned no code in callback');
        return;
      }
      setExchanging(true);
      (async () => {
        const result = await exchangeCode(code, pkce);
        if (result.key) {
          onApiKey(result.key);
        } else {
          onError(result.error ?? 'Token exchange failed');
          setExchanging(false);
        }
      })();
    },
    [pkce, onApiKey, onError],
  );

  const handleShouldStartLoad = React.useCallback(
    (req: WebViewNavigation) => {
      if (req.url.startsWith(LOOPBACK_CALLBACK)) {
        handleCallbackUrl(req.url);
        return false;
      }
      return true;
    },
    [handleCallbackUrl],
  );

  return (
    <View style={[styles.container, { backgroundColor: c.background }]}>
      <View style={[styles.header, { backgroundColor: c.header }]}>
        <TouchableOpacity onPress={onCancel} testID="openrouter-auth-cancel">
          <Text style={[styles.headerButton, { color: c.textOnHeader }]}>Cancel</Text>
        </TouchableOpacity>
        <Text style={[styles.title, { color: c.textOnHeader }]}>
          Connect OpenRouter
        </Text>
        <View style={styles.headerSpacer} />
      </View>
      {exchanging ? (
        <View style={styles.center}>
          <ActivityIndicator size="large" />
          <Text style={[styles.exchangeText, { color: c.text }]}>
            Exchanging code for API key…
          </Text>
        </View>
      ) : (
        <WebView
          source={{ uri: authUrl }}
          originWhitelist={['https://*', 'http://localhost*']}
          onShouldStartLoadWithRequest={handleShouldStartLoad}
          onError={(event) => {
            // RN-side load failures (DNS, TLS) bubble here. The loopback
            // case is caught by onShouldStartLoadWithRequest first; if
            // anything else fails we surface it.
            const url = event.nativeEvent.url ?? '';
            if (url.startsWith(LOOPBACK_CALLBACK)) return;
            onError(
              event.nativeEvent.description ?? 'WebView load failed',
            );
          }}
          style={styles.webview}
          testID="openrouter-auth-webview"
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: 16,
  },
  headerButton: { fontSize: 16, fontWeight: '600' },
  title: { fontSize: 18, fontWeight: 'bold' },
  headerSpacer: { width: 50 },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 32,
  },
  exchangeText: { marginTop: 12, fontSize: 14 },
  webview: { flex: 1 },
});
