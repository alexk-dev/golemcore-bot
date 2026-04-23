import type { ReactElement } from 'react';
import { Button, Form } from '../ui/tailwind-components';
import type { SchedulerFrequency, SchedulerMode } from './schedulerTypes';
import { normalizeTimeInput, parseLimitInput, type SchedulerTargetOption } from './schedulerFormUtils';

const PRESET_TIMES: readonly string[] = ['09:00', '12:00', '18:00', '21:00'];
const PRESET_LIMITS: readonly number[] = [1, 3, 5, 10];
const PRESET_CRON_EXPRESSIONS: ReadonlyArray<{ label: string; value: string }> = [
  { label: 'Every minute', value: '* * * * *' },
  { label: 'Every 5 min', value: '*/5 * * * *' },
  { label: 'Weekdays 09:00', value: '0 9 * * MON-FRI' },
];
const WEEKDAY_OPTIONS: ReadonlyArray<{ value: number; label: string }> = [
  { value: 1, label: 'Mon' },
  { value: 2, label: 'Tue' },
  { value: 3, label: 'Wed' },
  { value: 4, label: 'Thu' },
  { value: 5, label: 'Fri' },
  { value: 6, label: 'Sat' },
  { value: 7, label: 'Sun' },
];

interface ToggleGroupProps<TValue extends string> {
  label: string;
  options: ReadonlyArray<{ value: TValue; label: string }>;
  selected: TValue;
  onChange: (value: TValue) => void;
}

interface TargetSelectorProps {
  label: string;
  helpText?: string;
  featureEnabled: boolean;
  targetOptions: SchedulerTargetOption[];
  effectiveTargetId: string;
  onTargetChange: (targetId: string) => void;
}

interface SimpleScheduleFieldsProps {
  featureEnabled: boolean;
  frequency: SchedulerFrequency;
  days: number[];
  time: string;
  isTimeValid: boolean;
  onFrequencyChange: (frequency: SchedulerFrequency) => void;
  onToggleDay: (day: number) => void;
  onTimeChange: (value: string) => void;
  onPresetTimeSelect: (value: string) => void;
}

interface AdvancedCronFieldsProps {
  featureEnabled: boolean;
  cronExpression: string;
  isCronValid: boolean;
  onCronExpressionChange: (value: string) => void;
  onPresetCronSelect: (value: string) => void;
}

interface RepeatLimitFieldProps {
  featureEnabled: boolean;
  limitInput: string;
  onLimitInputChange: (value: string) => void;
  onPresetLimitSelect: (value: string) => void;
}

interface ScheduleEnabledFieldProps {
  featureEnabled: boolean;
  enabled: boolean;
  onEnabledChange: (enabled: boolean) => void;
}

interface ClearContextFieldProps {
  featureEnabled: boolean;
  clearContextBeforeRun: boolean;
  onChange: (clearContextBeforeRun: boolean) => void;
}

function isSchedulerFrequency(value: string): value is SchedulerFrequency {
  return value === 'daily' || value === 'weekdays' || value === 'weekly' || value === 'custom';
}

function toSchedulerFrequency(value: string, fallback: SchedulerFrequency): SchedulerFrequency {
  return isSchedulerFrequency(value) ? value : fallback;
}

function isWeeklyOrCustom(frequency: SchedulerFrequency): boolean {
  return frequency === 'weekly' || frequency === 'custom';
}

export function SchedulerModeToggle({
  selected,
  onChange,
}: Pick<ToggleGroupProps<SchedulerMode>, 'selected' | 'onChange'>): ReactElement {
  return (
    <ToggleGroup
      label="Mode"
      options={[
        { value: 'simple', label: 'Simple' },
        { value: 'advanced', label: 'Advanced cron' },
      ]}
      selected={selected}
      onChange={onChange}
    />
  );
}

export function TargetSelector({
  label,
  helpText,
  featureEnabled,
  targetOptions,
  effectiveTargetId,
  onTargetChange,
}: TargetSelectorProps): ReactElement {
  return (
    <Form.Group className="mb-3">
      <Form.Label>{label}</Form.Label>
      <Form.Select
        size="sm"
        value={effectiveTargetId}
        onChange={(event) => onTargetChange(event.target.value)}
        disabled={!featureEnabled || targetOptions.length === 0}
      >
        {targetOptions.length === 0 && <option value="">No targets available</option>}
        {targetOptions.map((option) => (
          <option key={option.id} value={option.id}>{option.label}</option>
        ))}
      </Form.Select>
      {effectiveTargetId.length > 0 && (
        <Form.Text className="text-body-secondary">
          Selected ID: <code>{effectiveTargetId}</code>
        </Form.Text>
      )}
      {effectiveTargetId.length === 0 && helpText != null && helpText.length > 0 && (
        <Form.Text className="text-body-secondary">{helpText}</Form.Text>
      )}
    </Form.Group>
  );
}

