package me.golemcore.bot.auto;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.ScheduledTask;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SessionRunCoordinator;
import me.golemcore.bot.domain.service.StringValueSupport;
import me.golemcore.bot.port.outbound.SessionPort;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes a single scheduled run, including reflection and report dispatch.
 */
@Component
@Slf4j
public class ScheduledRunExecutor {

    private final AutoModeService autoModeService;
    private final SessionRunCoordinator sessionRunCoordinator;
    private final RuntimeConfigService runtimeConfigService;
    private final SessionPort sessionPort;
    private final ScheduledRunMessageFactory scheduledRunMessageFactory;
    private final ScheduleReportSender reportSender;
    private final ScheduledTaskShellRunner scheduledTaskShellRunner;
    private final java.util.Set<String> activeScheduledTaskIds = ConcurrentHashMap.newKeySet();

    public ScheduledRunExecutor(
            AutoModeService autoModeService,
            SessionRunCoordinator sessionRunCoordinator,
            RuntimeConfigService runtimeConfigService,
            SessionPort sessionPort,
            ScheduledRunMessageFactory scheduledRunMessageFactory,
            ScheduleReportSender reportSender,
            ScheduledTaskShellRunner scheduledTaskShellRunner) {
        this.autoModeService = autoModeService;
        this.sessionRunCoordinator = sessionRunCoordinator;
        this.runtimeConfigService = runtimeConfigService;
        this.sessionPort = sessionPort;
        this.scheduledRunMessageFactory = scheduledRunMessageFactory;
        this.reportSender = reportSender;
        this.scheduledTaskShellRunner = scheduledTaskShellRunner;
    }

    public ScheduledRunOutcome executeSchedule(
            ScheduleEntry schedule,
            ScheduleDeliveryContext deliveryContext,
            int timeoutMinutes) {
        ScheduledRunMessage scheduleMessage;
        try {
            scheduleMessage = scheduledRunMessageFactory.buildForSchedule(schedule).orElse(null);
        } catch (RuntimeException exception) { // NOSONAR - build failures are retryable scheduler attempts
            log.error("[ScheduledRunExecutor] Failed to build message for schedule {}: {}",
                    schedule.getId(), exception.getMessage(), exception);
            return ScheduledRunOutcome.FAILED;
        }
        if (scheduleMessage == null) {
            log.debug("[ScheduledRunExecutor] No action for schedule {}", schedule.getId());
            return ScheduledRunOutcome.SKIPPED_TARGET_MISSING;
        }

        ScheduleDeliveryContext effectiveDeliveryContext = deliveryContext != null
                ? deliveryContext
                : ScheduleDeliveryContext.auto();
        String scheduledTaskId = StringValueSupport.isBlank(scheduleMessage.scheduledTaskId())
                ? null
                : scheduleMessage.scheduledTaskId();
        if (scheduledTaskId != null && !activeScheduledTaskIds.add(scheduledTaskId)) {
            log.info("[ScheduledRunExecutor] Scheduled task {} is already running; skipping schedule {}",
                    scheduledTaskId, schedule.getId());
            return ScheduledRunOutcome.SKIPPED_TASK_BUSY;
        }

        try {
            Optional<ScheduledTask> scheduledTask = resolveScheduledTask(scheduleMessage);
            if (scheduledTask.filter(ScheduledTask::isShellCommandMode).isPresent()) {
                String runId = UUID.randomUUID().toString();
                Message syntheticMessage = buildSyntheticMessageForShellHistory(
                        scheduleMessage,
                        schedule,
                        effectiveDeliveryContext,
                        runId);
                return executeShellSchedule(
                        scheduleMessage,
                        schedule,
                        scheduledTask.orElseThrow(),
                        timeoutMinutes,
                        deliveryContext,
                        syntheticMessage);
            }
            resetCompletedTaskIfNeeded(scheduleMessage);
            if (schedule.isClearContextBeforeRun()) {
                clearSessionContext(
                        effectiveDeliveryContext.channelType(),
                        effectiveDeliveryContext.sessionChatId(),
                        schedule.getId());
            }
            log.info("[ScheduledRunExecutor] Processing schedule {}: {}", schedule.getId(), scheduleMessage.content());
            boolean success = submitAndAwait(scheduleMessage, schedule, timeoutMinutes, effectiveDeliveryContext,
                    deliveryContext);
            return success ? ScheduledRunOutcome.EXECUTED : ScheduledRunOutcome.FAILED;
        } catch (TimeoutException e) {
            recordFailureAndMaybeReflectSafely(
                    scheduleMessage,
                    schedule,
                    timeoutMinutes,
                    effectiveDeliveryContext,
                    "Run timed out after " + timeoutMinutes + " minutes",
                    "timeout",
                    null);
            log.error("[ScheduledRunExecutor] Schedule {} timed out after {} minutes", schedule.getId(),
                    timeoutMinutes);
            return ScheduledRunOutcome.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailureAndMaybeReflectSafely(
                    scheduleMessage,
                    schedule,
                    timeoutMinutes,
                    effectiveDeliveryContext,
                    e.getMessage(),
                    "interrupted",
                    null);
            log.error("[ScheduledRunExecutor] Schedule {} interrupted: {}", schedule.getId(), e.getMessage(), e);
            return ScheduledRunOutcome.FAILED;
        } catch (ExecutionException e) {
            recordFailureAndMaybeReflectSafely(
                    scheduleMessage,
                    schedule,
                    timeoutMinutes,
                    effectiveDeliveryContext,
                    e.getMessage(),
                    "execution_exception",
                    null);
            log.error("[ScheduledRunExecutor] Failed to process schedule {}: {}", schedule.getId(), e.getMessage(), e);
            return ScheduledRunOutcome.FAILED;
        } catch (RuntimeException e) {
            recordFailureAndMaybeReflectSafely(
                    scheduleMessage,
                    schedule,
                    timeoutMinutes,
                    effectiveDeliveryContext,
                    e.getMessage(),
                    "runtime_exception",
                    null);
            log.error("[ScheduledRunExecutor] Schedule {} failed before run completion: {}", schedule.getId(),
                    e.getMessage(), e);
            return ScheduledRunOutcome.FAILED;
        } finally {
            if (scheduledTaskId != null) {
                activeScheduledTaskIds.remove(scheduledTaskId);
            }
        }
    }

