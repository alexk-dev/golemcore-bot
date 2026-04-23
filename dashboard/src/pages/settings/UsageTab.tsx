import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Form } from '../../components/ui/tailwind-components';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdateSessionRetention, useUpdateUsage } from '../../hooks/useSettings';
import type { SessionRetentionConfig, UsageConfig } from '../../api/settingsTypes';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

interface UsageTabProps {
  config: UsageConfig;
  sessionRetention: SessionRetentionConfig;
}

function toNullableString(value: string): string | null {
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

export default function UsageTab({ config, sessionRetention }: UsageTabProps): ReactElement {
  const updateUsage = useUpdateUsage();
  const updateSessionRetention = useUpdateSessionRetention();
  const [form, setForm] = useState<UsageConfig>({ ...config });
  const [retentionForm, setRetentionForm] = useState<SessionRetentionConfig>({ ...sessionRetention });
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);
  const isRetentionDirty = useMemo(() => hasDiff(retentionForm, sessionRetention), [retentionForm, sessionRetention]);

  useEffect(() => {
    setForm({ ...config });
  }, [config]);

  useEffect(() => {
    setRetentionForm({ ...sessionRetention });
  }, [sessionRetention]);

  const handleSave = async (): Promise<void> => {
    await updateUsage.mutateAsync(form);
    toast.success('Usage settings saved');
  };

  const handleRetentionSave = async (): Promise<void> => {
    await updateSessionRetention.mutateAsync(retentionForm);
    toast.success('Session retention settings saved');
  };

  return (
    <>
      <Card className="settings-card mb-3">
        <Card.Body>
          <SettingsCardTitle title="Usage Tracking" />
          <Form.Check
            type="switch"
            label={<>Enable Usage Tracking <HelpTip text="Enable collection of LLM request/token/latency metrics for Analytics." /></>}
            checked={form.enabled ?? true}
            onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
            className="mb-3"
          />
          <SettingsSaveBar>
            <Button type="button" variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isDirty || updateUsage.isPending}>
              {updateUsage.isPending ? 'Saving...' : 'Save'}
            </Button>
            <SaveStateHint isDirty={isDirty} />
          </SettingsSaveBar>
        </Card.Body>
      </Card>

      <Card className="settings-card">
        <Card.Body>
          <SettingsCardTitle title="Session Retention" />
          <p className="text-body-secondary small mb-3">
            Automatically remove old chat sessions from workspace storage before they accumulate and fill the disk.
          </p>
          <Form.Check
            type="switch"
            label={<>Enable automatic cleanup <HelpTip text="Run background cleanup for persisted chat sessions. Active or protected sessions are skipped." /></>}
            checked={retentionForm.enabled ?? true}
            onChange={(e) => setRetentionForm({ ...retentionForm, enabled: e.target.checked })}
            className="mb-3"
          />
          <div className="row g-3 mb-3">
            <div className="col-md-6">
              <Form.Group>
                <Form.Label className="small fw-medium">
                  Keep sessions for <HelpTip text="ISO-8601 duration. Sessions older than this are eligible for deletion. Default: P30D (30 days)." />
                </Form.Label>
                <Form.Control
                  size="sm"
                  value={retentionForm.maxAge ?? 'P30D'}
                  onChange={(e) => setRetentionForm({ ...retentionForm, maxAge: toNullableString(e.target.value) })}
                  placeholder="P30D"
                />
              </Form.Group>
            </div>
            <div className="col-md-6">
              <Form.Group>
                <Form.Label className="small fw-medium">
                  Cleanup interval <HelpTip text="How often the cleanup job scans stored sessions. Default: PT24H (once per day)." />
                </Form.Label>
                <Form.Control
                  size="sm"
                  value={retentionForm.cleanupInterval ?? 'PT24H'}
                  onChange={(e) => setRetentionForm({ ...retentionForm, cleanupInterval: toNullableString(e.target.value) })}
                  placeholder="PT24H"
                />
              </Form.Group>
            </div>
          </div>
          <div className="d-grid gap-2">
            <Form.Check
              type="switch"
              label={<>Protect active sessions <HelpTip text="Do not delete the session currently selected in web or Telegram pointers." /></>}
              checked={retentionForm.protectActiveSessions ?? true}
              onChange={(e) => setRetentionForm({ ...retentionForm, protectActiveSessions: e.target.checked })}
            />
            <Form.Check
              type="switch"
              label={<>Protect sessions with plan mode data <HelpTip text="Keep sessions that still have collecting, ready, approved, or executing plans attached." /></>}
              checked={retentionForm.protectSessionsWithPlans ?? true}
              onChange={(e) => setRetentionForm({ ...retentionForm, protectSessionsWithPlans: e.target.checked })}
            />
            <Form.Check
              type="switch"
              label={<>Protect sessions with delayed actions <HelpTip text="Keep sessions referenced by pending or leased delayed actions and reminders." /></>}
              checked={retentionForm.protectSessionsWithDelayedActions ?? true}
              onChange={(e) => setRetentionForm({ ...retentionForm, protectSessionsWithDelayedActions: e.target.checked })}
            />
          </div>
          <SettingsSaveBar>
            <Button type="button" variant="primary" size="sm" onClick={() => { void handleRetentionSave(); }} disabled={!isRetentionDirty || updateSessionRetention.isPending}>
              {updateSessionRetention.isPending ? 'Saving...' : 'Save'}
            </Button>
            <SaveStateHint isDirty={isRetentionDirty} />
          </SettingsSaveBar>
        </Card.Body>
      </Card>
    </>
  );
}
