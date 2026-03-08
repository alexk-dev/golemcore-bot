import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Form, Row } from 'react-bootstrap';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdateAdvanced } from '../../hooks/useSettings';
import type { CompactionConfig, RateLimitConfig, SecurityConfig } from '../../api/settings';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';

export type AdvancedMode = 'all' | 'rateLimit' | 'security' | 'compaction';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableInt(value: string): number | null {
  const parsed = parseInt(value, 10);
  return Number.isNaN(parsed) ? null : parsed;
}

interface AdvancedTabProps {
  rateLimit: RateLimitConfig;
  security: SecurityConfig;
  compaction: CompactionConfig;
  mode?: AdvancedMode;
}

interface RateLimitCardProps {
  rl: RateLimitConfig;
  setRl: (value: RateLimitConfig) => void;
}

function RateLimitCard({ rl, setRl }: RateLimitCardProps): ReactElement {
  return (
    <Card className="settings-card h-100">
      <Card.Body>
        <SettingsCardTitle
          title="Rate Limiting"
          tip="Throttle user requests to prevent abuse and manage API costs"
        />
        <Form.Check type="switch" label="Enable" checked={rl.enabled ?? false}
          onChange={(e) => setRl({ ...rl, enabled: e.target.checked })} className="mb-3" />
        <Form.Group className="mb-2">
          <Form.Label className="small fw-medium">
            Requests/minute <HelpTip text="Maximum LLM requests per user per minute" />
          </Form.Label>
          <Form.Control size="sm" type="number" value={rl.userRequestsPerMinute ?? 20}
            onChange={(e) => setRl({ ...rl, userRequestsPerMinute: toNullableInt(e.target.value) })} />
        </Form.Group>
        <Form.Group className="mb-2">
          <Form.Label className="small fw-medium">
            Requests/hour <HelpTip text="Maximum LLM requests per user per hour" />
          </Form.Label>
          <Form.Control size="sm" type="number" value={rl.userRequestsPerHour ?? 100}
            onChange={(e) => setRl({ ...rl, userRequestsPerHour: toNullableInt(e.target.value) })} />
        </Form.Group>
        <Form.Group>
          <Form.Label className="small fw-medium">
            Requests/day <HelpTip text="Maximum LLM requests per user per day" />
          </Form.Label>
          <Form.Control size="sm" type="number" value={rl.userRequestsPerDay ?? 500}
            onChange={(e) => setRl({ ...rl, userRequestsPerDay: toNullableInt(e.target.value) })} />
        </Form.Group>
      </Card.Body>
    </Card>
  );
}

interface CompactionCardProps {
  comp: CompactionConfig;
  setComp: (value: CompactionConfig) => void;
}

function CompactionCard({ comp, setComp }: CompactionCardProps): ReactElement {
  return (
    <Card className="settings-card h-100">
      <Card.Body>
        <SettingsCardTitle
          title="Context Compaction"
          tip="Automatically compress conversation history when it approaches the token limit"
        />
        <Form.Check type="switch" label="Enable" checked={comp.enabled ?? true}
          onChange={(e) => setComp({ ...comp, enabled: e.target.checked })} className="mb-3" />
        <Form.Group className="mb-2">
          <Form.Label className="small fw-medium">
            Max Context Tokens <HelpTip text="Token threshold that triggers automatic context compaction" />
          </Form.Label>
          <Form.Control size="sm" type="number" value={comp.maxContextTokens ?? 50000}
            onChange={(e) => setComp({ ...comp, maxContextTokens: toNullableInt(e.target.value) })} />
        </Form.Group>
        <Form.Group>
          <Form.Label className="small fw-medium">
            Keep Last Messages <HelpTip text="Number of recent messages to preserve during compaction" />
          </Form.Label>
          <Form.Control size="sm" type="number" value={comp.keepLastMessages ?? 20}
            onChange={(e) => setComp({ ...comp, keepLastMessages: toNullableInt(e.target.value) })} />
        </Form.Group>
      </Card.Body>
    </Card>
  );
}

interface SecurityCardProps {
  sec: SecurityConfig;
  setSec: (value: SecurityConfig) => void;
}