    private ScheduledRunOutcome executeShellSchedule(
            ScheduledRunMessage scheduleMessage,
            ScheduleEntry schedule,
            ScheduledTask scheduledTask,
            int timeoutMinutes,
            ScheduleDeliveryContext reportFallbackDeliveryContext,
            Message syntheticMessage) {
        try {
            ScheduledTaskShellRunner.ShellRunResult result = scheduledTaskShellRunner.run(scheduledTask,
                    timeoutMinutes);
            String reportBody = result.reportBody();
            if (result.success()) {
                handleRunSuccess(scheduleMessage, null);
                persistShellRunHistory(syntheticMessage, reportBody, "COMPLETED", null, null);
                reportSender.sendReport(
                        schedule,
                        scheduledRunMessageFactory.buildReportHeader(scheduleMessage),
                        reportBody,
                        reportFallbackDeliveryContext);
                return ScheduledRunOutcome.EXECUTED;
            }

            recordFailedRunState(scheduleMessage, result.summary(), result.fingerprint(), null);
            persistShellRunHistory(
                    syntheticMessage,
                    reportBody != null ? reportBody : result.summary(),
                    "FAILED",
                    result.summary(),
                    result.fingerprint());
            reportSender.sendReport(
                    schedule,
                    scheduledRunMessageFactory.buildReportHeader(scheduleMessage),
                    reportBody,
                    reportFallbackDeliveryContext);
            return ScheduledRunOutcome.FAILED;
        } catch (TimeoutException e) {
            recordFailedRunState(
                    scheduleMessage,
                    "Shell command timed out after " + timeoutMinutes + " minutes",
                    "shell_timeout",
                    null);
            persistShellRunHistory(
                    syntheticMessage,
                    "Shell command timed out after " + timeoutMinutes + " minutes",
                    "FAILED",
                    "Shell command timed out after " + timeoutMinutes + " minutes",
                    "shell_timeout");
            log.error("[ScheduledRunExecutor] Shell schedule {} timed out after {} minutes", schedule.getId(),
                    timeoutMinutes);
            return ScheduledRunOutcome.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailedRunState(scheduleMessage, e.getMessage(), "shell_interrupted", null);
            persistShellRunHistory(
                    syntheticMessage,
                    e.getMessage(),
                    "FAILED",
                    e.getMessage(),
                    "shell_interrupted");
            log.error("[ScheduledRunExecutor] Shell schedule {} interrupted: {}", schedule.getId(), e.getMessage(), e);
            return ScheduledRunOutcome.FAILED;
        } catch (ExecutionException e) {
            recordFailedRunState(scheduleMessage, e.getMessage(), "shell_execution_exception", null);
            persistShellRunHistory(
                    syntheticMessage,
                    e.getMessage(),
                    "FAILED",
                    e.getMessage(),
                    "shell_execution_exception");
            log.error("[ScheduledRunExecutor] Shell schedule {} failed: {}", schedule.getId(), e.getMessage(), e);
            return ScheduledRunOutcome.FAILED;
        } catch (RuntimeException e) {
            recordFailedRunState(scheduleMessage, e.getMessage(), "shell_runtime_exception", null);
            persistShellRunHistory(
                    syntheticMessage,
                    e.getMessage(),
                    "FAILED",
                    e.getMessage(),
                    "shell_runtime_exception");
            log.error("[ScheduledRunExecutor] Shell schedule {} failed before completion: {}", schedule.getId(),
                    e.getMessage(), e);
            return ScheduledRunOutcome.FAILED;
        }
    }

