import { type ReactElement, useEffect, useState } from 'react';
import { Button, Card, Spinner } from '../components/ui/tailwind-components';
import { useNavigate } from 'react-router-dom';
import type { Goal, GoalTask } from '../api/goals';
import { GoalsWorkspace } from '../components/scheduler/GoalsWorkspace';
import type { AutomationEditorItem } from '../components/scheduler/SchedulerAutomationEditModal';
import { getSchedulerPrefillHref } from '../components/scheduler/automationLinks';
import {
  useCreateGoal,
  useCreateTask,
  useDeleteGoal,
  useDeleteTask,
  useGoalsState,
  useUpdateGoal,
  useUpdateTask,
} from '../hooks/useGoals';
import { useSchedulerBusyState } from '../hooks/useScheduler';

function LoadingState(): ReactElement {
  return (
    <div className="dashboard-main">
      <div className="d-flex align-items-center gap-2 text-body-secondary">
        <Spinner size="sm" />
        <span>Loading goals & tasks...</span>
      </div>
    </div>
  );
}

interface ErrorStateProps {
  onRetry: () => void;
}

function ErrorState({ onRetry }: ErrorStateProps): ReactElement {
  return (
    <div className="dashboard-main">
      <Card className="text-center py-4">
        <Card.Body>
          <p className="text-danger mb-3">Failed to load goals & tasks.</p>
          <Button type="button" variant="secondary" size="sm" onClick={onRetry}>
            Retry
          </Button>
        </Card.Body>
      </Card>
    </div>
  );
}

export default function GoalsPage(): ReactElement {
  const navigate = useNavigate();
  const goalsQuery = useGoalsState();
  const createGoalMutation = useCreateGoal();
  const updateGoalMutation = useUpdateGoal();
  const deleteGoalMutation = useDeleteGoal();
  const createTaskMutation = useCreateTask();
  const updateTaskMutation = useUpdateTask();
  const deleteTaskMutation = useDeleteTask();
  const [editorItem, setEditorItem] = useState<AutomationEditorItem | null>(null);

  const data = goalsQuery.data;
  const isBusy = useSchedulerBusyState([
    createGoalMutation,
    updateGoalMutation,
    deleteGoalMutation,
    createTaskMutation,
    updateTaskMutation,
    deleteTaskMutation,
  ]);

  useEffect(() => {
    // Ensure deep links to specific goals/tasks land on the correct card after async data resolves.
    if (data == null) {
      return;
    }
    const hash = window.location.hash.slice(1);
    if (hash.length === 0) {
      return;
    }
    window.requestAnimationFrame(() => {
      document.getElementById(hash)?.scrollIntoView({ block: 'start' });
    });
  }, [data]);

  if (goalsQuery.isLoading) {
    return <LoadingState />;
  }

  if (goalsQuery.isError || data == null) {
    return <ErrorState onRetry={() => { void goalsQuery.refetch(); }} />;
  }

  return (
    <GoalsWorkspace
      data={data}
      isBusy={isBusy}
      editorItem={editorItem}
      editorBusy={updateGoalMutation.isPending || updateTaskMutation.isPending}
      onCreateGoal={async (request) => {
        await createGoalMutation.mutateAsync(request);
      }}
      onCreateTask={async (request) => {
        await createTaskMutation.mutateAsync(request);
      }}
      onSaveEditorGoal={async (goalId, request) => {
        await updateGoalMutation.mutateAsync({ goalId, request });
      }}
      onSaveEditorTask={async (taskId, request) => {
        await updateTaskMutation.mutateAsync({ taskId, request });
      }}
      onCloseEditor={() => setEditorItem(null)}
      onEditGoal={(goal: Goal) => setEditorItem({ kind: 'goal', value: goal })}
      onEditTask={(task: GoalTask) => setEditorItem({ kind: 'task', value: task })}
      onDeleteGoal={(goalId) => {
        void deleteGoalMutation.mutateAsync(goalId);
      }}
      onDeleteTask={(taskId) => {
        void deleteTaskMutation.mutateAsync(taskId);
      }}
      onScheduleGoal={(goalId) => navigate(getSchedulerPrefillHref('GOAL', goalId))}
      onScheduleTask={(taskId) => navigate(getSchedulerPrefillHref('TASK', taskId))}
    />
  );
}
