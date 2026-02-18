import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Form, OverlayTrigger, Tooltip } from 'react-bootstrap';
import { FiHelpCircle } from 'react-icons/fi';
import toast from 'react-hot-toast';
import { useUpdateSkills } from '../../hooks/useSettings';
import type { SkillsConfig } from '../../api/settings';

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
        <Card.Title className="h6 mb-3">Skills Runtime</Card.Title>
        <Form.Check
          type="switch"
          label={<>Enable Skills <Tip text="Allow loading and using skills from storage at runtime." /></>}
          checked={form.enabled ?? true}
          onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
          className="mb-3"
        />
        <Form.Check
          type="switch"
          label={<>Progressive Loading <Tip text="Expose skill summaries in context instead of full skill content until routing selects one." /></>}
          checked={form.progressiveLoading ?? true}
          onChange={(e) => setForm({ ...form, progressiveLoading: e.target.checked })}
          className="mb-3"
        />
        <div className="d-flex align-items-center gap-2">
          <Button variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updateSkills.isPending}>
            {updateSkills.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={isDirty} />
        </div>
      </Card.Body>
    </Card>
  );
}
