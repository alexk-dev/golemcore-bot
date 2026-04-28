package me.golemcore.bot.domain.system.toolloop.repeat;

import me.golemcore.bot.domain.model.ContextAttributes;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomyWorkKeyTest {

    @Test
    void derivesTaskKeyFromAutoMetadata() {
        Map<String, Object> metadata = Map.of(
                ContextAttributes.AUTO_MODE, true,
                ContextAttributes.CONVERSATION_KEY, "web:chat-1",
                ContextAttributes.AUTO_GOAL_ID, "goal-1",
                ContextAttributes.AUTO_TASK_ID, "task-1",
                ContextAttributes.AUTO_SCHEDULE_ID, "schedule-a");

        AutonomyWorkKey key = AutonomyWorkKey.fromMetadata(metadata).orElseThrow();

        assertEquals("web:chat-1", key.sessionKey());
        assertEquals("goal-1", key.goalId());
        assertEquals("task-1", key.taskId());
        assertEquals("auto/tool-ledgers/web_chat-1/tasks/task-1.json", key.storagePath());
    }

    @Test
    void derivesGoalKeyWhenTaskIsAbsent() {
        Map<String, Object> metadata = Map.of(
                ContextAttributes.AUTO_MODE, true,
                ContextAttributes.CONVERSATION_KEY, "web:chat-1",
                ContextAttributes.AUTO_GOAL_ID, "goal-1");

        AutonomyWorkKey key = AutonomyWorkKey.fromMetadata(metadata).orElseThrow();

        assertEquals("auto/tool-ledgers/web_chat-1/goals/goal-1.json", key.storagePath());
    }

    @Test
    void returnsEmptyForManualTurn() {
        Optional<AutonomyWorkKey> key = AutonomyWorkKey.fromMetadata(Map.of(
                ContextAttributes.CONVERSATION_KEY, "web:chat-1",
                ContextAttributes.AUTO_GOAL_ID, "goal-1"));

        assertTrue(key.isEmpty());
    }

    @Test
    void keySurvivesScheduleIdChangeForSameTask() {
        Map<String, Object> first = Map.of(
                ContextAttributes.AUTO_MODE, true,
                ContextAttributes.CONVERSATION_KEY, "web:chat-1",
                ContextAttributes.AUTO_GOAL_ID, "goal-1",
                ContextAttributes.AUTO_TASK_ID, "task-1",
                ContextAttributes.AUTO_SCHEDULE_ID, "schedule-a");
        Map<String, Object> second = Map.of(
                ContextAttributes.AUTO_MODE, true,
                ContextAttributes.CONVERSATION_KEY, "web:chat-1",
                ContextAttributes.AUTO_GOAL_ID, "goal-1",
                ContextAttributes.AUTO_TASK_ID, "task-1",
                ContextAttributes.AUTO_SCHEDULE_ID, "schedule-b");

        assertEquals(AutonomyWorkKey.fromMetadata(first).orElseThrow().storagePath(),
                AutonomyWorkKey.fromMetadata(second).orElseThrow().storagePath());
    }
}
