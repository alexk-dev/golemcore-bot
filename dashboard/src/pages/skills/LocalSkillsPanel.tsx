import { type ReactElement, type ReactNode, useEffect, useMemo, useState } from 'react';
import {
  FiArrowRight,
  FiCpu,
  FiGitBranch,
  FiKey,
  FiPlus,
  FiRefreshCw,
  FiSave,
  FiSettings,
  FiTrash2,
  FiX,
} from 'react-icons/fi';
import type { SkillInfo, SkillUpdateRequest } from '../../api/skills';
import HelpTip from '../../components/common/HelpTip';
import { Alert } from '../../components/ui/alert';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card';
import { Input, Select, Textarea } from '../../components/ui/field';
import { cn } from '../../lib/utils';
import {
  buildSkillUpdateRequest,
  createEmptyConditionDraft,
  createEmptyMcpEnvDraft,
  createEmptyVariableDraft,
  createSkillEditorDraft,
  serializeSkillUpdateRequest,
  type SkillConditionDraft,
  type SkillEditorDraft,
  type SkillEnvEntryDraft,
  type SkillVariableDraft,
} from './skillEditorDraft';

interface LocalSkillsPanelProps {
  detail: SkillInfo | undefined;
  detailError: boolean;
  detailLoading: boolean;
  filteredSkills: SkillInfo[];
  onDelete: () => void;
  onOpenMarketplace: () => void;
  onRefetchDetail: () => void;
  onSave: (request: SkillUpdateRequest) => Promise<SkillInfo>;
  onSearchChange: (query: string) => void;
  onSelectSkill: (name: string) => void;
  searchQuery: string;
  selectedSkillName: string | null;
  updatePending: boolean;
  deletePending: boolean;
}

interface LocalSkillDetailPaneProps {
  detail: SkillInfo | undefined;
  selectedSkillName: string | null;
  detailLoading: boolean;
  detailError: boolean;
  onRefetchDetail: () => void;
  updatePending: boolean;
  onSave: (request: SkillUpdateRequest) => Promise<SkillInfo>;
  deletePending: boolean;
  onDelete: () => void;
}

const MODEL_TIER_OPTIONS = [
  { value: '', label: 'Default routing' },
  { value: 'balanced', label: 'Balanced' },
  { value: 'smart', label: 'Smart' },
  { value: 'coding', label: 'Coding' },
  { value: 'deep', label: 'Deep' },
];

function LoadingState({ message }: { message: string }): ReactElement {
  return (
    <Card className="min-h-[28rem]">
      <CardContent className="flex min-h-[28rem] items-center justify-center">
        <div className="flex items-center gap-3 text-sm text-muted-foreground">
          <span className="h-4 w-4 animate-spin rounded-full border-2 border-current border-r-transparent" />
          <span>{message}</span>
        </div>
      </CardContent>
    </Card>
  );
}

function EmptyState({ onOpenMarketplace }: { onOpenMarketplace: () => void }): ReactElement {
  return (
    <div className="rounded-2xl border border-dashed border-border/80 bg-muted/20 px-4 py-8 text-center">
      <p className="text-sm text-muted-foreground">No skills match this filter.</p>
      <Button type="button" variant="link" className="mt-2" onClick={onOpenMarketplace}>
        Open marketplace
        <FiArrowRight size={14} />
      </Button>
    </div>
  );
}

