import { expect, test } from '@playwright/test';
import { installDashboardApiMocks } from './dashboardMocks';

test.beforeEach(async ({ page }) => {
  await installDashboardApiMocks(page);
  await page.addInitScript(() => {
    class StubWebSocket extends EventTarget {
      static readonly CONNECTING = 0;
      static readonly OPEN = 1;
      static readonly CLOSING = 2;
      static readonly CLOSED = 3;
      readyState = StubWebSocket.OPEN;
      url: string;
      onopen: ((event: Event) => void) | null = null;
      onmessage: ((event: MessageEvent<string>) => void) | null = null;
      onclose: ((event: CloseEvent) => void) | null = null;
      onerror: ((event: Event) => void) | null = null;
      constructor(url: string) {
        super();
        this.url = url;
        queueMicrotask(() => {
          this.onopen?.(new Event('open'));
        });
      }
      send(): void {
        /* no-op */
      }
      close(): void {
        this.readyState = StubWebSocket.CLOSED;
        this.onclose?.(new CloseEvent('close'));
      }
    }
    (window as unknown as { WebSocket: typeof StubWebSocket }).WebSocket = StubWebSocket;
  });
});

test('opens terminal panel, creates a second tab, closes a tab', async ({ page }) => {
  await page.goto('/dashboard/workspace');

  const terminalToggle = page.locator('[data-testid="workspace-toggle-terminal"]');
  await expect(terminalToggle).toHaveAttribute('aria-pressed', 'false', { timeout: 15_000 });

  await terminalToggle.click();
  await expect(terminalToggle).toHaveAttribute('aria-pressed', 'true');

  const tablist = page.locator('[role="tablist"][aria-label="Terminal tabs"]');
  await expect(tablist).toBeVisible();

  const tabs = tablist.locator('[role="tab"]');
  await expect(tabs).toHaveCount(1);
  await expect(tabs.first()).toHaveText(/Terminal 1/);

  await page.locator('[data-testid="terminal-new-tab"]').click();
  await expect(tabs).toHaveCount(2);
  await expect(tabs.nth(1)).toHaveText(/Terminal 2/);

  const firstTabId = await tabs
    .first()
    .evaluate((node) => node.getAttribute('data-testid')?.replace('terminal-tab-', '') ?? '');
  await page.locator(`[data-testid="terminal-tab-close-${firstTabId}"]`).click();

  await expect(tabs).toHaveCount(1);
  await expect(tabs.first()).toHaveText(/Terminal 2/);
});

test('Ctrl+` toggles the terminal panel', async ({ page }) => {
  await page.goto('/dashboard/workspace');

  const terminalToggle = page.locator('[data-testid="workspace-toggle-terminal"]');
  await expect(terminalToggle).toHaveAttribute('aria-pressed', 'false', { timeout: 15_000 });

  await page.keyboard.press('Control+`');
  await expect(terminalToggle).toHaveAttribute('aria-pressed', 'true');

  await page.keyboard.press('Control+`');
  await expect(terminalToggle).toHaveAttribute('aria-pressed', 'false');
});
