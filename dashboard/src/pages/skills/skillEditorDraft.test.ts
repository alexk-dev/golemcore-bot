import { describe, expect, it } from 'vitest';
import type { SkillInfo } from '../../api/skills';
import { buildSkillUpdateRequest, createSkillEditorDraft } from './skillEditorDraft';

function createSkillDetail(): SkillInfo {
  return {
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
  };
}

function expectParsedDraft(draft: ReturnType<typeof createSkillEditorDraft>): void {
  expect(draft.name).toBe('golemcore/code-reviewer');
  expect(draft.modelTier).toBe('coding');
  expect(draft.nextSkill).toBe('golemcore/review-summary');
  expect(draft.requirementEnv).toBe('GITHUB_TOKEN');
  expect(draft.requirementBinary).toBe('git');
  expect(draft.requirementSkills).toBe('golemcore/review-summary');
  expect(draft.conditionalNextSkills).toHaveLength(1);
  expect(draft.conditionalNextSkills[0]).toMatchObject({
    condition: 'success',
    skill: 'golemcore/review-summary',
  });
  expect(draft.variables).toHaveLength(1);
  expect(draft.variables[0]).toMatchObject({
    name: 'GITHUB_TOKEN',
    description: 'GitHub access token',
    defaultValue: 'local-token',
    required: true,
    secret: true,
  });
  expect(draft.mcpEnabled).toBe(true);
  expect(draft.mcpCommand).toBe('npx server');
  expect(draft.mcpStartupTimeout).toBe('30');
  expect(draft.mcpIdleTimeout).toBe('5');
  expect(draft.mcpEnv).toHaveLength(1);
  expect(draft.mcpEnv[0]).toMatchObject({
    key: 'TOKEN',
    value: '${GITHUB_TOKEN}',
  });
  expect(draft.extraMetadata).toEqual({
    custom_key: 'preserved',
  });
}

function expectStructuredMetadata(request: ReturnType<typeof buildSkillUpdateRequest>): void {
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
}

describe('skillEditorDraft', () => {
  it('parses supported metadata fields into editor draft state', () => {
    const draft = createSkillEditorDraft(createSkillDetail());
    expectParsedDraft(draft);
  });

  it('builds a structured update payload from supported metadata fields', () => {
    const draft = createSkillEditorDraft(createSkillDetail());

    const request = buildSkillUpdateRequest(draft);

    expectStructuredMetadata(request);
  });

  it('drops managed metadata sections when the editor clears them explicitly', () => {
    const draft = createSkillEditorDraft(createSkillDetail());

    draft.description = '';
    draft.modelTier = '';
    draft.requirementEnv = '';
    draft.requirementBinary = '';
    draft.requirementSkills = '';
    draft.variables = [];
    draft.mcpEnabled = false;
    draft.mcpCommand = '';
    draft.mcpStartupTimeout = '';
    draft.mcpIdleTimeout = '';
    draft.mcpEnv = [];
    draft.nextSkill = '';
    draft.conditionalNextSkills = [];

    const request = buildSkillUpdateRequest(draft);

    expect(request.metadata).toEqual({
      name: 'golemcore/code-reviewer',
      custom_key: 'preserved',
    });
  });
});
