import type { SkillInfo, SkillUpdateRequest } from '../../api/skills';

export interface SkillVariableDraft {
  id: string;
  name: string;
  description: string;
  defaultValue: string;
  required: boolean;
  secret: boolean;
}

export interface SkillConditionDraft {
  id: string;
  condition: string;
  skill: string;
}

export interface SkillEnvEntryDraft {
  id: string;
  key: string;
  value: string;
}

export interface SkillEditorDraft {
  name: string;
  description: string;
  modelTier: string;
  nextSkill: string;
  conditionalNextSkills: SkillConditionDraft[];
  requirementEnv: string;
  requirementBinary: string;
  requirementSkills: string;
  variables: SkillVariableDraft[];
  mcpEnabled: boolean;
  mcpCommand: string;
  mcpStartupTimeout: string;
  mcpIdleTimeout: string;
  mcpEnv: SkillEnvEntryDraft[];
  content: string;
  extraMetadata: Record<string, unknown>;
}

const MANAGED_METADATA_KEYS = new Set([
  'name',
  'description',
  'model_tier',
  'requires',
  'vars',
  'mcp',
  'next_skill',
  'conditional_next_skills',
]);

let nextDraftId = 0;

function createDraftId(): string {
  nextDraftId += 1;
  return `draft-${nextDraftId}`;
}

function asRecord(value: unknown): Record<string, unknown> | null {
  if (value == null || Array.isArray(value) || typeof value !== 'object') {
    return null;
  }
  return value as Record<string, unknown>;
}

function asString(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function toLineList(value: unknown): string {
  if (!Array.isArray(value)) {
    return '';
  }
  return value
    .filter((entry): entry is string => typeof entry === 'string' && entry.trim().length > 0)
    .join('\n');
}

function parseConditionRows(value: unknown): SkillConditionDraft[] {
  const conditionMap = asRecord(value);
  if (conditionMap == null) {
    return [];
  }
  return Object.entries(conditionMap).map(([condition, skill]) => ({
    id: createDraftId(),
    condition,
    skill: asString(skill),
  }));
}

function parseVariableRows(value: unknown): SkillVariableDraft[] {
  const varsMap = asRecord(value);
  if (varsMap == null) {
    return [];
  }
  return Object.entries(varsMap).map(([name, definition]) => {
    const rawDefinition = asRecord(definition);
    if (rawDefinition == null) {
      return {
        id: createDraftId(),
        name,
        description: '',
        defaultValue: asString(definition),
        required: false,
        secret: false,
      };
    }
    return {
      id: createDraftId(),
      name,
      description: asString(rawDefinition.description),
      defaultValue: asString(rawDefinition.default),
      required: rawDefinition.required === true,
      secret: rawDefinition.secret === true,
    };
  });
}

function parseMcpEnvRows(value: unknown): SkillEnvEntryDraft[] {
  const envMap = asRecord(value);
  if (envMap == null) {
    return [];
  }
  return Object.entries(envMap).map(([key, entryValue]) => ({
    id: createDraftId(),
    key,
    value: asString(entryValue),
  }));
}

function splitLines(value: string): string[] {
  return value
    .split('\n')
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0);
}

function toInteger(value: string): number | null {
  const normalized = value.trim();
  if (normalized.length === 0) {
    return null;
  }
  const parsed = Number.parseInt(normalized, 10);
  return Number.isNaN(parsed) ? null : parsed;
}

function trimToUndefined(value: string): string | undefined {
  const normalized = value.trim();
  return normalized.length > 0 ? normalized : undefined;
}

export function createSkillEditorDraft(detail: SkillInfo): SkillEditorDraft {
  const metadata = asRecord(detail.metadata) ?? {};
  const requirements = asRecord(metadata.requires);
  const mcp = asRecord(metadata.mcp);
  const nextSkill = trimToUndefined(asString(metadata.next_skill)) ?? '';

  const extraMetadata = Object.fromEntries(
    Object.entries(metadata).filter(([key]) => !MANAGED_METADATA_KEYS.has(key)),
  );

  return {
    name: trimToUndefined(asString(metadata.name)) ?? detail.name,
    description: asString(metadata.description) || detail.description || '',
    modelTier: trimToUndefined(asString(metadata.model_tier)) ?? '',
    nextSkill,
    conditionalNextSkills: parseConditionRows(metadata.conditional_next_skills),
    requirementEnv: toLineList(requirements?.env),
    requirementBinary: toLineList(requirements?.binary),
    requirementSkills: toLineList(requirements?.skills),
    variables: parseVariableRows(metadata.vars),
    mcpEnabled: mcp != null,
    mcpCommand: asString(mcp?.command),
    mcpStartupTimeout: mcp?.startup_timeout != null ? String(mcp.startup_timeout) : '',
    mcpIdleTimeout: mcp?.idle_timeout != null ? String(mcp.idle_timeout) : '',
    mcpEnv: parseMcpEnvRows(mcp?.env),
    content: detail.content ?? '',
    extraMetadata,
  };
}

