/**
 * CT1 — Web Smoke Tests
 *
 * Verifies the web build renders the first-run onboarding (Welcome) screen.
 * No real server needed — just the Metro web dev server.
 *
 * The app's first-launch route is `Welcome` (App.tsx: firstRunComplete=false →
 * initialRouteName='Welcome'). WelcomeScreen.tsx is a 3-step onboarding flow;
 * a fresh user lands on step 0 ("Do you have a Wyrdsekai server?"). Selectors
 * target the testIDs that WelcomeScreen exposes (welcome-screen / welcome-title
 * / welcome-server-url / welcome-connect / welcome-no-server).
 *
 * NOTE: React Native Web surfaces a component's `testID` as the DOM attribute
 * `data-testid`, which Playwright's getByTestId() matches by default.
 */

import { test, expect } from '@playwright/test';

test.describe('CT1: Web Smoke', () => {
  test('app renders the welcome / onboarding screen', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('welcome-screen')).toBeVisible();
  });

  test('welcome screen shows the title and subtitle', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('welcome-title')).toBeVisible();
    await expect(page.getByTestId('welcome-title')).toHaveText('Wyrdsekai');
    // Step-0 heading: the first decision a fresh user is asked.
    await expect(page.getByText('Do you have a Wyrdsekai server?')).toBeVisible();
  });

  test('welcome step 0 has the server-url input', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('welcome-server-url')).toBeVisible();
  });

  test('welcome step 0 has connect and no-server actions', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('welcome-connect')).toBeVisible();
    await expect(page.getByTestId('welcome-no-server')).toBeVisible();
  });
});
