import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Form, OverlayTrigger, Tooltip } from 'react-bootstrap';
import { FiHelpCircle } from 'react-icons/fi';
import toast from 'react-hot-toast';
import { useUpdateUsage } from '../../hooks/useSettings';
import type { UsageConfig } from '../../api/settings';

function Tip({ text }: { text: string }): ReactElement {
  return (
    <OverlayTrigger placement="top" overlay={<Tooltip>{text}</Tooltip>}>
      <span className="setting-tip"><FiHelpCircle /></span>
    </OverlayTrigger>
  );
}

function SaveStateHint({ isDirty }: { isDirty: boolean }): ReactElement {
  return <small className="text-body-secondary">{isDirty ? 'Unsaved changes' : 'All changes saved'}</small>;
}

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

interface UsageTabProps {
  config: UsageConfig;
}

export default function UsageTab({ config }: UsageTabProps): ReactElement {
  const updateUsage = useUpdateUsage();
  const [form, setForm] = useState<UsageConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
    await updateUsage.mutateAsync(form);
    toast.success('Usage settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <Card.Title className="h6 mb-3">Usage Tracking</Card.Title>
        <Form.Check
          type="switch"
          label={<>Enable Usage Tracking <Tip text="Enable collection of LLM request/token/latency metrics for Analytics." /></>}
          checked={form.enabled ?? true}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
          className="mb-3"
        />
        <div className="d-flex align-items-center gap-2">
          <Button variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updateUsage.isPending}>
            {updateUsage.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}
