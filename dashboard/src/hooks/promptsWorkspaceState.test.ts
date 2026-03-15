import { describe, expect, it } from 'vitest';
import type { PromptSection } from '../api/prompts';
import {
  INITIAL_PROMPTS_WORKSPACE_STATE,
  promptsWorkspaceReducer,
} from './promptsWorkspaceState';

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

describe('promptsWorkspaceState', () => {
  it('selects a prompt and resets transient dialog state', () => {
    const previousState = {
      ...INITIAL_PROMPTS_WORKSPACE_STATE,
      selectedName: 'rules',
      preview: 'stale preview',
      previewError: 'stale error',
      pendingAction: { type: 'create' as const, name: 'custom-next' },
      isUnsavedDialogOpen: true,
      isDeleteDialogOpen: true,
    };

    expect(
      promptsWorkspaceReducer(previousState, {
        type: 'selectPrompt',
        section: createSection({ name: 'identity', deletable: false }),
      })
    ).toEqual({
      ...INITIAL_PROMPTS_WORKSPACE_STATE,
      selectedName: 'identity',
      draft: {
        name: 'identity',
        description: 'Custom section',
        order: 100,
        enabled: true,
        deletable: false,
        content: 'Prompt body',
      },
    });
  });

  it('opens and closes the unsaved dialog without mutating the draft', () => {
    const baseState = promptsWorkspaceReducer(INITIAL_PROMPTS_WORKSPACE_STATE, {
      type: 'selectPrompt',
      section: createSection(),
    });

    const openedState = promptsWorkspaceReducer(baseState, {
      type: 'openUnsavedDialog',
      pendingAction: { type: 'reorder', name: 'custom', targetName: 'rules' },
    });

    expect(openedState.isUnsavedDialogOpen).toBe(true);
    expect(openedState.pendingAction).toEqual({
      type: 'reorder',
      name: 'custom',
      targetName: 'rules',
    });

    expect(promptsWorkspaceReducer(openedState, { type: 'closeUnsavedDialog' })).toEqual({
      ...baseState,
      pendingAction: null,
      isUnsavedDialogOpen: false,
    });
  });

  it('clears the editor only when clearEditor is dispatched', () => {
    const populatedState = promptsWorkspaceReducer(INITIAL_PROMPTS_WORKSPACE_STATE, {
      type: 'selectPrompt',
      section: createSection(),
    });

    expect(promptsWorkspaceReducer(populatedState, { type: 'markCreateHandled' }).createResetToken).toBe(1);
    expect(promptsWorkspaceReducer(populatedState, { type: 'clearEditor' })).toEqual(
      INITIAL_PROMPTS_WORKSPACE_STATE
    );
  });
});
