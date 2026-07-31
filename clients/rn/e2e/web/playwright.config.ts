import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright configuration for Wyrdsekai RN web E2E tests.
 *
 * Three projects:
 * - ct1-smoke: Web build renders (no server needed)
 * - ct2-flows: Full auth/room/nav flows (real Wyrdsekai server + NATS)
 * - ct3-inference: WebGPU/WebLLM inference (real model, real GPU)
 */
export default defineConfig({
  testDir: '.',
  globalSetup: './global-setup.ts',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false, // sequential within project — shared server state
  workers: 1, // single worker — tests share server state
  retries: 0,
  reporter: [['html', { open: 'never' }], ['list']],

  use: {
    baseURL: 'http://localhost:8081',
    screenshot: 'only-on-failure',
    trace: 'on-first-retry',
    // Disable web security so cross-origin fetch to Wyrdsekai server works
    // (web app on :8081, server on :7070 — different origins)
    launchOptions: {
      args: ['--disable-web-security'],
    },
  },

  projects: [
    {
      name: 'ct1-smoke',
      testMatch: /ct1-.+\.spec\.ts$/,
      use: {
        ...devices['Desktop Chrome'],
      },
    },
    {
      name: 'ct2-flows',
      testMatch: /ct2-.+\.spec\.ts$/,
      use: {
        ...devices['Desktop Chrome'],
      },
    },
    {
      name: 'ct3-inference',
      testMatch: /ct3-.+\.spec\.ts$/,
      timeout: 180_000, // WebLLM model loading can be slow
      use: {
        ...devices['Desktop Chrome'],
        // System Chrome required — Playwright's headless shell lacks WebGPU.
        // Flags per https://developer.chrome.com/blog/supercharge-web-ai-testing
        channel: 'chrome',
        launchOptions: {
          args: [
            '--disable-web-security',
            '--headless=new',
            '--no-sandbox',
            '--use-angle=vulkan',
            '--enable-features=Vulkan',
            '--disable-vulkan-surface',
            '--enable-unsafe-webgpu',
            '--enable-dawn-features=allow_unsafe_apis',
          ],
        },
      },
    },
  ],

  webServer: {
    command: 'npm run web -- --port 8081',
    port: 8081,
    timeout: 60_000,
    reuseExistingServer: !process.env.CI,
    cwd: '../..',
  },
});
