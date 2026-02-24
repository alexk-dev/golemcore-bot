import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Form } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdateUsage } from '../../hooks/useSettings';
import type { UsageConfig } from '../../api/settings';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toEnabled(value: boolean | null | undefined): boolean {
  return value ?? true;
}

interface UsageTabProps {
  config: UsageConfig;
}

export default function UsageTab({ config }: UsageTabProps): ReactElement {
  const updateUsage = useUpdateUsage();
  const [form, setForm] = useState<UsageConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    // Sync local draft after config refresh from backend.
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
    await updateUsage.mutateAsync(form);
    toast.success('Usage tracking settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle title="Usage Tracking" />
        <Form.Check
          type="switch"
          label={<>Enable Usage Tracking <HelpTip text="Track request counts, tokens, and latency for analytics." /></>}
          checked={toEnabled(form.enabled)}
          onChange={(event) => setForm({ ...form, enabled: event.target.checked })}
          className="mb-3"
        />
        <SettingsSaveBar>
          <Button
            type="button"
            variant="primary"
            size="sm"
            onClick={() => { void handleSave(); }}
            disabled={!isDirty || updateUsage.isPending}
          >
            {updateUsage.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}
