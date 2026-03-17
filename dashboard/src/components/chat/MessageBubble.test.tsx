import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import MessageBubble from './MessageBubble';

describe('MessageBubble', () => {
  it('renders a neutral fallback when assistant model metadata is missing', () => {
    const html = renderToStaticMarkup(
      <MessageBubble
        role="assistant"
        content="Final answer"
        model={null}
        tier={null}
      />,
    );

    expect(html).toContain('System reply');
    expect(html).not.toContain('Unknown tier');
    expect(html).not.toContain('Model unavailable');
  });

  it('renders assistant model and tier when live hints are present', () => {
    const html = renderToStaticMarkup(
      <MessageBubble
        role="assistant"
        content="Final answer"
        model="gemini-3.1-flash-lite-preview"
        tier="smart"
      />,
    );

    expect(html).toContain('gemini-3.1-flash-lite-preview');
    expect(html).toContain('Smart');
  });

  it('renders the skill badge only when skill metadata is present', () => {
    const htmlWithSkill = renderToStaticMarkup(
      <MessageBubble
        role="assistant"
        content="Final answer"
        model="openai/o3-mini"
        tier="smart"
        skill="reviewer-skill"
      />,
    );
    const htmlWithoutSkill = renderToStaticMarkup(
      <MessageBubble
        role="assistant"
        content="Final answer"
        model="openai/o3-mini"
        tier="smart"
        skill={null}
      />,
    );

    expect(htmlWithSkill).toContain('reviewer-skill');
    expect(htmlWithoutSkill).not.toContain('reviewer-skill');
  });

  it('prefers the formatted model label and hides the tier chip when tier is blank', () => {
    const html = renderToStaticMarkup(
      <MessageBubble
        role="assistant"
        content="Final answer"
        model="openai/o3-mini"
        tier=" "
        modelLabel="OpenAI o3 mini:high"
      />,
    );

    expect(html).toContain('OpenAI o3 mini:high');
    expect(html).not.toContain('assistant-tier-chip');
  });
});