    private void recordFailureAndMaybeReflectSafely(
            ScheduledRunMessage scheduleMessage,
            ScheduleEntry schedule,
            int timeoutMinutes,
            ScheduleDeliveryContext deliveryContext,
            String summary,
            String fingerprint,
            String activeSkillName) {
        try {
            recordFailureAndMaybeReflect(
                    scheduleMessage,
                    schedule,
                    timeoutMinutes,
                    deliveryContext,
                    summary,
                    fingerprint,
                    activeSkillName);
        } catch (RuntimeException e) {
            log.warn("[ScheduledRunExecutor] Failed to record failed run state for schedule {}: {}",
                    schedule.getId(), e.getMessage(), e);
        }
    }

    private boolean submitAndAwait(
            ScheduledRunMessage scheduleMessage,
            ScheduleEntry schedule,
            int timeoutMinutes,
            ScheduleDeliveryContext deliveryContext,
            ScheduleDeliveryContext reportFallbackDeliveryContext)
            throws InterruptedException, ExecutionException, TimeoutException {
        Message syntheticMessage = scheduledRunMessageFactory.buildSyntheticMessage(
                scheduleMessage,
                schedule,
                deliveryContext,
                UUID.randomUUID().toString());
        CompletableFuture<Void> completion = sessionRunCoordinator.submit(syntheticMessage);
        completion.get(timeoutMinutes, TimeUnit.MINUTES);

        String runStatus = readStatus(syntheticMessage);
        String assistantText = readAssistantText(syntheticMessage);
        String activeSkillName = readActiveSkillName(syntheticMessage);
        if ("FAILED".equals(runStatus)) {
            String failureSummary = readFailureSummary(syntheticMessage);
            recordFailureAndMaybeReflect(
                    scheduleMessage,
                    schedule,
                    timeoutMinutes,
                    deliveryContext,
                    failureSummary,
                    readFailureFingerprint(syntheticMessage),
                    activeSkillName);
            reportSender.sendReport(
                    schedule,
                    scheduledRunMessageFactory.buildReportHeader(scheduleMessage),
                    failureSummary != null ? failureSummary : assistantText,
                    reportFallbackDeliveryContext);
            return false;
        }

        if (scheduleMessage.reflectionActive()) {
            applyReflectionResult(scheduleMessage, assistantText);
            return true;
        }

        handleRunSuccess(scheduleMessage, activeSkillName);
        reportSender.sendReport(
                schedule,
                scheduledRunMessageFactory.buildReportHeader(scheduleMessage),
                assistantText,
                reportFallbackDeliveryContext);
        return true;
    }

