/**
 * IBM Granite TTM (TinyTimeMixer) forecaster for phone via ONNX Runtime.
 *
 * 800K params, ~1MB model. Pure MLP mixer — no attention, no exotic ops,
 * trivial ONNX export. Zero-shot competitive with models 40-60x larger.
 *
 * Switched from Chronos-Bolt-Tiny (9M, ~35MB) to TTM (800K, ~1MB) on 2026-03-29.
 * Reason: Chronos ONNX export blocked (aten::nanmean, discussion #272).
 * TTM is 11x smaller, trivially exportable, and better suited for phone.
 *
 * Model: ibm-granite/granite-timeseries-ttm-r2 on HuggingFace
 * Input:  float32 tensor shape (1, 512, 1) named "context"
 * Output: float32 tensor shape (1, 96, 1) named "forecast"
 * ~0.7ms inference on CPU.
 *
 * Inference goes through the in-tree wyrd-onnx TurboModule, which calls
 * onnxruntime-android / onnxruntime-objc directly. The previous wrapper
 * (onnxruntime-react-native) had no working New-Architecture path.
 */
import { InferenceSession, Tensor } from 'wyrd-onnx';

export const MODEL_FILENAME = 'granite-ttm-r2.onnx';
/** Path to bundled model in app assets. */
export const MODEL_ASSET_PATH = 'granite-ttm-r2.onnx';
/** Actual model size: ~1.0MB ONNX. */
export const EXPECTED_SIZE_BYTES = 1_100_000;
/** Fixed context length: 512 time steps input. */
export const CONTEXT_LENGTH = 512;
/** Forecast horizon: 96 steps output. */
export const FORECAST_HORIZON = 96;

export class TtmPhoneForecaster {
  private session: InferenceSession | null = null;
  private loaded = false;

  constructor(private readonly modelPath: string) {}

  async load(): Promise<boolean> {
    try {
      this.session = await InferenceSession.create(this.modelPath);
      this.loaded = true;
      return true;
    } catch (e) {
      console.warn('TTM model load failed:', e);
      this.loaded = false;
      return false;
    }
  }

  async forecast(
    values: number[],
    horizon: number = FORECAST_HORIZON,
  ): Promise<Array<{ predicted: number; lowerBound: number; upperBound: number }>> {
    if (!this.loaded || !this.session || values.length < 10) return [];

    // Pad or truncate to CONTEXT_LENGTH (512).
    // If fewer values than 512, left-pad with zeros so recent data
    // aligns to the end of the context window.
    const context = new Float32Array(CONTEXT_LENGTH);
    const offset = Math.max(0, CONTEXT_LENGTH - values.length);
    for (let i = 0; i < values.length && offset + i < CONTEXT_LENGTH; i++) {
      context[offset + i] = values[i];
    }

    // Run inference: input shape (1, 512, 1), output shape (1, 96, 1)
    const inputTensor = new Tensor('float32', context, [1, CONTEXT_LENGTH, 1]);
    const results = await this.session.run({ context: inputTensor });
    const output = results['forecast']?.data as Float32Array;
    if (!output) return [];

    // Parse output into forecast points with 10% confidence bands
    const actualHorizon = Math.min(horizon, output.length);
    const result: Array<{ predicted: number; lowerBound: number; upperBound: number }> = [];
    for (let i = 0; i < actualHorizon; i++) {
      const predicted = output[i];
      const margin = Math.abs(predicted) * 0.1;
      result.push({
        predicted,
        lowerBound: predicted - margin,
        upperBound: predicted + margin,
      });
    }
    return result;
  }

  isAvailable(): boolean {
    return this.loaded;
  }
}

/** @deprecated Switched from Chronos to TTM. Use TtmPhoneForecaster. */
export const ChronosPhoneForecaster = TtmPhoneForecaster;
