import { expect, test } from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs/promises';
import { installDashboardApiMocks } from './dashboardMocks';

const OUTPUT_DIR = path.join(process.cwd(), 'tests', 'workspace-terminal-proof');

async function ensureOutputDir(): Promise<void> {
  await fs.mkdir(OUTPUT_DIR, { recursive: true });
}

test.beforeAll(async () => {
  await ensureOutputDir();
});

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
          this.onmessage?.(new MessageEvent('message', {
            data: JSON.stringify({
              type: 'output',
              data: btoa('demo@golemcore:~$ '),
            }),
          }));
        });
      }

      send(data: string): void {
        try {
          const parsed = JSON.parse(data) as { type?: string; data?: string };
          if (parsed.type !== 'input' || typeof parsed.data !== 'string') {
            return;
          }
          const decoded = atob(parsed.data);
          this.onmessage?.(new MessageEvent('message', {
            data: JSON.stringify({
              type: 'output',
              data: btoa(decoded),
            }),
          }));
        } catch {
          // ignore malformed frames in the stub transport
        }
      }

      close(): void {
        this.readyState = StubWebSocket.CLOSED;
        this.onclose?.(new CloseEvent('close'));
      }
    }

    (window as unknown as { WebSocket: typeof StubWebSocket }).WebSocket = StubWebSocket;
  });
});

test('terminal shows a shell prompt and accepts typed input', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/dashboard/workspace');

  const terminalToggle = page.locator('[data-testid="workspace-toggle-terminal"]');
  await terminalToggle.click();
  await expect(terminalToggle).toHaveAttribute('aria-pressed', 'true');

  const terminal = page.locator('.xterm-screen');
  await expect(terminal).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText('demo@golemcore:~$')).toBeVisible({ timeout: 15_000 });

  await terminal.click();
  await page.keyboard.type('echo hi');
  await expect(page.getByText('echo hi')).toBeVisible({ timeout: 15_000 });

  await page.screenshot({
    path: path.join(OUTPUT_DIR, 'terminal-shell-proof.png'),
    fullPage: false,
  });
});
