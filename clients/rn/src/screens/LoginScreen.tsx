/**
 * LoginScreen — account creation / login shown after pairing, before Birth.
 *
 * Queries GET /api/auth/status on mount to determine mode:
 * - First user (no accounts): shows "Create Account" form only.
 * - Existing server (has accounts): shows tabbed Login / Create Account.
 *
 * On success: saves auth credentials to appModeStore, links device token,
 * then navigates to BirthScreen.
 *
 * Mirrors the KMP LoginScreen (clients/kmp/.../LoginScreen.kt).
 */
import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  ActivityIndicator,
} from 'react-native';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/types';
import { useThemeColors } from '../theme/useTheme';
import { useAppModeStore } from '../state/appModeStore';
import {
  checkStatus,
  login,
  register,
  linkDevice,
  type ServerStatus,
} from '../network/AuthClient';

type Props = NativeStackScreenProps<RootStackParamList, 'Login'>;

export function LoginScreen({ navigation, route }: Props) {
  const c = useThemeColors();
  const { setAuth } = useAppModeStore();

  const { serverUrl, deviceToken } = route.params;

  // Server status
  const [serverStatus, setServerStatus] = useState<ServerStatus | null>(null);
  const [statusLoading, setStatusLoading] = useState(true);
  const [statusError, setStatusError] = useState<string | null>(null);

  // Tab: 0 = login, 1 = register
  const [tab, setTab] = useState(0);

  // Form fields
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Query server status on mount
  useEffect(() => {
    let cancelled = false;
    setStatusLoading(true);
    setStatusError(null);

    checkStatus(serverUrl)
      .then((status) => {
        if (cancelled) return;
        setServerStatus(status);
        // First user: default to register tab
        if (!status.hasUsers) {
          setTab(1);
        }
      })
      .catch(() => {
        if (cancelled) return;
        setStatusError('Could not reach server at ' + serverUrl);
      })
      .finally(() => {
        if (!cancelled) setStatusLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [serverUrl]);

  const completeLogin = useCallback(
    async (authToken: string, userId: string, role: string) => {
      // Save auth credentials
      setAuth(authToken, userId, role);

      // Link device token (non-fatal if it fails)
      if (deviceToken) {
        await linkDevice(serverUrl, authToken, deviceToken);
      }

      // Proceed to Birth screen
      navigation.replace('Birth');
    },
    [serverUrl, deviceToken, setAuth, navigation],
  );

  const handleLogin = useCallback(async () => {
    if (!username.trim() || !password) return;
    setSubmitting(true);
    setError(null);

    try {
      const result = await login(serverUrl, username.trim(), password);
      await completeLogin(result.token, result.userId, result.role);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Login failed.');
    }
    setSubmitting(false);
  }, [serverUrl, username, password, completeLogin]);

  const handleRegister = useCallback(async () => {
    if (!username.trim() || !password) return;
    if (password !== confirmPassword) {
      setError('Passwords do not match.');
      return;
    }
    setSubmitting(true);
    setError(null);

    try {
      const result = await register(
        serverUrl,
        username.trim(),
        password,
        username.trim(),
      );
      await completeLogin(result.token, result.userId, result.role);
    } catch (e: unknown) {
      setError(
        e instanceof Error ? e.message : 'Registration failed.',
      );
    }
    setSubmitting(false);
  }, [serverUrl, username, password, confirmPassword, completeLogin]);

  const isFirstUser = serverStatus != null && !serverStatus.hasUsers;
  const isRegister = tab === 1 || isFirstUser;

  const canSubmit =
    username.trim().length > 0 &&
    password.length > 0 &&
    (!isRegister || (confirmPassword.length > 0 && confirmPassword === password)) &&
    !submitting;

  // --- Loading state ---
  if (statusLoading) {
    return (
      <View
        style={[styles.container, { backgroundColor: c.background }]}
        testID="login-screen-loading"
      >
        <Text style={[styles.title, { color: c.primary }]}>Account</Text>
        <ActivityIndicator
          color={c.primary}
          style={styles.spinner}
          testID="login-spinner"
        />
        <Text style={[styles.hint, { color: c.textSecondary }]}>
          Checking server...
        </Text>
      </View>
    );
  }

  // --- Error reaching server ---
  if (statusError) {
    return (
      <View
        style={[styles.container, { backgroundColor: c.background }]}
        testID="login-screen-error"
      >
        <Text style={[styles.title, { color: c.primary }]}>Account</Text>
        <Text style={[styles.errorText, { color: c.error }]}>{statusError}</Text>
      </View>
    );
  }

  // --- Main form ---
  return (
    <View
      style={[styles.container, { backgroundColor: c.background }]}
      testID="login-screen"
    >
      <Text style={[styles.title, { color: c.primary }]}>Account</Text>

      {/* First-user welcome message */}
      {isFirstUser && (
        <Text style={[styles.hint, { color: c.textSecondary }]}>
          Welcome. You are the first user -- create your account to become
          steward.
        </Text>
      )}

      {/* Tab selector (only when server has existing users) */}
      {!isFirstUser && (
        <View style={styles.tabRow}>
          <TouchableOpacity
            style={[
              styles.tab,
              tab === 0 && { borderBottomColor: c.primary, borderBottomWidth: 2 },
            ]}
            onPress={() => {
              setTab(0);
              setError(null);
            }}
            testID="login-tab"
          >
            <Text
              style={[
                styles.tabText,
                { color: tab === 0 ? c.primary : c.textSecondary },
              ]}
            >
              Login
            </Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[
              styles.tab,
              tab === 1 && { borderBottomColor: c.primary, borderBottomWidth: 2 },
            ]}
            onPress={() => {
              setTab(1);
              setError(null);
            }}
            testID="register-tab"
          >
            <Text
              style={[
                styles.tabText,
                { color: tab === 1 ? c.primary : c.textSecondary },
              ]}
            >
              Create Account
            </Text>
          </TouchableOpacity>
        </View>
      )}

      {/* Username */}
      <TextInput
        style={[
          styles.input,
          {
            borderColor: c.inputBorder,
            backgroundColor: c.inputBackground,
            color: c.text,
          },
        ]}
        value={username}
        onChangeText={(t) => {
          setUsername(t);
          setError(null);
        }}
        placeholder="Username"
        placeholderTextColor={c.placeholder}
        autoCapitalize="none"
        autoCorrect={false}
        testID="login-username-input"
      />

      {/* Password */}
      <TextInput
        style={[
          styles.input,
          {
            borderColor: c.inputBorder,
            backgroundColor: c.inputBackground,
            color: c.text,
          },
        ]}
        value={password}
        onChangeText={(t) => {
          setPassword(t);
          setError(null);
        }}
        placeholder="Password"
        placeholderTextColor={c.placeholder}
        secureTextEntry
        testID="login-password-input"
      />

      {/* Confirm password (register/first user only) */}
      {isRegister && (
        <TextInput
          style={[
            styles.input,
            {
              borderColor:
                confirmPassword.length > 0 && confirmPassword !== password
                  ? c.error
                  : c.inputBorder,
              backgroundColor: c.inputBackground,
              color: c.text,
            },
          ]}
          value={confirmPassword}
          onChangeText={(t) => {
            setConfirmPassword(t);
            setError(null);
          }}
          placeholder="Confirm Password"
          placeholderTextColor={c.placeholder}
          secureTextEntry
          testID="login-confirm-password-input"
        />
      )}

      {/* Error message */}
      {error && (
        <Text style={[styles.errorText, { color: c.error }]} testID="login-error">
          {error}
        </Text>
      )}

      {/* Submit button */}
      <TouchableOpacity
        style={[
          styles.submitButton,
          { backgroundColor: canSubmit ? c.primary : c.border },
        ]}
        onPress={isRegister ? handleRegister : handleLogin}
        disabled={!canSubmit}
        testID="login-submit-button"
      >
        {submitting ? (
          <View style={styles.submitRow}>
            <ActivityIndicator color={c.textOnPrimary} size="small" />
            <Text
              style={[
                styles.submitText,
                { color: c.textOnPrimary, marginLeft: 8 },
              ]}
            >
              {isRegister ? 'Creating...' : 'Logging in...'}
            </Text>
          </View>
        ) : (
          <Text style={[styles.submitText, { color: c.textOnPrimary }]}>
            {isRegister ? 'Create Account' : 'Login'}
          </Text>
        )}
      </TouchableOpacity>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    padding: 32,
  },
  title: {
    fontSize: 32,
    fontWeight: 'bold',
    textAlign: 'center',
    marginBottom: 8,
  },
  hint: {
    fontSize: 14,
    textAlign: 'center',
    marginBottom: 24,
    lineHeight: 20,
  },
  tabRow: {
    flexDirection: 'row',
    justifyContent: 'center',
    marginBottom: 24,
    gap: 24,
  },
  tab: {
    paddingBottom: 8,
    paddingHorizontal: 4,
  },
  tabText: {
    fontSize: 16,
    fontWeight: '600',
  },
  input: {
    borderWidth: 1,
    borderRadius: 8,
    padding: 12,
    marginBottom: 12,
    fontSize: 16,
  },
  errorText: {
    fontSize: 13,
    textAlign: 'center',
    marginBottom: 12,
  },
  submitButton: {
    paddingVertical: 14,
    borderRadius: 8,
    alignItems: 'center',
    marginTop: 4,
  },
  submitText: {
    fontWeight: 'bold',
    fontSize: 16,
  },
  submitRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  spinner: {
    marginTop: 24,
    marginBottom: 12,
  },
});
