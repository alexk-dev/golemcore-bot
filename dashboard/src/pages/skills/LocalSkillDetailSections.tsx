import type { ReactElement } from 'react';
import {
  FiCpu,
  FiGitBranch,
  FiKey,
  FiPlus,
  FiSave,
  FiSettings,
  FiTrash2,
} from 'react-icons/fi';
import type { SkillInfo } from '../../api/skills';
import HelpTip from '../../components/common/HelpTip';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Input, Select, Textarea } from '../../components/ui/field';
import {
  createEmptyConditionDraft,
  createEmptyMcpEnvDraft,
  createEmptyVariableDraft,
  type SkillEditorDraft,
} from './skillEditorDraft';
import {
  ConditionRow,
  FieldStack,
  KeyValueRow,
  SectionCard,
  Toggle,
  VariableRow,
} from './LocalSkillsPanelShared';
import { MODEL_TIER_OPTIONS } from './LocalSkillsPanelConstants';

type UpdateSkillDraft = (updater: (current: SkillEditorDraft) => SkillEditorDraft) => void;

interface SkillSectionProps {
  draft: SkillEditorDraft;
  updateDraft: UpdateSkillDraft;
}

export function SkillIdentitySection({ draft, updateDraft }: SkillSectionProps): ReactElement {
  return (
    <SectionCard
      title="Identity"
      description="Canonical skill metadata that drives discovery, summaries, and model routing."
      icon={<FiCpu size={16} />}
    >
      <div className="grid gap-4 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,0.8fr)]">
        <FieldStack label="Skill name" hint="Supports namespaced runtime names for installed artifacts.">
          <Input
            value={draft.name}
            onChange={(event) => updateDraft((current) => ({ ...current, name: event.target.value }))}
            placeholder="golemcore/code-reviewer"
            autoCapitalize="off"
            autoCorrect="off"
            spellCheck={false}
          />
        </FieldStack>
        <FieldStack label="Model tier" hint="Optional tier override for this skill.">
          <Select
            value={draft.modelTier}
            onChange={(event) => updateDraft((current) => ({ ...current, modelTier: event.target.value }))}
          >
            {MODEL_TIER_OPTIONS.map((option) => (
              <option key={option.value || 'default'} value={option.value}>
                {option.label}
              </option>
            ))}
          </Select>
        </FieldStack>
      </div>
      <FieldStack label="Description">
        <Textarea
          rows={3}
          value={draft.description}
          onChange={(event) => updateDraft((current) => ({ ...current, description: event.target.value }))}
          placeholder="Summarize what the skill does and when it should be used."
        />
      </FieldStack>
    </SectionCard>
  );
}

export function SkillRequirementsSection({ draft, updateDraft }: SkillSectionProps): ReactElement {
  return (
    <SectionCard
      title="Requirements"
      description="One entry per line. These fields stay in frontmatter as `requires.env`, `requires.binary`, and `requires.skills`."
      icon={<FiSettings size={16} />}
    >
      <FieldStack
        label={<>Required environment variables <HelpTip text="One variable name per line. Use only env variable keys such as GITHUB_TOKEN or OPENAI_API_KEY." /></>}
      >
        <Textarea
          rows={4}
          value={draft.requirementEnv}
          onChange={(event) => updateDraft((current) => ({ ...current, requirementEnv: event.target.value }))}
        />
      </FieldStack>
      <FieldStack
        label={<>Required binaries <HelpTip text="One executable per line. Use command names that must exist in PATH, for example git, node, or docker." /></>}
      >
        <Textarea
          rows={4}
          value={draft.requirementBinary}
          onChange={(event) => updateDraft((current) => ({ ...current, requirementBinary: event.target.value }))}
        />
      </FieldStack>
      <FieldStack
        label={<>Required skills <HelpTip text="One skill runtime name per line. Use the exact installed skill id, for example golemcore/review-summary." /></>}
      >
        <Textarea
          rows={4}
          value={draft.requirementSkills}
          onChange={(event) => updateDraft((current) => ({ ...current, requirementSkills: event.target.value }))}
        />
      </FieldStack>
    </SectionCard>
  );
}

