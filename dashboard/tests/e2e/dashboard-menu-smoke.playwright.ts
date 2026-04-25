import { expect, test, type Page } from '@playwright/test';
import { installDashboardApiMocks } from './dashboardMocks';

interface MenuRouteExpectation {
  label: string;
  path: string;
  visibleText: string | RegExp;
}

const CORE_ROUTES: MenuRouteExpectation[] = [
  { label: 'Chat', path: '/', visibleText: 'Start a focused conversation' },
  { label: 'Settings', path: '/settings', visibleText: 'Select a settings category' },
  { label: 'Prompts', path: '/prompts', visibleText: 'Prompt sections are injected' },
];

const SECONDARY_ROUTES: MenuRouteExpectation[] = [
  { label: 'Sessions', path: '/sessions', visibleText: 'No sessions found.' },
  { label: 'Goals & Tasks', path: '/goals', visibleText: 'Create and manage goals' },
  { label: 'Scheduler', path: '/scheduler', visibleText: 'No scheduled tasks yet.' },
  { label: 'Webhooks', path: '/webhooks', visibleText: 'Configure inbound HTTP hooks' },
  { label: 'Analytics', path: '/analytics', visibleText: 'Analytics' },
  { label: 'Skills', path: '/skills', visibleText: 'Skill Workspace' },
  { label: 'Diagnostics', path: '/diagnostics', visibleText: 'This page shows the effective runtime paths/env' },
  { label: 'Workspace', path: '/workspace', visibleText: 'Open a file, make a quick change' },
  { label: 'Logs', path: '/logs', visibleText: 'Live stream with virtualized rendering' },
];

function routeUrlPattern(route: MenuRouteExpectation): RegExp {
  if (route.path === '/') {
    return /\/dashboard\/?$/;
  }
  return new RegExp(`/dashboard${route.path}`);
}

async function expectRoute(page: Page, route: MenuRouteExpectation): Promise<void> {
  await expect(page).toHaveURL(routeUrlPattern(route));
  await expect(page.locator('main').getByText(route.visibleText).first()).toBeVisible({ timeout: 15_000 });
}

test.beforeEach(async ({ page }) => {
  await installDashboardApiMocks(page);
});

test('opens core dashboard sections from the sidebar menu', async ({ page }) => {
  await page.goto('/');

  for (const route of CORE_ROUTES) {
    await page.getByRole('link', { name: route.label }).click();
    await expectRoute(page, route);
  }
});

test('opens remaining primary menu sections without a blank page', async ({ page }) => {
  await page.goto('/');

  for (const route of SECONDARY_ROUTES) {
    await page.getByRole('link', { name: route.label }).click();
    await expectRoute(page, route);
  }
});
