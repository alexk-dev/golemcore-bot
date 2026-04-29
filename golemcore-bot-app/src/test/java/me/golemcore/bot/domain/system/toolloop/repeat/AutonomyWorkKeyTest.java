package me.golemcore.bot.domain.system.toolloop.repeat;

import me.golemcore.bot.domain.model.ContextAttributes;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertTrue(key.storagePath().startsWith("auto/tool-ledgers/web_chat-1-"));
        assertTrue(key.storagePath().contains("/tasks/task-1-"));
        assertTrue(key.storagePath().endsWith(".json"));
        assertEquals("auto", key.storageDirectory());
        assertEquals(key.storagePath().substring("auto/".length()), key.storageFile());
    }

    @Test
    void derivesGoalKeyWhenTaskIsAbsent() {
        Map<String, Object> metadata = Map.of(
                ContextAttributes.AUTO_MODE, true,
                ContextAttributes.CONVERSATION_KEY, "web:chat-1",
                ContextAttributes.AUTO_GOAL_ID, "goal-1");

        AutonomyWorkKey key = AutonomyWorkKey.fromMetadata(metadata).orElseThrow();

        assertTrue(key.storagePath().startsWith("auto/tool-ledgers/web_chat-1-"));
        assertTrue(key.storagePath().contains("/goals/goal-1-"));
        assertTrue(key.storagePath().endsWith(".json"));
    }

    @Test
    void returnsEmptyForManualTurn() {
        Optional<AutonomyWorkKey> key = AutonomyWorkKey.fromMetadata(Map.of(
                ContextAttributes.CONVERSATION_KEY, "web:chat-1",
                ContextAttributes.AUTO_GOAL_ID, "goal-1"));

        assertTrue(key.isEmpty());
    }

    @Test
    void returnsEmptyWhenAutoMetadataIsIncomplete() {
        assertTrue(AutonomyWorkKey.fromMetadata(null).isEmpty());
        assertTrue(AutonomyWorkKey.fromMetadata(Map.of(ContextAttributes.AUTO_MODE, true)).isEmpty());
        assertTrue(AutonomyWorkKey.fromMetadata(Map.of(
                ContextAttributes.AUTO_MODE, true,
                ContextAttributes.CONVERSATION_KEY, "  ")).isEmpty());
    }

    @Test
    void sanitizesUnsafeStorageSegments() {
        Map<String, Object> metadata = Map.of(
                ContextAttributes.AUTO_MODE, true,
                ContextAttributes.CONVERSATION_KEY, "web/chat 1",
                ContextAttributes.AUTO_GOAL_ID, "goal:1");

        AutonomyWorkKey key = AutonomyWorkKey.fromMetadata(metadata).orElseThrow();

        assertTrue(key.storagePath().startsWith("auto/tool-ledgers/web_chat_1-"));
        assertTrue(key.storagePath().contains("/goals/goal_1-"));
        assertTrue(key.storagePath().endsWith(".json"));
    }

    @Test
    void storagePathIncludesHashToAvoidSanitizedSegmentCollisions() {
        AutonomyWorkKey slashTask = new AutonomyWorkKey("web:chat-1", "goal-1", "task/a", null);
        AutonomyWorkKey underscoreTask = new AutonomyWorkKey("web:chat-1", "goal-1", "task_a", null);

        assertNotEquals(slashTask.storagePath(), underscoreTask.storagePath());
        assertTrue(slashTask.storagePath().contains("task_a-"));
        assertTrue(underscoreTask.storagePath().contains("task_a-"));
    }

    @Test
    void sameTaskIdUnderDifferentGoalsUsesDifferentTaskLedgerPath() {
        AutonomyWorkKey first = new AutonomyWorkKey("web:chat-1", "goal-a", "task-1", null);
        AutonomyWorkKey second = new AutonomyWorkKey("web:chat-1", "goal-b", "task-1", null);

        assertNotEquals(first.storagePath(), second.storagePath());
    }

    @Test
    void noopLedgerStoreDoesNothing() {
        ToolUseLedgerStore store = ToolUseLedgerStore.noop();

        store.save(new AutonomyWorkKey("session", "goal", null, null), new ToolUseLedger());

        assertTrue(store.load(new AutonomyWorkKey("session", "goal", null, null), Duration.ofMinutes(1)).isEmpty());
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
