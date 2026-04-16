import { test, type Page } from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs/promises';
import { installDashboardApiMocks } from './dashboardMocks';

interface AuditRoute {
  slug: string;
  url: string;
}

interface OverflowHit {
  tag: string;
  id: string;
  classes: string;
  text: string;
  rect: { left: number; right: number; width: number };
}

interface AuditReport {
  slug: string;
  viewport: string;
  url: string;
  documentScrollHeight: number;
  bodyScrollWidth: number;
  innerWidth: number;
  topbarBoundingTop: number | null;
  topbarPosition: string | null;
  topbarVisibleAfterScroll: boolean | null;
  overflowHits: OverflowHit[];
  tapTargetsTooSmall: number;
}

const ROUTES: AuditRoute[] = [
  { slug: 'chat-root', url: '/' },
  { slug: 'prompts', url: '/dashboard/prompts' },
  { slug: 'webhooks', url: '/dashboard/webhooks' },
  { slug: 'diagnostics', url: '/dashboard/diagnostics' },
  { slug: 'logs', url: '/dashboard/logs' },
  { slug: 'settings', url: '/dashboard/settings' },
  { slug: 'sessions', url: '/dashboard/sessions' },
  { slug: 'skills', url: '/dashboard/skills' },
];

const VIEWPORTS = [
  { label: '375x667', width: 375, height: 667 },
  { label: '414x896', width: 414, height: 896 },
  { label: '768x1024', width: 768, height: 1024 },
];

const OUT_DIR = path.join(process.cwd(), 'tests', 'mobile-audit');

async function gatherAudit(page: Page, slug: string, viewport: string, url: string): Promise<AuditReport> {
  const metrics = await page.evaluate(() => {
    const body = document.body;
    const html = document.documentElement;
    const topbar = document.querySelector<HTMLElement>('.topbar');
    const topbarPosition = topbar != null ? window.getComputedStyle(topbar).position : null;
    const topbarBoundingTop = topbar != null ? topbar.getBoundingClientRect().top : null;

    const overflowHits: OverflowHit[] = [];
    for (const el of Array.from(body.querySelectorAll<HTMLElement>('*'))) {
      if (el.closest('.sidebar') != null) {
        const sidebar = el.closest('.sidebar') as HTMLElement;
        if (!sidebar.classList.contains('mobile-open')) {
          continue;
        }
      }
      const rect = el.getBoundingClientRect();
      if (rect.width === 0 || rect.height === 0) {
        continue;
      }
      if (rect.right > window.innerWidth + 1 || rect.left < -1) {
        overflowHits.push({
          tag: el.tagName,
          id: el.id,
          classes: el.className.toString().slice(0, 100),
          text: (el.textContent ?? '').trim().slice(0, 60),
          rect: { left: Math.round(rect.left), right: Math.round(rect.right), width: Math.round(rect.width) },
        });
        if (overflowHits.length >= 8) break;
      }
    }

    let tapTargetsTooSmall = 0;
    for (const btn of Array.from(document.querySelectorAll<HTMLElement>('button, a[href]'))) {
      const rect = btn.getBoundingClientRect();
      if (rect.width === 0 || rect.height === 0) continue;
      if (rect.width < 36 || rect.height < 36) {
        tapTargetsTooSmall += 1;
      }
    }

    return {
      documentScrollHeight: Math.max(body.scrollHeight, html.scrollHeight),
      bodyScrollWidth: Math.max(body.scrollWidth, html.scrollWidth),
      innerWidth: window.innerWidth,
      topbarBoundingTop,
      topbarPosition,
      overflowHits,
      tapTargetsTooSmall,
    };
  });

  await page.evaluate(() => {
    const main = document.querySelector<HTMLElement>('main#main-content, main.dashboard-main, main.dashboard-main-shell');
    if (main != null) {
      main.scrollTop = Math.min(main.scrollHeight, 400);
    }
    window.scrollTo(0, 400);
  });
  const topbarVisibleAfterScroll = await page.evaluate(() => {
    const topbar = document.querySelector<HTMLElement>('.topbar');
    if (topbar == null) return null;
    const rect = topbar.getBoundingClientRect();
    return rect.top >= -1 && rect.top <= 10;
  });

  return {
    slug,
    viewport,
    url,
    ...metrics,
    topbarVisibleAfterScroll,
  };
}

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  await fs.mkdir(OUT_DIR, { recursive: true });
});

test('chat composer toggle hides input while keeping conversation readable', async ({ browser }) => {
  const context = await browser.newContext({
    viewport: { width: 375, height: 667 },
    isMobile: true,
    hasTouch: true,
  });
  const page = await context.newPage();
  await installDashboardApiMocks(page);
  await page.goto('/', { waitUntil: 'networkidle' }).catch(() => {});
  await page.waitForTimeout(400);
  await page.getByRole('button', { name: 'Plan next step' }).click();

  await page.getByRole('button', { name: /Hide message composer/i }).click();
  await page.waitForTimeout(150);
  const shotCollapsed = path.join(OUT_DIR, 'chat-composer-collapsed.png');
  await page.screenshot({ path: shotCollapsed, fullPage: false });

  const collapsedState = await page.evaluate(() => {
    const layout = document.querySelector('.chat-page-layout');
    const conversation = document.querySelector('.chat-window');
    const form = document.querySelector('#chat-composer-form');
    const toggle = document.querySelector('.chat-composer-toggle');
    return {
      layoutHasComposerCollapsedClass: layout?.classList.contains('chat-page-layout--composer-collapsed') ?? false,
      conversationPresent: conversation != null,
      formPresent: form != null,
      togglePresent: toggle != null,
    };
  });

  if (!collapsedState.layoutHasComposerCollapsedClass) {
    throw new Error('chat-page-layout--composer-collapsed class should be applied after click');
  }
  if (!collapsedState.conversationPresent) {
    throw new Error('conversation should remain visible when composer is collapsed');
  }
  if (collapsedState.formPresent) {
    throw new Error('message composer form should be hidden when collapsed');
  }
  if (!collapsedState.togglePresent) {
    throw new Error('composer toggle should remain available when collapsed');
  }

  await context.close();
});

for (const viewport of VIEWPORTS) {
  test(`mobile audit @ ${viewport.label}`, async ({ browser }) => {
    test.setTimeout(120_000);
    const context = await browser.newContext({
      viewport: { width: viewport.width, height: viewport.height },
      isMobile: viewport.width < 768,
      hasTouch: viewport.width < 768,
    });
    const page = await context.newPage();
    await installDashboardApiMocks(page);

    const reports: AuditReport[] = [];
    for (const route of ROUTES) {
      await page.goto(route.url, { waitUntil: 'networkidle' }).catch(() => {});
      const report = await gatherAudit(page, route.slug, viewport.label, route.url);
      reports.push(report);
      const screenshotPath = path.join(OUT_DIR, `${viewport.label}-${route.slug}.png`);
      await page.screenshot({ path: screenshotPath, fullPage: false });
    }

    const reportPath = path.join(OUT_DIR, `report-${viewport.label}.json`);
    await fs.writeFile(reportPath, JSON.stringify(reports, null, 2));
    await context.close();
  });
}
