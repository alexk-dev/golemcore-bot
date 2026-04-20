import { type ReactElement, useEffect, useMemo, useState } from 'react';
import { Button, Card, Col, Form, Row } from '../../components/ui/tailwind-components';
import toast from 'react-hot-toast';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import { useUpdateAdvanced } from '../../hooks/useSettings';
import type {
  CompactionConfig,
  FollowThroughConfig,
  RateLimitConfig,
  ResilienceConfig,
  SecurityConfig,
} from '../../api/settingsTypes';
import { SaveStateHint, SettingsSaveBar } from '../../components/common/SettingsSaveBar';

export type AdvancedMode = 'all' | 'rateLimit' | 'security' | 'compaction' | 'resilience';

function hasDiff<T>(current: T, initial: T): boolean {
  return JSON.stringify(current) !== JSON.stringify(initial);
}

function toNullableInt(value: string): number | null {
  const parsed = parseInt(value, 10);
  return Number.isNaN(parsed) ? null : parsed;
}

function toNullableFloat(value: string): number | null {
  const parsed = Number.parseFloat(value);
  return Number.isNaN(parsed) ? null : parsed;
}

interface AdvancedTabProps {
  rateLimit: RateLimitConfig;
  security: SecurityConfig;
  compaction: CompactionConfig;
  resilience: ResilienceConfig;
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
            Trigger Mode <HelpTip text="Choose whether auto compaction should follow the active model's context window or a fixed token threshold" />
          </Form.Label>
          <Form.Select
            size="sm"
            value={comp.triggerMode ?? 'model_ratio'}
            onChange={(e) => setComp({ ...comp, triggerMode: e.target.value as CompactionConfig['triggerMode'] })}
          >
            <option value="model_ratio">Model Context Ratio</option>
            <option value="token_threshold">Fixed Token Threshold</option>
          </Form.Select>
        </Form.Group>
        {(comp.triggerMode ?? 'model_ratio') === 'model_ratio' ? (
          <Form.Group className="mb-2">
            <Form.Label className="small fw-medium">
              Model Ratio <HelpTip text="Multiply the selected model's max input tokens by this coefficient to decide when to compact" />
            </Form.Label>
            <Form.Control
              size="sm"
              type="number"
              min={0.01}
              max={1}
              step="0.01"
              value={comp.modelThresholdRatio ?? 0.95}
              onChange={(e) => setComp({ ...comp, modelThresholdRatio: toNullableFloat(e.target.value) })}
            />
          </Form.Group>
        ) : (
          <Form.Group className="mb-2">
          <Form.Label className="small fw-medium">
            Max Context Tokens <HelpTip text="Token threshold that triggers automatic context compaction" />
          </Form.Label>
          <Form.Control size="sm" type="number" value={comp.maxContextTokens ?? 50000}
            onChange={(e) => setComp({ ...comp, maxContextTokens: toNullableInt(e.target.value) })} />
        </Form.Group>
        )}
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


interface ResilienceCardProps {
  resilience: ResilienceConfig;
  setResilience: (value: ResilienceConfig) => void;
}

const DEFAULT_FOLLOW_THROUGH: FollowThroughConfig = {
  enabled: true,
  modelTier: 'routing',
  timeoutSeconds: 5,
  maxChainDepth: 1,
};

function readFollowThrough(resilience: ResilienceConfig): FollowThroughConfig {
  return resilience.followThrough ?? DEFAULT_FOLLOW_THROUGH;
}

function ResilienceCard({ resilience, setResilience }: ResilienceCardProps): ReactElement {
  const followThrough = readFollowThrough(resilience);
  const updateFollowThrough = (patch: Partial<FollowThroughConfig>): void => {
    setResilience({ ...resilience, followThrough: { ...followThrough, ...patch } });
  };

  return (
    <Card className="settings-card h-100">
      <Card.Body>
        <SettingsCardTitle
          title="Resilience"
          tip="Fallback routing, retries, degradation, and delayed recovery behavior"
        />
        <Form.Check type="switch" label="Enable" checked={resilience.enabled ?? true}
          onChange={(e) => setResilience({ ...resilience, enabled: e.target.checked })} className="mb-3" />
        <Form.Group className="mb-3">
          <Form.Label className="small fw-medium">
            L2 Provider Fallback Max Attempts <HelpTip text="Maximum number of L2 reroutes within one turn, regardless of strategy. Sequential still stops earlier if the fallback chain is shorter." />
          </Form.Label>
          <Form.Control
            size="sm"
            type="number"
            min={1}
            value={resilience.l2ProviderFallbackMaxAttempts ?? 5}
            onChange={(e) => setResilience({ ...resilience, l2ProviderFallbackMaxAttempts: toNullableInt(e.target.value) })}
          />
        </Form.Group>

        <hr className="my-3" />
        <div className="small fw-semibold mb-2">
          Follow-Through Nudge <HelpTip text="Detect assistant replies that commit to an action without invoking any tool, then inject a synthetic user-role nudge so the agent loop continues." />
        </div>
        <Form.Check type="switch"
          label={<>Enable <HelpTip text="Master switch for the follow-through classifier. Also gated by the top-level Resilience Enable switch." /></>}
          checked={followThrough.enabled ?? true}
          onChange={(e) => updateFollowThrough({ enabled: e.target.checked })}
          className="mb-2" />
        <Form.Group className="mb-2">
          <Form.Label className="small fw-medium">
            Classifier Model Tier <HelpTip text="Model tier used for the classifier LLM call (e.g. routing, fast, balanced)." />
          </Form.Label>
          <Form.Control
            size="sm"
            type="text"
            value={followThrough.modelTier ?? 'routing'}
            onChange={(e) => updateFollowThrough({ modelTier: e.target.value })}
          />
        </Form.Group>
        <Form.Group className="mb-2">
          <Form.Label className="small fw-medium">
            Classifier Timeout (seconds) <HelpTip text="Per-call timeout for the classifier. Exceeding it fails closed (no nudge)." />
          </Form.Label>
          <Form.Control
            size="sm"
            type="number"
            min={1}
            value={followThrough.timeoutSeconds ?? 5}
            onChange={(e) => updateFollowThrough({ timeoutSeconds: toNullableInt(e.target.value) })}
          />
        </Form.Group>
        <Form.Group>
          <Form.Label className="small fw-medium">
            Max Chain Depth <HelpTip text="Maximum consecutive nudges per conversation before the layer stands down. Default 1 (one nudge per stranded commitment)." />
          </Form.Label>
          <Form.Control
            size="sm"
            type="number"
            min={0}
            value={followThrough.maxChainDepth ?? 1}
            onChange={(e) => updateFollowThrough({ maxChainDepth: toNullableInt(e.target.value) })}
          />
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

export function AdvancedTab({ rateLimit, security, compaction, resilience, mode = 'all' }: AdvancedTabProps): ReactElement {
  const updateAdvanced = useUpdateAdvanced();
  const [rl, setRl] = useState<RateLimitConfig>({ ...rateLimit });
  const [sec, setSec] = useState<SecurityConfig>({ ...security });
  const [comp, setComp] = useState<CompactionConfig>({ ...compaction });
  const [res, setRes] = useState<ResilienceConfig>({ ...resilience });
  const isAdvancedDirty = useMemo(
    () => hasDiff(rl, rateLimit) || hasDiff(sec, security) || hasDiff(comp, compaction) || hasDiff(res, resilience),
    [rl, rateLimit, sec, security, comp, compaction, res, resilience],
  );

  useEffect(() => { setRl({ ...rateLimit }); }, [rateLimit]);
  useEffect(() => { setSec({ ...security }); }, [security]);
  useEffect(() => { setComp({ ...compaction }); }, [compaction]);
  useEffect(() => { setRes({ ...resilience }); }, [resilience]);

  const handleSave = async (): Promise<void> => {
    await updateAdvanced.mutateAsync({ rateLimit: rl, security: sec, compaction: comp, resilience: res });
    toast.success('Advanced settings saved');
  };

  const showRateLimit = mode === 'all' || mode === 'rateLimit';
  const showSecurity = mode === 'all' || mode === 'security';
  const showCompaction = mode === 'all' || mode === 'compaction';
  const showResilience = mode === 'all' || mode === 'resilience';

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

        {showResilience && <Col lg={4}>
          <ResilienceCard resilience={res} setResilience={setRes} />
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
