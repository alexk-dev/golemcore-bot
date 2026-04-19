import { expect, test } from '@playwright/test';
import { installDashboardApiMocks } from './dashboardMocks';

test.beforeEach(async ({ page }) => {
  await installDashboardApiMocks(page);
});

test('workspace page renders with chat visible and terminal hidden by default on desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
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

test('toggles desktop chat split and terminal panels and persists layout through reload', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
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

test('renders compact workspace mode on mobile and switches between editor and chat', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 667 });
  await page.goto('/dashboard/workspace');

  await expect(page.locator('[data-testid="workspace-page-compact"]')).toBeVisible();
  await expect(page.locator('[data-testid="workspace-compact-pane-editor"]')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('[data-testid="workspace-compact-pane-chat"]')).toHaveAttribute('aria-pressed', 'false');
  await expect(page.locator('[data-testid="workspace-toggle-chat"]')).toHaveCount(0);

  const buttons = page.locator('.workspace-toolbar-button');
  const buttonCount = await buttons.count();
  for (let index = 0; index < buttonCount; index += 1) {
    const height = await buttons.nth(index).evaluate((element) => Math.round(element.getBoundingClientRect().height));
    expect(height).toBeGreaterThanOrEqual(44);
  }

  await page.locator('[data-testid="workspace-compact-pane-chat"]').click();
  await expect(page.locator('[data-testid="workspace-compact-pane-chat"]')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('[data-testid="workspace-chat-pane"]')).toBeVisible();

  await page.locator('[data-testid="workspace-compact-pane-editor"]').click();
  await expect(page.locator('[data-testid="workspace-editor-pane"]')).toBeVisible();
});

test('opens terminal deep-link on desktop', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/dashboard/workspace?focus=terminal');

  const terminalToggle = page.locator('[data-testid="workspace-toggle-terminal"]');
  await expect(page.locator('[data-testid="workspace-page-desktop"]')).toBeVisible();
  await expect(terminalToggle).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('[data-testid="workspace-terminal-pane"]')).toBeVisible();
});

test('opens mobile terminal as bottom sheet and supports focus route for chat', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 667 });
  await page.goto('/dashboard/workspace?focus=chat');

  await expect(page.locator('[data-testid="workspace-compact-pane-chat"]')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('[data-testid="workspace-chat-pane"]')).toBeVisible();

  const terminalToggle = page.locator('[data-testid="workspace-toggle-terminal"]');
  await expect(terminalToggle).toHaveAttribute('aria-pressed', 'false');

  await terminalToggle.click();
  await expect(terminalToggle).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('heading', { name: 'Terminal' })).toBeVisible();
  await expect(page.locator('.workspace-terminal-offcanvas')).toBeVisible();
  const metrics = await page.locator('.workspace-terminal-offcanvas').evaluate((element) => ({
    height: element.getBoundingClientRect().height,
    viewportHeight: window.innerHeight,
  }));
  expect(metrics.height / metrics.viewportHeight).toBeGreaterThanOrEqual(0.88);
  await expect(page.locator('.workspace-terminal-offcanvas [data-testid="workspace-terminal-pane"]')).toBeVisible();

  await page.locator('.workspace-terminal-offcanvas').getByRole('button', { name: 'Close', exact: true }).click();
  await expect(terminalToggle).toHaveAttribute('aria-pressed', 'false');
});
