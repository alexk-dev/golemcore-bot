import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Alert, Badge, Button, Card, Col, Form, Row } from '../../components/ui/tailwind-components';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import type { HiveStatusResponse } from '../../api/hive';
import { useUpdateHive } from '../../hooks/useSettings';
import { useHiveStatus, useJoinHive, useLeaveHive, useReconnectHive } from '../../hooks/useHive';
import type { HiveConfig } from '../../api/settingsTypes';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';
import {
  formatHivePolicyVersion,
  getHiveManagedPolicyDetails,
  type HiveManagedPolicyDetails,
  resolveHiveManagedPolicyVariant,
} from './hiveManagedPolicySupport';
import { HiveActionButtons } from './HiveActionButtons';
import { HiveSdlcSettings } from './HiveSdlcSettings';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

interface HiveTabProps {
  config: HiveConfig;
  hiveStatus?: HiveStatusResponse | null;
}

export default function HiveTab({ config, hiveStatus }: HiveTabProps): ReactElement {
  const updateHive = useUpdateHive();
  const hiveStatusQuery = useHiveStatus();
  const joinHive = useJoinHive();
  const reconnectHive = useReconnectHive();
  const leaveHive = useLeaveHive();
  const [form, setForm] = useState<HiveConfig>({ ...config });
  const [joinCode, setJoinCode] = useState('');
  const isManaged = form.managedByProperties === true;
  const isDirty = useMemo(() => hasDiff(form, config), [form, config]);
  const status = hiveStatus ?? hiveStatusQuery.data;
  const isBusy = joinHive.isPending || reconnectHive.isPending || leaveHive.isPending;

  useEffect(() => {
    // Keep the local form synchronized with the latest runtime-config snapshot after query refreshes.
    setForm({ ...config });
  }, [config]);

  const handleSave = async (): Promise<void> => {
    await updateHive.mutateAsync(form);
    toast.success('Hive settings saved');
  };

  const handleJoin = async (): Promise<void> => {
    await joinHive.mutateAsync({ joinCode: isManaged ? null : joinCode.trim() });
    if (!isManaged) {
      setJoinCode('');
    }
    toast.success('Hive join completed');
  };

  const handleReconnect = async (): Promise<void> => {
    await reconnectHive.mutateAsync();
    toast.success('Hive reconnect completed');
  };

  const handleLeave = async (): Promise<void> => {
    await leaveHive.mutateAsync();
    toast.success('Hive session cleared');
  };

  return (
    <Card className="settings-card">
      <Card.Body>
        <SettingsCardTitle
          title="Hive"
          tip="Configure the control-plane endpoint and bot identity values that Hive uses during enrollment and reconnect flows."
        />

        {isManaged && (
          <Alert variant="warning">
            Hive settings are managed by `bot.hive.*` bootstrap properties. Update application properties instead of the dashboard.
          </Alert>
        )}
        <HiveConnectionSummary status={status} />
        <HiveRuntimeSettings form={form} isManaged={isManaged} setForm={setForm} />
        <HiveSdlcSettings form={form} isManaged={isManaged} setForm={setForm} />
        <HiveJoinWorkspace
          isManaged={isManaged}
          isBusy={isBusy}
          joinCode={joinCode}
          setJoinCode={setJoinCode}
          status={status}
          fallbackServerUrl={form.serverUrl}
          joinPending={joinHive.isPending}
          reconnectPending={reconnectHive.isPending}
          leavePending={leaveHive.isPending}
          onJoin={handleJoin}
          onReconnect={handleReconnect}
          onLeave={handleLeave}
        />

        <SettingsSaveBar>
          <Button
            type="button"
            variant="primary"
            size="sm"
            onClick={() => { void handleSave(); }}
            disabled={isManaged || !isDirty || updateHive.isPending}
          >
            {updateHive.isPending ? 'Saving...' : 'Save'}
          </Button>
          <SaveStateHint isDirty={!isManaged && isDirty} />
        </SettingsSaveBar>
      </Card.Body>
    </Card>
  );
}

function resolveStatusVariant(state: string): 'secondary' | 'success' | 'warning' | 'danger' | 'primary' {
  if (state === 'CONNECTED') {
    return 'success';
  }
  if (state === 'JOINING') {
    return 'primary';
  }
  if (state === 'DEGRADED') {
    return 'warning';
  }
  if (state === 'REVOKED' || state === 'ERROR') {
    return 'danger';
  }
  return 'secondary';
}

interface HiveConnectionSummaryProps {
  status: HiveStatusResponse | undefined;
}

