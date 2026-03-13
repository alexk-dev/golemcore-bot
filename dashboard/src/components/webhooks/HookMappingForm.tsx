import { Badge, Button, Card, Col, Form, InputGroup, Row } from 'react-bootstrap';
import HelpTip from '../common/HelpTip';
import type { HookAction, HookAuthMode, HookMappingDraft } from '../../api/webhooks';

interface HookMappingFormProps {
  mapping: HookMappingDraft;
  onChange: (nextMapping: HookMappingDraft) => void;
  onCopyEndpoint: (name: string) => void;
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

function HookIdentitySection({
  mapping,
  onChange,
  onCopyEndpoint,
}: HookMappingFormProps) {
  const normalizedName = mapping.name.trim();

  return (
    <Row className="g-3">
      <Col md={4}>
        <Form.Group>
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
        <Form.Group>
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
        <Form.Group>
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
        <Form.Group>
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
  if (mapping.authMode !== 'hmac') {
    return null;
  }

  return (
    <Row className="g-3 mt-0">
      <Col md={4}>
        <Form.Group>
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
        <Form.Group>
          <Form.Label className="small fw-medium">
            HMAC Secret
            <HelpTip text="Shared secret used to verify payload integrity." />
          </Form.Label>
          <Form.Control
            size="sm"
            type="password"
            value={mapping.hmacSecret ?? ''}
            onChange={(event) => onChange({ ...mapping, hmacSecret: toNullableString(event.target.value) })}
            autoComplete="new-password"
            placeholder={mapping.hmacSecretPresent === true ? 'Configured (hidden)' : ''}
          />
        </Form.Group>
      </Col>

      <Col md={4}>
        <Form.Group>
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
}

function HookAgentSection({ mapping, onChange }: HookAgentSectionProps) {
  if (mapping.action !== 'agent') {
    return null;
  }

  return (
    <Row className="g-3 mt-0">
      <Col md={4}>
        <Form.Group>
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

      <Col md={4}>
        <Form.Check
          type="switch"
          className="mt-md-4 mt-2"
          label={
            <>
              Deliver to channel
              <HelpTip text="Forward completed agent response to a channel (e.g. Telegram)." />
            </>
          }
          checked={mapping.deliver}
          onChange={(event) => onChange({ ...mapping, deliver: event.target.checked })}
        />
      </Col>

      {mapping.deliver && (
        <>
          <Col md={2}>
            <Form.Group>
              <Form.Label className="small fw-medium">Channel</Form.Label>
              <Form.Control
                size="sm"
                value={mapping.channel ?? ''}
                onChange={(event) => onChange({ ...mapping, channel: toNullableString(event.target.value) })}
                placeholder="telegram"
              />
            </Form.Group>
          </Col>

          <Col md={2}>
            <Form.Group>
              <Form.Label className="small fw-medium">To</Form.Label>
              <Form.Control
                size="sm"
                value={mapping.to ?? ''}
                onChange={(event) => onChange({ ...mapping, to: toNullableString(event.target.value) })}
                placeholder="chat id"
              />
            </Form.Group>
          </Col>
        </>
      )}
    </Row>
  );
}

export function HookMappingForm({ mapping, onChange, onCopyEndpoint }: HookMappingFormProps) {
  return (
    <Card className="webhook-editor-card border">
      <Card.Body className="p-3">
        <HookIdentitySection mapping={mapping} onChange={onChange} onCopyEndpoint={onCopyEndpoint} />
        <HookSecuritySection mapping={mapping} onChange={onChange} />
        <HookAgentSection mapping={mapping} onChange={onChange} />
      </Card.Body>
    </Card>
  );
}
