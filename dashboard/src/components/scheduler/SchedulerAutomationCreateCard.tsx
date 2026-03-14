import { type ChangeEvent, type ReactElement, useState } from 'react';
import { Button, Card, Form } from 'react-bootstrap';
import type { CreateGoalRequest, CreateTaskRequest, Goal } from '../../api/goals';

type AutomationCreateMode = 'goal' | 'task';

interface AutomationCreateFormState {
  mode: AutomationCreateMode;
  goalId: string;
  title: string;
  description: string;
  prompt: string;
}

interface SchedulerAutomationCreateCardProps {
  featureEnabled: boolean;
  goals: Goal[];
  busy: boolean;
  onCreateGoal: (request: CreateGoalRequest) => Promise<void> | void;
  onCreateTask: (request: CreateTaskRequest) => Promise<void> | void;
}

function buildInitialFormState(goals: Goal[]): AutomationCreateFormState {
  return {
    mode: 'goal',
    goalId: goals[0]?.id ?? '',
    title: '',
    description: '',
    prompt: '',
  };
}

function normalizeOptionalValue(value: string): string | null {
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

function isSubmitDisabled(featureEnabled: boolean, busy: boolean, title: string): boolean {
  if (!featureEnabled || busy) {
    return true;
  }
  return title.trim().length === 0;
}

export function SchedulerAutomationCreateCard({
  featureEnabled,
  goals,
  busy,
  onCreateGoal,
  onCreateTask,
}: SchedulerAutomationCreateCardProps): ReactElement {
  const [form, setForm] = useState<AutomationCreateFormState>(() => buildInitialFormState(goals));
  const submitDisabled = isSubmitDisabled(featureEnabled, busy, form.title);

  const handleFieldChange = (field: keyof AutomationCreateFormState) => (
    event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>,
  ): void => {
    const value = event.target.value;
    setForm((current) => ({ ...current, [field]: value }));
  };

  const handleModeChange = (mode: AutomationCreateMode): void => {
    setForm((current) => ({
      ...current,
      mode,
      goalId: mode === 'task' ? current.goalId || goals[0]?.id || '' : current.goalId,
    }));
  };

  const handleSubmit = async (): Promise<void> => {
    if (submitDisabled) {
      return;
    }

    if (form.mode === 'goal') {
      await onCreateGoal({
        title: form.title.trim(),
        description: normalizeOptionalValue(form.description),
        prompt: normalizeOptionalValue(form.prompt),
      });
      setForm(buildInitialFormState(goals));
      return;
    }

    await onCreateTask({
      goalId: normalizeOptionalValue(form.goalId),
      title: form.title.trim(),
      description: normalizeOptionalValue(form.description),
      prompt: normalizeOptionalValue(form.prompt),
      status: null,
    });
    setForm((current) => ({
      ...current,
      title: '',
      description: '',
      prompt: '',
    }));
  };

  return (
    <Card className="h-100">
      <Card.Header className="fw-semibold">Create goal or task</Card.Header>
      <Card.Body>
        <Form.Group className="mb-3">
          <Form.Label>Type</Form.Label>
          <div className="d-flex gap-2">
            <Button
              type="button"
              size="sm"
              variant={form.mode === 'goal' ? 'primary' : 'secondary'}
              onClick={() => handleModeChange('goal')}
            >
              Goal
            </Button>
            <Button
              type="button"
              size="sm"
              variant={form.mode === 'task' ? 'primary' : 'secondary'}
              onClick={() => handleModeChange('task')}
            >
              Task
            </Button>
          </div>
        </Form.Group>

        {form.mode === 'task' && (
          <Form.Group className="mb-3">
            <Form.Label>Attach to goal</Form.Label>
            <Form.Select
              size="sm"
              value={form.goalId}
              onChange={handleFieldChange('goalId')}
              disabled={!featureEnabled}
            >
              <option value="">No goal (standalone task)</option>
              {goals.map((goal) => (
                <option key={goal.id} value={goal.id}>
                  {goal.title} ({goal.status})
                </option>
              ))}
            </Form.Select>
          </Form.Group>
        )}

        <Form.Group className="mb-3">
          <Form.Label>Title</Form.Label>
          <Form.Control
            size="sm"
            value={form.title}
            onChange={handleFieldChange('title')}
            disabled={!featureEnabled}
            placeholder={form.mode === 'goal' ? 'Launch partner beta' : 'Prepare launch checklist'}
          />
        </Form.Group>

        <Form.Group className="mb-3">
          <Form.Label>Details</Form.Label>
          <Form.Control
            as="textarea"
            rows={3}
            value={form.description}
            onChange={handleFieldChange('description')}
            disabled={!featureEnabled}
            placeholder="Optional notes for the dashboard and context."
          />
        </Form.Group>

        <Form.Group className="mb-3">
          <Form.Label>Prompt</Form.Label>
          <Form.Control
            as="textarea"
            rows={5}
            value={form.prompt}
            onChange={handleFieldChange('prompt')}
            disabled={!featureEnabled}
            placeholder="If set, scheduler/execution uses this prompt instead of the title."
          />
          <Form.Text className="text-body-secondary">
            Leave empty to fall back to the title during execution.
          </Form.Text>
        </Form.Group>

        <Button type="button" size="sm" variant="primary" disabled={submitDisabled} onClick={() => { void handleSubmit(); }}>
          {busy ? 'Saving...' : form.mode === 'goal' ? 'Create goal' : 'Create task'}
        </Button>
      </Card.Body>
    </Card>
  );
}
