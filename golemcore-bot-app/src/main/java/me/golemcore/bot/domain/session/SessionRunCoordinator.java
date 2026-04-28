package me.golemcore.bot.domain.session;

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

import me.golemcore.bot.domain.scheduling.DelayedSessionActionService;
import me.golemcore.bot.domain.resilience.ResilienceObservabilitySupport;
import me.golemcore.bot.domain.events.RuntimeEventService;
import me.golemcore.bot.domain.runtimeconfig.TurnRuntimeConfigView;
import me.golemcore.bot.port.outbound.RuntimeEventPublishPort;
import me.golemcore.bot.port.outbound.SessionRunDispatchPort;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FailureEvent;
import me.golemcore.bot.domain.model.FailureKind;
import me.golemcore.bot.domain.model.FailureSource;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.TurnRunResult;
import me.golemcore.bot.domain.model.hive.HiveRuntimeContracts;
import me.golemcore.bot.port.outbound.SessionPort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * Coordinates execution of {@link AgentLoop} per session.
 *
 * <p>
 * Supports:
 * </p>
 * <ul>
 * <li>Accept inbound messages while a run is executing (queued).</li>
 * <li>Expose awaitable submissions for background producers such as auto
 * mode.</li>
 * <li>/stop: interrupt the current run and pause processing until the next
 * inbound.</li>
 * <li>After /stop, flush queued user messages into raw history before
 * processing the next inbound.</li>
 * </ul>
 */
@Service
@Slf4j
public class SessionRunCoordinator implements SessionRunDispatchPort {

    private static final int MAX_QUEUED_MESSAGES_PER_SESSION = 100;
    private static final String SKIPPED_AFTER_STOP_REQUEST = "Skipped after stop request";

    private final AgentLoop agentLoop;
    private final ExecutorService sessionRunExecutor;
    private final TurnRuntimeConfigView runtimeConfigService;
    private final DelayedSessionActionService delayedSessionActionService;

    private final Map<SessionKey, SessionRunner> runners = new ConcurrentHashMap<>();
    private final PendingCompletionRegistry pendingCompletions = new PendingCompletionRegistry();
    private final QueuedMessageFlushService queuedMessageFlushService;
    private final StopRequestController stopRequestController;
    private final SessionActivityTracker activityTracker = new SessionActivityTracker();
    private final TurnRunResultMapper turnRunResultMapper = new TurnRunResultMapper();

    public SessionRunCoordinator(SessionPort sessionPort, AgentLoop agentLoop, ExecutorService sessionRunExecutor,
            RuntimeEventService runtimeEventService, TurnRuntimeConfigView runtimeConfigService,
            DelayedSessionActionService delayedSessionActionService,
            RuntimeEventPublishPort runtimeEventPublishPort) {
        this.agentLoop = agentLoop;
        this.sessionRunExecutor = sessionRunExecutor;
        this.runtimeConfigService = runtimeConfigService;
        this.delayedSessionActionService = delayedSessionActionService;
        this.queuedMessageFlushService = new QueuedMessageFlushService(sessionPort);
        this.stopRequestController = new StopRequestController(sessionPort, runtimeEventService,
                runtimeEventPublishPort);
    }

    public void enqueue(Message inbound) {
        Objects.requireNonNull(inbound, "inbound");
        Long activitySequence = activityTracker.recordIfRealUserActivity(inbound);
        if (delayedSessionActionService != null && activitySequence != null) {
            try {
                delayedSessionActionService.cancelOnUserActivity(inbound);
            } catch (RuntimeException e) { // NOSONAR - inbound delivery must remain available
                log.warn("[SessionRunCoordinator] Failed to cancel delayed actions on user activity: {}",
                        e.getMessage());
            }
        }
        SessionKey key = new SessionKey(inbound.getChannelType(), inbound.getChatId());
        SessionRunner runner = runners.computeIfAbsent(key, SessionRunner::new);
        runner.enqueue(inbound, activitySequence);
    }

    public boolean enqueueInternalContinuationIfNoNewerRealUserActivity(
            Message inbound,
            long baselineRealUserActivitySequence) {
        Objects.requireNonNull(inbound, "inbound");
        SessionKey key = new SessionKey(inbound.getChannelType(), inbound.getChatId());
        SessionRunner runner = runners.computeIfAbsent(key, SessionRunner::new);
        return runner.enqueueInternalContinuationIfNoNewerRealUserActivity(
                inbound, baselineRealUserActivitySequence);
    }

    /**
     * Submit a message through the same per-session queue used by external inbound
     * traffic and return a future that completes when that specific turn is
     * processed or discarded.
     */
    public CompletableFuture<Void> submit(Message inbound) {
        return submit(inbound, null);
    }

