import NativeWyrdOnnx, { type RunResult } from './NativeWyrdOnnx';

export type { RunResult };

/**
 * High-level session wrapper around the native TurboModule.
 *
 * Mirrors the shape of `onnxruntime-react-native`'s InferenceSession enough that
 * ChronosPhoneForecaster can swap the import with no behavioral change for the
 * single-input/single-output models it loads.
 */
export class InferenceSession {
  private constructor(private handle: number) {}

  static async create(modelPath: string): Promise<InferenceSession> {
    const handle = await NativeWyrdOnnx.loadModel(modelPath);
    return new InferenceSession(handle);
  }

  /**
   * Run inference. `feeds` maps input name → Tensor.
   * Returns a map of output name → Tensor. Only the first input/output are wired
   * (matches TTM and most forecasting models — extend if a multi-output model arrives).
   */
  async run(feeds: Record<string, Tensor>): Promise<Record<string, Tensor>> {
    const [inputName, inputTensor] = Object.entries(feeds)[0];
    const result = await NativeWyrdOnnx.run(
      this.handle,
      inputName,
      Array.from(inputTensor.data),
      Array.from(inputTensor.dims)
    );
    return {
      forecast: new Tensor('float32', new Float32Array(result.data), result.shape),
    };
  }

  async release(): Promise<void> {
    await NativeWyrdOnnx.close(this.handle);
  }
}

export class Tensor {
  constructor(
    public readonly type: 'float32',
    public readonly data: Float32Array,
    public readonly dims: number[]
  ) {}
}
