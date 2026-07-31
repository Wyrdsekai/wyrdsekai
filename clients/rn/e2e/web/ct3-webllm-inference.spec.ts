/**
 * CT3 — WebLLM Inference Tests
 *
 * Tests real WebGPU inference via WebLLM in the browser.
 * Requires:
 *   1. A GPU with sufficient free VRAM (~2 GB for smallest model)
 *   2. System Chrome installed (headless shell lacks WebGPU)
 *   3. WYRD_WEBGPU=1 env var
 *
 * Run with: WYRD_WEBGPU=1 npm run test:e2e:web:inference
 */

import { test, expect, Page } from '@playwright/test';

const SERVER_URL = process.env.WYRD_SERVER_URL ?? 'http://localhost:7070';

/** The smallest f32 model — widest compat, no shader-f16 needed. */
const TEST_MODEL_ID = 'Qwen3-0.6B-q4f32_1-MLC';

/** Navigate to WebNode Dashboard as a fresh user. */
async function goToDashboard(page: Page) {
  await page.goto('/');
  const username = `e2e_ct3_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;
  await page.getByTestId('server-url-input').fill(SERVER_URL);
  await page.getByTestId('username-input').fill(username);
  await page.getByTestId('password-input').fill('testpass123');
  await page.getByTestId('register-button').click();
  await expect(page.getByTestId('room-name')).toBeVisible({ timeout: 15_000 });
  await page.getByTestId('settings-button').click();
  await expect(page.getByTestId('settings-screen')).toBeVisible({ timeout: 5_000 });
  await page.getByTestId('web-node-dashboard-button').click();
  await expect(page.getByTestId('web-node-dashboard')).toBeVisible({ timeout: 5_000 });
}

/** Check if the browser actually has WebGPU with a working adapter. */
async function checkWebGPU(page: Page): Promise<{ available: boolean; reason?: string }> {
  return page.evaluate(async () => {
    try {
      if (!navigator.gpu) return { available: false, reason: 'No navigator.gpu' };
      const adapter = await navigator.gpu.requestAdapter();
      if (!adapter) return { available: false, reason: 'No adapter' };
      // Try creating a device to verify it actually works
      const device = await adapter.requestDevice();
      device.destroy();
      return { available: true };
    } catch (e: any) {
      return { available: false, reason: e?.message ?? 'Unknown error' };
    }
  });
}

test.describe('CT3: WebLLM Inference', () => {
  test('web node dashboard shows capabilities', async ({ page }) => {
    await page.goto('/');
    const username = `e2e_webllm_${Date.now()}`;
    await page.getByTestId('server-url-input').fill(SERVER_URL);
    await page.getByTestId('username-input').fill(username);
    await page.getByTestId('password-input').fill('testpass123');
    await page.getByTestId('register-button').click();
    await expect(page.getByTestId('room-name')).toBeVisible({ timeout: 15_000 });

    await page.getByTestId('settings-button').click();
    await expect(page.getByTestId('settings-screen')).toBeVisible({ timeout: 5_000 });

    const dashButton = page.getByTestId('web-node-dashboard-button');
    const hasDash = await dashButton.isVisible({ timeout: 3_000 }).catch(() => false);

    if (hasDash) {
      await dashButton.click();
      await expect(page.getByTestId('web-node-dashboard')).toBeVisible({ timeout: 5_000 });
      await expect(page.getByTestId('node-status-dot')).toBeVisible();
    }
  });

  test('load real model and verify active', async ({ page }) => {
    test.skip(!process.env.WYRD_WEBGPU, 'Set WYRD_WEBGPU=1 to enable');

    // Runtime check — skip fast if no WebGPU
    await page.goto('/');
    const gpu = await checkWebGPU(page);
    test.skip(!gpu.available, `Browser lacks WebGPU: ${gpu.reason}`);

    // Capture console errors for diagnostics
    const errors: string[] = [];
    page.on('console', msg => {
      if (msg.type() === 'error') errors.push(msg.text());
    });

    await goToDashboard(page);

    const loadButton = page.getByTestId(`webllm-load-${TEST_MODEL_ID}`);
    await expect(loadButton).toBeVisible({ timeout: 5_000 });
    await loadButton.click();

    // Wait for "Active" badge — model download + WebGPU compile
    const activeLocator = page.locator('[data-testid="web-node-dashboard"]')
      .getByText('Active', { exact: true });

    try {
      await expect(activeLocator).toBeVisible({ timeout: 120_000 });
    } catch {
      // Check for OOM or other GPU errors
      const oomErrors = errors.filter(e => e.includes('OutOfMemory') || e.includes('Device was lost'));
      if (oomErrors.length > 0) {
        test.skip(true, `GPU out of memory — other processes may be using VRAM. Errors: ${oomErrors[0]}`);
      }
      const loadErrors = errors.filter(e => e.includes('[WebLLM]'));
      if (loadErrors.length > 0) {
        throw new Error(`WebLLM model load failed: ${loadErrors.join('; ')}`);
      }
      throw new Error(`Model load timed out. Browser errors: ${errors.join('; ')}`);
    }
  });

  test('start companion with loaded model', async ({ page }) => {
    test.skip(!process.env.WYRD_WEBGPU, 'Set WYRD_WEBGPU=1 to enable');

    await page.goto('/');
    const gpu = await checkWebGPU(page);
    test.skip(!gpu.available, `Browser lacks WebGPU: ${gpu.reason}`);

    const errors: string[] = [];
    page.on('console', msg => {
      if (msg.type() === 'error') errors.push(msg.text());
    });

    await goToDashboard(page);

    const loadButton = page.getByTestId(`webllm-load-${TEST_MODEL_ID}`);
    await expect(loadButton).toBeVisible({ timeout: 5_000 });
    await loadButton.click();

    const activeLocator = page.locator('[data-testid="web-node-dashboard"]')
      .getByText('Active', { exact: true });

    try {
      await expect(activeLocator).toBeVisible({ timeout: 120_000 });
    } catch {
      const oomErrors = errors.filter(e => e.includes('OutOfMemory') || e.includes('Device was lost'));
      if (oomErrors.length > 0) {
        test.skip(true, `GPU out of memory — other processes may be using VRAM`);
      }
      throw new Error(`Model load failed: ${errors.filter(e => e.includes('[WebLLM]')).join('; ')}`);
    }

    const startButton = page.getByTestId('start-companion-button');
    await expect(startButton).toBeVisible({ timeout: 5_000 });
    await startButton.click();
    await expect(page.getByText(/Idle|Thinking/)).toBeVisible({ timeout: 30_000 });
  });
});
