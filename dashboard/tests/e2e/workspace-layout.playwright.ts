import { expect, test } from '@playwright/test';
import { installDashboardApiMocks } from './dashboardMocks';

test.beforeEach(async ({ page }) => {
  await installDashboardApiMocks(page);
});

test('workspace page renders with chat visible and terminal hidden by default', async ({ page }) => {
  await page.goto('/dashboard/workspace');

  const chatToggle = page.locator('[data-testid="workspace-toggle-chat"]');
  const terminalToggle = page.locator('[data-testid="workspace-toggle-terminal"]');

  await expect(chatToggle).toHaveAttribute('aria-pressed', 'true', { timeout: 15_000 });
  await expect(terminalToggle).toHaveAttribute('aria-pressed', 'false');

  await expect(page.locator('[data-testid="chat-session-tab"]').first()).toBeVisible();
});

test('/ide and /chat redirect to /workspace', async ({ page }) => {
  await page.goto('/dashboard/ide');
  await expect(page).toHaveURL(/\/workspace/);

  await page.goto('/dashboard/chat');
  await expect(page).toHaveURL(/\/workspace/);
});

test('toggles chat and terminal panels and persists layout through reload', async ({ page }) => {
  await page.goto('/dashboard/workspace');

  const chatToggle = page.locator('[data-testid="workspace-toggle-chat"]');
  const terminalToggle = page.locator('[data-testid="workspace-toggle-terminal"]');

  await expect(chatToggle).toHaveAttribute('aria-pressed', 'true', { timeout: 15_000 });

  await chatToggle.click();
  await expect(chatToggle).toHaveAttribute('aria-pressed', 'false');

  await terminalToggle.click();
  await expect(terminalToggle).toHaveAttribute('aria-pressed', 'true');

  const persistedBeforeReload = await page.evaluate(() =>
    window.localStorage.getItem('golem-workspace-layout'),
  );
  expect(persistedBeforeReload).not.toBeNull();
  const parsed = JSON.parse(persistedBeforeReload ?? '{}') as {
    isChatVisible: boolean;
    isTerminalVisible: boolean;
  };
  expect(parsed.isChatVisible).toBe(false);
  expect(parsed.isTerminalVisible).toBe(true);

  await page.reload();
  await expect(chatToggle).toHaveAttribute('aria-pressed', 'false', { timeout: 15_000 });
  await expect(terminalToggle).toHaveAttribute('aria-pressed', 'true');
});
