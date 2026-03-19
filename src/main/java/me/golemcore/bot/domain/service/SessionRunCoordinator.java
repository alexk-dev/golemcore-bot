package me.golemcore.bot.domain.service;

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

import me.golemcore.bot.adapter.outbound.hive.HiveEventBatchPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FailureEvent;
import me.golemcore.bot.domain.model.FailureKind;
import me.golemcore.bot.domain.model.FailureSource;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeEvent;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.port.outbound.SessionPort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
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
@RequiredArgsConstructor
@Slf4j
public class SessionRunCoordinator {

    private static final int MAX_QUEUED_MESSAGES_PER_SESSION = 100;

    private final SessionPort sessionPort;
    private final AgentLoop agentLoop;
    private final ExecutorService sessionRunExecutor;
    private final RuntimeEventService runtimeEventService;
    private final RuntimeConfigService runtimeConfigService;
    private final HiveEventBatchPublisher hiveEventBatchPublisher;

    private final Map<SessionKey, SessionRunner> runners = new ConcurrentHashMap<>();
    private final Map<Message, List<CompletableFuture<Void>>> pendingCompletions = Collections
            .synchronizedMap(new IdentityHashMap<>());

    public void enqueue(Message inbound) {
        SessionKey key = new SessionKey(inbound.getChannelType(), inbound.getChatId());
        SessionRunner runner = runners.computeIfAbsent(key, SessionRunner::new);
        runner.enqueue(inbound);
    }

    /**
     * Submit a message through the same per-session queue used by external inbound
     * traffic and return a future that completes when that specific turn is
     * processed or discarded.
     */
    public CompletableFuture<Void> submit(Message inbound) {
        Objects.requireNonNull(inbound, "inbound");

        CompletableFuture<Void> completion = new CompletableFuture<>();
        registerPendingCompletion(inbound, completion);
        enqueue(inbound);
        return completion;
    }

    public void requestStop(String channelType, String chatId) {
        SessionKey key = new SessionKey(channelType, chatId);
        SessionRunner runner = runners.get(key);
        if (runner != null) {
            runner.requestStop();
            return;
        }

        markInterruptRequested(key);
        publishStopRequestedEvent(key);
        log.info("[Stop] stop requested while no active runner: channel={}, chatId={}", channelType, chatId);
    }

    int runnerCount() {
        return runners.size();
    }

    private final class SessionRunner {

        private final SessionKey key;
        private final Object lock = new Object();
        private final Deque<Message> queuedSteeringMessages = new ArrayDeque<>();
        private final Deque<Message> queuedFollowUpMessages = new ArrayDeque<>();

        private boolean pausedAfterStop = false;
        private Optional<Future<?>> runningTask = Optional.empty();

        private SessionRunner(SessionKey key) {
            this.key = key;
        }

