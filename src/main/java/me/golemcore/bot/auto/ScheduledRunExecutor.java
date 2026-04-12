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
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SessionRunCoordinator;
import me.golemcore.bot.domain.service.StringValueSupport;
import me.golemcore.bot.port.outbound.SessionPort;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

    public ScheduledRunExecutor(
            AutoModeService autoModeService,
            SessionRunCoordinator sessionRunCoordinator,
            RuntimeConfigService runtimeConfigService,
            SessionPort sessionPort,
            ScheduledRunMessageFactory scheduledRunMessageFactory,
            ScheduleReportSender reportSender) {
        this.autoModeService = autoModeService;
        this.sessionRunCoordinator = sessionRunCoordinator;
        this.runtimeConfigService = runtimeConfigService;
        this.sessionPort = sessionPort;
        this.scheduledRunMessageFactory = scheduledRunMessageFactory;
        this.reportSender = reportSender;
    }

    public void executeSchedule(ScheduleEntry schedule, ScheduleDeliveryContext deliveryContext, int timeoutMinutes) {
        ScheduledRunMessage scheduleMessage = scheduledRunMessageFactory.buildForSchedule(schedule).orElse(null);
        if (scheduleMessage == null) {
            log.debug("[ScheduledRunExecutor] No action for schedule {}", schedule.getId());
            return;
        }

        ScheduleDeliveryContext effectiveDeliveryContext = deliveryContext != null
                ? deliveryContext
                : ScheduleDeliveryContext.auto();
        resetCompletedTaskIfNeeded(scheduleMessage);
        if (schedule.isClearContextBeforeRun()) {
            clearSessionContext(
                    effectiveDeliveryContext.channelType(),
                    effectiveDeliveryContext.sessionChatId(),
                    schedule.getId());
        }

        try {
            log.info("[ScheduledRunExecutor] Processing schedule {}: {}", schedule.getId(), scheduleMessage.content());
            submitAndAwait(scheduleMessage, schedule, timeoutMinutes, effectiveDeliveryContext, deliveryContext);
        } catch (TimeoutException e) {
            recordFailureAndMaybeReflect(
                    scheduleMessage,
                    schedule,
                    timeoutMinutes,
                    effectiveDeliveryContext,
                    "Run timed out after " + timeoutMinutes + " minutes",
                    "timeout",
                    null);
            log.error("[ScheduledRunExecutor] Schedule {} timed out after {} minutes", schedule.getId(),
                    timeoutMinutes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailureAndMaybeReflect(
                    scheduleMessage,
                    schedule,
                    timeoutMinutes,
                    effectiveDeliveryContext,
                    e.getMessage(),
                    "interrupted",
                    null);
            log.error("[ScheduledRunExecutor] Schedule {} interrupted: {}", schedule.getId(), e.getMessage(), e);
        } catch (ExecutionException e) {
            recordFailureAndMaybeReflect(
                    scheduleMessage,
                    schedule,
                    timeoutMinutes,
                    effectiveDeliveryContext,
                    e.getMessage(),
                    "execution_exception",
                    null);
            log.error("[ScheduledRunExecutor] Failed to process schedule {}: {}", schedule.getId(), e.getMessage(), e);
        }
    }

    private void submitAndAwait(
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
            return;
        }

        if (scheduleMessage.reflectionActive()) {
            autoModeService.applyReflectionResult(scheduleMessage.goalId(), scheduleMessage.taskId(), assistantText);
            return;
        }

        handleRunSuccess(scheduleMessage, activeSkillName);
        reportSender.sendReport(
                schedule,
                scheduledRunMessageFactory.buildReportHeader(scheduleMessage),
                assistantText,
                reportFallbackDeliveryContext);
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

        autoModeService.applyReflectionResult(
                reflectionMessage.goalId(),
                reflectionMessage.taskId(),
                readAssistantText(syntheticMessage));
    }

    private void handleRunSuccess(ScheduledRunMessage scheduleMessage, String activeSkillName) {
        if (!scheduleMessage.reflectionActive() && !StringValueSupport.isBlank(scheduleMessage.goalId())) {
            autoModeService.recordAutoRunSuccess(scheduleMessage.goalId(), scheduleMessage.taskId(), activeSkillName);
        }
    }

    private void recordFailedRunState(
            ScheduledRunMessage scheduleMessage,
            String summary,
            String fingerprint,
            String activeSkillName) {
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
        if (StringValueSupport.isBlank(scheduleMessage.goalId())) {
            return false;
        }
        return autoModeService.shouldTriggerReflection(scheduleMessage.goalId(), scheduleMessage.taskId());
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
}
