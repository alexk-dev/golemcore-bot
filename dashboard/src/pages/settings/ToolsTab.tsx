import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Form } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';
import { useUpdateTools } from '../../hooks/useSettings';
import type { ToolsConfig } from '../../api/settings';
import { ShellEnvironmentVariablesCard } from './ShellEnvironmentVariablesCard';

type ToolsMode = 'filesystem' | 'shell' | 'automation' | 'goals';
type ToolFlagKey =
  | 'filesystemEnabled'
  | 'shellEnabled'
  | 'skillManagementEnabled'
  | 'skillTransitionEnabled'
  | 'tierEnabled'
  | 'goalManagementEnabled';

interface ToolsTabProps {
  config: ToolsConfig;
  mode: ToolsMode;
}

interface ToolMeta {
  key: ToolFlagKey;
  label: string;
  desc: string;
  tip: string;
}

interface ToolToggleRowProps {
  item: ToolMeta;
  checked: boolean;
  onToggle: (enabled: boolean) => void;
  withBorder?: boolean;
}

const FILESYSTEM_TOOL: ToolMeta = {
  key: 'filesystemEnabled',
  label: 'Filesystem',
  desc: 'Read and write files inside the sandboxed workspace.',
  tip: 'Allows the bot to inspect and modify files under the configured workspace.',
};

const SHELL_TOOL: ToolMeta = {
  key: 'shellEnabled',
  label: 'Shell',
  desc: 'Execute sandboxed shell commands.',
  tip: 'Allows command execution such as ls, rg, git, and build/test tooling inside the sandbox.',
};

const AUTOMATION_TOOLS: ToolMeta[] = [
  {
    key: 'skillManagementEnabled',
    label: 'Skill Management',
    desc: 'Create and update skills at runtime.',
    tip: 'Allows the LLM to create, list, and delete skill definitions programmatically.',
  },
  {
    key: 'skillTransitionEnabled',
    label: 'Skill Transition',
    desc: 'Switch skills inside a conversation pipeline.',
    tip: 'Allows the LLM to transition between skills during a conversation pipeline.',
  },
  {
    key: 'tierEnabled',
    label: 'Tier Tool',
    desc: 'Change model tier during a session.',
    tip: 'Allows the LLM to upgrade or downgrade the current model tier.',
  },
];

const GOAL_TOOL: ToolMeta = {
  key: 'goalManagementEnabled',
  label: 'Goal Management',
  desc: 'Manage autonomous mode goals.',
  tip: 'Allows the LLM to create, update, and complete goals in auto mode.',
};

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function ToolToggleRow({ item, checked, onToggle, withBorder = true }: ToolToggleRowProps): ReactElement {
  return (
    <div className={`tools-toggle-row d-flex align-items-start py-2${withBorder ? ' border-bottom' : ''}`}>
      <Form.Check
        type="switch"
        checked={checked}
        onChange={(event) => onToggle(event.target.checked)}
        className="me-3"
        aria-label={`Toggle ${item.label}`}
      />
      <div>
        <div className="fw-medium small">
          {item.label} <HelpTip text={item.tip} />
        </div>
        <div className="meta-text tools-row-desc">{item.desc}</div>
      </div>
    </div>
  );
}

function SingleToolCard({
  title,
  item,
  checked,
  onToggle,
}: {
  title: string;
  item: ToolMeta;
  checked: boolean;
  onToggle: (enabled: boolean) => void;
}): ReactElement {
  return (
    <Card className="settings-card tools-card mb-3">
      <Card.Body>
        <SettingsCardTitle title={title} className="tools-card-title" />
        <Form.Check
          type="switch"
          label={<>{`Enable ${item.label}`} <HelpTip text={item.tip} /></>}
          checked={checked}
          onChange={(event) => onToggle(event.target.checked)}
          className="mb-2"
        />
        <div className="meta-text">{item.desc}</div>
      </Card.Body>
    </Card>
  );
}

export default function ToolsTab({ config, mode }: ToolsTabProps): ReactElement {
  const updateTools = useUpdateTools();
  const [form, setForm] = useState<ToolsConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const setToolEnabled = (key: ToolFlagKey, enabled: boolean): void => {
    setForm((prev) => ({ ...prev, [key]: enabled }));
  };

  const handleSave = async (): Promise<void> => {
    await updateTools.mutateAsync(form);
    toast.success('Tool settings saved');
  };

  return (
    <section className="tools-tab">
      {mode === 'filesystem' && (
        <SingleToolCard
          title="Filesystem Tool"
          item={FILESYSTEM_TOOL}
          checked={form.filesystemEnabled ?? true}
          onToggle={(enabled) => setToolEnabled('filesystemEnabled', enabled)}
        />
      )}

      {mode === 'shell' && (
        <>
          <SingleToolCard
            title="Shell Tool"
            item={SHELL_TOOL}
            checked={form.shellEnabled ?? true}
            onToggle={(enabled) => setToolEnabled('shellEnabled', enabled)}
          />
          <ShellEnvironmentVariablesCard
            variables={form.shellEnvironmentVariables ?? []}
            onVariablesChange={(shellEnvironmentVariables) => setForm((prev) => ({ ...prev, shellEnvironmentVariables }))}
            isShellEnabled={form.shellEnabled ?? true}
          />
        </>
      )}

      {mode === 'automation' && (
        <Card className="settings-card tools-card mb-3">
          <Card.Body>
            <SettingsCardTitle title="Automation Tools" className="tools-card-title" />
            {AUTOMATION_TOOLS.map((tool, index) => (
              <ToolToggleRow
                key={tool.key}
                item={tool}
                checked={form[tool.key] ?? true}
                onToggle={(enabled) => setToolEnabled(tool.key, enabled)}
                withBorder={index < AUTOMATION_TOOLS.length - 1}
              />
            ))}
          </Card.Body>
        </Card>
      )}

      {mode === 'goals' && (
        <SingleToolCard
          title="Goal Management"
          item={GOAL_TOOL}
          checked={form.goalManagementEnabled ?? true}
          onToggle={(enabled) => setToolEnabled('goalManagementEnabled', enabled)}
        />
      )}

      <SettingsSaveBar className="mt-3">
        <Button type="button" variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updateTools.isPending}>
          {updateTools.isPending ? 'Saving...' : 'Save'}
        </Button>
        <SaveStateHint isDirty={isDirty} />
      </SettingsSaveBar>
    </section>
  );
}
