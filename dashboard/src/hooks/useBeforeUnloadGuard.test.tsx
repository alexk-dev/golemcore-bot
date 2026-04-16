/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useBeforeUnloadGuard } from './useBeforeUnloadGuard';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

function GuardHarness({ isDirty }: { isDirty: boolean }): null {
  useBeforeUnloadGuard(isDirty);
  return null;
}

describe('useBeforeUnloadGuard', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('calls preventDefault on beforeunload when dirty', () => {
    const addSpy = vi.spyOn(window, 'addEventListener');
    const removeSpy = vi.spyOn(window, 'removeEventListener');
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);

    act(() => { root.render(<GuardHarness isDirty />); });

    const registered = addSpy.mock.calls.find(([eventName]) => eventName === 'beforeunload');
    expect(registered).toBeDefined();

    const handler = registered?.[1] as EventListener;
    const evt = new Event('beforeunload', { cancelable: true });
    const preventSpy = vi.spyOn(evt, 'preventDefault');
    handler(evt);
    expect(preventSpy).toHaveBeenCalledOnce();

    act(() => { root.unmount(); });
    expect(removeSpy.mock.calls.some(([eventName]) => eventName === 'beforeunload')).toBe(true);

    addSpy.mockRestore();
    removeSpy.mockRestore();
  });

  it('does not attach the listener when not dirty', () => {
    const addSpy = vi.spyOn(window, 'addEventListener');
    const container = document.createElement('div');
    document.body.appendChild(container);
    const root = createRoot(container);

    act(() => { root.render(<GuardHarness isDirty={false} />); });

    expect(addSpy.mock.calls.some(([eventName]) => eventName === 'beforeunload')).toBe(false);

    act(() => { root.unmount(); });
    addSpy.mockRestore();
  });
});