export function SkillSequencesSection({ draft, updateDraft }: SkillSectionProps): ReactElement {
  return (
    <SectionCard
      title="Sequences"
      description="Configure automatic and conditional skill transitions using `next_skill` and `conditional_next_skills`."
      icon={<FiGitBranch size={16} />}
    >
      <FieldStack label="Default next skill" hint="Runs when the skill completes and no explicit transition happens.">
        <Input
          value={draft.nextSkill}
          onChange={(event) => updateDraft((current) => ({ ...current, nextSkill: event.target.value }))}
        />
      </FieldStack>
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <div>
            <div className="text-sm font-medium text-foreground">Conditional transitions</div>
            <div className="text-xs leading-5 text-muted-foreground">Map conditions to target skills.</div>
          </div>
          <Button
            type="button"
            size="sm"
            variant="secondary"
            onClick={() => updateDraft((current) => ({
              ...current,
              conditionalNextSkills: [...current.conditionalNextSkills, createEmptyConditionDraft()],
            }))}
          >
            <FiPlus size={14} />
            Add condition
          </Button>
        </div>
        {draft.conditionalNextSkills.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-border/70 bg-card/40 px-4 py-5 text-sm text-muted-foreground">
            No conditional transitions configured.
          </div>
        ) : (
          <div className="space-y-3">
            {draft.conditionalNextSkills.map((row) => (
              <ConditionRow
                key={row.id}
                row={row}
                onChange={(nextRow) =>
                  updateDraft((current) => ({
                    ...current,
                    conditionalNextSkills: current.conditionalNextSkills.map((entry) => (entry.id === row.id ? nextRow : entry)),
                  }))
                }
                onRemove={() =>
                  updateDraft((current) => ({
                    ...current,
                    conditionalNextSkills: current.conditionalNextSkills.filter((entry) => entry.id !== row.id),
                  }))
                }
              />
            ))}
          </div>
        )}
      </div>
    </SectionCard>
  );
}

export function SkillVariablesSection({
  draft,
  detail,
  updateDraft,
}: SkillSectionProps & { detail: SkillInfo }): ReactElement {
  return (
    <SectionCard
      title="Variables"
      description="Define supported `vars` entries with defaults, requirements, and secret flags."
      icon={<FiKey size={16} />}
    >
      <div className="flex items-center justify-between">
        <div className="text-xs leading-5 text-muted-foreground">
          Resolved values are shown read-only from the current runtime.
        </div>
        <Button
          type="button"
          size="sm"
          variant="secondary"
          onClick={() => updateDraft((current) => ({
            ...current,
            variables: [...current.variables, createEmptyVariableDraft()],
          }))}
        >
          <FiPlus size={14} />
          Add variable
        </Button>
      </div>
      {draft.variables.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-border/70 bg-card/40 px-4 py-5 text-sm text-muted-foreground">
          No variables defined in this skill.
        </div>
      ) : (
        <div className="space-y-3">
          {draft.variables.map((row) => (
            <VariableRow
              key={row.id}
              row={row}
              resolvedValue={row.name.trim().length > 0 ? detail.resolvedVariables?.[row.name.trim()] : undefined}
              onChange={(nextRow) =>
                updateDraft((current) => ({
                  ...current,
                  variables: current.variables.map((entry) => (entry.id === row.id ? nextRow : entry)),
                }))
              }
              onRemove={() =>
                updateDraft((current) => ({
                  ...current,
                  variables: current.variables.filter((entry) => entry.id !== row.id),
                }))
              }
            />
          ))}
        </div>
      )}
    </SectionCard>
  );
}

