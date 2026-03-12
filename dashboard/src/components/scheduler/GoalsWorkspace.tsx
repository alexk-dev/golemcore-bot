import type { ReactElement } from 'react';
import { Card, Col, Row } from 'react-bootstrap';
import type { Goal, GoalTask, GoalsResponse } from '../../api/goals';
import { SchedulerAutomationCreateCard } from './SchedulerAutomationCreateCard';
import {
  type AutomationEditorItem,
  SchedulerAutomationEditModal,
} from './SchedulerAutomationEditModal';
import { SchedulerAutomationListCard } from './SchedulerAutomationListCard';
import { GoalsStatusHeader } from './GoalsStatusHeader';

interface GoalsWorkspaceProps {
  data: GoalsResponse;
  isBusy: boolean;
  editorItem: AutomationEditorItem | null;
  editorBusy: boolean;
  onCreateGoal: (title: string, description: string | null, prompt: string | null) => Promise<void>;
  onCreateTask: (goalId: string | null, title: string, description: string | null, prompt: string | null) => Promise<void>;
  onSaveEditorGoal: (goalId: string, request: { title: string; description: string | null; prompt: string | null; status: Goal['status'] }) => Promise<void>;
  onSaveEditorTask: (taskId: string, request: { title: string; description: string | null; prompt: string | null; status: GoalTask['status'] }) => Promise<void>;
  onCloseEditor: () => void;
  onEditGoal: (goal: Goal) => void;
  onEditTask: (task: GoalTask) => void;
  onDeleteGoal: (goalId: string) => void;
  onDeleteTask: (taskId: string) => void;
  onScheduleGoal: (goalId: string) => void;
  onScheduleTask: (taskId: string) => void;
}

export function GoalsWorkspace({
  data,
  isBusy,
  editorItem,
  editorBusy,
  onCreateGoal,
  onCreateTask,
  onSaveEditorGoal,
  onSaveEditorTask,
  onCloseEditor,
  onEditGoal,
  onEditTask,
  onDeleteGoal,
  onDeleteTask,
  onScheduleGoal,
  onScheduleTask,
}: GoalsWorkspaceProps): ReactElement {
  return (
    <div className="dashboard-main">
      <GoalsStatusHeader
        featureEnabled={data.featureEnabled}
        autoModeEnabled={data.autoModeEnabled}
      />

      {!data.featureEnabled && (
        <Card className="mb-3">
          <Card.Body className="text-body-secondary">
            Goals & Tasks management is unavailable because auto mode feature is disabled in runtime config.
          </Card.Body>
        </Card>
      )}

      <Row className="g-3">
        <Col xl={4}>
          <SchedulerAutomationCreateCard
            featureEnabled={data.featureEnabled}
            goals={data.goals}
            busy={isBusy}
            onCreateGoal={(request) => onCreateGoal(request.title, request.description, request.prompt)}
            onCreateTask={(request) => onCreateTask(request.goalId, request.title, request.description, request.prompt)}
          />
        </Col>

        <Col xl={8}>
          <SchedulerAutomationListCard
            goals={data.goals}
            standaloneTasks={data.standaloneTasks}
            busy={isBusy}
            onEditGoal={onEditGoal}
            onEditTask={onEditTask}
            onDeleteGoal={onDeleteGoal}
            onDeleteTask={onDeleteTask}
            onScheduleGoal={onScheduleGoal}
            onScheduleTask={onScheduleTask}
          />
        </Col>
      </Row>

      <SchedulerAutomationEditModal
        show={editorItem != null}
        item={editorItem}
        busy={editorBusy}
        onHide={onCloseEditor}
        onSaveGoal={onSaveEditorGoal}
        onSaveTask={onSaveEditorTask}
      />
    </div>
  );
}
