import type { ReactElement, ReactNode } from 'react';
import { FiX } from 'react-icons/fi';
import HelpTip from '../../components/common/HelpTip';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import { Card, CardContent } from '../../components/ui/card';
import { Input } from '../../components/ui/field';
import type {
  SkillConditionDraft,
  SkillEnvEntryDraft,
  SkillVariableDraft,
} from './skillEditorDraft';

export function LoadingState({ message }: { message: string }): ReactElement {
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

export function SectionCard({
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

export function FieldStack({
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

export function Toggle({
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

export function KeyValueRow({
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

export function ConditionRow({
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

export function VariableRow({
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
