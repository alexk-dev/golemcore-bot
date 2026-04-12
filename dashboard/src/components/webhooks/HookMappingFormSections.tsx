import { type ReactElement, useState } from 'react';
import { FiEye, FiEyeOff } from 'react-icons/fi';
import type { HookMappingDraft } from '../../api/webhooks';
import { cn } from '../../lib/utils';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';
import { Input, Select, Textarea } from '../ui/field';
import { HookMappingFieldHeading } from './HookMappingFieldHeading';
import { HOOK_TEMPLATE_EXAMPLES, HOOK_TEMPLATE_PATH_HINT } from './hookTemplateExamples';
import {
  ACTION_DESCRIPTIONS,
  AUTH_DESCRIPTIONS,
  controlClassName,
  fieldHelpClassName,
  resolveHookAction,
  resolveHookAuthMode,
  surfaceClassName,
  toNullableString,
  toNullableTemplate,
} from './HookMappingFormUtils';

interface HookIdentitySectionProps {
  mapping: HookMappingDraft;
  onChange: (nextMapping: HookMappingDraft) => void;
  onCopyEndpoint: (name: string) => void;
}

export function HookIdentitySection({
  mapping,
  onChange,
  onCopyEndpoint,
}: HookIdentitySectionProps): ReactElement {
  const normalizedName = mapping.name.trim();

  return (
    <div className="space-y-4">
      <div className="grid gap-4 xl:grid-cols-[minmax(0,1.2fr)_minmax(12rem,0.7fr)_minmax(12rem,0.7fr)]">
        <div className={surfaceClassName}>
          <HookMappingFieldHeading
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
          <HookMappingFieldHeading
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
          <HookMappingFieldHeading
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
        <HookMappingFieldHeading
          label="Message Template"
          help="Template for the message sent to the bot. Use JSON-path placeholders such as {request.command} or {payload.meta.client_id}. If empty, raw payload is used."
        />
        <Textarea
          rows={4}
          value={mapping.messageTemplate ?? ''}
          onChange={(event) => onChange({ ...mapping, messageTemplate: toNullableTemplate(event.target.value) })}
          placeholder="Push to {repository.full_name} by {pusher.name}: {head_commit.message}"
          className="min-h-[8.5rem] rounded-2xl border-border/80 bg-background/80 shadow-none"
        />
        <p className={fieldHelpClassName}>{HOOK_TEMPLATE_PATH_HINT}</p>
        <div className="mt-3 rounded-2xl border border-border/80 bg-background/70 p-3">
          <div className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">Example paths</div>
          <div className="mt-3 grid gap-2 xl:grid-cols-2">
            {HOOK_TEMPLATE_EXAMPLES.map((example) => (
              <div
                key={example.path}
                className="rounded-2xl border border-border/70 bg-card/70 px-3 py-2 shadow-soft"
              >
                <code className="text-[0.78rem] text-foreground">{example.path}</code>
                <p className="mt-2 text-sm leading-6 text-muted-foreground">{example.description}</p>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

interface HookSecuritySectionProps {
  mapping: HookMappingDraft;
  onChange: (nextMapping: HookMappingDraft) => void;
}

export function HookSecuritySection({ mapping, onChange }: HookSecuritySectionProps): ReactElement | null {
  const [showHmacSecret, setShowHmacSecret] = useState(false);

  if (mapping.authMode !== 'hmac') {
    return null;
  }

  const hmacToggleLabel = showHmacSecret ? 'Hide HMAC secret' : 'Show HMAC secret';

  return (
    <div className="grid gap-4 xl:grid-cols-3">
      <div className={surfaceClassName}>
        <HookMappingFieldHeading
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
        <HookMappingFieldHeading
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
        <HookMappingFieldHeading
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