function HiveConnectionSummary({ status }: HiveConnectionSummaryProps): ReactElement {
  const managedPolicy = getHiveManagedPolicyDetails(status);

  return (
    <>
      <HiveConnectionBadges status={status} />
      <HivePolicySummary policy={managedPolicy} />
      <HiveStatusAlert variant="warning" message={managedPolicy?.lastErrorDigest} prefix="Last policy error: " />
      <HiveStatusAlert variant="danger" message={status?.lastError} />
    </>
  );
}

function HiveConnectionBadges({ status }: HiveConnectionSummaryProps): ReactElement {
  const state = status?.state ?? 'DISCONNECTED';
  const controlState = status?.controlChannelState ?? 'DISCONNECTED';

  return (
    <div className="d-flex flex-wrap align-items-center gap-2 mb-3">
      <span className="small text-body-secondary">Connection status</span>
      <Badge bg={resolveStatusVariant(state)}>{status?.state ?? 'LOADING'}</Badge>
      <span className="small text-body-secondary">Control channel</span>
      <Badge bg={resolveStatusVariant(controlState)}>{controlState}</Badge>
      {status?.golemId ? <span className="small text-body-secondary">Golem ID: {status.golemId}</span> : null}
    </div>
  );
}

interface HivePolicySummaryProps {
  policy: HiveManagedPolicyDetails | null;
}

function HivePolicySummary({ policy }: HivePolicySummaryProps): ReactElement | null {
  if (policy == null) {
    return null;
  }

  return (
    <div className="d-flex flex-wrap align-items-center gap-2 mb-3">
      <span className="small text-body-secondary">Policy group</span>
      <Badge bg="dark">{policy.policyGroupId}</Badge>
      <span className="small text-body-secondary">Policy sync</span>
      <Badge bg={resolveHiveManagedPolicyVariant(policy.syncStatus)}>
        {policy.syncStatus ?? 'UNKNOWN'}
      </Badge>
      <span className="small text-body-secondary">
        Applied {formatHivePolicyVersion(policy.appliedVersion)}
      </span>
      <span className="small text-body-secondary">
        Target {formatHivePolicyVersion(policy.targetVersion)}
      </span>
    </div>
  );
}

interface HiveStatusAlertProps {
  variant: 'warning' | 'danger';
  message: string | null | undefined;
  prefix?: string;
}

function HiveStatusAlert({ variant, message, prefix = '' }: HiveStatusAlertProps): ReactElement | null {
  if (message == null || message.length === 0) {
    return null;
  }

  return (
    <Alert variant={variant} className="mb-3">
      {prefix}{message}
    </Alert>
  );
}

interface HiveRuntimeSettingsProps {
  form: HiveConfig;
  isManaged: boolean;
  setForm: (next: HiveConfig) => void;
}

function HiveRuntimeSettings({ form, isManaged, setForm }: HiveRuntimeSettingsProps): ReactElement {
  return (
    <>
      <Form.Check
        type="switch"
        label={<>Enable Hive integration <HelpTip text="Turns on the Hive integration runtime surface for this bot." /></>}
        checked={form.enabled ?? false}
        onChange={(event) => setForm({ ...form, enabled: event.target.checked })}
        disabled={isManaged}
        className="mb-3"
      />

      <Row className="g-3 mb-3">
        <Col md={6}>
          <Form.Group controlId="hive-server-url">
            <Form.Label className="small fw-medium">
              Hive Server URL <HelpTip text="Base HTTP URL for the Hive control plane, for example https://hive.example.com." />
            </Form.Label>
            <Form.Control
              type="url"
              size="sm"
              value={form.serverUrl ?? ''}
              onChange={(event) => setForm({ ...form, serverUrl: event.target.value || null })}
              disabled={isManaged}
              placeholder="https://hive.example.com"
            />
          </Form.Group>
        </Col>
        <Col md={6}>
          <Form.Group controlId="hive-display-name">
            <Form.Label className="small fw-medium">
              Display Name <HelpTip text="Human-readable bot name shown in Hive fleet views." />
            </Form.Label>
            <Form.Control
              type="text"
              size="sm"
              value={form.displayName ?? ''}
              onChange={(event) => setForm({ ...form, displayName: event.target.value || null })}
              disabled={isManaged}
              placeholder="Build Runner"
            />
          </Form.Group>
        </Col>
      </Row>

      <Row className="g-3 mb-3">
        <Col md={6}>
          <Form.Group controlId="hive-host-label">
            <Form.Label className="small fw-medium">
              Host Label <HelpTip text="Optional machine label surfaced in Hive for assignment and diagnostics." />
            </Form.Label>
            <Form.Control
              type="text"
              size="sm"
              value={form.hostLabel ?? ''}
              onChange={(event) => setForm({ ...form, hostLabel: event.target.value || null })}
              disabled={isManaged}
              placeholder="builder-lab-a"
            />
          </Form.Group>
        </Col>
        <Col md={6} className="d-flex align-items-end">
          <Form.Check
            type="switch"
            label={<>Auto-connect on startup <HelpTip text="When enabled, the bot should attempt Hive reconnect/join automatically during startup flows." /></>}
            checked={form.autoConnect ?? false}
            onChange={(event) => setForm({ ...form, autoConnect: event.target.checked })}
            disabled={isManaged}
          />
        </Col>
      </Row>
    </>
  );
}