function SkillListItem({
  skill,
  selected,
  onSelect,
}: {
  skill: SkillInfo;
  selected: boolean;
  onSelect: () => void;
}): ReactElement {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={cn(
        'w-full rounded-2xl border px-4 py-3 text-left transition-all duration-200',
        selected
          ? 'border-primary/35 bg-primary/10 shadow-glow'
          : 'border-border/70 bg-card/60 hover:border-primary/20 hover:bg-card',
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-foreground">{skill.name}</div>
          <div className="mt-1 line-clamp-2 text-xs leading-5 text-muted-foreground">
            {skill.description?.trim().length ? skill.description : 'No description provided.'}
          </div>
        </div>
        <div className="flex shrink-0 flex-wrap justify-end gap-1">
          <Badge variant={skill.available ? 'success' : 'secondary'}>
            {skill.available ? 'On' : 'Off'}
          </Badge>
          {skill.hasMcp && <Badge variant="info">MCP</Badge>}
          {skill.modelTier != null && skill.modelTier.length > 0 && skill.modelTier !== 'balanced' && (
            <Badge variant="warning">{skill.modelTier}</Badge>
          )}
        </div>
      </div>
    </button>
  );
}

function SectionCard({
  title,
  description,
  icon,
  children,
}: {
  title: string;
  description: string;
  icon: ReactElement;
  children: ReactElement | ReactElement[];
}): ReactElement {
  return (
    <div className="rounded-2xl border border-border/70 bg-muted/20 p-4">
      <div className="flex items-start gap-3">
        <div className="inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl border border-border/70 bg-card/80 text-muted-foreground">
          {icon}
        </div>
        <div className="min-w-0">
          <h3 className="text-sm font-semibold text-foreground">{title}</h3>
          <p className="mt-1 text-xs leading-5 text-muted-foreground">{description}</p>
        </div>
      </div>
      <div className="mt-4 space-y-4">{children}</div>
    </div>
  );
}

function FieldStack({
  label,
  hint,
  children,
}: {
  label: ReactNode;
  hint?: string;
  children: ReactElement;
}): ReactElement {
  return (
    <label className="block space-y-2">
      <span className="text-sm font-medium text-foreground">{label}</span>
      {children}
      {hint != null && hint.length > 0 && <span className="block text-xs leading-5 text-muted-foreground">{hint}</span>}
    </label>
  );
}

function Toggle({
  label,
  checked,
  onChange,
}: {
  label: ReactNode;
  checked: boolean;
  onChange: (checked: boolean) => void;
}): ReactElement {
  return (
    <label className="inline-flex items-center gap-3 rounded-xl border border-border/70 bg-card/60 px-3 py-2 text-sm text-foreground">
      <input
        type="checkbox"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
        className="h-4 w-4 rounded border-border text-primary focus:ring-primary"
      />
      <span>{label}</span>
    </label>
  );
}

function KeyValueRow({
  labelKey,
  labelValue,
  row,
  onChange,
  onRemove,
}: {
  labelKey: string;
  labelValue: string;
  row: SkillEnvEntryDraft;
  onChange: (next: SkillEnvEntryDraft) => void;
  onRemove: () => void;
}): ReactElement {
  return (
    <div className="grid gap-3 rounded-2xl border border-border/60 bg-card/70 p-3 md:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto]">
      <FieldStack label={labelKey}>
        <Input value={row.key} onChange={(event) => onChange({ ...row, key: event.target.value })} placeholder="KEY" />
      </FieldStack>
      <FieldStack label={labelValue}>
        <Input value={row.value} onChange={(event) => onChange({ ...row, value: event.target.value })} placeholder="value" />
      </FieldStack>
      <div className="flex items-end">
        <Button type="button" size="sm" variant="ghost" onClick={onRemove}>
          <FiX size={14} />
          Remove
        </Button>
      </div>
    </div>
  );
}

function ConditionRow({
  row,
  onChange,
  onRemove,
}: {
  row: SkillConditionDraft;
  onChange: (next: SkillConditionDraft) => void;
  onRemove: () => void;
}): ReactElement {
  return (
    <div className="grid gap-3 rounded-2xl border border-border/60 bg-card/70 p-3 md:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto]">
      <FieldStack label="When condition is">
        <Input value={row.condition} onChange={(event) => onChange({ ...row, condition: event.target.value })} placeholder="success" />
      </FieldStack>
      <FieldStack label="Switch to skill">
        <Input value={row.skill} onChange={(event) => onChange({ ...row, skill: event.target.value })} placeholder="follow-up-skill" />
      </FieldStack>
      <div className="flex items-end">
        <Button type="button" size="sm" variant="ghost" onClick={onRemove}>
          <FiX size={14} />
          Remove
        </Button>
      </div>
    </div>
  );
}

function VariableRow({
  row,
  resolvedValue,
  onChange,
  onRemove,
}: {
  row: SkillVariableDraft;
  resolvedValue?: string;
  onChange: (next: SkillVariableDraft) => void;
  onRemove: () => void;
}): ReactElement {
  return (
    <div className="space-y-3 rounded-2xl border border-border/60 bg-card/70 p-3">
      <div className="grid gap-3 md:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <FieldStack label="Variable name">
          <Input value={row.name} onChange={(event) => onChange({ ...row, name: event.target.value })} placeholder="API_TOKEN" />
        </FieldStack>
        <FieldStack label="Default value">
          <Input value={row.defaultValue} onChange={(event) => onChange({ ...row, defaultValue: event.target.value })} placeholder="Optional default" />
        </FieldStack>
      </div>
      <FieldStack label="Description">
        <Input value={row.description} onChange={(event) => onChange({ ...row, description: event.target.value })} placeholder="Describe what this variable configures" />
      </FieldStack>
      <div className="flex flex-wrap items-center gap-2">
        <Toggle
          label={<>Required <HelpTip text="Required variables must be provided for the skill to be considered ready to run." /></>}
          checked={row.required}
          onChange={(checked) => onChange({ ...row, required: checked })}
        />
        <Toggle
          label={<>Secret <HelpTip text="Secret variables contain sensitive values such as API keys or tokens and should be masked in the UI." /></>}
          checked={row.secret}
          onChange={(checked) => onChange({ ...row, secret: checked })}
        />
        {resolvedValue != null && resolvedValue.length > 0 && (
          <Badge variant="secondary">Resolved: {row.secret ? '***' : resolvedValue}</Badge>
        )}
        <div className="ml-auto">
          <Button type="button" size="sm" variant="ghost" onClick={onRemove}>
            <FiX size={14} />
            Remove
          </Button>
        </div>
      </div>
    </div>
  );
}

function LocalSkillDetailPane({
  detail,
  selectedSkillName,
  detailLoading,
  detailError,
  onRefetchDetail,
  updatePending,
  onSave,
  deletePending,
  onDelete,
}: LocalSkillDetailPaneProps): ReactElement {
  const [draft, setDraft] = useState<SkillEditorDraft | null>(null);
  const [initializedFor, setInitializedFor] = useState<string | null>(null);
  const [initialRequestSnapshot, setInitialRequestSnapshot] = useState('');

  useEffect(() => {
    if (selectedSkillName == null) {
      setDraft(null);
      setInitializedFor(null);
      setInitialRequestSnapshot('');
    }
  }, [selectedSkillName]);

  useEffect(() => {
    if (detail == null || selectedSkillName == null || detail.name !== selectedSkillName || initializedFor === selectedSkillName) {
      return;
    }
    const nextDraft = createSkillEditorDraft(detail);
    const snapshot = serializeSkillUpdateRequest(buildSkillUpdateRequest(nextDraft));
    setDraft(nextDraft);
    setInitializedFor(selectedSkillName);
    setInitialRequestSnapshot(snapshot);
  }, [detail, initializedFor, selectedSkillName]);

  const currentRequest = useMemo(
    () => (draft != null ? buildSkillUpdateRequest(draft) : null),
    [draft],
  );
  const currentSnapshot = useMemo(
    () => (currentRequest != null ? serializeSkillUpdateRequest(currentRequest) : ''),
    [currentRequest],
  );
  const isDirty = draft != null && currentSnapshot !== initialRequestSnapshot;

  const updateDraft = (updater: (current: SkillEditorDraft) => SkillEditorDraft): void => {
    setDraft((current) => (current != null ? updater(current) : current));
  };

  const saveDraft = async (): Promise<void> => {
    if (currentRequest == null || !isDirty) {
      return;
    }
    const updated = await onSave(currentRequest);
    const nextDraft = createSkillEditorDraft(updated);
    const nextSnapshot = serializeSkillUpdateRequest(buildSkillUpdateRequest(nextDraft));
    setDraft(nextDraft);
    setInitializedFor(updated.name);
    setInitialRequestSnapshot(nextSnapshot);
  };

  if (selectedSkillName != null && selectedSkillName.length > 0 && detailLoading) {
    return <LoadingState message="Loading skill..." />;
  }

  if (selectedSkillName != null && selectedSkillName.length > 0 && detailError) {
    return (
      <Card className="min-h-[28rem]">
        <CardContent className="flex min-h-[28rem] items-center justify-center">
          <div className="w-full max-w-md space-y-4 text-center">
            <Alert variant="danger">Failed to load the selected skill.</Alert>
            <Button type="button" size="sm" variant="secondary" onClick={onRefetchDetail}>
              <FiRefreshCw size={14} />
              Retry
            </Button>
          </div>
        </CardContent>
      </Card>
    );
  }

  if (selectedSkillName != null && selectedSkillName.length > 0 && detail != null && draft != null) {
    return (
      <Card className="min-h-[28rem]">
        <CardHeader className="items-start">
          <div className="space-y-2">
            <CardTitle className="text-lg">{detail.name}</CardTitle>
            <CardDescription>
              Edit supported `SKILL.md` metadata fields and the markdown body from one workspace.
            </CardDescription>
          </div>
          <div className="flex flex-wrap justify-end gap-1">
            {detail.hasMcp && <Badge variant="info">MCP</Badge>}
            {detail.modelTier != null && detail.modelTier.length > 0 && (
              <Badge variant="secondary">{detail.modelTier}</Badge>
            )}
            {!detail.available && <Badge variant="warning">Unavailable</Badge>}
          </div>
        </CardHeader>
        <CardContent className="space-y-4">
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

          <div className="grid gap-4 xl:grid-cols-2">
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
          </div>

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

          <div className="flex flex-wrap gap-2">
            <Button type="button" size="sm" onClick={() => { void saveDraft(); }} disabled={!isDirty || updatePending}>
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
              <span className="inline-flex items-center rounded-xl border border-border/60 bg-card/60 px-3 py-2 text-xs text-muted-foreground">
                Metadata and body are in sync with the stored skill.
              </span>
            )}
          </div>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card className="min-h-[28rem]">
      <CardContent className="flex min-h-[28rem] items-center justify-center">
        <div className="space-y-3 text-center">
          <div className="mx-auto inline-flex h-12 w-12 items-center justify-center rounded-2xl border border-border/80 bg-muted/30 text-muted-foreground">
            <FiCpu size={18} />
          </div>
          <div>
            <h3 className="text-base font-semibold text-foreground">Select a skill to edit</h3>
            <p className="mt-1 text-sm text-muted-foreground">
              Pick a local skill from the list to inspect metadata and update the full `SKILL.md`.
            </p>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}

export function LocalSkillsPanel({
  detail,
  detailError,
  detailLoading,
  filteredSkills,
  onDelete,
  onOpenMarketplace,
  onRefetchDetail,
  onSave,
  onSearchChange,
  onSelectSkill,
  searchQuery,
  selectedSkillName,
  updatePending,
  deletePending,
}: LocalSkillsPanelProps): ReactElement {
  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(18rem,24rem)_1fr]">
      <Card className="overflow-hidden">
        <CardHeader className="items-start">
          <div className="space-y-2">
            <CardTitle>Installed skills</CardTitle>
            <CardDescription>Search the local workspace and choose a skill to edit.</CardDescription>
          </div>
          <Badge variant="secondary">{filteredSkills.length}</Badge>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <label htmlFor="skills-search" className="text-sm font-medium text-foreground">
              Search
            </label>
            <Input
              id="skills-search"
              placeholder="Search skills..."
              value={searchQuery}
              onChange={(event) => onSearchChange(event.target.value)}
            />
          </div>

          <div className="space-y-2">
            {filteredSkills.map((skill) => (
              <SkillListItem
                key={skill.name}
                skill={skill}
                selected={selectedSkillName === skill.name}
                onSelect={() => onSelectSkill(skill.name)}
              />
            ))}
            {filteredSkills.length === 0 && <EmptyState onOpenMarketplace={onOpenMarketplace} />}
          </div>
        </CardContent>
      </Card>

      <LocalSkillDetailPane
        detail={detail}
        selectedSkillName={selectedSkillName}
        detailLoading={detailLoading}
        detailError={detailError}
        onRefetchDetail={onRefetchDetail}
        updatePending={updatePending}
        onSave={onSave}
        deletePending={deletePending}
        onDelete={onDelete}
      />
    </div>
  );
}