    private void recordFailureAndMaybeReflect(
            ScheduledRunMessage scheduleMessage,
            ScheduleEntry schedule,
            int timeoutMinutes,
            ScheduleDeliveryContext deliveryContext,
            String summary,
            String fingerprint,
            String activeSkillName) {
        recordFailedRunState(scheduleMessage, summary, fingerprint, activeSkillName);
        if (!shouldRunReflection(scheduleMessage)) {
            return;
        }

        try {
            runReflection(scheduleMessage, schedule, timeoutMinutes, deliveryContext);
        } catch (TimeoutException e) {
            recordFailedRunState(scheduleMessage,
                    "Reflection timed out after " + timeoutMinutes + " minutes",
                    "reflection_timeout",
                    null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailedRunState(scheduleMessage, e.getMessage(), "reflection_interrupted", null);
        } catch (ExecutionException e) {
            recordFailedRunState(scheduleMessage, e.getMessage(), "reflection_execution_exception", null);
        }
    }

    private void runReflection(
            ScheduledRunMessage scheduleMessage,
            ScheduleEntry schedule,
            int timeoutMinutes,
            ScheduleDeliveryContext deliveryContext)
            throws InterruptedException, ExecutionException, TimeoutException {
        ScheduledRunMessage reflectionMessage = scheduledRunMessageFactory
                .buildReflectionMessage(scheduleMessage, schedule.getId())
                .orElse(null);
        if (reflectionMessage == null) {
            return;
        }

        Message syntheticMessage = scheduledRunMessageFactory.buildSyntheticMessage(
                reflectionMessage,
                schedule,
                deliveryContext,
                UUID.randomUUID().toString());
        CompletableFuture<Void> completion = sessionRunCoordinator.submit(syntheticMessage);
        completion.get(timeoutMinutes, TimeUnit.MINUTES);

        String runStatus = readStatus(syntheticMessage);
        if ("FAILED".equals(runStatus)) {
            recordFailedRunState(
                    reflectionMessage,
                    readFailureSummary(syntheticMessage),
                    readFailureFingerprint(syntheticMessage),
                    readActiveSkillName(syntheticMessage));
            return;
        }

        applyReflectionResult(reflectionMessage, readAssistantText(syntheticMessage));
    }

    private void handleRunSuccess(ScheduledRunMessage scheduleMessage, String activeSkillName) {
        if (scheduleMessage.reflectionActive()) {
            return;
        }
        if (!StringValueSupport.isBlank(scheduleMessage.scheduledTaskId())) {
            autoModeService.recordScheduledTaskSuccess(scheduleMessage.scheduledTaskId(), activeSkillName);
            return;
        }
        if (!StringValueSupport.isBlank(scheduleMessage.goalId())) {
            autoModeService.recordAutoRunSuccess(scheduleMessage.goalId(), scheduleMessage.taskId(), activeSkillName);
        }
    }

    private void recordFailedRunState(
            ScheduledRunMessage scheduleMessage,
            String summary,
            String fingerprint,
            String activeSkillName) {
        if (!StringValueSupport.isBlank(scheduleMessage.scheduledTaskId())) {
            autoModeService.recordScheduledTaskFailure(
                    scheduleMessage.scheduledTaskId(),
                    summary,
                    fingerprint,
                    activeSkillName);
            return;
        }
        if (StringValueSupport.isBlank(scheduleMessage.goalId())) {
            return;
        }

        autoModeService.recordAutoRunFailure(
                scheduleMessage.goalId(),
                scheduleMessage.taskId(),
                summary,
                fingerprint,
                activeSkillName);
    }

    private boolean shouldRunReflection(ScheduledRunMessage scheduleMessage) {
        if (scheduleMessage.reflectionActive()) {
            return false;
        }
        if (!runtimeConfigService.isAutoReflectionEnabled()) {
            return false;
        }
        if (!StringValueSupport.isBlank(scheduleMessage.scheduledTaskId())) {
            return autoModeService.shouldTriggerScheduledTaskReflection(scheduleMessage.scheduledTaskId());
        }
        if (StringValueSupport.isBlank(scheduleMessage.goalId())) {
            return false;
        }
        return autoModeService.shouldTriggerReflection(scheduleMessage.goalId(), scheduleMessage.taskId());
    }

    private void applyReflectionResult(ScheduledRunMessage scheduleMessage, String assistantText) {
        if (!StringValueSupport.isBlank(scheduleMessage.scheduledTaskId())) {
            autoModeService.applyScheduledTaskReflectionResult(scheduleMessage.scheduledTaskId(), assistantText);
            return;
        }
        if (!StringValueSupport.isBlank(scheduleMessage.goalId())) {
            autoModeService.applyReflectionResult(scheduleMessage.goalId(), scheduleMessage.taskId(), assistantText);
        }
    }

    private String readStatus(Message syntheticMessage) {
        return syntheticMessage.getMetadata() != null
                ? (String) syntheticMessage.getMetadata().get(ContextAttributes.AUTO_RUN_STATUS)
                : null;
    }

    private String readFailureSummary(Message syntheticMessage) {
        return syntheticMessage.getMetadata() != null
                ? (String) syntheticMessage.getMetadata().get(ContextAttributes.AUTO_RUN_FAILURE_SUMMARY)
                : null;
    }

    private String readFailureFingerprint(Message syntheticMessage) {
        return syntheticMessage.getMetadata() != null
                ? (String) syntheticMessage.getMetadata().get(ContextAttributes.AUTO_RUN_FAILURE_FINGERPRINT)
                : null;
    }

    private String readAssistantText(Message syntheticMessage) {
        return syntheticMessage.getMetadata() != null
                ? (String) syntheticMessage.getMetadata().get(ContextAttributes.AUTO_RUN_ASSISTANT_TEXT)
                : null;
    }

    private String readActiveSkillName(Message syntheticMessage) {
        return syntheticMessage.getMetadata() != null
                ? (String) syntheticMessage.getMetadata().get(ContextAttributes.AUTO_RUN_ACTIVE_SKILL)
                : null;
    }

    private void clearSessionContext(String channelType, String sessionChatId, String scheduleId) {
        String sessionId = sessionPort.getOrCreate(channelType, sessionChatId).getId();
        sessionPort.clearMessages(sessionId);
        log.info("[ScheduledRunExecutor] Cleared session context before schedule run: scheduleId={}, sessionId={}",
                scheduleId, sessionId);
    }

    private void resetCompletedTaskIfNeeded(ScheduledRunMessage scheduleMessage) {
        if (!scheduleMessage.resetTaskBeforeRun() || StringValueSupport.isBlank(scheduleMessage.goalId())
                || StringValueSupport.isBlank(scheduleMessage.taskId())) {
            return;
        }
        autoModeService.updateTaskStatus(
                scheduleMessage.goalId(),
                scheduleMessage.taskId(),
                me.golemcore.bot.domain.model.AutoTask.TaskStatus.PENDING,
                null);
        log.info("[ScheduledRunExecutor] Reset completed task {} to PENDING for scheduled re-run",
                scheduleMessage.taskId());
    }

    private Optional<ScheduledTask> resolveScheduledTask(ScheduledRunMessage scheduleMessage) {
        if (scheduleMessage == null || StringValueSupport.isBlank(scheduleMessage.scheduledTaskId())) {
            return Optional.empty();
        }
        return autoModeService.getScheduledTask(scheduleMessage.scheduledTaskId());
    }

    private Message buildSyntheticMessageForShellHistory(
            ScheduledRunMessage scheduleMessage,
            ScheduleEntry schedule,
            ScheduleDeliveryContext deliveryContext,
            String runId) {
        try {
            Message syntheticMessage = Objects.requireNonNull(
                    scheduledRunMessageFactory.buildSyntheticMessage(
                            scheduleMessage,
                            schedule,
                            deliveryContext,
                            runId),
                    "Synthetic scheduled-run message is required for shell history");
            return enrichSyntheticMessageForShellHistory(
                    syntheticMessage,
                    scheduleMessage,
                    schedule,
                    deliveryContext,
                    runId);
        } catch (RuntimeException exception) { // NOSONAR - shell execution must not depend on history metadata
            log.warn("[ScheduledRunExecutor] Failed to build synthetic shell history message for schedule {}: {}",
                    schedule.getId(), exception.getMessage());
            return buildFallbackSyntheticMessageForShellHistory(
                    scheduleMessage,
                    schedule,
                    deliveryContext,
                    runId);
        }
    }

    private Message buildFallbackSyntheticMessageForShellHistory(
            ScheduledRunMessage scheduleMessage,
            ScheduleEntry schedule,
            ScheduleDeliveryContext deliveryContext,
            String runId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ContextAttributes.AUTO_MODE, true);
        metadata.put(ContextAttributes.AUTO_RUN_KIND, scheduleMessage.runKind().name());
        metadata.put(ContextAttributes.AUTO_RUN_ID, runId);
        metadata.put(ContextAttributes.AUTO_SCHEDULE_ID, schedule.getId());
        metadata.put(ContextAttributes.CONVERSATION_KEY, deliveryContext.sessionChatId());
        metadata.put(ContextAttributes.TRANSPORT_CHAT_ID, deliveryContext.transportChatId());
        if (!StringValueSupport.isBlank(scheduleMessage.goalId())) {
            metadata.put(ContextAttributes.AUTO_GOAL_ID, scheduleMessage.goalId());
        }
        if (!StringValueSupport.isBlank(scheduleMessage.taskId())) {
            metadata.put(ContextAttributes.AUTO_TASK_ID, scheduleMessage.taskId());
        }
        if (!StringValueSupport.isBlank(scheduleMessage.scheduledTaskId())) {
            metadata.put(ContextAttributes.AUTO_SCHEDULED_TASK_ID, scheduleMessage.scheduledTaskId());
        }
        if (scheduleMessage.reflectionActive()) {
            metadata.put(ContextAttributes.AUTO_REFLECTION_ACTIVE, true);
        }

        return Message.builder()
                .id(UUID.randomUUID().toString())
                .role("user")
                .content(scheduleMessage.content())
                .channelType(deliveryContext.channelType())
                .chatId(deliveryContext.sessionChatId())
                .senderId("auto")
                .metadata(metadata)
                .timestamp(java.time.Instant.now())
                .build();
    }

