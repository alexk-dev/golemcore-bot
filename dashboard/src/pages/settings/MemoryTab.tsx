import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Form } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdateMemory } from '../../hooks/useSettings';
import type { MemoryConfig } from '../../api/settings';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableInt(value: string): number | null {
  const parsed = parseInt(value, 10);
  return Number.isNaN(parsed) ? null : parsed;
}

interface MemoryTabProps {
  config: MemoryConfig;
}

export default function MemoryTab({ config }: MemoryTabProps): ReactElement {
  const updateMemory = useUpdateMemory();
  const [form, setForm] = useState<MemoryConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
    await updateMemory.mutateAsync(form);
    toast.success('Memory settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle title="Memory" />
        <Form.Check
          type="switch"
          label={<>Enable Memory <HelpTip text="Persist user/assistant exchanges into long-term notes and include memory context in prompts." /></>}
          checked={form.enabled ?? true}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
          className="mb-3"
        />
        <Form.Group className="mb-3">
          <Form.Label className="small fw-medium">
            Recent Days <HelpTip text="How many previous daily memory files to include in context." />
          </Form.Label>
          <Form.Control
            size="sm"
            type="number"
            min={1}
            max={90}
            value={form.recentDays ?? 7}
            onChange={(e) => setForm({ ...form, recentDays: toNullableInt(e.target.value) })}
          />
        </Form.Group>
        <SettingsSaveBar>
          <Button type="button" variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updateMemory.isPending}>
            {updateMemory.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}
