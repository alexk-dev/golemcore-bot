import { describe, expect, it } from 'vitest';

import type { AvailableModel } from '../../api/models';
import {
  normalizeModelFallbacks,
  resolveTemperatureAfterModelChange,
  toNullableModelFallbackString,
} from './modelFallbacksEditorSupport';

const providers: Record<string, AvailableModel[]> = {
  openai: [
    {
      id: 'openai/gpt-5.1',
      displayName: 'GPT-5.1',
      hasReasoning: true,
      reasoningLevels: ['none', 'medium'],
      supportsVision: true,
      supportsTemperature: true,
    },
    {
      id: 'openai/o3-reasoning',
      displayName: 'o3',
      hasReasoning: true,
      reasoningLevels: ['low', 'high'],
      supportsVision: false,
      supportsTemperature: false,
    },
  ],
};

describe('resolveTemperatureAfterModelChange', () => {
  it('keeps current temperature when switching to a model that supports temperature', () => {
    expect(resolveTemperatureAfterModelChange(0.4, 'gpt-5.1', 'openai', providers)).toBe(0.4);
  });

  it('clears temperature when switching to a model that does not support temperature', () => {
    expect(resolveTemperatureAfterModelChange(0.4, 'o3-reasoning', 'openai', providers)).toBeNull();
  });

  it('keeps current temperature when the new model cannot be resolved (e.g. unavailable provider)', () => {
    expect(resolveTemperatureAfterModelChange(0.4, 'claude-sonnet', 'anthropic', providers)).toBe(0.4);
  });

  it('keeps current temperature when model editor id is blank (not configured)', () => {
    expect(resolveTemperatureAfterModelChange(0.4, '', 'openai', providers)).toBe(0.4);
  });
});

describe('toNullableModelFallbackString', () => {
  it('returns null for empty input', () => {
    expect(toNullableModelFallbackString('')).toBeNull();
  });

  it('returns the string as-is when non-empty', () => {
    expect(toNullableModelFallbackString('medium')).toBe('medium');
  });
});

describe('normalizeModelFallbacks', () => {
  it('truncates to 5 entries', () => {
    const input = Array.from({ length: 7 }).map((_, i) => ({
      model: { provider: 'openai', id: `openai/m-${i}` },
      reasoning: null,
      temperature: null,
    }));

    expect(normalizeModelFallbacks(input)).toHaveLength(5);
  });
});
