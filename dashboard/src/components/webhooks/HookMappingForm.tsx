import { type ReactElement, useEffect, useMemo, useRef, useState } from 'react';
import { FiEye, FiEyeOff } from 'react-icons/fi';
import type { SystemChannelResponse } from '../../api/system';
import type { HookAction, HookAuthMode, HookMappingDraft } from '../../api/webhooks';
import { cn } from '../../lib/utils';
import HelpTip from '../common/HelpTip';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';
import { Card, CardContent } from '../ui/card';
import { Input, Select, Textarea } from '../ui/field';

interface HookMappingFormProps {
  mapping: HookMappingDraft;
  onChange: (nextMapping: HookMappingDraft) => void;
  onCopyEndpoint: (name: string) => void;
  linkedTelegramUserId?: string | null;
  availableChannels: SystemChannelResponse[];
  channelsLoading: boolean;
}

interface DeliveryChannelOption {
  value: string;
  label: string;
  running: boolean;
  unavailable?: boolean;
}

const ACTION_DESCRIPTIONS: Record<HookAction, string> = {
  wake: 'Fire-and-forget trigger. Returns 200 immediately.',
  agent: 'Runs a full agent turn. Returns 202 and can deliver callback or channel output.',
};

const AUTH_DESCRIPTIONS: Record<HookAuthMode, string> = {
  bearer: 'Uses the global webhook bearer token.',
  hmac: 'Validates signed payload using HMAC-SHA256.',
};

const surfaceClassName = 'rounded-3xl border border-border/80 bg-card/70 p-4 shadow-soft backdrop-blur-sm';
const fieldLabelClassName = 'mb-2 flex items-center gap-2 text-[0.72rem] font-semibold uppercase tracking-[0.16em] text-muted-foreground';
const fieldHelpClassName = 'mt-2 text-sm leading-6 text-muted-foreground';
const controlClassName = 'h-12 rounded-2xl border-border/80 bg-background/80 shadow-none';

function toNullableString(value: string): string | null {
  const normalized = value.trim();
  return normalized.length > 0 ? normalized : null;
}

