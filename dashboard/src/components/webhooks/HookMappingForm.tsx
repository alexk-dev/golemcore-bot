import { useEffect, useRef, useState } from 'react';
import { Badge, Button, Card, Col, Form, InputGroup, Row } from 'react-bootstrap';
import { FiEye, FiEyeOff } from 'react-icons/fi';
import HelpTip from '../common/HelpTip';
import type { HookAction, HookAuthMode, HookMappingDraft } from '../../api/webhooks';

interface HookMappingFormProps {
  mapping: HookMappingDraft;
  onChange: (nextMapping: HookMappingDraft) => void;
  onCopyEndpoint: (name: string) => void;
  linkedTelegramUserId?: string | null;
}

const ACTION_DESCRIPTIONS: Record<HookAction, string> = {
  wake: 'Fire-and-forget trigger. Returns 200 immediately.',
  agent: 'Runs a full agent turn. Returns 202 and can deliver callback/channel response.',
};

const AUTH_DESCRIPTIONS: Record<HookAuthMode, string> = {
  bearer: 'Uses global webhook bearer token.',
  hmac: 'Validates signed payload using HMAC-SHA256.',
};

function toNullableString(value: string): string | null {
  const normalized = value.trim();
  return normalized.length > 0 ? normalized : null;
}

function resolveHookAction(value: string): HookAction {
  return value === 'agent' ? 'agent' : 'wake';
}

function resolveHookAuthMode(value: string): HookAuthMode {
  return value === 'hmac' ? 'hmac' : 'bearer';
}

function normalizeChannel(value: string | null | undefined): string | null {
  if (value == null) {
    return null;
  }
  const normalized = value.trim().toLowerCase();
  return normalized.length > 0 ? normalized : null;
}

function HookIdentitySection({
  mapping,
  onChange,
  onCopyEndpoint,
}: HookMappingFormProps) {
  const normalizedName = mapping.name.trim();

  return (
    <Row className="g-3">
      <Col md={4}>
        <Form.Group className="h-100">
          <Form.Label className="small fw-medium">
            Hook Name
            <HelpTip text="Endpoint path segment: /api/hooks/{name}. Use lowercase letters, digits, and hyphens." />
          </Form.Label>
          <InputGroup size="sm">
            <InputGroup.Text>/api/hooks/</InputGroup.Text>
            <Form.Control
              value={mapping.name}
              onChange={(event) => onChange({ ...mapping, name: event.target.value })}
              placeholder="github-push"
              autoCapitalize="off"
              autoCorrect="off"
              spellCheck={false}
            />
          </InputGroup>
          {normalizedName.length > 0 && (
            <div className="d-flex align-items-center gap-2 mt-2">
              <Badge bg="secondary" className="text-truncate webhook-endpoint-badge">
                /api/hooks/{normalizedName}
              </Badge>
              <Button
                type="button"
                size="sm"
                variant="secondary"
                onClick={() => onCopyEndpoint(normalizedName)}
              >
                Copy URL
              </Button>
            </div>
          )}
        </Form.Group>
      </Col>

      <Col md={4}>
        <Form.Group className="h-100">
          <Form.Label className="small fw-medium">
            Action
            <HelpTip text="Choose wake for lightweight event ingestion or agent for full reasoning/tool execution." />
          </Form.Label>
          <Form.Select
            size="sm"
            value={mapping.action}
            onChange={(event) => onChange({ ...mapping, action: resolveHookAction(event.target.value) })}
          >
            <option value="wake">Wake</option>
            <option value="agent">Agent</option>
          </Form.Select>
          <div className="small text-body-secondary mt-1">{ACTION_DESCRIPTIONS[mapping.action]}</div>
        </Form.Group>
      </Col>

      <Col md={4}>
        <Form.Group className="h-100">
          <Form.Label className="small fw-medium">
            Auth Mode
            <HelpTip text="Bearer is easiest. HMAC is recommended for third-party webhooks (GitHub, Stripe, etc.)." />
          </Form.Label>
          <Form.Select
            size="sm"
            value={mapping.authMode}
            onChange={(event) => onChange({ ...mapping, authMode: resolveHookAuthMode(event.target.value) })}
          >
            <option value="bearer">Bearer</option>
            <option value="hmac">HMAC-SHA256</option>
          </Form.Select>
          <div className="small text-body-secondary mt-1">{AUTH_DESCRIPTIONS[mapping.authMode]}</div>
        </Form.Group>
      </Col>

      <Col md={12}>
        <Form.Group className="h-100">
          <Form.Label className="small fw-medium">
            Message Template
            <HelpTip text="Template for message sent to the bot. Use {field.path} placeholders from JSON payload. If empty, raw payload is used." />
          </Form.Label>
          <Form.Control
            size="sm"
            as="textarea"
            rows={3}
            value={mapping.messageTemplate ?? ''}
            onChange={(event) => onChange({ ...mapping, messageTemplate: toNullableString(event.target.value) })}
            placeholder="Push to {repository.full_name} by {pusher.name}: {head_commit.message}"
          />
        </Form.Group>
      </Col>
    </Row>
  );
}