    public CompletableFuture<Void> submit(Message inbound, Runnable onStart) {
        Objects.requireNonNull(inbound, "inbound");

        CompletableFuture<Void> completion = new CompletableFuture<>();
        pendingCompletions.registerCompletion(inbound, completion);
        pendingCompletions.registerStartCallback(inbound, onStart);
        enqueue(inbound);
        return completion;
    }

    public CompletableFuture<TurnRunResult> submitForResult(Message inbound) {
        Objects.requireNonNull(inbound, "inbound");

        CompletableFuture<TurnRunResult> completion = new CompletableFuture<>();
        pendingCompletions.registerResultCompletion(inbound, completion);
        enqueue(inbound);
        return completion;
    }

    public void requestStop(String channelType, String chatId) {
        clearDelayedActionsForStop(channelType, chatId);
        requestStop(channelType, chatId, null, null);
    }

    public void requestStop(String channelType, String chatId, String expectedRunId, String expectedCommandId) {
        SessionKey key = new SessionKey(channelType, chatId);
        SessionRunner runner = runners.get(key);
        if (runner != null) {
            runner.requestStop(expectedRunId, expectedCommandId);
            return;
        }

        if (isHiveTargetedStop(expectedRunId, expectedCommandId)) {
            log.info(
                    "[Stop] targeted hive stop ignored without active runner: channel={}, chatId={}, runId={}, commandId={}",
                    channelType, chatId, expectedRunId, expectedCommandId);
            return;
        }
        stopRequestController.markInterruptRequested(key);
        stopRequestController.publishStopRequestedEvent(key);
        log.info("[Stop] stop requested while no active runner: channel={}, chatId={}", channelType, chatId);
    }

    private void clearDelayedActionsForStop(String channelType, String chatId) {
        if (delayedSessionActionService == null) {
            return;
        }
        try {
            int cleared = delayedSessionActionService.clearActiveActions(channelType, chatId);
            if (cleared > 0) {
                log.info("[Stop] cleared {} delayed actions for channel={}, chatId={}", cleared, channelType, chatId);
            }
        } catch (RuntimeException e) { // NOSONAR - stop must remain available even if cleanup fails
            log.warn("[Stop] failed to clear delayed actions: {}", e.getMessage());
        }
    }

    int runnerCount() {
        return runners.size();
    }

    public boolean hasActiveOrQueuedWork() {
        for (SessionRunner runner : runners.values()) {
            if (runner.hasActiveOrQueuedWork()) {
                return true;
            }
        }
        return false;
    }

    private final class SessionRunner {

        private final SessionKey key;
        private final Object lock = new Object();
        private final Deque<Message> queuedSteeringMessages = new ArrayDeque<>();
        private final Deque<Message> queuedFollowUpMessages = new ArrayDeque<>();
        private final Deque<Message> queuedInternalContinuationMessages = new ArrayDeque<>();

        private long latestRealUserActivitySequence = 0L;
        private boolean pausedAfterStop = false;
        private Optional<Future<?>> runningTask = Optional.empty();
        private Optional<Message> runningInbound = Optional.empty();

        private SessionRunner(SessionKey key) {
            this.key = key;
        }

        void enqueue(Message inbound, Long activitySequence) {
            Objects.requireNonNull(inbound, "inbound");

            String queueKind = resolveQueueKind(inbound);
            synchronized (lock) {
                if (activitySequence != null) {
                    recordRealUserActivityLocked(inbound, activitySequence.longValue());
                }
                if (shouldDropStaleInternalContinuationLocked(inbound, queueKind)) {
                    return;
                }
                if (pausedAfterStop) {
                    if (inbound.isInternalMessage()) {
                        // Internal traffic (resilience retries, follow-through nudges, delayed
                        // wake-ups) must not override a user-issued /stop. Drop silently and
                        // keep the pause intact.
                        pendingCompletions.reject(inbound, "Dropped internal inbound after stop");
                        log.debug(
                                "[SessionRunCoordinator] dropped internal inbound during paused-after-stop: channel={}, chatId={}",
                                key.channelType(), key.chatId());
                        return;
                    }
                    resumeWithInbound(inbound);
                    return;
                }

                if (isRunning()) {
                    if (ContextAttributes.TURN_QUEUE_KIND_STEERING.equals(queueKind)) {
                        enqueueSteering(inbound);
                    } else {
                        enqueueFollowUp(inbound);
                    }
                    return;
                }
            }

            markQueueKind(inbound, queueKind);
            startRun(inbound, new ArrayDeque<>());
        }