export function SkillMcpSection({ draft, updateDraft }: SkillSectionProps): ReactElement {
  return (
    <SectionCard
      title="MCP"
      description="Manage Model Context Protocol server settings for this skill."
      icon={<FiSettings size={16} />}
    >
      <div className="space-y-4">
        <Toggle
          label="Enable MCP server"
          checked={draft.mcpEnabled}
          onChange={(checked) => updateDraft((current) => ({
            ...current,
            mcpEnabled: checked,
            mcpCommand: checked ? current.mcpCommand : '',
            mcpStartupTimeout: checked ? current.mcpStartupTimeout : '',
            mcpIdleTimeout: checked ? current.mcpIdleTimeout : '',
            mcpEnv: checked ? current.mcpEnv : [],
          }))}
        />
        {draft.mcpEnabled && (
          <div className="space-y-4">
            <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_12rem_12rem]">
              <FieldStack label="Command">
                <Input
                  value={draft.mcpCommand}
                  onChange={(event) => updateDraft((current) => ({ ...current, mcpCommand: event.target.value }))}
                  placeholder="npx -y @modelcontextprotocol/server-github"
                />
              </FieldStack>
              <FieldStack label="Startup timeout">
                <Input
                  value={draft.mcpStartupTimeout}
                  onChange={(event) => updateDraft((current) => ({ ...current, mcpStartupTimeout: event.target.value }))}
                  placeholder="30"
                  inputMode="numeric"
                />
              </FieldStack>
              <FieldStack label="Idle timeout">
                <Input
                  value={draft.mcpIdleTimeout}
                  onChange={(event) => updateDraft((current) => ({ ...current, mcpIdleTimeout: event.target.value }))}
                  placeholder="5"
                  inputMode="numeric"
                />
              </FieldStack>
            </div>
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <div>
                  <div className="text-sm font-medium text-foreground">Environment variables</div>
                  <div className="text-xs leading-5 text-muted-foreground">These values are stored under `mcp.env`.</div>
                </div>
                <Button
                  type="button"
                  size="sm"
                  variant="secondary"
                  onClick={() => updateDraft((current) => ({
                    ...current,
                    mcpEnv: [...current.mcpEnv, createEmptyMcpEnvDraft()],
                  }))}
                >
                  <FiPlus size={14} />
                  Add entry
                </Button>
              </div>
              {draft.mcpEnv.length === 0 ? (
                <div className="rounded-2xl border border-dashed border-border/70 bg-card/40 px-4 py-5 text-sm text-muted-foreground">
                  No MCP environment variables configured.
                </div>
              ) : (
                <div className="space-y-3">
                  {draft.mcpEnv.map((row) => (
                    <KeyValueRow
                      key={row.id}
                      labelKey="Environment key"
                      labelValue="Environment value"
                      row={row}
                      onChange={(nextRow) =>
                        updateDraft((current) => ({
                          ...current,
                          mcpEnv: current.mcpEnv.map((entry) => (entry.id === row.id ? nextRow : entry)),
                        }))
                      }
                      onRemove={() =>
                        updateDraft((current) => ({
                          ...current,
                          mcpEnv: current.mcpEnv.filter((entry) => entry.id !== row.id),
                        }))
                      }
                    />
                  ))}
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </SectionCard>
  );
}

export function SkillInstructionsSection({ draft, updateDraft }: SkillSectionProps): ReactElement {
  return (
    <SectionCard
      title="Instructions"
      description="The markdown body of `SKILL.md` is stored separately from frontmatter metadata."
      icon={<FiCpu size={16} />}
    >
      <FieldStack label="SKILL.md body">
        <Textarea
          rows={18}
          value={draft.content}
          onChange={(event) => updateDraft((current) => ({ ...current, content: event.target.value }))}
          className="min-h-[22rem] font-mono text-xs leading-6"
        />
      </FieldStack>
    </SectionCard>
  );
}

interface SkillEditorActionsProps {
  isDirty: boolean;
  updatePending: boolean;
  deletePending: boolean;
  onSave: () => void;
  onDelete: () => void;
}

export function SkillEditorActions({
  isDirty,
  updatePending,
  deletePending,
  onSave,
  onDelete,
}: SkillEditorActionsProps): ReactElement {
  return (
    <div className="flex flex-wrap gap-2">
      <Button type="button" size="sm" onClick={onSave} disabled={!isDirty || updatePending}>
        {updatePending ? (
          <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-r-transparent" />
        ) : (
          <FiSave size={14} />
        )}
        {updatePending ? 'Saving...' : 'Save'}
      </Button>
      <Button type="button" size="sm" variant="destructive" onClick={onDelete} disabled={deletePending}>
        <FiTrash2 size={14} />
        {deletePending ? 'Deleting...' : 'Delete'}
      </Button>
      {!isDirty && (
        <Badge variant="secondary" className="rounded-xl border border-border/60 bg-card/60 px-3 py-2 text-xs text-muted-foreground">
          Metadata and body are in sync with the stored skill.
        </Badge>
      )}
    </div>
  );
}