interface HookSecuritySectionProps {
  mapping: HookMappingDraft;
  onChange: (nextMapping: HookMappingDraft) => void;
}

function HookSecuritySection({ mapping, onChange }: HookSecuritySectionProps) {
  const [showHmacSecret, setShowHmacSecret] = useState(false);

  if (mapping.authMode !== 'hmac') {
    return null;
  }

  const hmacToggleLabel = showHmacSecret ? 'Hide HMAC secret' : 'Show HMAC secret';

  return (
    <Row className="g-3 mt-0">
      <Col md={4}>
        <Form.Group className="h-100">
          <Form.Label className="small fw-medium">
            Signature Header
            <HelpTip text="Header containing signature from provider (e.g. x-hub-signature-256)." />
          </Form.Label>
          <Form.Control
            size="sm"
            value={mapping.hmacHeader ?? ''}
            onChange={(event) => onChange({ ...mapping, hmacHeader: toNullableString(event.target.value) })}
            placeholder="x-hub-signature-256"
          />
        </Form.Group>
      </Col>

      <Col md={4}>
        <Form.Group className="h-100">
          <Form.Label className="small fw-medium">
            HMAC Secret
            <HelpTip text="Shared secret used to verify payload integrity." />
          </Form.Label>
          <InputGroup size="sm">
            <Form.Control
              size="sm"
              type={showHmacSecret ? 'text' : 'password'}
              value={mapping.hmacSecret ?? ''}
              onChange={(event) => onChange({ ...mapping, hmacSecret: toNullableString(event.target.value) })}
              autoComplete="new-password"
              placeholder={mapping.hmacSecretPresent === true ? 'Configured' : 'Enter HMAC secret'}
            />
            <Button
              type="button"
              variant="secondary"
              aria-label={hmacToggleLabel}
              title={hmacToggleLabel}
              aria-pressed={showHmacSecret}
              onClick={() => setShowHmacSecret((current) => !current)}
            >
              {showHmacSecret ? <FiEyeOff /> : <FiEye />}
            </Button>
          </InputGroup>
        </Form.Group>
      </Col>

      <Col md={4}>
        <Form.Group className="h-100">
          <Form.Label className="small fw-medium">
            Signature Prefix
            <HelpTip text="Optional prefix stripped from header before compare (e.g. sha256=)." />
          </Form.Label>
          <Form.Control
            size="sm"
            value={mapping.hmacPrefix ?? ''}
            onChange={(event) => onChange({ ...mapping, hmacPrefix: toNullableString(event.target.value) })}
            placeholder="sha256="
          />
        </Form.Group>
      </Col>
    </Row>
  );
}

interface HookAgentSectionProps {
  mapping: HookMappingDraft;
  onChange: (nextMapping: HookMappingDraft) => void;
  linkedTelegramUserId?: string | null;
}

