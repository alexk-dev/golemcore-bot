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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeEventType;
import me.golemcore.bot.port.outbound.SessionPort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private final Map<SessionKey, SessionRunner> runners = new ConcurrentHashMap<>();

    public void enqueue(Message inbound) {
        SessionKey key = new SessionKey(inbound.getChannelType(), inbound.getChatId());
        SessionRunner runner = runners.computeIfAbsent(key, SessionRunner::new);
        runner.enqueue(inbound);
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

    private final class SessionRunner {

        private final SessionKey key;
        private final Object lock = new Object();
        private final Deque<Message> queuedSteeringMessages = new ArrayDeque<>();
        private final Deque<Message> queuedFollowUpMessages = new ArrayDeque<>();

        private boolean pausedAfterStop = false;
        private Future<?> runningTask;

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
                taskToCancel = runningTask;
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
                markQueueKind(followUp, ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP);
                queued.add(followUp);
            }
            while (!queuedSteeringMessages.isEmpty()) {
                Message steering = queuedSteeringMessages.removeFirst();
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
            return runningTask != null && !runningTask.isDone();
        }

        private void startRun(Message inbound, Deque<Message> prefix) {
            SessionKey runKey = new SessionKey(inbound.getChannelType(), inbound.getChatId());
            runningTask = sessionRunExecutor.submit(() -> {
                try {
                    clearInterruptRequested(runKey);
                    if (!prefix.isEmpty()) {
                        flushQueuedMessages(runKey, prefix);
                    }
                    agentLoop.processMessage(inbound);
                } catch (Exception e) { // NOSONAR - must not kill executor thread
                    handleRunFailure(inbound, e);
                } finally {
                    onRunComplete();
                }
            });
        }

        private void handleRunFailure(Message inbound, Exception e) {
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                log.info("[SessionRunCoordinator] run interrupted: channel={}, chatId={}",
                        inbound.getChannelType(), inbound.getChatId());
                return;
            }

            log.error("[SessionRunCoordinator] run failed: channel={}, chatId={}: {}",
                    inbound.getChannelType(), inbound.getChatId(), e.getMessage(), e);
        }

        private void onRunComplete() {
            Message next;
            synchronized (lock) {
                runningTask = null;
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
            if (!queuedFollowUpMessages.isEmpty()) {
                if (isQueueModeAll(runtimeConfigService.getTurnQueueFollowUpMode())) {
                    Message mergedFollowUp = mergeQueuedMessages(queuedFollowUpMessages,
                            ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP);
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

            queuedSteeringMessages.clear();
            queuedSteeringMessages.addLast(inbound);
            log.debug("[SessionRunCoordinator] steering queue mode one-at-a-time: replaced pending steering messages");
        }

        private void enqueueFollowUp(Message inbound) {
            if (isQueueModeAll(runtimeConfigService.getTurnQueueFollowUpMode())) {
                enqueueWithBound(queuedFollowUpMessages, inbound, "follow-up");
                return;
            }

            if (queuedFollowUpMessages.isEmpty()) {
                queuedFollowUpMessages.addLast(inbound);
            } else {
                queuedFollowUpMessages.removeFirst();
                queuedFollowUpMessages.addLast(inbound);
                log.debug(
                        "[SessionRunCoordinator] follow-up queue mode one-at-a-time: replaced pending follow-up message");
            }
        }

        private void enqueueWithBound(Deque<Message> queue, Message inbound, String queueLabel) {
            if (queue.size() >= MAX_QUEUED_MESSAGES_PER_SESSION) {
                queue.removeFirst();
                log.warn(
                        "[SessionRunCoordinator] {} queue limit reached ({}), dropped oldest message: channel={}, chatId={}",
                        queueLabel,
                        MAX_QUEUED_MESSAGES_PER_SESSION,
                        key.channelType(),
                        key.chatId());
            }
            queue.addLast(inbound);
        }

        private Message mergeQueuedMessages(Deque<Message> queue, String queueKind) {
            Message first = queue.removeFirst();
            StringBuilder builder = new StringBuilder();
            appendMergedChunk(builder, first.getContent());
            while (!queue.isEmpty()) {
                Message next = queue.removeFirst();
                appendMergedChunk(builder, next.getContent());
            }
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

    private String resolveQueueKind(Message inbound) {
        if (!runtimeConfigService.isTurnQueueSteeringEnabled()) {
            return ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP;
        }
        if (inbound == null || inbound.getMetadata() == null) {
            return ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP;
        }

        Object explicit = inbound.getMetadata().get(ContextAttributes.TURN_QUEUE_KIND);
        if (explicit instanceof String explicitKind) {
            String normalized = explicitKind.trim().toLowerCase();
            if (ContextAttributes.TURN_QUEUE_KIND_STEERING.equals(normalized)) {
                return ContextAttributes.TURN_QUEUE_KIND_STEERING;
            }
            if (ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP.equals(normalized)) {
                return ContextAttributes.TURN_QUEUE_KIND_FOLLOW_UP;
            }
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

    private record SessionKey(String channelType, String chatId) {
        private SessionKey {
            Objects.requireNonNull(channelType, "channelType");
            Objects.requireNonNull(chatId, "chatId");
        }
    }
}
