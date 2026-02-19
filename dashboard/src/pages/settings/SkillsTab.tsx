import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Form } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdateSkills } from '../../hooks/useSettings';
import type { SkillsConfig } from '../../api/settings';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

interface SkillsTabProps {
  config: SkillsConfig;
}

export default function SkillsTab({ config }: SkillsTabProps): ReactElement {
  const updateSkills = useUpdateSkills();
  const [form, setForm] = useState<SkillsConfig>({ ...config });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
    await updateSkills.mutateAsync(form);
    toast.success('Skills runtime settings saved');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle title="Skills Runtime" />
        <Form.Check
          type="switch"
          label={<>Enable Skills <HelpTip text="Allow loading and using skills from storage at runtime." /></>}
          checked={form.enabled ?? true}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
          className="mb-3"
        />
        <Form.Check
          type="switch"
          label={<>Progressive Loading <HelpTip text="Expose skill summaries in context instead of full skill content until routing selects one." /></>}
          checked={form.progressiveLoading ?? true}
          onChange={(e) => setForm({ ...form, progressiveLoading: e.target.checked })}
          className="mb-3"
        />
        <SettingsSaveBar>
          <Button type="button" variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updateSkills.isPending}>
            {updateSkills.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}