interface HiveJoinWorkspaceProps {
  isManaged: boolean;
  isBusy: boolean;
  joinCode: string;
  setJoinCode: (value: string) => void;
  status: HiveStatusResponse | undefined;
  fallbackServerUrl: string | null;
  joinPending: boolean;
  reconnectPending: boolean;
  leavePending: boolean;
  onJoin: () => Promise<void>;
  onReconnect: () => Promise<void>;
  onLeave: () => Promise<void>;
}

function HiveJoinWorkspace({
  isManaged,
  isBusy,
  joinCode,
  setJoinCode,
  status,
  fallbackServerUrl,
  joinPending,
  reconnectPending,
  leavePending,
  onJoin,
  onReconnect,
  onLeave,
}: HiveJoinWorkspaceProps): ReactElement {
  return (
    <>
      <Row className="g-3 mb-3">
        <HiveJoinCodeField
          isManaged={isManaged}
          isBusy={isBusy}
          joinCode={joinCode}
          setJoinCode={setJoinCode}
        />
        <HiveSessionDetails status={status} fallbackServerUrl={fallbackServerUrl} />
      </Row>

      <HiveActionButtons
        isManaged={isManaged}
        isBusy={isBusy}
        joinCode={joinCode}
        status={status}
        joinPending={joinPending}
        reconnectPending={reconnectPending}
        leavePending={leavePending}
        onJoin={onJoin}
        onReconnect={onReconnect}
        onLeave={onLeave}
      />
    </>
  );
}

interface HiveJoinCodeFieldProps {
  isManaged: boolean;
  isBusy: boolean;
  joinCode: string;
  setJoinCode: (value: string) => void;
}

function HiveJoinCodeField({ isManaged, isBusy, joinCode, setJoinCode }: HiveJoinCodeFieldProps): ReactElement {
  return (
    <Col md={6}>
      <Form.Group controlId="hive-join-code">
        <Form.Label className="small fw-medium">
          Join Code <HelpTip text="Paste the Hive join code in the form <TOKEN>:<URL>. It is used only for the join action and is never persisted in runtime settings." />
        </Form.Label>
        <Form.Control
          type="text"
          size="sm"
          value={isManaged ? '' : joinCode}
          onChange={(event) => setJoinCode(event.target.value)}
          disabled={isManaged || isBusy}
          placeholder={isManaged ? 'Managed by bot.hive.joinCode' : 'token-id.secret:https://hive.example.com'}
        />
      </Form.Group>
    </Col>
  );
}

interface HiveSessionDetailsProps {
  status: HiveStatusResponse | undefined;
  fallbackServerUrl: string | null;
}

function resolveDetailValue(
  value: string | number | null | undefined,
  fallback: string | number = '—',
): string | number {
  return value ?? fallback;
}

function HiveSessionDetails({ status, fallbackServerUrl }: HiveSessionDetailsProps): ReactElement {
  const detailRows = [
    ['Server URL', resolveDetailValue(status?.serverUrl, fallbackServerUrl ?? '—')],
    ['Last connected', resolveDetailValue(status?.lastConnectedAt)],
    ['Last heartbeat', resolveDetailValue(status?.lastHeartbeatAt)],
    ['Last token refresh', resolveDetailValue(status?.lastTokenRotatedAt)],
    ['Last control message', resolveDetailValue(status?.controlChannelLastMessageAt)],
    ['Buffered commands', resolveDetailValue(status?.bufferedCommandCount, 0)],
    ['Received commands', resolveDetailValue(status?.receivedCommandCount, 0)],
    ['Last command ID', resolveDetailValue(status?.lastReceivedCommandId)],
  ] as const;

  return (
    <Col md={6}>
      <div className="small text-body-secondary mb-1">Session details</div>
      {detailRows.map(([label, value]) => (
        <div key={label} className="small">{label}: {value}</div>
      ))}
      {status?.controlChannelLastError ? (
        <div className="small text-danger">Control error: {status.controlChannelLastError}</div>
      ) : null}
    </Col>
  );
}
