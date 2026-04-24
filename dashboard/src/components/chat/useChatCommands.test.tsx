import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { useChatCommands } from './useChatCommands';

function SuggestionsHarness({ text }: { text: string }) {
  const { suggestions } = useChatCommands(text, {}, false);
  return <>{suggestions.map((suggestion) => suggestion.label).join('|')}</>;
}

describe('useChatCommands', () => {
  it('does not suggest removed plan list command', () => {
    const html = renderToStaticMarkup(<SuggestionsHarness text="/pl" />);

    expect(html).toContain('/plan');
    expect(html).not.toContain('/plans');
  });

  it('does not suggest unsupported goal or task schedule creation', () => {
    const html = renderToStaticMarkup(<SuggestionsHarness text="/schedule " />);

    expect(html).toContain('list');
    expect(html).toContain('delete');
    expect(html).toContain('help');
    expect(html).not.toContain('goal');
    expect(html).not.toContain('task');
  });
});
