import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ProviderNameCombobox } from './ProviderNameCombobox';

describe('ProviderNameCombobox', () => {
  it('renders a combobox that keeps freeform input support', () => {
    const html = renderToStaticMarkup(
      <ProviderNameCombobox
        value="open"
        suggestions={['openai', 'openrouter', 'anthropic']}
        placeholder="Provider name"
        onValueChange={vi.fn()}
        onSubmit={vi.fn()}
      />,
    );

    expect(html).toContain('role="combobox"');
    expect(html).toContain('aria-autocomplete="list"');
    expect(html).toContain('Provider name');
  });
});
