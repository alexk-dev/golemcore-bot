import { type ChangeEvent, type ReactElement, useEffect, useState } from 'react';
import { Button, Form, Modal } from '../ui/tailwind-components';
import type {
  Goal,
  GoalStatus,
  GoalTask,
  GoalTaskStatus,
  UpdateGoalRequest,
  UpdateTaskRequest,
} from '../../api/goals';
import { getExplicitModelTierOptions } from '../../lib/modelTiers';

export type AutomationEditorItem =
  | { kind: 'goal'; value: Goal }
  | { kind: 'task'; value: GoalTask };

interface AutomationEditorFormState {
  title: string;
  description: string;
  prompt: string;
  reflectionModelTier: string;
  reflectionTierPriority: boolean;
  status: string;
}

interface SchedulerAutomationEditModalProps {
  show: boolean;
  item: AutomationEditorItem | null;
  busy: boolean;
  onHide: () => void;
  onSaveGoal: (goalId: string, request: UpdateGoalRequest) => Promise<void> | void;
  onSaveTask: (taskId: string, request: UpdateTaskRequest) => Promise<void> | void;
}

function buildInitialFormState(item: AutomationEditorItem | null): AutomationEditorFormState {
  if (item == null) {
    return {
      title: '',
      description: '',
      prompt: '',
      reflectionModelTier: '',
      reflectionTierPriority: false,
      status: 'ACTIVE',
    };
  }

  return {
    title: item.value.title,
    description: item.value.description ?? '',
    prompt: item.value.prompt ?? '',
    reflectionModelTier: item.value.reflectionModelTier ?? '',
    reflectionTierPriority: item.value.reflectionTierPriority,
    status: item.value.status,
  };
}

function normalizeOptionalValue(value: string): string | null {
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

function isGoalStatus(value: string): value is GoalStatus {
  return value === 'ACTIVE' || value === 'COMPLETED' || value === 'PAUSED' || value === 'CANCELLED';
}

function isTaskStatus(value: string): value is GoalTaskStatus {
  return value === 'PENDING'
    || value === 'IN_PROGRESS'
    || value === 'COMPLETED'
    || value === 'FAILED'
    || value === 'SKIPPED';
}

function resolveStatusOptions(item: AutomationEditorItem | null): string[] {
  if (item?.kind === 'task') {
    return ['PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'SKIPPED'];
  }
  return ['ACTIVE', 'COMPLETED', 'PAUSED', 'CANCELLED'];
}

function resolveTitle(item: AutomationEditorItem | null): string {
  if (item?.kind === 'task') {
    return 'Edit task';
  }
  return 'Edit goal';
}

export function SchedulerAutomationEditModal({
  show,
  item,
  busy,
  onHide,
  onSaveGoal,
  onSaveTask,
}: SchedulerAutomationEditModalProps): ReactElement {
  const [form, setForm] = useState<AutomationEditorFormState>(() => buildInitialFormState(item));

  useEffect(() => {
    // Sync the modal form whenever the selected goal/task changes.
    setForm(buildInitialFormState(item));
  }, [item]);

  const handleFieldChange = (field: keyof AutomationEditorFormState) => (
    event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>,
  ): void => {
    const value = event.target.value;
    setForm((current) => ({ ...current, [field]: value }));
  };

  const handleSave = async (): Promise<void> => {
    if (item == null || form.title.trim().length === 0) {
      return;
    }

    if (item.kind === 'goal' && isGoalStatus(form.status)) {
      await onSaveGoal(item.value.id, {
        title: form.title.trim(),
        description: normalizeOptionalValue(form.description),
        prompt: normalizeOptionalValue(form.prompt),
        reflectionModelTier: normalizeOptionalValue(form.reflectionModelTier),
        reflectionTierPriority: form.reflectionTierPriority,
        status: form.status,
      });
      onHide();
      return;
    }

    if (item.kind === 'task' && isTaskStatus(form.status)) {
      await onSaveTask(item.value.id, {
        title: form.title.trim(),
        description: normalizeOptionalValue(form.description),
        prompt: normalizeOptionalValue(form.prompt),
        reflectionModelTier: normalizeOptionalValue(form.reflectionModelTier),
        reflectionTierPriority: form.reflectionTierPriority,
        status: form.status,
      });
      onHide();
    }
  };

  const submitDisabled = item == null || busy || form.title.trim().length === 0;
  const statusOptions = resolveStatusOptions(item);

  return (
    <Modal show={show} onHide={onHide}>
      <Modal.Header closeButton>
        <Modal.Title>{resolveTitle(item)}</Modal.Title>
      </Modal.Header>
      <Modal.Body>
        {item?.kind === 'task' && (
          <div className="small text-body-secondary mb-3">
            {item.value.standalone ? 'Standalone task' : 'Attached to goal'}
          </div>
        )}

        <Form.Group className="mb-3">
          <Form.Label>Title</Form.Label>
          <Form.Control size="sm" value={form.title} onChange={handleFieldChange('title')} />
        </Form.Group>

        <Form.Group className="mb-3">
          <Form.Label>Details</Form.Label>
          <Form.Control
            as="textarea"
            rows={3}
            value={form.description}
            onChange={handleFieldChange('description')}
          />
        </Form.Group>

        <Form.Group className="mb-3">
          <Form.Label>Prompt</Form.Label>
          <Form.Control
            as="textarea"
            rows={5}
            value={form.prompt}
            onChange={handleFieldChange('prompt')}
          />
        </Form.Group>

        <Form.Group className="mb-3">
          <Form.Label>Reflection tier</Form.Label>
          <Form.Select
            size="sm"
            value={form.reflectionModelTier}
            onChange={handleFieldChange('reflectionModelTier')}
          >
            <option value="">Use default reflection model</option>
            {getExplicitModelTierOptions().map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </Form.Select>
        </Form.Group>

        <Form.Check
          type="switch"
          className="mb-3"
          label="Reflection tier has priority"
          checked={form.reflectionTierPriority}
          disabled={form.reflectionModelTier.length === 0}
          onChange={(event) =>
            setForm((current) => ({ ...current, reflectionTierPriority: event.target.checked }))
          }
        />

        <Form.Group className="mb-0">
          <Form.Label>Status</Form.Label>
          <Form.Select size="sm" value={form.status} onChange={handleFieldChange('status')}>
            {statusOptions.map((status) => (
              <option key={status} value={status}>
                {status}
              </option>
            ))}
          </Form.Select>
        </Form.Group>
      </Modal.Body>
      <Modal.Footer>
        <Button type="button" variant="secondary" size="sm" onClick={onHide}>
          Cancel
        </Button>
        <Button type="button" variant="primary" size="sm" disabled={submitDisabled} onClick={() => { void handleSave(); }}>
          {busy ? 'Saving...' : 'Save'}
        </Button>
      </Modal.Footer>
    </Modal>
  );
}