    private Message enrichSyntheticMessageForShellHistory(
            Message syntheticMessage,
            ScheduledRunMessage scheduleMessage,
            ScheduleEntry schedule,
            ScheduleDeliveryContext deliveryContext,
            String runId) {
        Map<String, Object> metadata = syntheticMessage.getMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(syntheticMessage.getMetadata());
        metadata.putIfAbsent(ContextAttributes.AUTO_MODE, true);
        metadata.putIfAbsent(ContextAttributes.AUTO_RUN_KIND, scheduleMessage.runKind().name());
        metadata.putIfAbsent(ContextAttributes.AUTO_RUN_ID, runId);
        metadata.putIfAbsent(ContextAttributes.AUTO_SCHEDULE_ID, schedule.getId());
        metadata.putIfAbsent(ContextAttributes.CONVERSATION_KEY, deliveryContext.sessionChatId());
        metadata.putIfAbsent(ContextAttributes.TRANSPORT_CHAT_ID, deliveryContext.transportChatId());
        if (!StringValueSupport.isBlank(scheduleMessage.goalId())) {
            metadata.putIfAbsent(ContextAttributes.AUTO_GOAL_ID, scheduleMessage.goalId());
        }
        if (!StringValueSupport.isBlank(scheduleMessage.taskId())) {
            metadata.putIfAbsent(ContextAttributes.AUTO_TASK_ID, scheduleMessage.taskId());
        }
        if (!StringValueSupport.isBlank(scheduleMessage.scheduledTaskId())) {
            metadata.putIfAbsent(ContextAttributes.AUTO_SCHEDULED_TASK_ID, scheduleMessage.scheduledTaskId());
        }
        if (scheduleMessage.reflectionActive()) {
            metadata.putIfAbsent(ContextAttributes.AUTO_REFLECTION_ACTIVE, true);
        }

        return Message.builder()
                .id(!StringValueSupport.isBlank(syntheticMessage.getId()) ? syntheticMessage.getId()
                        : UUID.randomUUID().toString())
                .role(syntheticMessage.getRole() != null ? syntheticMessage.getRole() : "user")
                .content(syntheticMessage.getContent() != null ? syntheticMessage.getContent()
                        : scheduleMessage.content())
                .channelType(!StringValueSupport.isBlank(syntheticMessage.getChannelType())
                        ? syntheticMessage.getChannelType()
                        : deliveryContext.channelType())
                .chatId(!StringValueSupport.isBlank(syntheticMessage.getChatId())
                        ? syntheticMessage.getChatId()
                        : deliveryContext.sessionChatId())
                .senderId(!StringValueSupport.isBlank(syntheticMessage.getSenderId())
                        ? syntheticMessage.getSenderId()
                        : "auto")
                .metadata(metadata)
                .timestamp(syntheticMessage.getTimestamp() != null ? syntheticMessage.getTimestamp()
                        : java.time.Instant.now())
                .build();
    }

