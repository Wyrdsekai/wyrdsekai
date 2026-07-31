/**
 * Jest mock for wyrd-onnx — the TurboModule bridge to ONNX Runtime.
 *
 * <p>Tests run on Node and can't load native RN modules. The standalone
 * conformance suite only uses {@link PhoneNode}'s top-level surface
 * (examine/rename/drop/say/look) which doesn't exercise oracle inference,
 * so providing a no-op stub here is sufficient for the unit-level
 * conformance tests we run in CI.</p>
 */

export const InferenceSession = {
  async create(): Promise<unknown> {
    throw new Error('wyrd-onnx is mocked in tests — oracle inference unavailable');
  },
};

export class Tensor {
  constructor(_type: string, _data: unknown, _dims?: number[]) {}
}

export default {
  InferenceSession,
  Tensor,
};
