/* @vitest-environment jsdom */

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import {
  createEmptyWebhookMapping,
  JSON_SCHEMA_DRAFT_2020_12_DOCS_URL,
  type HookMappingDraft,
} from '../../api/webhooks';
import { HookAgentSection } from './HookMappingAgentSection';

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean | undefined;
}

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

interface RenderResult {
  container: HTMLDivElement;
  root: Root;
}

function renderSection(mapping: HookMappingDraft): RenderResult {
  const container = document.createElement('div');
  document.body.appendChild(container);
  const root = createRoot(container);

  act(() => {
    root.render(
      <HookAgentSection
        mapping={mapping}
        onChange={() => undefined}
        availableChannels={[]}
        channelsLoading={false}
      />,
    );
  });

  return { container, root };
}

function schemaResponseTierSelect(container: HTMLElement): HTMLSelectElement {
  const selects = Array.from(container.querySelectorAll('select'));
  const select = selects[2];
  if (!(select instanceof HTMLSelectElement)) {
    throw new Error('Schema response tier select not found');
  }
  return select;
}

describe('HookAgentSection', () => {
  it('renders the official JSON Schema Draft 2020-12 documentation link', () => {
    const html = renderToStaticMarkup(
      <HookAgentSection
        mapping={{
          ...createEmptyWebhookMapping(),
          name: 'sync-hook',
          action: 'agent',
          syncResponse: true,
        }}
        onChange={() => undefined}
        availableChannels={[]}
        channelsLoading={false}
      />,
    );

    expect(html).toContain(JSON_SCHEMA_DRAFT_2020_12_DOCS_URL);
    expect(html).toContain('JSON Schema Draft 2020-12');
  });

  it('keeps schema response tier disabled until a response JSON Schema is configured', () => {
    const withoutSchema = renderSection({
      ...createEmptyWebhookMapping(),
      name: 'sync-hook',
      action: 'agent',
      syncResponse: true,
      responseValidationModelTier: 'special5',
    });

    expect(schemaResponseTierSelect(withoutSchema.container).disabled).toBe(true);

    act(() => {
      withoutSchema.root.unmount();
    });
    withoutSchema.container.remove();

    const withSchema = renderSection({
      ...createEmptyWebhookMapping(),
      name: 'sync-hook',
      action: 'agent',
      syncResponse: true,
      responseJsonSchema: '{"type":"object"}',
    });

    expect(schemaResponseTierSelect(withSchema.container).disabled).toBe(false);

    act(() => {
      withSchema.root.unmount();
    });
    withSchema.container.remove();
  });
});