    private void persistShellRunHistory(
            Message syntheticMessage,
            String assistantText,
            String status,
            String failureSummary,
            String failureFingerprint) {
        if (syntheticMessage == null
                || StringValueSupport.isBlank(syntheticMessage.getChannelType())
                || StringValueSupport.isBlank(syntheticMessage.getChatId())) {
            return;
        }

        try {
            AgentSession session = sessionPort.getOrCreate(syntheticMessage.getChannelType(),
                    syntheticMessage.getChatId());
            if (session == null) {
                return;
            }
            session.addMessage(copyHistoryMessage(syntheticMessage));
            session.addMessage(buildShellAssistantMessage(
                    syntheticMessage,
                    assistantText,
                    status,
                    failureSummary,
                    failureFingerprint));
            sessionPort.save(session);
        } catch (RuntimeException exception) { // NOSONAR - history write must not change shell run outcome
            log.warn("[ScheduledRunExecutor] Failed to persist shell run history: {}", exception.getMessage());
        }
    }

    private Message copyHistoryMessage(Message message) {
        Map<String, Object> copiedMetadata = message.getMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(message.getMetadata());
        return Message.builder()
                .id(!StringValueSupport.isBlank(message.getId()) ? message.getId() : UUID.randomUUID().toString())
                .role(message.getRole())
                .content(message.getContent())
                .channelType(message.getChannelType())
                .chatId(message.getChatId())
                .senderId(message.getSenderId())
                .metadata(copiedMetadata)
                .timestamp(message.getTimestamp() != null ? message.getTimestamp() : java.time.Instant.now())
                .build();
    }

