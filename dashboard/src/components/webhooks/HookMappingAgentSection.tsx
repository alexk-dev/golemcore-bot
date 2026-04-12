import { type ReactElement, useEffect, useMemo, useRef } from 'react';
import type { SystemChannelResponse } from '../../api/system';
import type { HookMappingDraft } from '../../api/webhooks';
import { cn } from '../../lib/utils';
import { getExplicitModelTierOptions } from '../../lib/modelTiers';
import HelpTip from '../common/HelpTip';
import { Badge } from '../ui/badge';
import { Input, Select } from '../ui/field';
import { HookMappingFieldHeading } from './HookMappingFieldHeading';
import {
  controlClassName,
  fieldHelpClassName,
  normalizeChannel,
  shouldPopulateTelegramTarget,
  surfaceClassName,
  toNullableString,
  type DeliveryChannelOption,
} from './HookMappingFormUtils';

interface HookAgentSectionProps {
  mapping: HookMappingDraft;
  onChange: (nextMapping: HookMappingDraft) => void;
  linkedTelegramUserId?: string | null;
  availableChannels: SystemChannelResponse[];
  channelsLoading: boolean;
}

interface DeliveryState {
  channelOptions: DeliveryChannelOption[];
  deliveryChannelsHelp: string;
  normalizedChannel: string | null;
  isTelegramDelivery: boolean;
  resolvedTargetValue: string;
  targetPlaceholder: string;
  telegramTarget: string | null;
  telegramAutofillKey: string | null;
}