        boolean enqueueInternalContinuationIfNoNewerRealUserActivity(
                Message inbound,
                long baselineRealUserActivitySequence) {
            Objects.requireNonNull(inbound, "inbound");

            long normalizedBaseline = Math.max(0L, baselineRealUserActivitySequence);
            synchronized (lock) {
                if (latestRealUserActivitySequence > normalizedBaseline) {
                    log.debug(
                            "[SessionRunCoordinator] dropped internal continuation due to newer user activity: channel={}, chatId={}",
                            key.channelType(), key.chatId());
                    emitInternalContinuationMetric(inbound,
                            "follow_through.nudge.canceled_user_activity",
                            Map.of(
                                    "reason", "newer_real_user_activity",
                                    "baseline_sequence", normalizedBaseline,
                                    "latest_sequence", latestRealUserActivitySequence));
                    return false;
                }
                attachRealUserActivitySequence(inbound,
                        normalizedBaseline > 0L ? normalizedBaseline : latestRealUserActivitySequence);
                if (pausedAfterStop) {
                    pendingCompletions.reject(inbound, "Dropped internal inbound after stop");
                    log.debug(
                            "[SessionRunCoordinator] dropped internal continuation during paused-after-stop: channel={}, chatId={}",
                            key.channelType(), key.chatId());
                    emitInternalContinuationMetric(inbound,
                            "follow_through.nudge.canceled_user_activity",
                            Map.of(
                                    "reason", "paused_after_stop",
                                    "baseline_sequence", normalizedBaseline,
                                    "latest_sequence", latestRealUserActivitySequence));
                    return false;
                }
                if (isRunning()
                        || !queuedSteeringMessages.isEmpty()
                        || !queuedFollowUpMessages.isEmpty()
                        || !queuedInternalContinuationMessages.isEmpty()) {
                    enqueueInternalContinuation(inbound);
                    return true;
                }
            }

            markQueueKind(inbound, resolveQueueKind(inbound));
            startRun(inbound, new ArrayDeque<>());
            return true;
        }

        void requestStop(String expectedRunId, String expectedCommandId) {
            Future<?> taskToCancel;
            boolean shouldPause;
            Message cancelledQueuedMessage = null;
            boolean targetedHiveStop = isHiveTargetedStop(expectedRunId, expectedCommandId);
            synchronized (lock) {
                if (targetedHiveStop) {
                    cancelledQueuedMessage = removeQueuedHiveMessage(expectedRunId, expectedCommandId);
                    if (cancelledQueuedMessage == null
                            && !matchesHiveTarget(runningInbound.orElse(null), expectedRunId, expectedCommandId)) {
                        log.info(
                                "[Stop] targeted hive stop ignored for non-matching runner: channel={}, chatId={}, runId={}, commandId={}",
                                key.channelType(), key.chatId(), expectedRunId, expectedCommandId);
                        return;
                    }
                }
                shouldPause = !targetedHiveStop
                        && (isRunning()
                                || !queuedSteeringMessages.isEmpty()
                                || !queuedFollowUpMessages.isEmpty()
                                || !queuedInternalContinuationMessages.isEmpty());
                pausedAfterStop = shouldPause;
                taskToCancel = runningTask.orElse(null);
            }

            if (cancelledQueuedMessage != null) {
                pendingCompletions.reject(cancelledQueuedMessage,
                        HiveRuntimeContracts.CANCELLED_BY_CONTROL_COMMAND_MESSAGE);
                stopRequestController.publishHiveInterruptedFallback(cancelledQueuedMessage);
                log.info("[Stop] removed queued hive command: channel={}, chatId={}, runId={}, commandId={}",
                        key.channelType(), key.chatId(), expectedRunId, expectedCommandId);
                if (!shouldPause) {
                    evictIfIdle();
                }
                return;
            }

            stopRequestController.markInterruptRequested(key);
            stopRequestController.publishStopRequestedEvent(key);

            if (taskToCancel != null) {
                boolean cancelled = taskToCancel.cancel(true);
                log.info("[Stop] cancel requested (cancelled={})", cancelled);
            } else {
                log.info("[Stop] stop requested while idle");
            }

            if (!shouldPause) {
                evictIfIdle();
            }
        }

        private void resumeWithInbound(Message inbound) {
            Deque<Message> prefix;
            synchronized (lock) {
                pausedAfterStop = false;
                prefix = drainQueuedPrefixLocked();
            }
            markQueueKind(inbound, ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP);
            startRun(inbound, prefix);
        }

        private Deque<Message> drainQueuedPrefixLocked() {
            List<Message> queued = new ArrayList<>();
            while (!queuedFollowUpMessages.isEmpty()) {
                Message followUp = queuedFollowUpMessages.removeFirst();
                pendingCompletions.reject(followUp, SKIPPED_AFTER_STOP_REQUEST);
                if (followUp.isInternalMessage()) {
                    continue;
                }
                markQueueKind(followUp, ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP);
                queued.add(followUp);
            }
            while (!queuedSteeringMessages.isEmpty()) {
                Message steering = queuedSteeringMessages.removeFirst();
                pendingCompletions.reject(steering, SKIPPED_AFTER_STOP_REQUEST);
                markQueueKind(steering, ContextAttributes.TURN_QUEUE_KIND_STEERING);
                queued.add(steering);
            }
            while (!queuedInternalContinuationMessages.isEmpty()) {
                Message internalContinuation = queuedInternalContinuationMessages.removeFirst();
                pendingCompletions.reject(internalContinuation, SKIPPED_AFTER_STOP_REQUEST);
            }

            queued.sort(Comparator.comparing(this::resolveTimestamp));
            return new ArrayDeque<>(queued);
        }

