import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import IncidentCard from './IncidentCard';
import type { IncidentViewModel } from './types';

const incident: IncidentViewModel = {
  id: 'inc-1',
  runId: 'run-1',
  severity: 'error',
  title: 'LLM provider is temporarily unavailable',
  message: 'Your task has been saved and will retry automatically in 5 minutes.',
  code: 'llm.provider.circuit.open',
  createdAt: '2026-04-29T10:43:00.000Z',
  retryCountdownSeconds: 299,
  taskSaved: true,
  actions: [
    { id: 'retry_now', label: 'Retry now', kind: 'primary' },
    { id: 'switch_model', label: 'Switch model', kind: 'secondary' },
    { id: 'continue_manually', label: 'Continue manually', kind: 'secondary' },
    { id: 'open_logs', label: 'Open logs', kind: 'secondary' },
  ],
};

describe('IncidentCard', () => {
  it('renders the title, message and code', () => {
    const html = renderToStaticMarkup(<IncidentCard incident={incident} />);
    expect(html).toContain('LLM provider is temporarily unavailable');
    expect(html).toContain('llm.provider.circuit.open');
    expect(html).toContain('Task saved automatically');
  });

  it('renders all recovery actions', () => {
    const html = renderToStaticMarkup(<IncidentCard incident={incident} />);
    expect(html).toContain('Retry now');
    expect(html).toContain('Switch model');
    expect(html).toContain('Continue manually');
    expect(html).toContain('Open logs');
  });
});
