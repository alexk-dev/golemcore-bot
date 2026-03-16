import toast from 'react-hot-toast';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PromptSection } from '../api/prompts';
import {
  performPromptReorder,
  requestPromptPreview,
  runPendingPromptAction,
} from './promptsWorkspaceCommands';

vi.mock('react-hot-toast', () => ({
  default: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

function createSection(overrides: Partial<PromptSection> = {}): PromptSection {
  return {
    name: 'custom',
    description: 'Custom section',
    order: 100,
    enabled: true,
    deletable: true,
    content: 'Prompt body',
    ...overrides,
  };
}

describe('promptsWorkspaceCommands', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('returns a preview error state when draft preview fails', async () => {
    const previewMutation = {
      mutateAsync: vi.fn().mockRejectedValue(new Error('preview exploded')),
    };

    await expect(
      requestPromptPreview({
        draft: {
          name: 'identity',
          description: 'Identity',
          order: 10,
          enabled: true,
          deletable: false,
          content: 'Hello {{BOT_NAME}}',
        },
        previewMutation,
      })
    ).resolves.toEqual({
      preview: '',
      previewError: 'preview exploded',
    });
  });

  it('reselects the active prompt after a successful reorder', async () => {
    const reorderMutation = {
      mutateAsync: vi.fn().mockResolvedValue(undefined),
    };
    const selectPrompt = vi.fn();
    const sections = [
      createSection({ name: 'identity', order: 10, deletable: false }),
      createSection({ name: 'rules', order: 20, deletable: false }),
      createSection({ name: 'custom', order: 30 }),
    ];

    await performPromptReorder({
      sourceName: 'custom',
      targetName: 'identity',
      sections,
      selectedName: 'rules',
      reorderMutation,
      selectPrompt,
    });

    expect(reorderMutation.mutateAsync).toHaveBeenCalledTimes(1);
    expect(selectPrompt).toHaveBeenCalledWith(expect.objectContaining({ name: 'rules', order: 30 }));
    expect(toast.success).toHaveBeenCalledWith('Prompt priorities updated');
  });

  it('routes pending reorder actions through the reorder callback', async () => {
    const requestCreate = vi.fn().mockResolvedValue(true);
    const requestReorder = vi.fn().mockResolvedValue(undefined);
    const selectPrompt = vi.fn();

    await runPendingPromptAction({
      pendingAction: {
        type: 'reorder',
        name: 'custom',
        targetName: 'identity',
      },
      sections: [createSection({ name: 'custom' }), createSection({ name: 'identity' })],
      requestCreate,
      requestReorder,
      selectPrompt,
    });

    expect(requestReorder).toHaveBeenCalledWith('custom', 'identity');
    expect(requestCreate).not.toHaveBeenCalled();
    expect(selectPrompt).not.toHaveBeenCalled();
  });
});