        private Instant resolveTimestamp(Message message) {
            return message != null && message.getTimestamp() != null ? message.getTimestamp() : Instant.EPOCH;
        }

        private boolean isRunning() {
            return runningTask.map(task -> !task.isDone()).orElse(false);
        }

        private boolean hasActiveOrQueuedWork() {
            synchronized (lock) {
                return isRunning()
                        || !queuedSteeringMessages.isEmpty()
                        || !queuedFollowUpMessages.isEmpty()
                        || !queuedInternalContinuationMessages.isEmpty()
                        || pausedAfterStop;
            }
        }

        private void startRun(Message inbound, Deque<Message> prefix) {
            SessionKey runKey = new SessionKey(inbound.getChannelType(), inbound.getChatId());
            FutureTask<Void> task = new FutureTask<>(() -> {
                try {
                    stopRequestController.clearInterruptRequested(runKey);
                    if (!prefix.isEmpty()) {
                        queuedMessageFlushService.flush(runKey.channelType(), runKey.chatId(), prefix);
                    }
                    pendingCompletions.runStartCallbacks(inbound);
                    AgentContext context = agentLoop.processMessage(inbound);
                    pendingCompletions.complete(inbound);
                    pendingCompletions.completeResult(inbound, turnRunResultMapper.map(inbound, context));
                } catch (Exception e) { // NOSONAR - must not kill executor thread
                    handleRunFailure(inbound, e);
                    pendingCompletions.fail(inbound, e);
                } finally {
                    onRunComplete();
                }
            }, null);
            synchronized (lock) {
                runningInbound = Optional.of(inbound);
                runningTask = Optional.of(task);
            }
            try {
                sessionRunExecutor.execute(task);
            } catch (RuntimeException e) {
                synchronized (lock) {
                    if (runningTask.filter(task::equals).isPresent()) {
                        runningTask = Optional.empty();
                        runningInbound = Optional.empty();
                    }
                }
                handleRunFailure(inbound, e);
                pendingCompletions.fail(inbound, e);
                evictIfIdle();
            }
        }

        private void handleRunFailure(Message inbound, Exception e) {
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                boolean stopRequested = stopRequestController.isInterruptRequested(key);
                stopRequestController.clearInterruptRequested(key);
                if (stopRequested) {
                    stopRequestController.publishHiveInterruptedFallback(inbound);
                }
                log.info("[SessionRunCoordinator] run interrupted: channel={}, chatId={}",
                        inbound.getChannelType(), inbound.getChatId());
                if (!stopRequested) {
                    annotateRunFailure(inbound, new FailureEvent(
                            FailureSource.SYSTEM,
                            "SessionRunCoordinator",
                            FailureKind.TIMEOUT,
                            "Run interrupted",
                            Instant.now()));
                }
                return;
            }

            annotateRunFailure(inbound, new FailureEvent(
                    FailureSource.SYSTEM,
                    "SessionRunCoordinator",
                    FailureKind.EXCEPTION,
                    e.getMessage(),
                    Instant.now()));
            log.error("[SessionRunCoordinator] run failed: channel={}, chatId={}: {}",
                    inbound.getChannelType(), inbound.getChatId(), e.getMessage(), e);
        }

        private void onRunComplete() {
            Message next;
            synchronized (lock) {
                runningTask = Optional.empty();
                runningInbound = Optional.empty();
                if (pausedAfterStop) {
                    return;
                }
                next = dequeueNextLocked();
            }
            if (next != null) {
                startRun(next, new ArrayDeque<>());
                return;
            }
            evictIfIdle();
        }

