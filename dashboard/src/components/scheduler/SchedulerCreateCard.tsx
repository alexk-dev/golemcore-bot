import type { ReactElement } from 'react';
import { Button, Card, Form } from 'react-bootstrap';
import type { SchedulerGoal } from '../../api/scheduler';
import type { SchedulerFrequency, SchedulerTargetType, ScheduleFormState } from './schedulerTypes';
import { buildGoalOptions, buildTaskOptions, normalizeTimeInput, parseLimitInput, type SchedulerTargetOption } from './schedulerFormUtils';

const PRESET_TIMES: readonly string[] = ['09:00', '12:00', '18:00', '21:00'];
const PRESET_LIMITS: readonly number[] = [1, 3, 5, 10];
const WEEKDAY_OPTIONS: ReadonlyArray<{ value: number; label: string }> = [
  { value: 1, label: 'Mon' },
  { value: 2, label: 'Tue' },
  { value: 3, label: 'Wed' },
  { value: 4, label: 'Thu' },
  { value: 5, label: 'Fri' },
  { value: 6, label: 'Sat' },
  { value: 7, label: 'Sun' },
];

interface SchedulerCreateCardProps {
  featureEnabled: boolean;
  goals: SchedulerGoal[];
  form: ScheduleFormState;
  isTimeValid: boolean;
  isFormValid: boolean;
  isCreating: boolean;
  onTargetTypeChange: (targetType: SchedulerTargetType) => void;
  onTargetChange: (targetId: string) => void;
  onFrequencyChange: (frequency: SchedulerFrequency) => void;
  onToggleDay: (day: number) => void;
  onTimeChange: (value: string) => void;
  onPresetTimeSelect: (value: string) => void;
  onLimitInputChange: (value: string) => void;
  onPresetLimitSelect: (value: string) => void;
  onSubmit: () => void;
}

interface SchedulerSubmitState {
  featureEnabled: boolean;
  isFormValid: boolean;
  isCreating: boolean;
  effectiveTargetId: string;
  parsedLimit: number | null;
}

function isSchedulerFrequency(value: string): value is SchedulerFrequency {
  return value === 'daily' || value === 'weekdays' || value === 'weekly' || value === 'custom';
}

function toSchedulerFrequency(value: string, fallback: SchedulerFrequency): SchedulerFrequency {
  return isSchedulerFrequency(value) ? value : fallback;
}

function resolveTargetOptions(targetType: SchedulerTargetType, goals: SchedulerGoal[]): SchedulerTargetOption[] {
  if (targetType === 'GOAL') {
    return buildGoalOptions(goals);
  }
  return buildTaskOptions(goals);
}

function resolveEffectiveTargetId(options: SchedulerTargetOption[], targetId: string): string {
  const isPresent = options.some((option) => option.id === targetId);
  if (isPresent) {
    return targetId;
  }
  return options[0]?.id ?? '';
}

function isWeeklyOrCustom(frequency: SchedulerFrequency): boolean {
  return frequency === 'weekly' || frequency === 'custom';
}

function shouldDisableSubmit(state: SchedulerSubmitState): boolean {
  if (!state.featureEnabled) {
    return true;
  }
  if (!state.isFormValid) {
    return true;
  }
  if (state.isCreating) {
    return true;
  }
  if (state.effectiveTargetId.length === 0) {
    return true;
  }
  return state.parsedLimit == null;
}

export function SchedulerCreateCard({
  featureEnabled,
  goals,
  form,
  isTimeValid,
  isFormValid,
  isCreating,
  onTargetTypeChange,
  onTargetChange,
  onFrequencyChange,
  onToggleDay,
  onTimeChange,
  onPresetTimeSelect,
  onLimitInputChange,
  onPresetLimitSelect,
  onSubmit,
}: SchedulerCreateCardProps): ReactElement {
  const targetOptions = resolveTargetOptions(form.targetType, goals);
  const effectiveTargetId = resolveEffectiveTargetId(targetOptions, form.targetId);
  const isWeeklyFrequency = isWeeklyOrCustom(form.frequency);
  const normalizedTime = normalizeTimeInput(form.time);
  const parsedLimit = parseLimitInput(form.limitInput);

  const submitDisabled = shouldDisableSubmit({
    featureEnabled,
    isFormValid,
    isCreating,
    effectiveTargetId,
    parsedLimit,
  });

  return (
    <Card className="h-100">
      <Card.Header className="fw-semibold">Create schedule</Card.Header>
      <Card.Body>
        <Form.Group className="mb-3">
          <Form.Label>Target type</Form.Label>
          <div className="d-flex gap-2">
            <Button
              type="button"
              size="sm"
              variant={form.targetType === 'GOAL' ? 'primary' : 'secondary'}
              onClick={() => onTargetTypeChange('GOAL')}
            >
              Goal
            </Button>
            <Button
              type="button"
              size="sm"
              variant={form.targetType === 'TASK' ? 'primary' : 'secondary'}
              onClick={() => onTargetTypeChange('TASK')}
            >
              Task
            </Button>
          </div>
        </Form.Group>

        <Form.Group className="mb-3">
          <Form.Label>Target</Form.Label>
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
        </Form.Group>

        <Form.Group className="mb-3">
          <Form.Label>Frequency</Form.Label>
          <Form.Select
            size="sm"
            value={form.frequency}
            onChange={(event) => onFrequencyChange(toSchedulerFrequency(event.target.value, form.frequency))}
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
                  variant={form.days.includes(option.value) ? 'primary' : 'secondary'}
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
            {PRESET_TIMES.map((time) => (
              <Button
                key={time}
                type="button"
                size="sm"
                variant={normalizedTime === time ? 'primary' : 'secondary'}
                onClick={() => onPresetTimeSelect(time)}
              >
                {time}
              </Button>
            ))}
          </div>
          <Form.Control
            size="sm"
            value={form.time}
            onChange={(event) => onTimeChange(event.target.value)}
            isInvalid={!isTimeValid}
            disabled={!featureEnabled}
            placeholder="HH:mm or HHmm"
          />
          <Form.Text className={!isTimeValid ? 'text-danger' : 'text-body-secondary'}>
            Use HH:mm (09:30) or HHmm (0930).
          </Form.Text>
        </Form.Group>

        <Form.Group className="mb-3">
          <Form.Label>Repeat limit</Form.Label>
          <div className="d-flex flex-wrap gap-2 mb-2">
            {PRESET_LIMITS.map((limit) => (
              <Button
                key={limit}
                type="button"
                size="sm"
                variant={form.limitInput === String(limit) ? 'primary' : 'secondary'}
                onClick={() => onPresetLimitSelect(String(limit))}
              >
                {limit}
              </Button>
            ))}
            <Button
              type="button"
              size="sm"
              variant={form.limitInput === '0' ? 'primary' : 'secondary'}
              onClick={() => onPresetLimitSelect('0')}
            >
              Unlimited
            </Button>
          </div>
          <Form.Control
            size="sm"
            value={form.limitInput}
            onChange={(event) => onLimitInputChange(event.target.value)}
            isInvalid={parsedLimit == null}
            disabled={!featureEnabled}
            placeholder="0 for unlimited"
          />
        </Form.Group>

        <Button
          type="button"
          variant="primary"
          size="sm"
          disabled={submitDisabled}
          onClick={onSubmit}
        >
          {isCreating ? 'Creating...' : 'Create schedule'}
        </Button>
      </Card.Body>
    </Card>
  );
}