        void enqueue(Message inbound) {
            Objects.requireNonNull(inbound, "inbound");

            String queueKind = resolveQueueKind(inbound);
            synchronized (lock) {
                if (pausedAfterStop) {
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

        void requestStop() {
            Future<?> taskToCancel;
            boolean shouldPause;
            synchronized (lock) {
                shouldPause = isRunning() || !queuedSteeringMessages.isEmpty() || !queuedFollowUpMessages.isEmpty();
                pausedAfterStop = shouldPause;
                taskToCancel = runningTask.orElse(null);
            }

            markInterruptRequested(key);
            publishStopRequestedEvent(key);

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
                rejectPendingCompletion(followUp, "Skipped after stop request");
                if (followUp.isInternalMessage()) {
                    continue;
                }
                markQueueKind(followUp, ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP);
                queued.add(followUp);
            }
            while (!queuedSteeringMessages.isEmpty()) {
                Message steering = queuedSteeringMessages.removeFirst();
                rejectPendingCompletion(steering, "Skipped after stop request");
                markQueueKind(steering, ContextAttributes.TURN_QUEUE_KIND_STEERING);
                queued.add(steering);
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

        private void startRun(Message inbound, Deque<Message> prefix) {
            SessionKey runKey = new SessionKey(inbound.getChannelType(), inbound.getChatId());
            runningTask = Optional.of(sessionRunExecutor.submit(() -> {
                try {
                    clearInterruptRequested(runKey);
                    if (!prefix.isEmpty()) {
                        flushQueuedMessages(runKey, prefix);
                    }
                    agentLoop.processMessage(inbound);
                    completePendingCompletion(inbound);
                } catch (Exception e) { // NOSONAR - must not kill executor thread
                    handleRunFailure(inbound, e);
                    failPendingCompletion(inbound, e);
                } finally {
                    onRunComplete();
                }
            }));
        }

        private void handleRunFailure(Message inbound, Exception e) {
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                boolean stopRequested = isInterruptRequested(key);
                clearInterruptRequested(key);
                if (stopRequested) {
                    publishHiveInterruptedFallback(inbound);
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
                markQueueKind(internalRetry, ContextAttributes.TURN_QUEUE_KIND_INTERNAL_RETRY);
                return internalRetry;
            }
            if (!queuedFollowUpMessages.isEmpty()) {
                if (isQueueModeAll(runtimeConfigService.getTurnQueueFollowUpMode())) {
                    Message mergedFollowUp = mergeQueuedFollowUpMessagesLocked();
                    markQueueKind(mergedFollowUp, ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP);
                    return mergedFollowUp;
                }
                Message nextFollowUp = queuedFollowUpMessages.removeFirst();
                markQueueKind(nextFollowUp, ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP);
                return nextFollowUp;
            }
            return null;
        }

        private void enqueueSteering(Message inbound) {
            if (isQueueModeAll(runtimeConfigService.getTurnQueueSteeringMode())) {
                enqueueWithBound(queuedSteeringMessages, inbound, "steering");
                return;
            }

            while (!queuedSteeringMessages.isEmpty()) {
                Message replaced = queuedSteeringMessages.removeFirst();
                rejectPendingCompletion(replaced, "Replaced by a newer steering message");
            }
            queuedSteeringMessages.addLast(inbound);
            log.debug("[SessionRunCoordinator] steering queue mode one-at-a-time: replaced pending steering messages");
        }

        private void enqueueFollowUp(Message inbound) {
            if (ContextAttributes.TURN_QUEUE_KIND_INTERNAL_RETRY.equals(resolveQueueKind(inbound))) {
                java.util.Iterator<Message> iterator = queuedFollowUpMessages.iterator();
                while (iterator.hasNext()) {
                    Message queued = iterator.next();
                    if (!ContextAttributes.TURN_QUEUE_KIND_INTERNAL_RETRY.equals(resolveQueueKind(queued))) {
                        continue;
                    }
                    iterator.remove();
                    rejectPendingCompletion(queued, "Replaced by a newer internal retry message");
                }
                enqueueWithBound(queuedFollowUpMessages, inbound, "internal-retry");
                return;
            }

            if (isQueueModeAll(runtimeConfigService.getTurnQueueFollowUpMode())) {
                enqueueWithBound(queuedFollowUpMessages, inbound, "follow-up");
                return;
            }

            if (queuedFollowUpMessages.isEmpty()) {
                queuedFollowUpMessages.addLast(inbound);
            } else {
                Message replaced = queuedFollowUpMessages.removeFirst();
                rejectPendingCompletion(replaced, "Replaced by a newer follow-up message");
                queuedFollowUpMessages.addLast(inbound);
                log.debug(
                        "[SessionRunCoordinator] follow-up queue mode one-at-a-time: replaced pending follow-up message");
            }
        }

        private void enqueueWithBound(Deque<Message> queue, Message inbound, String queueLabel) {
            if (queue.size() >= MAX_QUEUED_MESSAGES_PER_SESSION) {
                Message dropped = queue.removeFirst();
                rejectPendingCompletion(dropped,
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
                if (ContextAttributes.TURN_QUEUE_KIND_INTERNAL_RETRY.equals(resolveQueueKind(queuedMessage))) {
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
            transferPendingCompletions(first, merged);
            while (!queuedFollowUpMessages.isEmpty()) {
                Message next = queuedFollowUpMessages.peekFirst();
                if (next == null || ContextAttributes.TURN_QUEUE_KIND_INTERNAL_RETRY.equals(resolveQueueKind(next))) {
                    break;
                }
                next = queuedFollowUpMessages.removeFirst();
                appendMergedChunk(builder, next.getContent());
                transferPendingCompletions(next, merged);
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
            transferPendingCompletions(first, merged);
            while (!queue.isEmpty()) {
                Message next = queue.removeFirst();
                appendMergedChunk(builder, next.getContent());
                transferPendingCompletions(next, merged);
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
                        && queuedFollowUpMessages.isEmpty();
            }
        }

        private void flushQueuedMessages(SessionKey runKey, Deque<Message> prefix) {
            if (prefix == null || prefix.isEmpty()) {
                return;
            }

            AgentSession session = sessionPort.getOrCreate(runKey.channelType(), runKey.chatId());
            if (session == null) {
                return;
            }

            int before = session.getMessages().size();
            for (Message message : prefix) {
                session.addMessage(message);
            }
            try {
                sessionPort.save(session);
            } catch (Exception e) { // NOSONAR - best-effort persistence
                log.error("[SessionRunCoordinator] failed to persist prefix flush: sessionId={}", session.getId(), e);
            }
            log.info("[SessionRunCoordinator] flushed {} queued messages ({} -> {})",
                    prefix.size(), before, session.getMessages().size());
        }
    }

    private void registerPendingCompletion(Message message, CompletableFuture<Void> completion) {
        synchronized (pendingCompletions) {
            pendingCompletions.computeIfAbsent(message, ignored -> new ArrayList<>()).add(completion);
        }
    }

    private void transferPendingCompletions(Message source, Message target) {
        List<CompletableFuture<Void>> completions = removePendingCompletions(source);
        if (completions.isEmpty()) {
            return;
        }

        synchronized (pendingCompletions) {
            pendingCompletions.computeIfAbsent(target, ignored -> new ArrayList<>()).addAll(completions);
        }
    }

    private void completePendingCompletion(Message message) {
        for (CompletableFuture<Void> completion : removePendingCompletions(message)) {
            completion.complete(null);
        }
    }

    private void failPendingCompletion(Message message, Throwable failure) {
        for (CompletableFuture<Void> completion : removePendingCompletions(message)) {
            completion.completeExceptionally(failure);
        }
    }

    private void rejectPendingCompletion(Message message, String reason) {
        failPendingCompletion(message, new IllegalStateException(reason));
    }

    private List<CompletableFuture<Void>> removePendingCompletions(Message message) {
        synchronized (pendingCompletions) {
            List<CompletableFuture<Void>> completions = pendingCompletions.remove(message);
            if (completions == null || completions.isEmpty()) {
                return List.of();
            }
            return new ArrayList<>(completions);
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
            if (ContextAttributes.TURN_QUEUE_KIND_INTERNAL_RETRY.equals(normalized)) {
                return ContextAttributes.TURN_QUEUE_KIND_INTERNAL_RETRY;
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

    private void markInterruptRequested(SessionKey key) {
        AgentSession session = sessionPort.getOrCreate(key.channelType(), key.chatId());
        if (session == null) {
            return;
        }

        Map<String, Object> metadata = session.getMetadata();
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
            session.setMetadata(metadata);
        }
        metadata.put(ContextAttributes.TURN_INTERRUPT_REQUESTED, true);
        sessionPort.save(session);
    }

    private void clearInterruptRequested(SessionKey key) {
        AgentSession session = sessionPort.getOrCreate(key.channelType(), key.chatId());
        if (session == null) {
            return;
        }

        Map<String, Object> metadata = session.getMetadata();
        if (metadata == null || !metadata.containsKey(ContextAttributes.TURN_INTERRUPT_REQUESTED)) {
            return;
        }
        metadata.remove(ContextAttributes.TURN_INTERRUPT_REQUESTED);
        sessionPort.save(session);
    }

    private void publishStopRequestedEvent(SessionKey key) {
        AgentSession session = sessionPort.getOrCreate(key.channelType(), key.chatId());
        if (session == null) {
            return;
        }
        runtimeEventService.emitForSession(session, RuntimeEventType.TURN_INTERRUPT_REQUESTED,
                Map.of("source", "command.stop"));
    }

    private boolean isInterruptRequested(SessionKey key) {
        AgentSession session = sessionPort.getOrCreate(key.channelType(), key.chatId());
        if (session == null) {
            return false;
        }
        Map<String, Object> metadata = session.getMetadata();
        return metadata != null && Boolean.TRUE.equals(metadata.get(ContextAttributes.TURN_INTERRUPT_REQUESTED));
    }

    private void publishHiveInterruptedFallback(Message inbound) {
        if (inbound == null || inbound.getChannelType() == null
                || !"hive".equalsIgnoreCase(inbound.getChannelType())) {
            return;
        }

        Map<String, Object> metadata = buildHiveMetadata(inbound);
        if (!metadata.containsKey(ContextAttributes.HIVE_THREAD_ID)) {
            return;
        }

        AgentSession session = sessionPort.getOrCreate(inbound.getChannelType(), inbound.getChatId());
        RuntimeEvent runtimeEvent = runtimeEventService.emitForSession(session, RuntimeEventType.TURN_FINISHED,
                Map.of("reason", "user_interrupt"));
        hiveEventBatchPublisher.publishRuntimeEvents(List.of(runtimeEvent), metadata);
    }

    private Map<String, Object> buildHiveMetadata(Message inbound) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        Map<String, Object> inboundMetadata = inbound.getMetadata();
        putHiveMetadata(metadata, ContextAttributes.HIVE_CARD_ID,
                readMetadataString(inboundMetadata, ContextAttributes.HIVE_CARD_ID));
        putHiveMetadata(metadata, ContextAttributes.HIVE_COMMAND_ID,
                readMetadataString(inboundMetadata, ContextAttributes.HIVE_COMMAND_ID));
        putHiveMetadata(metadata, ContextAttributes.HIVE_RUN_ID,
                readMetadataString(inboundMetadata, ContextAttributes.HIVE_RUN_ID));
        putHiveMetadata(metadata, ContextAttributes.HIVE_GOLEM_ID,
                readMetadataString(inboundMetadata, ContextAttributes.HIVE_GOLEM_ID));
        String threadId = readMetadataString(inboundMetadata, ContextAttributes.HIVE_THREAD_ID);
        if (threadId == null || threadId.isBlank()) {
            threadId = inbound.getChatId();
        }
        putHiveMetadata(metadata, ContextAttributes.HIVE_THREAD_ID, threadId);
        return metadata;
    }

    private void putHiveMetadata(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
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

    private record SessionKey(String channelType, String chatId) {
        private SessionKey {
            Objects.requireNonNull(channelType, "channelType");
            Objects.requireNonNull(chatId, "chatId");
        }
    }
}
