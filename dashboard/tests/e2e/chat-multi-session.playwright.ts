import { expect, test } from '@playwright/test';
import { installDashboardApiMocks } from './dashboardMocks';

test.beforeEach(async ({ page }) => {
  await installDashboardApiMocks(page);
});

test('opens, switches between, and closes chat session tabs', async ({ page }) => {
  await page.goto('/dashboard/workspace');

  const tabs = page.locator('[data-testid="chat-session-tab"]');
  await expect(tabs).toHaveCount(1, { timeout: 15_000 });

  const firstLabel = await tabs.first().textContent();
  expect(firstLabel).toContain('Chat 1');

  await page.locator('[data-testid="chat-session-new"]').click();
  await expect(tabs).toHaveCount(2);
  await expect(tabs.nth(1)).toHaveAttribute('data-active', 'true');

  await page.locator('[data-testid="chat-session-new"]').click();
  await expect(tabs).toHaveCount(3);
  await expect(tabs.nth(2)).toHaveAttribute('data-active', 'true');

  await tabs.nth(0).click();
  await expect(tabs.nth(0)).toHaveAttribute('data-active', 'true');
  await expect(tabs.nth(2)).toHaveAttribute('data-active', 'false');

  const closeButtons = page.locator('[data-testid="chat-session-close"]');
  await closeButtons.nth(2).click();
  await expect(tabs).toHaveCount(2);

  const persisted = await page.evaluate(() => ({
    open: JSON.parse(window.localStorage.getItem('golem-chat-open-sessions') ?? '[]') as string[],
    active: window.localStorage.getItem('golem-chat-session-id'),
  }));
  expect(persisted.open).toHaveLength(2);
  expect(persisted.active).not.toBeNull();
  expect(persisted.open).toContain(persisted.active);
});

test('Alt+N keyboard shortcut opens a new session tab', async ({ page }) => {
  await page.goto('/dashboard/workspace');

  const tabs = page.locator('[data-testid="chat-session-tab"]');
  await expect(tabs).toHaveCount(1, { timeout: 15_000 });

  await page.locator('body').click();
  await page.keyboard.press('Alt+KeyN');
  await expect(tabs).toHaveCount(2);
  await expect(tabs.nth(1)).toHaveAttribute('data-active', 'true');

  await page.keyboard.press('Alt+Digit1');
  await expect(tabs.nth(0)).toHaveAttribute('data-active', 'true');
});