function SecurityCard({ sec, setSec }: SecurityCardProps): ReactElement {
  return (
    <Card className="settings-card h-100">
      <Card.Body>
        <SettingsCardTitle
          title="Security"
          tip="Input validation and injection detection for incoming messages"
        />
        <Form.Check type="switch"
          label={<>Sanitize input <HelpTip text="Strip potentially dangerous characters and HTML from user messages" /></>}
          checked={sec.sanitizeInput ?? true}
          onChange={(e) => setSec({ ...sec, sanitizeInput: e.target.checked })} className="mb-2" />
        <Form.Check type="switch"
          label={<>Detect prompt injection <HelpTip text="Detect and block attempts to override the system prompt" /></>}
          checked={sec.detectPromptInjection ?? true}
          onChange={(e) => setSec({ ...sec, detectPromptInjection: e.target.checked })} className="mb-2" />
        <Form.Check type="switch"
          label={<>Detect command injection <HelpTip text="Detect and block shell command injection attempts in tool parameters" /></>}
          checked={sec.detectCommandInjection ?? true}
          onChange={(e) => setSec({ ...sec, detectCommandInjection: e.target.checked })} className="mb-3" />
        <Form.Check type="switch"
          label={<>Enable allowlist gate <HelpTip text="If disabled, allowlist checks are bypassed." /></>}
          checked={sec.allowlistEnabled ?? true}
          onChange={(e) => setSec({ ...sec, allowlistEnabled: e.target.checked })} className="mb-2" />
        <Form.Check type="switch"
          label={<>Tool confirmation <HelpTip text="Require user confirmation for destructive tool actions." /></>}
          checked={sec.toolConfirmationEnabled ?? false}
          onChange={(e) => setSec({ ...sec, toolConfirmationEnabled: e.target.checked })} className="mb-3" />
        <Form.Group>
          <Form.Label className="small fw-medium">
            Max Input Length <HelpTip text="Maximum allowed characters per user message" />
          </Form.Label>
          <Form.Control size="sm" type="number" value={sec.maxInputLength ?? 10000}
            onChange={(e) => setSec({ ...sec, maxInputLength: toNullableInt(e.target.value) })} />
        </Form.Group>
        <Form.Group className="mt-2">
          <Form.Label className="small fw-medium">
            Tool Confirmation Timeout (seconds)
          </Form.Label>
          <Form.Control
            size="sm"
            type="number"
            min={5}
            max={600}
            value={sec.toolConfirmationTimeoutSeconds ?? 60}
            onChange={(e) => setSec({ ...sec, toolConfirmationTimeoutSeconds: toNullableInt(e.target.value) })}
          />
        </Form.Group>
      </Card.Body>
    </Card>
  );
}

export function AdvancedTab({ rateLimit, security, compaction, mode = 'all' }: AdvancedTabProps): ReactElement {
  const updateAdvanced = useUpdateAdvanced();
  const [rl, setRl] = useState<RateLimitConfig>({ ...rateLimit });
  const [sec, setSec] = useState<SecurityConfig>({ ...security });
  const [comp, setComp] = useState<CompactionConfig>({ ...compaction });
  const isAdvancedDirty = useMemo(
    () => hasDiff(rl, rateLimit) || hasDiff(sec, security) || hasDiff(comp, compaction),
    [rl, rateLimit, sec, security, comp, compaction],
  );

  useEffect(() => { setRl({ ...rateLimit }); }, [rateLimit]);
  useEffect(() => { setSec({ ...security }); }, [security]);
  useEffect(() => { setComp({ ...compaction }); }, [compaction]);

  const handleSave = async (): Promise<void> => {
    await updateAdvanced.mutateAsync({ rateLimit: rl, security: sec, compaction: comp });
    toast.success('Advanced settings saved');
  };

  const showRateLimit = mode === 'all' || mode === 'rateLimit';
  const showSecurity = mode === 'all' || mode === 'security';
  const showCompaction = mode === 'all' || mode === 'compaction';

  return (
    <>
      <Row className="g-3 mb-3">
        {showRateLimit && <Col lg={4}>
          <RateLimitCard rl={rl} setRl={setRl} />
        </Col>}

        {showCompaction && <Col lg={4}>
          <CompactionCard comp={comp} setComp={setComp} />
        </Col>}

        {showSecurity && <Col lg={4}>
          <SecurityCard sec={sec} setSec={setSec} />
        </Col>}
      </Row>

      <SettingsSaveBar>
        <Button type="button" variant="primary" size="sm" onClick={() => { void handleSave(); }} disabled={!isAdvancedDirty || updateAdvanced.isPending}>
          {updateAdvanced.isPending ? 'Saving...' : 'Save All'}
        </Button>
        <SaveStateHint isDirty={isAdvancedDirty} />
      </SettingsSaveBar>
    </>
  );
}
