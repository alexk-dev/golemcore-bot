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
  const requirements = buildRequirementDraft(metadata);
  const mcp = buildMcpDraft(metadata);

  return {
    name: resolveDraftName(metadata, detail),
    description: resolveDraftDescription(metadata, detail),
    modelTier: trimToUndefined(asString(metadata.model_tier)) ?? '',
    nextSkill: trimToUndefined(asString(metadata.next_skill)) ?? '',
    conditionalNextSkills: parseConditionRows(metadata.conditional_next_skills),
    requirementEnv: requirements.env,
    requirementBinary: requirements.binary,
    requirementSkills: requirements.skills,
    variables: parseVariableRows(metadata.vars),
    mcpEnabled: mcp.enabled,
    mcpCommand: mcp.command,
    mcpStartupTimeout: mcp.startupTimeout,
    mcpIdleTimeout: mcp.idleTimeout,
    mcpEnv: mcp.env,
    content: detail.content ?? '',
    extraMetadata: extractExtraMetadata(metadata),
  };
}

export function buildSkillUpdateRequest(draft: SkillEditorDraft): SkillUpdateRequest {
  const metadata: Record<string, unknown> = {
    ...draft.extraMetadata,
  };

  assignIfPresent(metadata, 'name', trimToUndefined(draft.name));
  assignIfPresent(metadata, 'description', trimToUndefined(draft.description));
  assignIfPresent(metadata, 'model_tier', trimToUndefined(draft.modelTier));
  assignIfPresent(metadata, 'requires', buildRequiresMetadata(draft));
  assignIfPresent(metadata, 'vars', buildVariablesMetadata(draft.variables));
  assignIfPresent(metadata, 'mcp', buildMcpMetadata(draft));
  assignIfPresent(metadata, 'next_skill', trimToUndefined(draft.nextSkill));
  assignIfPresent(metadata, 'conditional_next_skills', buildConditionalNextSkillsMetadata(draft.conditionalNextSkills));

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

function resolveDraftName(metadata: Record<string, unknown>, detail: SkillInfo): string {
  return trimToUndefined(asString(metadata.name)) ?? detail.name;
}

function resolveDraftDescription(metadata: Record<string, unknown>, detail: SkillInfo): string {
  return trimToUndefined(asString(metadata.description)) ?? detail.description ?? '';
}

function buildRequirementDraft(metadata: Record<string, unknown>): {
  env: string;
  binary: string;
  skills: string;
} {
  const requirements = asRecord(metadata.requires);
  return {
    env: toLineList(requirements?.env),
    binary: toLineList(requirements?.binary),
    skills: toLineList(requirements?.skills),
  };
}

function buildMcpDraft(metadata: Record<string, unknown>): {
  enabled: boolean;
  command: string;
  startupTimeout: string;
  idleTimeout: string;
  env: SkillEnvEntryDraft[];
} {
  const mcp = asRecord(metadata.mcp);
  return {
    enabled: mcp != null,
    command: asString(mcp?.command),
    startupTimeout: mcp?.startup_timeout != null ? String(mcp.startup_timeout) : '',
    idleTimeout: mcp?.idle_timeout != null ? String(mcp.idle_timeout) : '',
    env: parseMcpEnvRows(mcp?.env),
  };
}

function extractExtraMetadata(metadata: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(metadata).filter(([key]) => !MANAGED_METADATA_KEYS.has(key)),
  );
}

function assignIfPresent(metadata: Record<string, unknown>, key: string, value: unknown): void {
  if (value != null) {
    metadata[key] = value;
  }
}

function buildRequiresMetadata(draft: SkillEditorDraft): Record<string, unknown> | null {
  const requirementEnv = splitLines(draft.requirementEnv);
  const requirementBinary = splitLines(draft.requirementBinary);
  const requirementSkills = splitLines(draft.requirementSkills);

  if (requirementEnv.length === 0 && requirementBinary.length === 0 && requirementSkills.length === 0) {
    return null;
  }

  const requires: Record<string, unknown> = {};
  assignIfPresent(requires, 'env', requirementEnv.length > 0 ? requirementEnv : null);
  assignIfPresent(requires, 'binary', requirementBinary.length > 0 ? requirementBinary : null);
  assignIfPresent(requires, 'skills', requirementSkills.length > 0 ? requirementSkills : null);
  return requires;
}

function buildVariablesMetadata(variables: SkillVariableDraft[]): Record<string, Record<string, unknown>> | null {
  const entries = variables
    .map((variable) => buildVariableEntry(variable))
    .filter((entry): entry is readonly [string, Record<string, unknown>] => entry != null);
  return entries.length > 0 ? Object.fromEntries(entries) : null;
}

function buildVariableEntry(variable: SkillVariableDraft): readonly [string, Record<string, unknown>] | null {
  const variableName = variable.name.trim();
  if (variableName.length === 0) {
    return null;
  }

  const definition: Record<string, unknown> = {};
  assignIfPresent(definition, 'description', trimToUndefined(variable.description));
  assignIfPresent(definition, 'default', trimToUndefined(variable.defaultValue));
  assignIfPresent(definition, 'required', variable.required ? true : null);
  assignIfPresent(definition, 'secret', variable.secret ? true : null);
  return [variableName, definition] as const;
}

function buildMcpMetadata(draft: SkillEditorDraft): Record<string, unknown> | null {
  const mcpCommand = trimToUndefined(draft.mcpCommand);
  const mcpEnv = buildMcpEnvMetadata(draft.mcpEnv);
  const mcpStartupTimeout = toInteger(draft.mcpStartupTimeout);
  const mcpIdleTimeout = toInteger(draft.mcpIdleTimeout);

  if (!draft.mcpEnabled && mcpCommand == null && mcpEnv == null && mcpStartupTimeout == null && mcpIdleTimeout == null) {
    return null;
  }

  const mcp: Record<string, unknown> = {};
  assignIfPresent(mcp, 'command', mcpCommand);
  assignIfPresent(mcp, 'env', mcpEnv);
  assignIfPresent(mcp, 'startup_timeout', mcpStartupTimeout);
  assignIfPresent(mcp, 'idle_timeout', mcpIdleTimeout);
  return mcp;
}

function buildMcpEnvMetadata(entries: SkillEnvEntryDraft[]): Record<string, string> | null {
  const mappedEntries = entries
    .map((entry) => {
      const key = entry.key.trim();
      if (key.length === 0) {
        return null;
      }
      return [key, entry.value] as const;
    })
    .filter((entry): entry is readonly [string, string] => entry != null);
  return mappedEntries.length > 0 ? Object.fromEntries(mappedEntries) : null;
}

function buildConditionalNextSkillsMetadata(entries: SkillConditionDraft[]): Record<string, string> | null {
  const mappedEntries = entries
    .map((entry) => {
      const condition = entry.condition.trim();
      const skill = entry.skill.trim();
      if (condition.length === 0 || skill.length === 0) {
        return null;
      }
      return [condition, skill] as const;
    })
    .filter((entry): entry is readonly [string, string] => entry != null);
  return mappedEntries.length > 0 ? Object.fromEntries(mappedEntries) : null;
}
