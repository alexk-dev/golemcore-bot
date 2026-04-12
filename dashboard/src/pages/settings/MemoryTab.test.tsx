import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { MemoryConfig, MemoryPreset } from '../../api/settingsTypes';
import MemoryTab from './MemoryTab';

const memoryConfig: MemoryConfig = {
  enabled: true,
  softPromptBudgetTokens: 1800,
  maxPromptBudgetTokens: 3500,
  workingTopK: 6,
  episodicTopK: 8,
  semanticTopK: 6,
  proceduralTopK: 4,
  promotionEnabled: true,
  promotionMinConfidence: 0.75,
  decayEnabled: true,
  decayDays: 30,
  retrievalLookbackDays: 21,
  codeAwareExtractionEnabled: true,
};

const memoryPresets: MemoryPreset[] = [
  {
    id: 'coding_balanced',
    label: 'Coding Balanced',
    comment: 'Balanced coding profile',
    memory: memoryConfig,
  },
];

vi.mock('../../hooks/useSettings', () => ({
  useMemoryPresets: () => ({
    data: memoryPresets,
    isLoading: false,
  }),
  useUpdateMemory: () => ({
    mutateAsync: vi.fn(),
    isPending: false,
  }),
}));

vi.mock('react-hot-toast', () => ({
  default: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

describe('MemoryTab', () => {
  it('renders disclosure mode and prompt style controls', () => {
    const html = renderToStaticMarkup(<MemoryTab config={memoryConfig} />);

    expect(html).toContain('Disclosure mode');
    expect(html).toContain('Prompt style');
    expect(html).toContain('Tool expansion');
    expect(html).toContain('Diagnostics verbosity');
  });
});