export function HookAgentSection({
  mapping,
  onChange,
  linkedTelegramUserId,
  availableChannels,
  channelsLoading,
}: HookAgentSectionProps): ReactElement | null {
  const telegramAutofillKeyRef = useRef<string | null>(null);
  const deliveryState = useDeliveryState(mapping, linkedTelegramUserId, availableChannels, channelsLoading);

  useEffect(() => {
    if (deliveryState.telegramAutofillKey == null) {
      telegramAutofillKeyRef.current = null;
      return;
    }
    if (telegramAutofillKeyRef.current === deliveryState.telegramAutofillKey) {
      return;
    }
    telegramAutofillKeyRef.current = deliveryState.telegramAutofillKey;
    onChange({ ...mapping, to: deliveryState.telegramTarget });
  }, [deliveryState.telegramAutofillKey, deliveryState.telegramTarget, mapping, onChange]);

  if (mapping.action !== 'agent') {
    return null;
  }

  return (
    <div className="grid gap-4 xl:grid-cols-[minmax(14rem,16rem)_minmax(0,1fr)]">
      <div className={surfaceClassName}>
        <HookMappingFieldHeading
          label="Model Tier"
          help="Optional tier override for this hook. Special tiers are explicit-only custom slots."
        />
        <Select
          value={mapping.model ?? ''}
          onChange={(event) => onChange({ ...mapping, model: toNullableString(event.target.value) })}
          className={controlClassName}
        >
          <option value="">Default</option>
          {getExplicitModelTierOptions().map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </Select>
        <p className={fieldHelpClassName}>
          Leave empty to inherit the default routing tier for agent webhook requests.
        </p>
      </div>

      <div className={cn(surfaceClassName, 'bg-gradient-to-br from-card via-card to-muted/20')}>
        <DeliveryHeader
          deliver={mapping.deliver}
          onToggle={(deliver) => onChange(nextMappingWithDeliveryTarget(mapping, deliver, deliveryState.normalizedChannel, deliveryState.telegramTarget))}
        />

        <div className={cn('mt-5 grid gap-4 md:grid-cols-2', !mapping.deliver && 'opacity-75')}>
          <DeliveryChannelField
            channel={deliveryState.normalizedChannel}
            disabled={!mapping.deliver}
            help={deliveryState.deliveryChannelsHelp}
            options={deliveryState.channelOptions}
            onChange={(rawValue) =>
              onChange(nextMappingWithDeliveryTarget(
                mapping,
                mapping.deliver,
                normalizeChannel(rawValue),
                deliveryState.telegramTarget,
              ))}
          />
          <DeliveryTargetField
            value={deliveryState.resolvedTargetValue}
            disabled={!mapping.deliver}
            placeholder={deliveryState.targetPlaceholder}
            isTelegramDelivery={deliveryState.isTelegramDelivery}
            telegramTarget={deliveryState.telegramTarget}
            onChange={(value) => onChange({ ...mapping, to: toNullableString(value) })}
          />
        </div>
      </div>
    </div>
  );
}

function DeliveryHeader({
  deliver,
  onToggle,
}: {
  deliver: boolean;
  onToggle: (deliver: boolean) => void;
}): ReactElement {
  return (
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
        aria-checked={deliver}
        onClick={() => onToggle(!deliver)}
        className={cn(
          'inline-flex items-center gap-3 self-start rounded-full border px-3 py-2 text-sm font-semibold transition-all',
          deliver
            ? 'border-primary/30 bg-primary/10 text-foreground shadow-soft'
            : 'border-border/80 bg-background/80 text-muted-foreground',
        )}
      >
        <span
          className={cn(
            'relative h-6 w-11 rounded-full transition-colors',
            deliver ? 'bg-primary' : 'bg-muted',
          )}
        >
          <span
            className={cn(
              'absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform',
              deliver ? 'translate-x-5' : 'translate-x-0',
            )}
          />
        </span>
        <span>{deliver ? 'Enabled' : 'Disabled'}</span>
      </button>
    </div>
  );
}

function DeliveryChannelField({
  channel,
  disabled,
  help,
  options,
  onChange,
}: {
  channel: string | null;
  disabled: boolean;
  help: string;
  options: DeliveryChannelOption[];
  onChange: (value: string) => void;
}): ReactElement {
  return (
    <div>
      <HookMappingFieldHeading
        label="Channel"
        help="Choose from channels currently registered in the runtime. Internal web and webhook transports are excluded."
      />
      <Select
        value={channel ?? ''}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
        className={controlClassName}
      >
        <option value="">Select channel</option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </Select>
      <p className={fieldHelpClassName}>{help}</p>
    </div>
  );
}

function DeliveryTargetField({
  value,
  disabled,
  placeholder,
  isTelegramDelivery,
  telegramTarget,
  onChange,
}: {
  value: string;
  disabled: boolean;
  placeholder: string;
  isTelegramDelivery: boolean;
  telegramTarget: string | null;
  onChange: (value: string) => void;
}): ReactElement {
  return (
    <div>
      <HookMappingFieldHeading
        label="Target ID"
        help="Target chat or user id on the selected channel. For Telegram, the connected user id is injected automatically when available."
      />
      <Input
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
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
  );
}

function useDeliveryState(
  mapping: HookMappingDraft,
  linkedTelegramUserId: string | null | undefined,
  availableChannels: SystemChannelResponse[],
  channelsLoading: boolean,
): DeliveryState {
  const normalizedChannel = normalizeChannel(mapping.channel);
  const isTelegramDelivery = normalizedChannel === 'telegram';
  const telegramTarget = linkedTelegramUserId ?? null;
  const channelOptions = useMemo(
    () => buildChannelOptions(availableChannels, normalizedChannel),
    [availableChannels, normalizedChannel],
  );

  return {
    channelOptions,
    deliveryChannelsHelp: buildDeliveryChannelsHelp(channelOptions, channelsLoading),
    normalizedChannel,
    isTelegramDelivery,
    resolvedTargetValue: mapping.to ?? (mapping.deliver && isTelegramDelivery && telegramTarget != null ? telegramTarget : ''),
    targetPlaceholder: isTelegramDelivery && telegramTarget != null ? telegramTarget : 'chat id',
    telegramTarget,
    telegramAutofillKey: buildTelegramAutofillKey(mapping, normalizedChannel, telegramTarget),
  };
}

function buildTelegramAutofillKey(
  mapping: HookMappingDraft,
  normalizedChannel: string | null,
  telegramTarget: string | null,
): string | null {
  if (!shouldPopulateTelegramTarget(mapping.deliver, normalizedChannel, telegramTarget, mapping.to)) {
    return null;
  }
  return `${mapping.name}|${normalizedChannel}|${telegramTarget}`;
}

function buildChannelOptions(
  availableChannels: SystemChannelResponse[],
  normalizedChannel: string | null,
): DeliveryChannelOption[] {
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
}

function buildDeliveryChannelsHelp(channelOptions: DeliveryChannelOption[], channelsLoading: boolean): string {
  if (channelsLoading) {
    return 'Loading registered delivery channels...';
  }
  if (channelOptions.length === 0) {
    return 'No delivery channels are currently registered. Channels come from plugins or built-in channel adapters.';
  }
  if (channelOptions.some((channel) => channel.unavailable)) {
    return 'This mapping points to a channel that is not currently registered.';
  }
  return 'Only registered delivery channels are shown here.';
}

function nextMappingWithDeliveryTarget(
  mapping: HookMappingDraft,
  deliver: boolean,
  channel: string | null,
  telegramTarget: string | null,
): HookMappingDraft {
  return {
    ...mapping,
    deliver,
    channel,
    to: shouldPopulateTelegramTarget(deliver, channel, telegramTarget, mapping.to)
      ? telegramTarget
      : mapping.to,
  };
}
