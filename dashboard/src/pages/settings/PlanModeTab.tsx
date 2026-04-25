import { type ReactElement, useEffect, useMemo, useState } from 'react';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';
import { Button, Card, Form } from '../../components/ui/tailwind-components';
import { useUpdatePlan } from '../../hooks/useSettings';
import { getExplicitModelTierOptions, isExplicitModelTier, type ExplicitModelTierId } from '../../lib/modelTiers';
import type { PlanConfig } from '../../api/settingsTypes';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableTier(value: string): ExplicitModelTierId | null {
  const trimmed = value.trim();
  return isExplicitModelTier(trimmed) ? trimmed : null;
}

interface PlanModeTabProps {
  config: PlanConfig;
}

export default function PlanModeTab({ config }: PlanModeTabProps): ReactElement {
  const updatePlan = useUpdatePlan();
  const [form, setForm] = useState<PlanConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
    await updatePlan.mutateAsync(form);
    toast.success('Plan mode settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle
          title="Plan Mode"
          tip="Ephemeral planning mode. Durable tracking belongs to session goals and tasks."
        />

        <Form.Group className="mb-3">
          <Form.Label className="small fw-medium">
            Plan tier override <HelpTip text="Optional model tier used while drafting a plan. Leave empty to use normal routing." />
          </Form.Label>
          <Form.Select
            size="sm"
            value={form.modelTier ?? ''}
            onChange={(event) => setForm({ ...form, modelTier: toNullableTier(event.target.value) })}
          >
            <option value="">Default routing</option>
            {getExplicitModelTierOptions().map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </Form.Select>
        </Form.Group>

        <SettingsSaveBar>
          <Button type="button" variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updatePlan.isPending}>
            {updatePlan.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}