    private Message buildShellAssistantMessage(
            Message syntheticMessage,
            String assistantText,
            String status,
            String failureSummary,
            String failureFingerprint) {
        Map<String, Object> metadata = syntheticMessage.getMetadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(syntheticMessage.getMetadata());
        if (!StringValueSupport.isBlank(status)) {
            metadata.put(ContextAttributes.AUTO_RUN_STATUS, status);
        }
        if (!StringValueSupport.isBlank(assistantText)) {
            metadata.put(ContextAttributes.AUTO_RUN_ASSISTANT_TEXT, assistantText);
        }
        if (!StringValueSupport.isBlank(failureSummary)) {
            metadata.put(ContextAttributes.AUTO_RUN_FAILURE_SUMMARY, failureSummary);
        }
        if (!StringValueSupport.isBlank(failureFingerprint)) {
            metadata.put(ContextAttributes.AUTO_RUN_FAILURE_FINGERPRINT, failureFingerprint);
        }

        return Message.builder()
                .id(UUID.randomUUID().toString())
                .role("assistant")
                .content(assistantText)
                .channelType(syntheticMessage.getChannelType())
                .chatId(syntheticMessage.getChatId())
                .senderId("auto")
                .metadata(metadata)
                .timestamp(java.time.Instant.now())
                .build();
    }
}