function HookAgentSection({ mapping, onChange, linkedTelegramUserId }: HookAgentSectionProps) {
  const telegramAutofillKeyRef = useRef<string | null>(null);
  const normalizedChannel = normalizeChannel(mapping.channel);
  const isTelegramDelivery = normalizedChannel === 'telegram';
  const isAgentMapping = mapping.action === 'agent';
  const telegramTarget = linkedTelegramUserId ?? null;
  const shouldAutoFillTelegramTarget = isAgentMapping
    && mapping.deliver
    && isTelegramDelivery
    && telegramTarget != null
    && (mapping.to == null || mapping.to.trim().length === 0);
  const telegramAutofillKey = shouldAutoFillTelegramTarget
    ? `${mapping.name}|${normalizedChannel}|${telegramTarget}`
    : null;

  useEffect(() => {
    if (telegramAutofillKey == null) {
      telegramAutofillKeyRef.current = null;
      return;
    }
    if (telegramAutofillKeyRef.current === telegramAutofillKey) {
      return;
    }
    telegramAutofillKeyRef.current = telegramAutofillKey;
    onChange({ ...mapping, to: telegramTarget });
  }, [telegramAutofillKey, mapping, onChange, telegramTarget]);

  if (!isAgentMapping) {
    return null;
  }

  const handleDeliverChange = (deliver: boolean): void => {
    const shouldPopulateTelegramTarget = deliver
      && isTelegramDelivery
      && telegramTarget != null
      && (mapping.to == null || mapping.to.trim().length === 0);

    onChange({
      ...mapping,
      deliver,
      to: shouldPopulateTelegramTarget ? telegramTarget : mapping.to,
    });
  };

  const handleChannelChange = (rawValue: string): void => {
    const channel = toNullableString(rawValue);
    const shouldPopulateTelegramTarget = mapping.deliver
      && normalizeChannel(channel) === 'telegram'
      && telegramTarget != null
      && (mapping.to == null || mapping.to.trim().length === 0);

    onChange({
      ...mapping,
      channel,
      to: shouldPopulateTelegramTarget ? telegramTarget : mapping.to,
    });
  };

  const targetPlaceholder = isTelegramDelivery && telegramTarget != null
    ? telegramTarget
    : 'chat id';

  return (
    <Row className="g-3 mt-0">
      <Col xl={3} md={6}>
        <Form.Group className="h-100">
          <Form.Label className="small fw-medium">
            Model Tier
            <HelpTip text="Optional tier override for this hook: balanced, smart, coding, deep." />
          </Form.Label>
          <Form.Select
            size="sm"
            value={mapping.model ?? ''}
            onChange={(event) => onChange({ ...mapping, model: toNullableString(event.target.value) })}
          >
            <option value="">Default</option>
            <option value="balanced">Balanced</option>
            <option value="smart">Smart</option>
            <option value="coding">Coding</option>
            <option value="deep">Deep</option>
          </Form.Select>
        </Form.Group>
      </Col>

      <Col xl={3} md={6}>
        <Form.Group className="h-100">
          <Form.Label className="small fw-medium">
            Delivery
            <HelpTip text="Forward completed agent response to a messaging channel such as Telegram." />
          </Form.Label>
          <div className="h-100 border rounded px-3 py-2 bg-body-tertiary d-flex align-items-center">
            <Form.Check
              type="switch"
              className="mb-0"
              label="Deliver to channel"
              checked={mapping.deliver}
              onChange={(event) => handleDeliverChange(event.target.checked)}
            />
          </div>
        </Form.Group>
      </Col>

      <Col xl={3} md={6}>
        <Form.Group className="h-100">
          <Form.Label className="small fw-medium">
            Channel
            <HelpTip text="Target delivery channel type. Use telegram to route the response into the linked Telegram chat." />
          </Form.Label>
          <Form.Control
            size="sm"
            value={mapping.channel ?? ''}
            disabled={!mapping.deliver}
            onChange={(event) => handleChannelChange(event.target.value)}
            placeholder="telegram"
          />
        </Form.Group>
      </Col>

      <Col xl={3} md={6}>
        <Form.Group className="h-100">
          <Form.Label className="small fw-medium">
            Target ID
            <HelpTip text="Target chat or user id on the selected delivery channel. For Telegram, the linked user id is used automatically when available." />
          </Form.Label>
          <Form.Control
            size="sm"
            value={mapping.to ?? ''}
            disabled={!mapping.deliver}
            onChange={(event) => onChange({ ...mapping, to: toNullableString(event.target.value) })}
            placeholder={targetPlaceholder}
          />
        </Form.Group>
      </Col>
    </Row>
  );
}

export function HookMappingForm({
  mapping,
  onChange,
  onCopyEndpoint,
  linkedTelegramUserId,
}: HookMappingFormProps) {
  return (
    <Card className="webhook-editor-card border">
      <Card.Body className="p-3">
        <HookIdentitySection mapping={mapping} onChange={onChange} onCopyEndpoint={onCopyEndpoint} />
        <HookSecuritySection mapping={mapping} onChange={onChange} />
        <HookAgentSection
          mapping={mapping}
          onChange={onChange}
          linkedTelegramUserId={linkedTelegramUserId}
        />
      </Card.Body>
    </Card>
  );
}
