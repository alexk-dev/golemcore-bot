import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import StatusPill from './StatusPill';

describe('StatusPill', () => {
  it('applies the requested tone class', () => {
    const html = renderToStaticMarkup(<StatusPill tone="success">Connected</StatusPill>);
    expect(html).toContain('agent-pill--success');
    expect(html).toContain('Connected');
  });

  it('renders a status dot when showDot is true', () => {
    const html = renderToStaticMarkup(<StatusPill showDot tone="warning">Reconnecting</StatusPill>);
    expect(html).toContain('agent-pill__dot');
  });

  it('omits the dot by default', () => {
    const html = renderToStaticMarkup(<StatusPill>Idle</StatusPill>);
    expect(html).not.toContain('agent-pill__dot');
  });
});
