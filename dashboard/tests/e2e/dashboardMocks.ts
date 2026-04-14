import type { Page, Route } from '@playwright/test';
import {
  TEST_SESSION_ID,
  TEST_SESSION_RECORD_ID,
  mockHealthResponse,
  mockHiveStatus,
  mockLogsPage,
  mockModelsConfig,
  mockPluginMarketplace,
  mockPrompts,
  mockRuntimeConfig,
  mockSchedulerState,
  mockSettings,
  mockSkills,
  mockSystemDiagnostics,
  mockUpdateStatusResponse,
} from './dashboardTestData';

interface MockRouteResponse {
  status?: number;
  body: unknown;
}

function jsonResponse(route: Route, response: MockRouteResponse): Promise<void> {
  return route.fulfill({
    status: response.status ?? 200,
    contentType: 'application/json',
    body: JSON.stringify(response.body),
  });
}

function getApiPath(url: string): string {
  return new URL(url).pathname.replace(/^\/api/, '');
}

function buildActiveSession(): unknown {
  return {
    channelType: 'web',
    clientInstanceId: 'playwright-client',
    transportChatId: null,
    conversationKey: TEST_SESSION_ID,
    sessionId: TEST_SESSION_RECORD_ID,
    source: 'mock',
  };
}

function buildSessionSummary(): unknown {
  return {
    id: TEST_SESSION_RECORD_ID,
    conversationKey: TEST_SESSION_ID,
    title: 'Playwright session',
    preview: null,
    channelType: 'web',
    updatedAt: '2026-04-13T00:00:00Z',
    createdAt: '2026-04-13T00:00:00Z',
    messageCount: 0,
  };
}

function routeApiGet(path: string): MockRouteResponse {
  if (path === '/settings/runtime') {
    return { body: mockRuntimeConfig };
  }
  if (path === '/settings') {
    return { body: mockSettings };
  }
  if (path === '/models') {
    return { body: mockModelsConfig };
  }
  if (path === '/models/available') {
    return { body: { openai: [] } };
  }
  if (path === '/prompts') {
    return { body: mockPrompts };
  }
  if (path === '/plugins/settings/catalog') {
    return { body: [] };
  }
  if (path === '/plugins/marketplace') {
    return { body: mockPluginMarketplace };
  }
  if (path === '/auth/me') {
    return { body: { username: 'playwright', mfaEnabled: false } };
  }
  if (path === '/hive/status') {
    return { body: mockHiveStatus };
  }
  if (path === '/system/health') {
    return { body: mockHealthResponse };
  }
  if (path === '/system/update/status') {
    return { body: mockUpdateStatusResponse };
  }
  if (path === '/system/channels') {
    return { body: [] };
  }
  if (path === '/system/diagnostics') {
    return { body: mockSystemDiagnostics };
  }
  if (path === '/system/logs') {
    return { body: mockLogsPage };
  }
  if (path === '/files/tree') {
    return { body: [] };
  }
  if (path === '/files/content') {
    return { body: { path: 'README.md', content: '', size: 0, updatedAt: '2026-04-13T00:00:00Z' } };
  }
  if (path === '/goals') {
    return { body: { featureEnabled: true, autoModeEnabled: false, goals: [], standaloneTasks: [] } };
  }
  if (path === '/scheduler') {
    return { body: mockSchedulerState };
  }
  if (path === '/sessions') {
    return { body: [] };
  }
  if (path === '/sessions/recent') {
    return { body: [] };
  }
  if (path === '/sessions/active') {
    return { body: buildActiveSession() };
  }
  if (path === '/sessions/resolve') {
    return { body: buildSessionSummary() };
  }
  if (path === `/sessions/${TEST_SESSION_RECORD_ID}/messages`) {
    return { body: {
      messages: [],
      hasMore: false,
      oldestMessageId: null,
    } };
  }
  if (path === '/skills') {
    return { body: mockSkills };
  }
  if (path === '/skills/marketplace') {
    return { body: { available: true, message: null, sourceType: 'repository', sourceDirectory: null, items: [] } };
  }
  if (path === '/webhooks/deliveries') {
    return { body: { deliveries: [] } };
  }
  return { body: {} };
}

function routeApiPost(path: string): MockRouteResponse {
  if (path === '/sessions/active') {
    return { body: buildActiveSession() };
  }
  return { body: {} };
}

export async function installDashboardApiMocks(page: Page): Promise<void> {
  await page.addInitScript((sessionId: string) => {
    window.localStorage.setItem('auth-storage', JSON.stringify({
      state: { accessToken: 'playwright-token' },
      version: 0,
    }));
    window.localStorage.setItem('golem-chat-client-instance-id', 'playwright-client');
    window.localStorage.setItem('golem-chat-session-id', sessionId);
  }, TEST_SESSION_ID);

  await page.route('/ws/chat**', (route) => route.abort());
  await page.route('/ws/logs**', (route) => route.abort());
  await page.route('**/api/**', (route) => {
    const request = route.request();
    const path = getApiPath(request.url());
    const method = request.method();
    if (method === 'GET') {
      return jsonResponse(route, routeApiGet(path));
    }
    if (method === 'POST' || method === 'PUT' || method === 'DELETE') {
      return jsonResponse(route, routeApiPost(path));
    }
    return jsonResponse(route, { body: {} });
  });
}
