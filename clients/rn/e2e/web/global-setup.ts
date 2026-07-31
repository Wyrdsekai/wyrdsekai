/**
 * Playwright global setup — starts the real Wyrdsekai server once,
 * shared across all test workers. CT1 smoke tests don't need the server,
 * so failure to start is a warning, not a fatal error.
 *
 * When WYRD_WEBGPU=1 (CT3 tests enabled), also checks GPU memory and
 * dynamically reconfigures SGLang if it's hogging too much VRAM for
 * WebLLM browser inference to coexist.
 */

import { ServerFixture } from '../shared/server-fixture';
import { GpuFixture } from '../shared/gpu-fixture';

const server = new ServerFixture();
const gpu = new GpuFixture();

async function globalSetup() {
  // When CT3 is enabled, ensure GPU memory is available for WebLLM
  if (process.env.WYRD_WEBGPU) {
    await gpu.ensureWebLLMMemory();
  }

  if (process.env.WYRD_SKIP_SERVER) {
    console.log('[globalSetup] WYRD_SKIP_SERVER set, skipping server start');
    return async () => {
      await gpu.restore();
    };
  }

  try {
    console.log('[globalSetup] Starting Wyrdsekai server on port 7070...');
    await server.start(60_000);
    console.log(`[globalSetup] Server ready at ${server.baseUrl()}`);
  } catch (e: any) {
    console.warn(`[globalSetup] Server start failed: ${e.message}`);
    console.warn('[globalSetup] CT2+ tests will fail. CT1 smoke tests will still work.');
    return async () => {
      await gpu.restore();
    };
  }

  // Return teardown function
  return async () => {
    console.log('[globalTeardown] Stopping Wyrdsekai server...');
    server.stop();
    await gpu.restore();
  };
}

export default globalSetup;
