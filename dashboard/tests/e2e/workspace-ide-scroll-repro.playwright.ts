import { expect, test } from '@playwright/test';
import { installDashboardApiMocks } from './dashboardMocks';

const MOCK_TREE = [
  {
    path: 'README.md',
    name: 'README.md',
    type: 'file',
    size: 12000,
    mimeType: 'text/markdown',
    updatedAt: '2026-04-13T00:00:00Z',
    binary: false,
    image: false,
    editable: true,
    hasChildren: false,
    children: [],
  },
];

const MOCK_CONTENT = Array.from({ length: 400 }, (_, index) => `Line ${index + 1} — scrolling test content`).join('\n');

test.beforeEach(async ({ page }) => {
  await installDashboardApiMocks(page);
  await page.route('**/api/files/tree**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_TREE),
    });
  });
  await page.route('**/api/files/content**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        path: 'README.md',
        content: MOCK_CONTENT,
        size: MOCK_CONTENT.length,
        updatedAt: '2026-04-13T00:00:00Z',
        mimeType: 'text/markdown',
        binary: false,
        image: false,
        editable: true,
        downloadUrl: null,
      }),
    });
  });
});

test('scrolls opened ide file content', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.goto('/dashboard/workspace');

  await expect(page.getByText('README.md')).toBeVisible({ timeout: 15_000 });
  await page.getByText('README.md').click();
  await expect(page.locator('.cm-editor')).toBeVisible({ timeout: 15_000 });

  await expect.poll(async () => {
    return page.locator('.cm-scroller').evaluate((element) => ({
      clientHeight: element.clientHeight,
      scrollHeight: element.scrollHeight,
    }));
  }).toEqual(expect.objectContaining({
    clientHeight: expect.any(Number),
    scrollHeight: expect.any(Number),
  }));

  const before = await page.locator('.cm-scroller').evaluate((element) => ({
    scrollTop: element.scrollTop,
    clientHeight: element.clientHeight,
    scrollHeight: element.scrollHeight,
  }));
  expect(before.scrollHeight).toBeGreaterThan(before.clientHeight);

  await page.locator('.cm-scroller').focus();
  await page.locator('.cm-scroller').evaluate((element) => {
    element.scrollTop = 1200;
    element.dispatchEvent(new Event('scroll'));
  });

  await expect.poll(async () => {
    return page.locator('.cm-scroller').evaluate((element) => element.scrollTop);
  }).toBeGreaterThan(0);
});