        private Message dequeueNextLocked() {
            if (!queuedSteeringMessages.isEmpty()) {
                if (isQueueModeAll(runtimeConfigService.getTurnQueueSteeringMode())) {
                    Message mergedSteering = mergeQueuedMessages(queuedSteeringMessages,
                            ContextAttributes.TURN_QUEUE_KIND_STEERING);
                    markQueueKind(mergedSteering, ContextAttributes.TURN_QUEUE_KIND_STEERING);
                    return mergedSteering;
                }
                Message nextSteering = queuedSteeringMessages.removeFirst();
                markQueueKind(nextSteering, ContextAttributes.TURN_QUEUE_KIND_STEERING);
                return nextSteering;
            }
            Message internalRetry = dequeueInternalRetryLocked();
            if (internalRetry != null) {
                markQueueKind(internalRetry, resolveQueueKind(internalRetry));
                return internalRetry;
            }
            if (!queuedFollowUpMessages.isEmpty()) {
                if (isQueueModeAll(runtimeConfigService.getTurnQueueFollowUpMode())) {
                    Message mergedFollowUp = mergeQueuedFollowUpMessagesLocked();
                    markQueueKind(mergedFollowUp, resolveQueueKind(mergedFollowUp));
                    return mergedFollowUp;
                }
                Message nextFollowUp = queuedFollowUpMessages.removeFirst();
                markQueueKind(nextFollowUp, resolveQueueKind(nextFollowUp));
                return nextFollowUp;
            }
            if (!queuedInternalContinuationMessages.isEmpty()) {
                Message nextInternalContinuation = queuedInternalContinuationMessages.removeFirst();
                markQueueKind(nextInternalContinuation, resolveQueueKind(nextInternalContinuation));
                return nextInternalContinuation;
            }
            return null;
        }

        private boolean shouldDropStaleInternalContinuationLocked(Message inbound, String queueKind) {
            if (!isInternalContinuationKind(queueKind)) {
                return false;
            }
            long baselineRealUserActivitySequence = resolveRealUserActivitySequence(inbound);
            if (latestRealUserActivitySequence > baselineRealUserActivitySequence) {
                pendingCompletions.reject(inbound,
                        "Dropped stale internal continuation after newer real user activity");
                log.debug(
                        "[SessionRunCoordinator] dropped stale internal continuation: channel={}, chatId={}",
                        key.channelType(), key.chatId());
                emitInternalContinuationMetric(inbound,
                        "follow_through.nudge.canceled_user_activity",
                        Map.of(
                                "reason", "stale_internal_continuation",
                                "baseline_sequence", baselineRealUserActivitySequence,
                                "latest_sequence", latestRealUserActivitySequence));
                return true;
            }
            attachRealUserActivitySequence(inbound, baselineRealUserActivitySequence > 0L
                    ? baselineRealUserActivitySequence
                    : latestRealUserActivitySequence);
            return false;
        }

        private long resolveRealUserActivitySequence(Message message) {
            if (message == null || message.getMetadata() == null) {
                return 0L;
            }
            Object raw = message.getMetadata().get(ContextAttributes.MESSAGE_REAL_USER_ACTIVITY_SEQUENCE);
            if (raw instanceof Number number) {
                return Math.max(0L, number.longValue());
            }
            return 0L;
        }

        private void enqueueSteering(Message inbound) {
            if (isQueueModeAll(runtimeConfigService.getTurnQueueSteeringMode())) {
                enqueueWithBound(queuedSteeringMessages, inbound, "steering");
                return;
            }

            while (!queuedSteeringMessages.isEmpty()) {
                Message replaced = queuedSteeringMessages.removeFirst();
                pendingCompletions.reject(replaced, "Replaced by a newer steering message");
            }
            queuedSteeringMessages.addLast(inbound);
            log.debug("[SessionRunCoordinator] steering queue mode one-at-a-time: replaced pending steering messages");
        }

        private void enqueueFollowUp(Message inbound) {
            String queueKind = resolveQueueKind(inbound);
            if (isInternalRetryKind(queueKind)) {
                java.util.Iterator<Message> iterator = queuedFollowUpMessages.iterator();
                while (iterator.hasNext()) {
                    Message queued = iterator.next();
                    if (!isInternalRetryKind(resolveQueueKind(queued))) {
                        continue;
                    }
                    iterator.remove();
                    pendingCompletions.reject(queued, "Replaced by a newer internal retry message");
                }
                enqueueWithBound(queuedFollowUpMessages, inbound, "internal-retry");
                return;
            }
            if (isInternalContinuationKind(queueKind)) {
                enqueueInternalContinuation(inbound);
                return;
            }
            if (isDelayedActionQueueKind(queueKind)) {
                enqueueFollowUpWithBound(inbound, "delayed-action");
                return;
            }

            if (isQueueModeAll(runtimeConfigService.getTurnQueueFollowUpMode())) {
                enqueueFollowUpWithBound(inbound, "follow-up");
                return;
            }

            Message replaced = removeOldestRegularFollowUpLocked();
            if (replaced != null) {
                pendingCompletions.reject(replaced, "Replaced by a newer follow-up message");
                log.debug(
                        "[SessionRunCoordinator] follow-up queue mode one-at-a-time: replaced pending follow-up message");
            }
            enqueueFollowUpWithBound(inbound, "follow-up");
        }

        private void enqueueInternalContinuation(Message inbound) {
            while (!queuedInternalContinuationMessages.isEmpty()) {
                Message replaced = queuedInternalContinuationMessages.removeFirst();
                pendingCompletions.reject(replaced, "Replaced by a newer internal continuation message");
            }
            queuedInternalContinuationMessages.addLast(inbound);
        }

