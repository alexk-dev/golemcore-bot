import { describe, expect, it } from 'vitest';
import { buildSkillUpdateRequest, createSkillEditorDraft } from './skillEditorDraft';

describe('skillEditorDraft', () => {
  it('builds a structured update payload from supported metadata fields', () => {
    const draft = createSkillEditorDraft({
      name: 'golemcore/code-reviewer',
      description: 'Review code',
      available: true,
      hasMcp: true,
      content: 'Review every diff.',
      metadata: {
        name: 'golemcore/code-reviewer',
        description: 'Review code',
        model_tier: 'coding',
        requires: {
          env: ['GITHUB_TOKEN'],
          binary: ['git'],
          skills: ['golemcore/review-summary'],
        },
        vars: {
          GITHUB_TOKEN: {
            description: 'GitHub access token',
            default: 'local-token',
            required: true,
            secret: true,
          },
        },
        mcp: {
          command: 'npx server',
          env: {
            TOKEN: '${GITHUB_TOKEN}',
          },
          startup_timeout: 30,
          idle_timeout: 5,
        },
        next_skill: 'golemcore/review-summary',
        conditional_next_skills: {
          success: 'golemcore/review-summary',
        },
        custom_key: 'preserved',
      },
    });

    const request = buildSkillUpdateRequest(draft);

    expect(request.content).toBe('Review every diff.');
    expect(request.metadata).toEqual({
      name: 'golemcore/code-reviewer',
      description: 'Review code',
      model_tier: 'coding',
      requires: {
        env: ['GITHUB_TOKEN'],
        binary: ['git'],
        skills: ['golemcore/review-summary'],
      },
      vars: {
        GITHUB_TOKEN: {
          description: 'GitHub access token',
          default: 'local-token',
          required: true,
          secret: true,
        },
      },
      mcp: {
        command: 'npx server',
        env: {
          TOKEN: '${GITHUB_TOKEN}',
        },
        startup_timeout: 30,
        idle_timeout: 5,
      },
      next_skill: 'golemcore/review-summary',
      conditional_next_skills: {
        success: 'golemcore/review-summary',
      },
      custom_key: 'preserved',
    });
  });
});
