import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { AutocompleteCombobox } from './AutocompleteCombobox';

describe('AutocompleteCombobox', () => {
  it('renders a reusable tailwind-style combobox shell with freeform input support', () => {
    const html = renderToStaticMarkup(
      <AutocompleteCombobox
        value="open"
        suggestions={['openai', 'openrouter', 'anthropic']}
        placeholder="Provider name"
        onValueChange={vi.fn()}
        onSubmit={vi.fn()}
      />,
    );

    expect(html).toContain('autocomplete-combobox');
    expect(html).toContain('autocomplete-combobox__control');
    expect(html).toContain('autocomplete-combobox__trigger');
    expect(html).toContain('role="combobox"');
    expect(html).toContain('aria-autocomplete="list"');
    expect(html).toContain('Toggle suggestions');
    expect(html).toContain('Provider name');
  });
});
