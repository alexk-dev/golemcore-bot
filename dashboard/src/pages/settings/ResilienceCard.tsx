import type { ReactElement } from 'react';
import { Card, Form } from '../../components/ui/tailwind-components';
import HelpTip from '../../components/common/HelpTip';
import SettingsCardTitle from '../../components/common/SettingsCardTitle';
import type {
  AutoProceedConfig,
  FollowThroughConfig,
  ResilienceConfig,
} from '../../api/settingsTypes';

function toNullableInt(value: string): number | null {
  const parsed = parseInt(value, 10);
  return Number.isNaN(parsed) ? null : parsed;
}

const DEFAULT_FOLLOW_THROUGH: FollowThroughConfig = {
  enabled: true,
  modelTier: 'routing',
  timeoutSeconds: 5,
  maxChainDepth: 1,
};

const DEFAULT_AUTO_PROCEED: AutoProceedConfig = {
  enabled: false,
  modelTier: 'routing',
  timeoutSeconds: 5,
  maxChainDepth: 2,
};

function readFollowThrough(resilience: ResilienceConfig): FollowThroughConfig {
  return resilience.followThrough ?? DEFAULT_FOLLOW_THROUGH;
}

function readAutoProceed(resilience: ResilienceConfig): AutoProceedConfig {
  return resilience.autoProceed ?? DEFAULT_AUTO_PROCEED;
}

export interface ResilienceCardProps {
  resilience: ResilienceConfig;
  setResilience: (value: ResilienceConfig) => void;
}

export function ResilienceCard({ resilience, setResilience }: ResilienceCardProps): ReactElement {
  const followThrough = readFollowThrough(resilience);
  const autoProceed = readAutoProceed(resilience);
  const updateFollowThrough = (patch: Partial<FollowThroughConfig>): void => {
    setResilience({ ...resilience, followThrough: { ...followThrough, ...patch } });
  };
  const updateAutoProceed = (patch: Partial<AutoProceedConfig>): void => {
    setResilience({ ...resilience, autoProceed: { ...autoProceed, ...patch } });
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

        <hr className="my-3" />
        <div className="small fw-semibold mb-2">
          Auto-Proceed <HelpTip text="When the assistant ends with a rhetorical confirmation question that has a single obvious forward path, dispatch a synthetic affirmative so the agent keeps moving. Off by default — aggressive pushing mode. Fails closed on destructive actions and skips when Follow-Through already fired this turn." />
        </div>
        <Form.Check type="switch"
          label={<>Enable <HelpTip text="Master switch for the auto-proceed classifier. Also gated by the top-level Resilience Enable switch." /></>}
          checked={autoProceed.enabled ?? false}
          onChange={(e) => updateAutoProceed({ enabled: e.target.checked })}
          className="mb-2" />
        <Form.Group className="mb-2">
          <Form.Label className="small fw-medium">
            Classifier Model Tier <HelpTip text="Model tier used for the classifier LLM call (e.g. routing, fast, balanced)." />
          </Form.Label>
          <Form.Control
            size="sm"
            type="text"
            value={autoProceed.modelTier ?? 'routing'}
            onChange={(e) => updateAutoProceed({ modelTier: e.target.value })}
          />
        </Form.Group>
        <Form.Group className="mb-2">
          <Form.Label className="small fw-medium">
            Classifier Timeout (seconds) <HelpTip text="Per-call timeout for the classifier. Exceeding it fails closed (no affirmation)." />
          </Form.Label>
          <Form.Control
            size="sm"
            type="number"
            min={1}
            value={autoProceed.timeoutSeconds ?? 5}
            onChange={(e) => updateAutoProceed({ timeoutSeconds: toNullableInt(e.target.value) })}
          />
        </Form.Group>
        <Form.Group>
          <Form.Label className="small fw-medium">
            Max Chain Depth <HelpTip text="Maximum consecutive auto-affirmations per conversation before the layer stands down. Default 2." />
          </Form.Label>
          <Form.Control
            size="sm"
            type="number"
            min={0}
            value={autoProceed.maxChainDepth ?? 2}
            onChange={(e) => updateAutoProceed({ maxChainDepth: toNullableInt(e.target.value) })}
          />
        </Form.Group>
      </Card.Body>
    </Card>
  );
}