export function SimpleScheduleFields({
  featureEnabled,
  frequency,
  days,
  time,
  isTimeValid,
  onFrequencyChange,
  onToggleDay,
  onTimeChange,
  onPresetTimeSelect,
}: SimpleScheduleFieldsProps): ReactElement {
  const normalizedTime = normalizeTimeInput(time);
  const localTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const isWeeklyFrequency = isWeeklyOrCustom(frequency);

  return (
    <>
      <Form.Group className="mb-3">
        <Form.Label>Frequency</Form.Label>
        <Form.Select
          size="sm"
          value={frequency}
          onChange={(event) => onFrequencyChange(toSchedulerFrequency(event.target.value, frequency))}
          disabled={!featureEnabled}
        >
          <option value="daily">Daily</option>
          <option value="weekdays">Weekdays (Mon-Fri)</option>
          <option value="weekly">Weekly (selected days)</option>
          <option value="custom">Custom days</option>
        </Form.Select>
      </Form.Group>

      {isWeeklyFrequency && (
        <Form.Group className="mb-3">
          <Form.Label>Days</Form.Label>
          <div className="scheduler-day-grid">
            {WEEKDAY_OPTIONS.map((option) => (
              <Button
                key={option.value}
                type="button"
                size="sm"
                variant={days.includes(option.value) ? 'primary' : 'secondary'}
                onClick={() => onToggleDay(option.value)}
              >
                {option.label}
              </Button>
            ))}
          </div>
        </Form.Group>
      )}

      <Form.Group className="mb-3">
        <Form.Label>Time (UTC)</Form.Label>
        <div className="d-flex flex-wrap gap-2 mb-2">
          {PRESET_TIMES.map((preset) => (
            <Button
              key={preset}
              type="button"
              size="sm"
              variant={normalizedTime === preset ? 'primary' : 'secondary'}
              onClick={() => onPresetTimeSelect(preset)}
            >
              {preset}
            </Button>
          ))}
        </div>
        <Form.Control
          size="sm"
          value={time}
          onChange={(event) => onTimeChange(event.target.value)}
          isInvalid={!isTimeValid}
          disabled={!featureEnabled}
          placeholder="HH:mm or HHmm"
        />
        <Form.Text className={!isTimeValid ? 'text-danger' : 'text-body-secondary'}>
          Stored in UTC. Your browser timezone: {localTimezone}.
        </Form.Text>
      </Form.Group>
    </>
  );
}

export function AdvancedCronFields({
  featureEnabled,
  cronExpression,
  isCronValid,
  onCronExpressionChange,
  onPresetCronSelect,
}: AdvancedCronFieldsProps): ReactElement {
  const trimmedCron = cronExpression.trim();

  return (
    <Form.Group className="mb-3">
      <Form.Label>Advanced cron (UTC)</Form.Label>
      <div className="d-flex flex-wrap gap-2 mb-2">
        {PRESET_CRON_EXPRESSIONS.map((preset) => (
          <Button
            key={preset.value}
            type="button"
            size="sm"
            variant={trimmedCron === preset.value ? 'primary' : 'secondary'}
            onClick={() => onPresetCronSelect(preset.value)}
          >
            {preset.label}
          </Button>
        ))}
      </div>
      <Form.Control
        size="sm"
        value={cronExpression}
        onChange={(event) => onCronExpressionChange(event.target.value)}
        isInvalid={!isCronValid}
        disabled={!featureEnabled}
        placeholder="* * * * * or 0 */5 * * * *"
      />
      <Form.Text className={!isCronValid ? 'text-danger' : 'text-body-secondary'}>
        Supports 5 or 6 fields. Example: <code>* * * * *</code> every minute, <code>*/5 * * * *</code> every 5 minutes.
      </Form.Text>
    </Form.Group>
  );
}

export function RepeatLimitField({
  featureEnabled,
  limitInput,
  onLimitInputChange,
  onPresetLimitSelect,
}: RepeatLimitFieldProps): ReactElement {
  const parsedLimit = parseLimitInput(limitInput);

  return (
    <Form.Group className="mb-3">
      <Form.Label>Repeat limit</Form.Label>
      <div className="d-flex flex-wrap gap-2 mb-2">
        {PRESET_LIMITS.map((limit) => (
          <Button
            key={limit}
            type="button"
            size="sm"
            variant={limitInput === String(limit) ? 'primary' : 'secondary'}
            onClick={() => onPresetLimitSelect(String(limit))}
          >
            {limit}
          </Button>
        ))}
        <Button
          type="button"
          size="sm"
          variant={limitInput === '0' ? 'primary' : 'secondary'}
          onClick={() => onPresetLimitSelect('0')}
        >
          Unlimited
        </Button>
      </div>
      <Form.Control
        size="sm"
        value={limitInput}
        onChange={(event) => onLimitInputChange(event.target.value)}
        isInvalid={parsedLimit == null}
        disabled={!featureEnabled}
        placeholder="0 for unlimited"
      />
    </Form.Group>
  );
}

export function ScheduleEnabledField({
  featureEnabled,
  enabled,
  onEnabledChange,
}: ScheduleEnabledFieldProps): ReactElement {
  return (
    <Form.Group className="mb-3">
      <Form.Check
        type="switch"
        label="Schedule enabled"
        checked={enabled}
        disabled={!featureEnabled}
        onChange={(event) => onEnabledChange(event.target.checked)}
      />
    </Form.Group>
  );
}

export function ClearContextField({
  featureEnabled,
  clearContextBeforeRun,
  onChange,
}: ClearContextFieldProps): ReactElement {
  return (
    <Form.Group className="mb-3">
      <Form.Check
        type="switch"
        label="Clear session context before each run"
        checked={clearContextBeforeRun}
        disabled={!featureEnabled}
        onChange={(event) => onChange(event.target.checked)}
      />
      <Form.Text className="text-body-secondary">
        Resets the schedule session transcript before execution. Previous run logs from that session will no longer appear.
      </Form.Text>
    </Form.Group>
  );
}

function ToggleGroup<TValue extends string>({
  label,
  options,
  selected,
  onChange,
}: ToggleGroupProps<TValue>): ReactElement {
  return (
    <Form.Group className="mb-3">
      <Form.Label>{label}</Form.Label>
      <div className="d-flex gap-2">
        {options.map((option) => (
          <Button
            key={option.value}
            type="button"
            size="sm"
            variant={selected === option.value ? 'primary' : 'secondary'}
            onClick={() => onChange(option.value)}
          >
            {option.label}
          </Button>
        ))}
      </div>
    </Form.Group>
  );
}
