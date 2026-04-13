/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../store/authStore';
import LoginPage from './LoginPage';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

vi.mock('../api/auth', () => ({
  exchangeHiveSsoCode: vi.fn(),
  getHiveSsoStatus: vi.fn(() => Promise.resolve({
    enabled: true,
    available: true,
    loginUrl: 'https://hive.example.com/api/v1/oauth2/authorize',
    reason: null,
  })),
  getMfaStatus: vi.fn(() => Promise.resolve({ mfaRequired: false })),
  login: vi.fn(),
}));

async function flushPromises(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

describe('LoginPage', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    window.localStorage.clear();
    window.sessionStorage.clear();
    useAuthStore.getState().setAccessToken(null);
  });

  it('keeps password fallback usable from Hive SSO login', async () => {
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root: Root = createRoot(container);

    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/login']}>
          <LoginPage />
        </MemoryRouter>,
      );
      await flushPromises();
    });

    expect(document.body.textContent ?? '').toContain('Continue with Hive SSO');
    const passwordInput = document.body.querySelector('input[type="password"]');
    expect(passwordInput).toBeInstanceOf(HTMLInputElement);
    const passwordFallbackButton = Array.from(document.body.querySelectorAll('button'))
      .find((button) => button.textContent === 'Use password instead');
    if (!(passwordFallbackButton instanceof HTMLButtonElement)) {
      throw new Error('Password fallback button not found');
    }

    await act(async () => {
      passwordFallbackButton.dispatchEvent(new MouseEvent('click', { bubbles: true }));
      await flushPromises();
    });

    expect(document.activeElement).toBe(passwordInput);
    expect(document.body.textContent ?? '').toContain('Login');
    expect(document.body.textContent ?? '').toContain('Use password instead');

    act(() => {
      root.unmount();
    });
    container.remove();
  });
});
