import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

/**
 * Codegen spec for the wyrd-onnx TurboModule.
 *
 * Mirrors the surface of KMP's OnnxSession (clients/kmp/shared/src/.../OnnxInference.*.kt):
 * load a model from a filesystem path, run a single-input inference, close the session.
 *
 * Inputs/outputs travel as plain number arrays because RN codegen does not support
 * TypedArray (Float32Array) across the JSI boundary. The native side converts to
 * FloatArray / NSArray<NSNumber>. For our forecasting use case (TTM 512→96 floats)
 * that copy is negligible.
 */
export interface Spec extends TurboModule {
  /** Load an ONNX model file. Returns an opaque session handle. */
  loadModel(modelPath: string): Promise<number>;

  /**
   * Run one input through the session.
   * @param handle session handle from loadModel
   * @param inputName name of the input tensor (e.g. "context")
   * @param inputData flat float values in row-major order
   * @param inputShape tensor shape as int dimensions (e.g. [1, 512, 1])
   */
  run(
    handle: number,
    inputName: string,
    inputData: number[],
    inputShape: number[]
  ): Promise<RunResult>;

  /** Release the session and any native resources. */
  close(handle: number): Promise<void>;
}

export interface RunResult {
  data: number[];
  shape: number[];
}

// Lazy resolution: getEnforcing at import-time throws and breaks app boot if the
// native lib isn't packaged (e.g. debug builds without the codegen .so wired up).
// Defer the lookup until first call so the app boots cleanly and only the actual
// inference path fails if the native side is missing. Callers (ChronosPhoneForecaster)
// already gate ONNX use behind feature flags.
let _module: Spec | null = null;
function getModule(): Spec {
  if (_module === null) {
    _module = TurboModuleRegistry.getEnforcing<Spec>('WyrdOnnx');
  }
  return _module;
}

export default {
  loadModel: (modelPath: string) => getModule().loadModel(modelPath),
  run: (handle: number, inputName: string, inputData: number[], inputShape: number[]) =>
    getModule().run(handle, inputName, inputData, inputShape),
  close: (handle: number) => getModule().close(handle),
} as Spec;
