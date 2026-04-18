import { expect, test, type Page } from '@playwright/test';
import { installDashboardApiMocks } from './dashboardMocks';

export interface MobileRouteExpectation {
  path: string;
  visibleText: string | RegExp;
}

const MOBILE_ROUTES: MobileRouteExpectation[] = [
  { path: '/', visibleText: 'Start a focused conversation' },
  { path: '/prompts', visibleText: 'Prompt sections are injected' },
  { path: '/webhooks', visibleText: 'Configure inbound HTTP hooks' },
  { path: '/diagnostics', visibleText: 'This page shows the effective runtime paths/env' },
  { path: '/logs', visibleText: 'Live stream with virtualized rendering' },
  { path: '/settings', visibleText: 'Select a settings category' },
  { path: '/workspace', visibleText: 'Open a file, make a quick change' },
];

function buildRoutePath(routePath: string): string {
  if (routePath === '/') {
    return '/';
  }
  return `/dashboard${routePath}`;
}

function isMobileViewportOverflowing(page: Page): Promise<boolean> {
  return page.evaluate(() => {
    const viewportWidth = window.innerWidth;
    const elements = Array.from(document.body.querySelectorAll('*'));
    return elements.some((element) => {
      const sidebar = element.closest('.sidebar');
      const isClosedSidebarElement = sidebar != null
        && !sidebar.classList.contains('mobile-open')
        && window.getComputedStyle(sidebar).position === 'fixed';
      if (isClosedSidebarElement) {
        return false;
      }

      const rect = element.getBoundingClientRect();
      if (rect.width === 0 || rect.height === 0) {
        return false;
      }
      return rect.right > viewportWidth + 1 || rect.left < -1;
    });
  });
}

test.use({
  viewport: { width: 375, height: 667 },
  isMobile: true,
  hasTouch: true,
});

test.beforeEach(async ({ page }) => {
  await installDashboardApiMocks(page);
});

test('closes mobile sidebar by tapping the backdrop', async ({ page }) => {
  await page.goto('/');

  await page.getByRole('button', { name: 'Open navigation' }).click();
  await expect(page.getByRole('complementary', { name: 'Primary navigation' }).getByLabel('Close navigation')).toBeVisible();

  await page.getByRole('button', { name: 'Close navigation menu' }).click();

  await expect(page.getByRole('button', { name: 'Open navigation' })).toBeVisible();
});

test('keeps primary sections within mobile viewport width', async ({ page }) => {
  for (const route of MOBILE_ROUTES) {
    await page.goto(buildRoutePath(route.path));
    await expect(page.locator('main').getByText(route.visibleText).first()).toBeVisible({ timeout: 15_000 });
    await expect.poll(() => isMobileViewportOverflowing(page), {
      message: `${route.path} should not create horizontal viewport overflow`,
    }).toBe(false);
  }
});