export function buildSkillUpdateRequest(draft: SkillEditorDraft): SkillUpdateRequest {
  const metadata: Record<string, unknown> = {
    ...draft.extraMetadata,
  };

  const name = trimToUndefined(draft.name);
  if (name != null) {
    metadata.name = name;
  }

  const description = trimToUndefined(draft.description);
  if (description != null) {
    metadata.description = description;
  }

  const modelTier = trimToUndefined(draft.modelTier);
  if (modelTier != null) {
    metadata.model_tier = modelTier;
  }

  const requirementEnv = splitLines(draft.requirementEnv);
  const requirementBinary = splitLines(draft.requirementBinary);
  const requirementSkills = splitLines(draft.requirementSkills);
  if (requirementEnv.length > 0 || requirementBinary.length > 0 || requirementSkills.length > 0) {
    const requires: Record<string, unknown> = {};
    if (requirementEnv.length > 0) {
      requires.env = requirementEnv;
    }
    if (requirementBinary.length > 0) {
      requires.binary = requirementBinary;
    }
    if (requirementSkills.length > 0) {
      requires.skills = requirementSkills;
    }
    metadata.requires = requires;
  }

  const variables = Object.fromEntries(
    draft.variables
      .map((variable) => {
        const variableName = variable.name.trim();
        if (variableName.length === 0) {
          return null;
        }
        const definition: Record<string, unknown> = {};
        const descriptionValue = trimToUndefined(variable.description);
        if (descriptionValue != null) {
          definition.description = descriptionValue;
        }
        const defaultValue = trimToUndefined(variable.defaultValue);
        if (defaultValue != null) {
          definition.default = defaultValue;
        }
        if (variable.required) {
          definition.required = true;
        }
        if (variable.secret) {
          definition.secret = true;
        }
        return [variableName, definition] as const;
      })
      .filter((entry): entry is readonly [string, Record<string, unknown>] => entry != null),
  );
  if (Object.keys(variables).length > 0) {
    metadata.vars = variables;
  }

  const mcpCommand = trimToUndefined(draft.mcpCommand);
  const mcpEnv = Object.fromEntries(
    draft.mcpEnv
      .map((entry) => {
        const key = entry.key.trim();
        if (key.length === 0) {
          return null;
        }
        return [key, entry.value] as const;
      })
      .filter((entry): entry is readonly [string, string] => entry != null),
  );
  const mcpStartupTimeout = toInteger(draft.mcpStartupTimeout);
  const mcpIdleTimeout = toInteger(draft.mcpIdleTimeout);
  if (draft.mcpEnabled || mcpCommand != null || Object.keys(mcpEnv).length > 0 || mcpStartupTimeout != null || mcpIdleTimeout != null) {
    const mcp: Record<string, unknown> = {};
    if (mcpCommand != null) {
      mcp.command = mcpCommand;
    }
    if (Object.keys(mcpEnv).length > 0) {
      mcp.env = mcpEnv;
    }
    if (mcpStartupTimeout != null) {
      mcp.startup_timeout = mcpStartupTimeout;
    }
    if (mcpIdleTimeout != null) {
      mcp.idle_timeout = mcpIdleTimeout;
    }
    metadata.mcp = mcp;
  }

  const nextSkill = trimToUndefined(draft.nextSkill);
  if (nextSkill != null) {
    metadata.next_skill = nextSkill;
  }

  const conditionalNextSkills = Object.fromEntries(
    draft.conditionalNextSkills
      .map((entry) => {
        const condition = entry.condition.trim();
        const skill = entry.skill.trim();
        if (condition.length === 0 || skill.length === 0) {
          return null;
        }
        return [condition, skill] as const;
      })
      .filter((entry): entry is readonly [string, string] => entry != null),
  );
  if (Object.keys(conditionalNextSkills).length > 0) {
    metadata.conditional_next_skills = conditionalNextSkills;
  }

  return {
    content: draft.content,
    metadata,
  };
}

export function serializeSkillUpdateRequest(request: SkillUpdateRequest): string {
  return JSON.stringify(request);
}

export function createEmptyVariableDraft(): SkillVariableDraft {
  return {
    id: createDraftId(),
    name: '',
    description: '',
    defaultValue: '',
    required: false,
    secret: false,
  };
}

export function createEmptyConditionDraft(): SkillConditionDraft {
  return {
    id: createDraftId(),
    condition: '',
    skill: '',
  };
}

export function createEmptyMcpEnvDraft(): SkillEnvEntryDraft {
  return {
    id: createDraftId(),
    key: '',
    value: '',
  };
}