        private void recordRealUserActivityLocked(Message inbound, long activitySequence) {
            latestRealUserActivitySequence = Math.max(latestRealUserActivitySequence, activitySequence);
            attachRealUserActivitySequence(inbound, latestRealUserActivitySequence);
            dropQueuedInternalContinuationsLocked("Cancelled by newer real user activity");
        }

        private void dropQueuedInternalContinuationsLocked(String reason) {
            while (!queuedInternalContinuationMessages.isEmpty()) {
                Message queued = queuedInternalContinuationMessages.removeFirst();
                pendingCompletions.reject(queued, reason);
                emitInternalContinuationMetric(queued,
                        "follow_through.nudge.canceled_user_activity",
                        Map.of(
                                "reason", "queued_internal_continuation_cleared",
                                "latest_sequence", latestRealUserActivitySequence));
            }
        }

        private void emitInternalContinuationMetric(Message inbound, String metricName,
                Map<String, Object> attributes) {
            if (metricName == null || metricName.isBlank() || inbound == null) {
                return;
            }
            Map<String, Object> metricAttributes = new LinkedHashMap<>();
            if (attributes != null) {
                metricAttributes.putAll(attributes);
            }
            String queueKind = resolveQueueKind(inbound);
            metricAttributes.put("queue_kind", queueKind);
            metricAttributes.put("internal_kind",
                    readMetadataString(inbound.getMetadata(), ContextAttributes.MESSAGE_INTERNAL_KIND));
            ResilienceObservabilitySupport.emitMessageMetric(log, metricName, inbound, metricAttributes);
        }

        private void attachRealUserActivitySequence(Message message, long activitySequence) {
            if (message == null) {
                return;
            }
            Map<String, Object> metadata = message.getMetadata() != null
                    ? new LinkedHashMap<>(message.getMetadata())
                    : new LinkedHashMap<>();
            metadata.put(ContextAttributes.MESSAGE_REAL_USER_ACTIVITY_SEQUENCE, activitySequence);
            message.setMetadata(metadata);
        }

        private void enqueueFollowUpWithBound(Message inbound, String queueLabel) {
            if (queuedFollowUpMessages.size() >= MAX_QUEUED_MESSAGES_PER_SESSION) {
                Message dropped = removeOldestRegularFollowUpLocked();
                if (dropped == null) {
                    dropped = queuedFollowUpMessages.removeFirst();
                }
                pendingCompletions.reject(dropped,
                        "Dropped oldest pending " + queueLabel + " message due to queue limit");
                log.warn(
                        "[SessionRunCoordinator] {} queue limit reached ({}), dropped oldest message: channel={}, chatId={}",
                        queueLabel,
                        MAX_QUEUED_MESSAGES_PER_SESSION,
                        key.channelType(),
                        key.chatId());
            }
            queuedFollowUpMessages.addLast(inbound);
        }

        @SuppressWarnings("PMD.AvoidBranchingStatementAsLastInLoop")
        private Message removeOldestRegularFollowUpLocked() {
            java.util.Iterator<Message> iterator = queuedFollowUpMessages.iterator();
            while (iterator.hasNext()) {
                Message queued = iterator.next();
                String queueKind = resolveQueueKind(queued);
                if (isInternalRetryKind(queueKind)) {
                    continue;
                }
                if (isDelayedActionQueueKind(queueKind)) {
                    continue;
                }
                iterator.remove();
                return queued;
            }
            return null;
        }

        private void enqueueWithBound(Deque<Message> queue, Message inbound, String queueLabel) {
            if (queue.size() >= MAX_QUEUED_MESSAGES_PER_SESSION) {
                Message dropped = queue.removeFirst();
                pendingCompletions.reject(dropped,
                        "Dropped oldest pending " + queueLabel + " message due to queue limit");
                log.warn(
                        "[SessionRunCoordinator] {} queue limit reached ({}), dropped oldest message: channel={}, chatId={}",
                        queueLabel,
                        MAX_QUEUED_MESSAGES_PER_SESSION,
                        key.channelType(),
                        key.chatId());
            }
            queue.addLast(inbound);
        }

        private Message dequeueInternalRetryLocked() {
            Message internalRetry = null;
            for (Message queuedMessage : queuedFollowUpMessages) {
                if (isInternalRetryKind(resolveQueueKind(queuedMessage))) {
                    internalRetry = queuedMessage;
                    break;
                }
            }
            if (internalRetry != null) {
                queuedFollowUpMessages.remove(internalRetry);
            }
            return internalRetry;
        }

