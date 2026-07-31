/**
 * BirthScreen -- the gate that ensures the companion's model is ready
 * before the room appears.
 *
 * State machine:
 *   checking -> downloading -> loading -> booting -> ready
 *
 * On first run (no model on disk), shows "{name} is being born..." with a
 * download progress bar. On subsequent launches (model cached), shows
 * "{name} is waking up..." and moves through the states quickly.
 *
 * Once the model is downloaded and loaded (or confirmed unavailable),
 * navigates to 'Standalone' where StandaloneNodeContext boots the PhoneNode.
 * The companion entry guarantee comes from Wave 1 (PhoneNode does not reach
 * 'running' until the companion enters the room).
 */
import React, { useEffect, useState, useRef } from 'react';
import { View, Text, StyleSheet, ActivityIndicator } from 'react-native';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/types';
import { useAppModeStore } from '../state/appModeStore';
import { useInference } from '../inference/InferenceContext';
import { useThemeColors } from '../theme/useTheme';

type Step =
  | 'checking'
  | 'downloading'
  | 'loading'
  | 'booting'
  | 'ready';

type Props = NativeStackScreenProps<RootStackParamList, 'Birth'>;

export function BirthScreen({ navigation }: Props) {
  const c = useThemeColors();
  const companionName = useAppModeStore((s) => s.companionName) || 'Wyrd';
  const inferenceUrl = useAppModeStore((s) => s.inferenceUrl);
  const { modelManager, inferenceRouter } = useInference();

  const [step, setStep] = useState<Step>('checking');
  const [progress, setProgress] = useState(0);
  const [statusText, setStatusText] = useState('');
  const [isFirstRun, setIsFirstRun] = useState(false);
  const hasStarted = useRef(false);

  useEffect(() => {
    if (hasStarted.current) return;
    hasStarted.current = true;

    (async () => {
      try {
        // -- Step 1: Check for existing model --
        setStep('checking');
        setStatusText(`${companionName} is waking up...`);

        const preferredModel = 'qwen3-4b-q4';
        const fallbackModel = 'qwen3-0.6b-q8';

        let modelPath = await modelManager.getModelPath(preferredModel);
        if (!modelPath) modelPath = await modelManager.getModelPath(fallbackModel);

        // First-run: never block onboarding on a 2.6GB local-model download.
        // Users can pull a model later from Settings if they want offline
        // inference. This covers all three Welcome paths:
        //   - "Use my server"          → remote inference handles thinking
        //   - "I have an API key"      → cloud inference handles thinking
        //   - "Just explore on my own" → companion has no replies until they
        //                                pull a model; world still works
        // The previous form gated this skip on `inferenceUrl`, which meant
        // the "Just explore" path fell through to RNFS download and crashed
        // when the model URL wasn't reachable.
        if (!modelPath) {
          setStep('booting');
          setStatusText(`${companionName} is entering the world...`);
          await new Promise((resolve) => setTimeout(resolve, 400));
          navigation.replace('Standalone');
          return;
        }

        // -- Step 2: Load model --
        setStep('loading');
        setStatusText('Loading model...');

        try {
          await inferenceRouter.loadLocalModel(modelPath, {
            nCtx: 2048,
            nThreads: Math.min(
              6,
              (globalThis as any).navigator?.hardwareConcurrency ?? 4,
            ),
          });
        } catch (loadErr: any) {
          // Model load failed -- proceed anyway (remote inference may work)
          console.warn('BirthScreen: model load failed:', loadErr?.message);
        }

        // -- Step 3: Navigate to Standalone (PhoneNode boots there) --
        setStep('booting');
        setStatusText(`${companionName} is entering the world...`);

        // Brief visual pause before transition
        await new Promise((resolve) => setTimeout(resolve, 600));

        setStep('ready');
        navigation.replace('Standalone');
      } catch (err: any) {
        // If model operations fail entirely, still proceed to Standalone.
        // The companion will work via remote inference or show an error there.
        console.warn('BirthScreen: error during model setup:', err?.message);
        setStatusText('Preparing rooms...');
        await new Promise((resolve) => setTimeout(resolve, 400));
        navigation.replace('Standalone');
      }
    })();
  }, []);

  return (
    <View
      style={[styles.container, { backgroundColor: c.background }]}
      testID="birth-screen"
    >
      {/* Companion name */}
      <Text style={[styles.companionName, { color: c.primary }]}>
        {companionName}
      </Text>

      <View style={styles.statusSection}>
        {/* Status message */}
        <Text style={[styles.statusText, { color: c.textSecondary }]}>
          {statusText}
        </Text>

        <View style={styles.indicatorContainer}>
          {step === 'downloading' ? (
            <>
              {/* Determinate progress bar during download */}
              <View
                style={[styles.progressTrack, { backgroundColor: c.border }]}
              >
                <View
                  style={[
                    styles.progressFill,
                    {
                      backgroundColor: c.primary,
                      width: `${Math.round(progress * 100)}%`,
                    },
                  ]}
                />
              </View>
              <Text style={[styles.progressLabel, { color: c.textMuted }]}>
                {Math.round(progress * 100)}%
              </Text>
            </>
          ) : step !== 'ready' ? (
            /* Indeterminate spinner for other states */
            <ActivityIndicator
              size="small"
              color={c.primary}
              testID="birth-spinner"
            />
          ) : null}
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 32,
  },
  companionName: {
    fontSize: 36,
    fontWeight: 'bold',
    letterSpacing: 2,
    textAlign: 'center',
    marginBottom: 48,
  },
  statusSection: {
    alignItems: 'center',
    minHeight: 80,
  },
  statusText: {
    fontSize: 16,
    textAlign: 'center',
    marginBottom: 24,
  },
  indicatorContainer: {
    alignItems: 'center',
    minHeight: 40,
  },
  progressTrack: {
    width: 220,
    height: 4,
    borderRadius: 2,
    overflow: 'hidden',
  },
  progressFill: {
    height: '100%',
    borderRadius: 2,
  },
  progressLabel: {
    fontSize: 13,
    marginTop: 8,
  },
});
