/**
 * CT2 — Local-companion flows (current local-first architecture).
 *
 * The app onboards into a *local* standalone companion node, NOT a server
 * account login. Flow: WelcomeScreen → (no server) → "My companion lives
 * here" → Birth → StandaloneRoomScreen. These tests drive that real path
 * against the web build (no server required — WYRD_SKIP_SERVER works).
 *
 * Replaces the obsolete ct2-auth-flow / ct2-room-navigation / ct2-settings
 * suites, which targeted the pre-onboarding server register/login screen the
 * app no longer presents (see App.tsx WelcomeScreenWrapper.onComplete →
 * setLocalMode → 'Birth'). StandaloneRoomScreen testIDs come from #684.
 */

import { test, expect, Page } from '@playwright/test';

/** Walk WelcomeScreen → local standalone room. */
async function onboardLocal(page: Page) {
  await page.goto('/');
  await expect(page.getByTestId('welcome-screen')).toBeVisible();
  await page.getByTestId('welcome-no-server').click(); // step 0 → 1 (no household server)
  await page.getByTestId('welcome-standalone').click(); // → onComplete → Birth → Standalone
  await expect(page.getByTestId('standalone-room-name')).toBeVisible({ timeout: 30_000 });
}

test.describe('CT2: Local companion', () => {
  test('onboarding lands in the local standalone room', async ({ page }) => {
    await onboardLocal(page);
    // The local home room renders with a name.
    const name = await page.getByTestId('standalone-room-name').textContent();
    expect((name ?? '').trim().length).toBeGreaterThan(0);
  });

  test('the companion is present in the room', async ({ page }) => {
    await onboardLocal(page);
    // Seeded companion ("Present: Wyrd") shows in the entity-presence strip.
    await expect(page.getByTestId('standalone-entity-presence')).toBeVisible({ timeout: 15_000 });
  });

  test('the command input and send button are available', async ({ page }) => {
    await onboardLocal(page);
    await expect(page.getByTestId('standalone-input')).toBeVisible();
    await expect(page.getByTestId('standalone-send-button')).toBeVisible();
  });

  test('look command renders room prose', async ({ page }) => {
    await onboardLocal(page);
    // "look" is a deterministic room command — no inference backend needed.
    await page.getByTestId('standalone-input').fill('look');
    await page.getByTestId('standalone-send-button').click();
    await expect(page.getByTestId('standalone-prose-list')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('standalone-prose-entry').first()).toBeVisible({ timeout: 15_000 });
  });

  test('settings dialog opens from the room', async ({ page }) => {
    await onboardLocal(page);
    await page.getByTestId('standalone-settings-button').click();
    await expect(page.getByTestId('standalone-settings-dialog')).toBeVisible({ timeout: 10_000 });
  });

  test('settings dialog exposes the switch-to-server-mode control', async ({ page }) => {
    await onboardLocal(page);
    // The "switch mode" affordance (back to server/household mode) lives inside
    // the Node Settings dialog, not on the room view.
    await page.getByTestId('standalone-settings-button').click();
    await expect(page.getByTestId('standalone-settings-dialog')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId('switch-mode-button')).toBeVisible();
  });
});