function toNullableTemplate(value: string): string | null {
  return value.length > 0 ? value : null;
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

interface FieldHeadingProps {
  label: string;
  help: string;
}

function FieldHeading({ label, help }: FieldHeadingProps): ReactElement {
  return (
    <div className={fieldLabelClassName}>
      <span>{label}</span>
      <HelpTip text={help} />
    </div>
  );
}

interface HookIdentitySectionProps {
  mapping: HookMappingDraft;
  onChange: (nextMapping: HookMappingDraft) => void;
  onCopyEndpoint: (name: string) => void;
}

function HookIdentitySection({
  mapping,
  onChange,
  onCopyEndpoint,
}: HookIdentitySectionProps): ReactElement {
  const normalizedName = mapping.name.trim();

  return (
    <div className="space-y-4">
      <div className="grid gap-4 xl:grid-cols-[minmax(0,1.2fr)_minmax(12rem,0.7fr)_minmax(12rem,0.7fr)]">
        <div className={surfaceClassName}>
          <FieldHeading
            label="Hook Name"
            help="Endpoint path segment: /api/hooks/{name}. Use lowercase letters, digits, and hyphens."
          />
          <div className="flex min-h-12 overflow-hidden rounded-2xl border border-border/80 bg-background/80 shadow-sm">
            <span className="inline-flex items-center border-r border-border/70 bg-muted/30 px-3 text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">
              /api/hooks/
            </span>
            <input
              value={mapping.name}
              onChange={(event) => onChange({ ...mapping, name: event.target.value })}
              placeholder="github-push"
              autoCapitalize="off"
              autoCorrect="off"
              spellCheck={false}
              className="h-12 w-full bg-transparent px-3 text-sm text-foreground outline-none placeholder:text-muted-foreground/70"
            />
          </div>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            {normalizedName.length > 0 ? (
              <>
                <Badge variant="secondary" className="normal-case tracking-normal">
                  /api/hooks/{normalizedName}
                </Badge>
                <Button variant="secondary" size="sm" onClick={() => onCopyEndpoint(normalizedName)}>
                  Copy URL
                </Button>
              </>
            ) : (
              <p className="text-sm text-muted-foreground">The endpoint path is generated from the hook name.</p>
            )}
          </div>
        </div>

        <div className={surfaceClassName}>
          <FieldHeading
            label="Action"
            help="Choose wake for lightweight event ingestion or agent for full reasoning and tool execution."
          />
          <Select
            value={mapping.action}
            onChange={(event) => onChange({ ...mapping, action: resolveHookAction(event.target.value) })}
            className={controlClassName}
          >
            <option value="wake">Wake</option>
            <option value="agent">Agent</option>
          </Select>
          <p className={fieldHelpClassName}>{ACTION_DESCRIPTIONS[mapping.action]}</p>
        </div>

        <div className={surfaceClassName}>
          <FieldHeading
            label="Auth Mode"
            help="Bearer is easiest. HMAC is recommended for third-party webhook providers such as GitHub or Stripe."
          />
          <Select
            value={mapping.authMode}
            onChange={(event) => onChange({ ...mapping, authMode: resolveHookAuthMode(event.target.value) })}
            className={controlClassName}
          >
            <option value="bearer">Bearer</option>
            <option value="hmac">HMAC-SHA256</option>
          </Select>
          <p className={fieldHelpClassName}>{AUTH_DESCRIPTIONS[mapping.authMode]}</p>
        </div>
      </div>

      <div className={surfaceClassName}>
        <FieldHeading
          label="Message Template"
          help="Template for the message sent to the bot. Use {field.path} placeholders from the JSON payload. If empty, raw payload is used."
        />
        <Textarea
          rows={4}
          value={mapping.messageTemplate ?? ''}
          onChange={(event) => onChange({ ...mapping, messageTemplate: toNullableTemplate(event.target.value) })}
          placeholder="Push to {repository.full_name} by {pusher.name}: {head_commit.message}"
          className="min-h-[8.5rem] rounded-2xl border-border/80 bg-background/80 shadow-none"
        />
      </div>
    </div>
  );
}

interface HookSecuritySectionProps {
  mapping: HookMappingDraft;
  onChange: (nextMapping: HookMappingDraft) => void;
}

function HookSecuritySection({ mapping, onChange }: HookSecuritySectionProps): ReactElement | null {
  const [showHmacSecret, setShowHmacSecret] = useState(false);

  if (mapping.authMode !== 'hmac') {
    return null;
  }

  const hmacToggleLabel = showHmacSecret ? 'Hide HMAC secret' : 'Show HMAC secret';

  return (
    <div className="grid gap-4 xl:grid-cols-3">
      <div className={surfaceClassName}>
        <FieldHeading
          label="Signature Header"
          help="Header containing the signature from the provider, for example x-hub-signature-256."
        />
        <Input
          value={mapping.hmacHeader ?? ''}
          onChange={(event) => onChange({ ...mapping, hmacHeader: toNullableString(event.target.value) })}
          placeholder="x-hub-signature-256"
          className={controlClassName}
        />
      </div>

      <div className={surfaceClassName}>
        <FieldHeading
          label="HMAC Secret"
          help="Shared secret used to verify payload integrity."
        />
        <div className="flex gap-2">
          <Input
            type={showHmacSecret ? 'text' : 'password'}
            value={mapping.hmacSecret ?? ''}
            onChange={(event) => onChange({ ...mapping, hmacSecret: toNullableString(event.target.value) })}
            autoComplete="new-password"
            placeholder={mapping.hmacSecretPresent === true ? 'Configured' : 'Enter HMAC secret'}
            className={cn(controlClassName, 'flex-1')}
          />
          <Button
            variant="secondary"
            size="icon"
            className="h-12 w-12 shrink-0 rounded-2xl"
            aria-label={hmacToggleLabel}
            title={hmacToggleLabel}
            aria-pressed={showHmacSecret}
            onClick={() => setShowHmacSecret((current) => !current)}
          >
            {showHmacSecret ? <FiEyeOff /> : <FiEye />}
          </Button>
        </div>
      </div>

      <div className={surfaceClassName}>
        <FieldHeading
          label="Signature Prefix"
          help="Optional prefix stripped from the signature header before compare, for example sha256=."
        />
        <Input
          value={mapping.hmacPrefix ?? ''}
          onChange={(event) => onChange({ ...mapping, hmacPrefix: toNullableString(event.target.value) })}
          placeholder="sha256="
          className={controlClassName}
        />
      </div>
    </div>
  );
}

interface HookAgentSectionProps {
  mapping: HookMappingDraft;
  onChange: (nextMapping: HookMappingDraft) => void;
  linkedTelegramUserId?: string | null;
  availableChannels: SystemChannelResponse[];
  channelsLoading: boolean;
}

function HookAgentSection({
  mapping,
  onChange,
  linkedTelegramUserId,
  availableChannels,
  channelsLoading,
}: HookAgentSectionProps): ReactElement | null {
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

  const channelOptions = useMemo<DeliveryChannelOption[]>(() => {
    const options: DeliveryChannelOption[] = availableChannels.map((channel) => ({
      value: channel.type,
      label: channel.running ? channel.type : `${channel.type} · offline`,
      running: channel.running,
    }));
    if (normalizedChannel != null && !options.some((option) => option.value === normalizedChannel)) {
      options.unshift({
        value: normalizedChannel,
        label: `${normalizedChannel} · unavailable`,
        running: false,
        unavailable: true,
      });
    }
    return options;
  }, [availableChannels, normalizedChannel]);

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
    const channel = normalizeChannel(rawValue);
    const shouldPopulateTelegramTarget = mapping.deliver
      && channel === 'telegram'
      && telegramTarget != null
      && (mapping.to == null || mapping.to.trim().length === 0);

    onChange({
      ...mapping,
      channel,
      to: shouldPopulateTelegramTarget ? telegramTarget : mapping.to,
    });
  };

  const resolvedTargetValue = mapping.to
    ?? (mapping.deliver && isTelegramDelivery && telegramTarget != null ? telegramTarget : '');
  const targetPlaceholder = isTelegramDelivery && telegramTarget != null
    ? telegramTarget
    : 'chat id';
  const deliveryChannelsHelp = channelsLoading
    ? 'Loading registered delivery channels...'
    : channelOptions.length === 0
      ? 'No delivery channels are currently registered. Channels come from plugins or built-in channel adapters.'
      : channelOptions.some((channel) => channel.unavailable)
        ? 'This mapping points to a channel that is not currently registered.'
        : 'Only registered delivery channels are shown here.';

  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(14rem,16rem)_minmax(0,1fr)]">
      <div className={surfaceClassName}>
        <FieldHeading
          label="Model Tier"
          help="Optional tier override for this hook: balanced, smart, coding, or deep."
        />
        <Select
          value={mapping.model ?? ''}
          onChange={(event) => onChange({ ...mapping, model: toNullableString(event.target.value) })}
          className={controlClassName}
        >
          <option value="">Default</option>
          <option value="balanced">Balanced</option>
          <option value="smart">Smart</option>
          <option value="coding">Coding</option>
          <option value="deep">Deep</option>
        </Select>
        <p className={fieldHelpClassName}>
          Leave empty to inherit the default routing tier for agent webhook requests.
        </p>
      </div>

      <div className={cn(surfaceClassName, 'bg-gradient-to-br from-card via-card to-muted/20')}>
        <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
          <div className="space-y-1">
            <div className="flex items-center gap-2 text-sm font-semibold text-foreground">
              <span>Delivery to Channel</span>
              <HelpTip text="Forward the completed agent response into a registered channel such as Telegram." />
            </div>
            <p className="text-sm leading-6 text-muted-foreground">
              Route the final agent output into one of the registered delivery channels.
            </p>
          </div>

          <button
            type="button"
            role="switch"
            aria-checked={mapping.deliver}
            onClick={() => handleDeliverChange(!mapping.deliver)}
            className={cn(
              'inline-flex items-center gap-3 self-start rounded-full border px-3 py-2 text-sm font-semibold transition-all',
              mapping.deliver
                ? 'border-primary/30 bg-primary/10 text-foreground shadow-soft'
                : 'border-border/80 bg-background/80 text-muted-foreground'
            )}
          >
            <span className={cn(
              'relative h-6 w-11 rounded-full transition-colors',
              mapping.deliver ? 'bg-primary' : 'bg-muted'
            )}
            >
              <span className={cn(
                'absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform',
                mapping.deliver ? 'translate-x-5' : 'translate-x-0'
              )}
              />
            </span>
            <span>{mapping.deliver ? 'Enabled' : 'Disabled'}</span>
          </button>
        </div>

        <div className={cn('mt-5 grid gap-4 md:grid-cols-2', !mapping.deliver && 'opacity-75')}>
          <div>
            <FieldHeading
              label="Channel"
              help="Choose from channels currently registered in the runtime. Internal web and webhook transports are excluded."
            />
            <Select
              value={normalizedChannel ?? ''}
              disabled={!mapping.deliver}
              onChange={(event) => handleChannelChange(event.target.value)}
              className={controlClassName}
            >
              <option value="">Select channel</option>
              {channelOptions.map((channel) => (
                <option key={channel.value} value={channel.value}>
                  {channel.label}
                </option>
              ))}
            </Select>
            <p className={fieldHelpClassName}>{deliveryChannelsHelp}</p>
          </div>

          <div>
            <FieldHeading
              label="Target ID"
              help="Target chat or user id on the selected channel. For Telegram, the connected user id is injected automatically when available."
            />
            <Input
              value={resolvedTargetValue}
              disabled={!mapping.deliver}
              onChange={(event) => onChange({ ...mapping, to: toNullableString(event.target.value) })}
              placeholder={targetPlaceholder}
              className={controlClassName}
            />
            <div className="mt-2 flex min-h-6 flex-wrap items-center gap-2">
              {isTelegramDelivery && telegramTarget != null && (
                <Badge variant="info" className="normal-case tracking-normal">
                  Connected Telegram user: {telegramTarget}
                </Badge>
              )}
              {isTelegramDelivery && telegramTarget == null && (
                <span className="text-sm text-amber-600 dark:text-amber-300">
                  Telegram is selected, but no connected Telegram user id is available yet.
                </span>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

export function HookMappingForm({
  mapping,
  onChange,
  onCopyEndpoint,
  linkedTelegramUserId,
  availableChannels,
  channelsLoading,
}: HookMappingFormProps): ReactElement {
  return (
    <Card className="webhook-editor-card overflow-hidden border-border/80 bg-card/70">
      <CardContent className="space-y-5 p-4 sm:p-5">
        <HookIdentitySection mapping={mapping} onChange={onChange} onCopyEndpoint={onCopyEndpoint} />
        <HookSecuritySection mapping={mapping} onChange={onChange} />
        <HookAgentSection
          mapping={mapping}
          onChange={onChange}
          linkedTelegramUserId={linkedTelegramUserId}
          availableChannels={availableChannels}
          channelsLoading={channelsLoading}
        />
      </CardContent>
    </Card>
  );
}
