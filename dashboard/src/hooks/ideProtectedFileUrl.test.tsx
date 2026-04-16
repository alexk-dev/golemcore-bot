/* @vitest-environment jsdom */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, type ReactElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { useProtectedFileObjectUrl } from './useProtectedFileObjectUrl';
import { fetchProtectedFileObjectUrl } from '../api/files';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

vi.mock('../api/files', () => ({
  fetchProtectedFileObjectUrl: vi.fn(),
}));

function HookProbe({ path }: { path: string | null }): ReactElement {
  const state = useProtectedFileObjectUrl(path);
  return <span data-url={state.objectUrl ?? ''} data-loading={String(state.loading)} data-error={String(state.error)} />;
}

function renderProbe(path: string | null): { container: HTMLDivElement; root: Root } {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);
  act(() => {
    root.render(<HookProbe path={path} />);
  });
  return { container, root };
}

async function flushPromises(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
  });
}

describe('useProtectedFileObjectUrl', () => {
  afterEach(() => {
    document.body.innerHTML = '';
    vi.clearAllMocks();
  });

  it('fetches a protected object URL and revokes it on cleanup', async () => {
    const revoke = vi.fn();
    vi.mocked(fetchProtectedFileObjectUrl).mockResolvedValue({ objectUrl: 'blob:test', revoke });

    const rendered = renderProbe('images/logo.png');
    await flushPromises();

    expect(fetchProtectedFileObjectUrl).toHaveBeenCalledWith('images/logo.png');
    expect(rendered.container.querySelector('span')?.getAttribute('data-url')).toBe('blob:test');

    act(() => {
      rendered.root.unmount();
    });

    expect(revoke).toHaveBeenCalledTimes(1);
  });

  it('does not fetch when no path is provided', () => {
    renderProbe(null);

    expect(fetchProtectedFileObjectUrl).not.toHaveBeenCalled();
  });
});