        private Message mergeQueuedFollowUpMessagesLocked() {
            Message first = queuedFollowUpMessages.removeFirst();
            String firstQueueKind = resolveQueueKind(first);
            if (isDelayedActionQueueKind(firstQueueKind)) {
                markQueueKind(first, firstQueueKind);
                return first;
            }
            StringBuilder builder = new StringBuilder();
            appendMergedChunk(builder, first.getContent());
            Message merged = Message.builder()
                    .id(first.getId())
                    .role(first.getRole())
                    .content(builder.toString())
                    .channelType(first.getChannelType())
                    .chatId(first.getChatId())
                    .senderId(first.getSenderId())
                    .timestamp(first.getTimestamp())
                    .metadata(first.getMetadata() != null ? new LinkedHashMap<>(first.getMetadata()) : null)
                    .build();
            pendingCompletions.transfer(first, merged);
            while (!queuedFollowUpMessages.isEmpty()) {
                Message next = queuedFollowUpMessages.peekFirst();
                if (next == null) {
                    break;
                }
                String nextQueueKind = resolveQueueKind(next);
                if (isInternalRetryKind(nextQueueKind) || isDelayedActionQueueKind(nextQueueKind)) {
                    break;
                }
                next = queuedFollowUpMessages.removeFirst();
                appendMergedChunk(builder, next.getContent());
                pendingCompletions.transfer(next, merged);
            }
            merged.setContent(builder.toString());
            markQueueKind(merged, ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP);
            return merged;
        }

        private Message mergeQueuedMessages(Deque<Message> queue, String queueKind) {
            Message first = queue.removeFirst();
            StringBuilder builder = new StringBuilder();
            appendMergedChunk(builder, first.getContent());
            Message merged = Message.builder()
                    .id(first.getId())
                    .role(first.getRole())
                    .content(builder.toString())
                    .channelType(first.getChannelType())
                    .chatId(first.getChatId())
                    .senderId(first.getSenderId())
                    .timestamp(first.getTimestamp())
                    .metadata(first.getMetadata() != null ? new LinkedHashMap<>(first.getMetadata()) : null)
                    .build();
            pendingCompletions.transfer(first, merged);
            while (!queue.isEmpty()) {
                Message next = queue.removeFirst();
                appendMergedChunk(builder, next.getContent());
                pendingCompletions.transfer(next, merged);
            }
            merged.setContent(builder.toString());
            markQueueKind(merged, queueKind);
            return merged;
        }

        private void appendMergedChunk(StringBuilder builder, String chunk) {
            if (chunk == null || chunk.isBlank()) {
                return;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(chunk.trim());
        }

        private void markQueueKind(Message message, String queueKind) {
            if (message == null) {
                return;
            }
            Map<String, Object> metadata = message.getMetadata() != null
                    ? new LinkedHashMap<>(message.getMetadata())
                    : new LinkedHashMap<>();
            metadata.put(ContextAttributes.TURN_QUEUE_KIND, queueKind);
            message.setMetadata(metadata);
        }

        private boolean isQueueModeAll(String mode) {
            return "all".equals(mode);
        }

        private void evictIfIdle() {
            if (isEvictable()) {
                boolean removed = runners.remove(key, this);
                if (removed) {
                    log.debug("[SessionRunCoordinator] evicted idle runner: channel={}, chatId={}",
                            key.channelType(), key.chatId());
                }
            }
        }

        private boolean isEvictable() {
            synchronized (lock) {
                return !pausedAfterStop && !isRunning() && queuedSteeringMessages.isEmpty()
                        && queuedFollowUpMessages.isEmpty()
                        && queuedInternalContinuationMessages.isEmpty()
                        && runningInbound.isEmpty();
            }
        }

        private Message removeQueuedHiveMessage(String expectedRunId, String expectedCommandId) {
            Message removed = removeQueuedHiveMessage(queuedSteeringMessages, expectedRunId, expectedCommandId);
            if (removed != null) {
                return removed;
            }
            return removeQueuedHiveMessage(queuedFollowUpMessages, expectedRunId, expectedCommandId);
        }

        @SuppressWarnings("PMD.AvoidBranchingStatementAsLastInLoop")
        private Message removeQueuedHiveMessage(
                Deque<Message> queue,
                String expectedRunId,
                String expectedCommandId) {
            java.util.Iterator<Message> iterator = queue.iterator();
            while (iterator.hasNext()) {
                Message queuedMessage = iterator.next();
                if (!matchesHiveTarget(queuedMessage, expectedRunId, expectedCommandId)) {
                    continue;
                }
                iterator.remove();
                return queuedMessage;
            }
            return null;
        }

    }

