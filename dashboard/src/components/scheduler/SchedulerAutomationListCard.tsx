import type { ReactElement } from 'react';
import { Badge, Button, Card } from '../ui/tailwind-components';
import type { Goal, GoalTask } from '../../api/goals';
import { getGoalAnchorId, getTaskAnchorId } from './automationLinks';

interface SchedulerAutomationListCardProps {
  goals: Goal[];
  standaloneTasks: GoalTask[];
  busy: boolean;
  onEditGoal: (goal: Goal) => void;
  onEditTask: (task: GoalTask) => void;
  onDeleteGoal: (goalId: string) => void;
  onDeleteTask: (taskId: string) => void;
}

interface TaskRowProps {
  task: GoalTask;
  busy: boolean;
  onEditTask: (task: GoalTask) => void;
  onDeleteTask: (taskId: string) => void;
}

interface GoalCardProps {
  goal: Goal;
  busy: boolean;
  onEditGoal: (goal: Goal) => void;
  onEditTask: (task: GoalTask) => void;
  onDeleteGoal: (goalId: string) => void;
  onDeleteTask: (taskId: string) => void;
}

function resolveBadgeVariant(status: string): 'primary' | 'success' | 'warning' | 'danger' | 'secondary' {
  if (status === 'ACTIVE' || status === 'IN_PROGRESS') {
    return 'primary';
  }
  if (status === 'COMPLETED') {
    return 'success';
  }
  if (status === 'PAUSED' || status === 'PENDING') {
    return 'warning';
  }
  if (status === 'FAILED' || status === 'CANCELLED') {
    return 'danger';
  }
  return 'secondary';
}

function renderTextBlock(label: string, value: string | null): ReactElement | null {
  if (value == null || value.length === 0) {
    return null;
  }
  return (
    <div className="small text-body-secondary">
      <strong>{label}:</strong> {value}
    </div>
  );
}

function renderReflectionPriority(enabled: boolean): ReactElement | null {
  if (!enabled) {
    return null;
  }
  return (
    <div className="small text-body-secondary">
      <strong>Reflection priority:</strong> Enabled
    </div>
  );
}

function TaskRow({
  task,
  busy,
  onEditTask,
  onDeleteTask,
}: TaskRowProps): ReactElement {
  return (
    <div id={getTaskAnchorId(task.id)} className="border rounded p-3">
      <div className="d-flex justify-content-between align-items-start gap-3">
        <div className="flex-grow-1">
          <div className="d-flex align-items-center gap-2 flex-wrap">
            <strong>{task.title}</strong>
            <Badge bg={resolveBadgeVariant(task.status)}>{task.status}</Badge>
            {task.standalone && <Badge bg="secondary">Standalone</Badge>}
          </div>
          {renderTextBlock('Details', task.description)}
          {renderTextBlock('Prompt', task.prompt)}
          {renderTextBlock('Reflection tier', task.reflectionModelTier)}
          {renderReflectionPriority(task.reflectionTierPriority)}
        </div>
        <div className="d-flex gap-2 flex-wrap justify-content-end">
          <Button type="button" size="sm" variant="secondary" disabled={busy} onClick={() => onEditTask(task)}>
            Edit
          </Button>
          <Button type="button" size="sm" variant="danger" disabled={busy} onClick={() => onDeleteTask(task.id)}>
            Delete
          </Button>
        </div>
      </div>
    </div>
  );
}

function GoalCard({
  goal,
  busy,
  onEditGoal,
  onEditTask,
  onDeleteGoal,
  onDeleteTask,
}: GoalCardProps): ReactElement {
  return (
    <div id={getGoalAnchorId(goal.id)} className="border rounded p-3">
      <div className="d-flex justify-content-between align-items-start gap-3">
        <div className="flex-grow-1">
          <div className="d-flex align-items-center gap-2 flex-wrap">
            <strong>{goal.title}</strong>
            <Badge bg={resolveBadgeVariant(goal.status)}>{goal.status}</Badge>
            <Badge bg="secondary">{goal.completedTasks}/{goal.totalTasks} tasks</Badge>
          </div>
          {renderTextBlock('Details', goal.description)}
          {renderTextBlock('Prompt', goal.prompt)}
          {renderTextBlock('Reflection tier', goal.reflectionModelTier)}
          {renderReflectionPriority(goal.reflectionTierPriority)}
        </div>
        <div className="d-flex gap-2 flex-wrap justify-content-end">
          <Button type="button" size="sm" variant="secondary" disabled={busy} onClick={() => onEditGoal(goal)}>
            Edit
          </Button>
          <Button type="button" size="sm" variant="danger" disabled={busy} onClick={() => onDeleteGoal(goal.id)}>
            Delete
          </Button>
        </div>
      </div>

      {goal.tasks.length > 0 ? (
        <div className="d-flex flex-column gap-2 mt-3">
          {goal.tasks.map((task) => (
            <TaskRow
              key={task.id}
              task={task}
              busy={busy}
              onEditTask={onEditTask}
              onDeleteTask={onDeleteTask}
            />
          ))}
        </div>
      ) : (
        <div className="small text-body-secondary mt-3">No tasks yet for this goal.</div>
      )}
    </div>
  );
}

export function SchedulerAutomationListCard({
  goals,
  standaloneTasks,
  busy,
  onEditGoal,
  onEditTask,
  onDeleteGoal,
  onDeleteTask,
}: SchedulerAutomationListCardProps): ReactElement {
  return (
    <Card className="h-100">
      <Card.Header className="fw-semibold">Goals & Tasks</Card.Header>
      <Card.Body className="d-flex flex-column gap-3">
        {goals.length === 0 && standaloneTasks.length === 0 && (
          <div className="text-body-secondary">No goals or tasks yet.</div>
        )}

        {goals.length > 0 && (
          <div className="d-flex flex-column gap-3">
            {goals.map((goal) => (
              <GoalCard
                key={goal.id}
                goal={goal}
                busy={busy}
                onEditGoal={onEditGoal}
                onEditTask={onEditTask}
                onDeleteGoal={onDeleteGoal}
                onDeleteTask={onDeleteTask}
              />
            ))}
          </div>
        )}

        {standaloneTasks.length > 0 && (
          <div className="d-flex flex-column gap-2">
            <div className="fw-semibold">Standalone tasks</div>
            {standaloneTasks.map((task) => (
              <TaskRow
                key={task.id}
                task={task}
                busy={busy}
                onEditTask={onEditTask}
                onDeleteTask={onDeleteTask}
              />
            ))}
          </div>
        )}
      </Card.Body>
    </Card>
  );
}