    private String resolveQueueKind(Message inbound) {
        if (inbound == null || inbound.getMetadata() == null) {
            return ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP;
        }

        Object explicit = inbound.getMetadata().get(ContextAttributes.TURN_QUEUE_KIND);
        if (explicit instanceof String explicitKind) {
            String normalized = explicitKind.trim().toLowerCase(Locale.ROOT);
            if (ContextAttributes.TURN_QUEUE_KIND_STEERING.equals(normalized)) {
                return ContextAttributes.TURN_QUEUE_KIND_STEERING;
            }
            if (ContextAttributes.TURN_QUEUE_KIND_INTERNAL_MODEL_RETRY.equals(normalized)) {
                return ContextAttributes.TURN_QUEUE_KIND_INTERNAL_MODEL_RETRY;
            }
            if (ContextAttributes.TURN_QUEUE_KIND_INTERNAL_RETRY.equals(normalized)) {
                return ContextAttributes.TURN_QUEUE_KIND_INTERNAL_RETRY;
            }
            if (ContextAttributes.TURN_QUEUE_KIND_INTERNAL_DELAYED_ACTION.equals(normalized)) {
                return ContextAttributes.TURN_QUEUE_KIND_INTERNAL_DELAYED_ACTION;
            }
            if (ContextAttributes.TURN_QUEUE_KIND_DELAYED_ACTION.equals(normalized)) {
                return ContextAttributes.TURN_QUEUE_KIND_DELAYED_ACTION;
            }
            if (ContextAttributes.TURN_QUEUE_KIND_INTERNAL_FOLLOW_THROUGH.equals(normalized)) {
                return ContextAttributes.TURN_QUEUE_KIND_INTERNAL_FOLLOW_THROUGH;
            }
            if (ContextAttributes.TURN_QUEUE_KIND_INTERNAL_AUTO_PROCEED.equals(normalized)) {
                return ContextAttributes.TURN_QUEUE_KIND_INTERNAL_AUTO_PROCEED;
            }
            if (ContextAttributes.TURN_QUEUE_KIND_INTERNAL_CONTINUATION.equals(normalized)) {
                return ContextAttributes.TURN_QUEUE_KIND_INTERNAL_CONTINUATION;
            }
            if (ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP.equals(normalized)) {
                return ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP;
            }
        }

        if (!runtimeConfigService.isTurnQueueSteeringEnabled()) {
            return ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP;
        }
        return ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP;
    }

    private boolean isInternalRetryKind(String queueKind) {
        return ContextAttributes.TURN_QUEUE_KIND_INTERNAL_MODEL_RETRY.equals(queueKind)
                || ContextAttributes.TURN_QUEUE_KIND_INTERNAL_RETRY.equals(queueKind);
    }

    private boolean isDelayedActionQueueKind(String queueKind) {
        return ContextAttributes.TURN_QUEUE_KIND_INTERNAL_DELAYED_ACTION.equals(queueKind)
                || ContextAttributes.TURN_QUEUE_KIND_DELAYED_ACTION.equals(queueKind);
    }

    private boolean isInternalContinuationKind(String queueKind) {
        return ContextAttributes.TURN_QUEUE_KIND_INTERNAL_FOLLOW_THROUGH.equals(queueKind)
                || ContextAttributes.TURN_QUEUE_KIND_INTERNAL_AUTO_PROCEED.equals(queueKind)
                || ContextAttributes.TURN_QUEUE_KIND_INTERNAL_CONTINUATION.equals(queueKind);
    }

    private String readMetadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof String stringValue) {
            String normalized = stringValue.trim();
            return normalized.isEmpty() ? null : normalized;
        }
        return null;
    }

    private void annotateRunFailure(Message inbound, FailureEvent failureEvent) {
        if (inbound == null || failureEvent == null) {
            return;
        }
        Map<String, Object> metadata = inbound.getMetadata();
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
            inbound.setMetadata(metadata);
        }
        metadata.put(ContextAttributes.AUTO_RUN_STATUS, "FAILED");
        metadata.put(ContextAttributes.AUTO_RUN_FINISH_REASON, "ERROR");
        if (failureEvent.message() != null && !failureEvent.message().isBlank()) {
            metadata.put(ContextAttributes.AUTO_RUN_FAILURE_SUMMARY, failureEvent.message());
            metadata.put(ContextAttributes.AUTO_RUN_FAILURE_FINGERPRINT,
                    failureEvent.message().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
        }
    }

    private boolean isHiveTargetedStop(String expectedRunId, String expectedCommandId) {
        return !isBlank(expectedRunId) || !isBlank(expectedCommandId);
    }

    private boolean matchesHiveTarget(Message message, String expectedRunId, String expectedCommandId) {
        if (message == null || message.getMetadata() == null || !isHiveTargetedStop(expectedRunId, expectedCommandId)) {
            return false;
        }
        String messageRunId = readMetadataString(message.getMetadata(), ContextAttributes.HIVE_RUN_ID);
        String messageCommandId = readMetadataString(message.getMetadata(), ContextAttributes.HIVE_COMMAND_ID);
        boolean runMatches = isBlank(expectedRunId) || expectedRunId.equals(messageRunId);
        boolean commandMatches = isBlank(expectedCommandId) || expectedCommandId.equals(messageCommandId);
        return runMatches && commandMatches;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
