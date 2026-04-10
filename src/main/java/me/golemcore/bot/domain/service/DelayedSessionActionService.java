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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.DelayedActionDeliveryMode;
import me.golemcore.bot.domain.model.DelayedActionKind;
import me.golemcore.bot.domain.model.DelayedActionStatus;
import me.golemcore.bot.domain.model.DelayedJobReadyEvent;
import me.golemcore.bot.domain.model.DelayedSessionAction;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.StoragePort;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable registry of one-shot delayed actions scoped to logical sessions.
 */
@Service
@Slf4j
public class DelayedSessionActionService {

    static final String AUTOMATION_DIR = "automation";
    static final String ACTIONS_FILE = "delayed-actions.json";
    private static final int REGISTRY_VERSION = 1;
    private static final String CHANNEL_WEBHOOK = "webhook";

    private final StoragePort storagePort;
    private final RuntimeConfigService runtimeConfigService;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final Object lock = new Object();

    private final Map<String, DelayedSessionAction> actions = new LinkedHashMap<>();
    private volatile boolean loaded = false;

    public DelayedSessionActionService(StoragePort storagePort,
            RuntimeConfigService runtimeConfigService,
            Clock clock) {
        this.storagePort = storagePort;
        this.runtimeConfigService = runtimeConfigService;
        this.clock = clock;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public DelayedSessionAction schedule(DelayedSessionAction candidate) {
        ensureLoaded();
        if (!runtimeConfigService.isDelayedActionsEnabled()) {
            throw new IllegalStateException("Delayed actions are disabled");
        }
        Objects.requireNonNull(candidate, "candidate");

        synchronized (lock) {
            Instant now = clock.instant();
            DelayedSessionAction normalized = normalizeForCreate(candidate, now);

            if (normalized.getDedupeKey() != null) {
                DelayedSessionAction existing = findActiveByDedupeKeyLocked(normalized.getDedupeKey());
                if (existing != null) {
                    return copyAction(existing);
                }
            }

            int pendingForSession = countPendingForSessionLocked(
                    normalized.getChannelType(),
                    normalized.getConversationKey());
            if (pendingForSession >= runtimeConfigService.getDelayedActionsMaxPendingPerSession()) {
                throw new IllegalStateException("Maximum pending delayed actions reached for this session");
            }

            actions.put(normalized.getId(), normalized);
            persistLocked(now);
            return copyAction(normalized);
        }
    }

    public DelayedSessionAction scheduleJobReadyNotification(DelayedJobReadyEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Event is required");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfNotBlank(payload, "message", event.message());
        putIfNotBlank(payload, "artifactPath", event.artifactPath());
        putIfNotBlank(payload, "artifactName", event.artifactName());
        putIfNotBlank(payload, "mimeType", event.mimeType());
        DelayedActionDeliveryMode deliveryMode = StringValueSupport.isBlank(event.artifactPath())
                ? DelayedActionDeliveryMode.DIRECT_MESSAGE
                : DelayedActionDeliveryMode.DIRECT_FILE;
        return schedule(DelayedSessionAction.builder()
                .channelType(event.channelType())
                .conversationKey(event.conversationKey())
                .transportChatId(event.transportChatId())
                .jobId(event.jobId())
                .kind(DelayedActionKind.NOTIFY_JOB_READY)
                .deliveryMode(deliveryMode)
                .runAt(clock.instant())
                .maxAttempts(runtimeConfigService.getDelayedActionsDefaultMaxAttempts())
                .dedupeKey(buildJobReadyDedupeKey(event))
                .cancelOnUserActivity(false)
                .createdBy("event:job_ready")
                .payload(payload)
                .build());
    }

    public Optional<DelayedSessionAction> get(String actionId) {
        ensureLoaded();
        synchronized (lock) {
            return Optional.ofNullable(copyAction(actions.get(actionId)));
        }
    }

    public List<DelayedSessionAction> listActions(String channelType, String conversationKey) {
        ensureLoaded();
        String normalizedChannel = normalizeChannelType(channelType);
        String normalizedConversation = normalizeConversationKey(conversationKey);
        synchronized (lock) {
            pruneRetainedTerminalLocked(clock.instant());
            return actions.values().stream()
                    .filter(action -> matchesSession(action, normalizedChannel, normalizedConversation))
                    .sorted(Comparator
                            .comparing(DelayedSessionAction::getRunAt, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(DelayedSessionAction::getCreatedAt,
                                    Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(this::copyAction)
                    .toList();
        }
    }

    public boolean cancelAction(String actionId, String channelType, String conversationKey) {
        ensureLoaded();
        synchronized (lock) {
            DelayedSessionAction action = actions.get(actionId);
            Instant now = clock.instant();
            if (!isUserMutable(action, channelType, conversationKey, now)) {
                return false;
            }
            action.setStatus(DelayedActionStatus.CANCELLED);
            action.setLeaseUntil(null);
            action.setUpdatedAt(now);
            action.setCompletedAt(now);
            action.setExpiresAt(resolveRetentionExpiry(now));
            persistLocked(now);
            return true;
        }
    }

    public boolean runNow(String actionId, String channelType, String conversationKey) {
        ensureLoaded();
        synchronized (lock) {
            DelayedSessionAction action = actions.get(actionId);
            Instant now = clock.instant();
            if (!isUserMutable(action, channelType, conversationKey, now)) {
                return false;
            }
            action.setRunAt(now);
            action.setStatus(DelayedActionStatus.SCHEDULED);
            action.setLeaseUntil(null);
            action.setUpdatedAt(now);
            persistLocked(now);
            return true;
        }
    }

    public void cancelOnUserActivity(Message inbound) {
        if (inbound == null || inbound.isInternalMessage() || AutoRunContextSupport.isAutoMessage(inbound)) {
            return;
        }
        ensureLoaded();
        String channelType = normalizeChannelType(inbound.getChannelType());
        String conversationKey = resolveConversationKey(inbound);
        Instant now = clock.instant();
        synchronized (lock) {
            boolean changed = false;
            for (DelayedSessionAction action : actions.values()) {
                if (action.isTerminal() || !action.isCancelOnUserActivity()) {
                    continue;
                }
                if (!matchesSession(action, channelType, conversationKey)) {
                    continue;
                }
                if (action.getRunAt() != null && !action.getRunAt().isAfter(now)) {
                    continue;
                }
                action.setStatus(DelayedActionStatus.CANCELLED);
                action.setLeaseUntil(null);
                action.setUpdatedAt(now);
                action.setCompletedAt(now);
                action.setExpiresAt(resolveRetentionExpiry(now));
                changed = true;
            }
            if (changed) {
                persistLocked(now);
            }
        }
    }

    public List<DelayedSessionAction> leaseDueActions(int limit) {
        ensureLoaded();
        if (limit < 1) {
            return List.of();
        }
        Instant now = clock.instant();
        synchronized (lock) {
            pruneRetainedTerminalLocked(now);
            Duration leaseDuration = runtimeConfigService.getDelayedActionsLeaseDuration();
            List<DelayedSessionAction> leased = actions.values().stream()
                    .filter(action -> !action.isTerminal())
                    .filter(action -> isLeaseable(action, now))
                    .sorted(Comparator
                            .comparing(DelayedSessionAction::getRunAt, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(DelayedSessionAction::getCreatedAt,
                                    Comparator.nullsLast(Comparator.naturalOrder())))
                    .limit(limit)
                    .toList();
            if (leased.isEmpty()) {
                return List.of();
            }
            Instant leaseUntil = now.plus(leaseDuration);
            for (DelayedSessionAction action : leased) {
                action.setStatus(DelayedActionStatus.LEASED);
                action.setLeaseUntil(leaseUntil);
                action.setUpdatedAt(now);
            }
            persistLocked(now);
            return leased.stream().map(this::copyAction).toList();
        }
    }

    public void markCompleted(String actionId) {
        transitionTerminal(actionId, DelayedActionStatus.COMPLETED, null);
    }

    public void markDeadLetter(String actionId, String error) {
        transitionTerminal(actionId, DelayedActionStatus.DEAD_LETTER, error);
    }

    public void rescheduleRetry(String actionId, Instant nextRunAt, String error) {
        ensureLoaded();
        synchronized (lock) {
            DelayedSessionAction action = actions.get(actionId);
            if (action == null || action.isTerminal()) {
                return;
            }
            Instant now = clock.instant();
            action.setStatus(DelayedActionStatus.SCHEDULED);
            action.setLeaseUntil(null);
            action.setAttempts(action.getAttempts() + 1);
            action.setRunAt(nextRunAt != null ? nextRunAt : now);
            action.setLastError(error);
            action.setUpdatedAt(now);
            persistLocked(now);
        }
    }

    private void transitionTerminal(String actionId, DelayedActionStatus status, String error) {
        ensureLoaded();
        synchronized (lock) {
            DelayedSessionAction action = actions.get(actionId);
            if (action == null) {
                return;
            }
            Instant now = clock.instant();
            action.setStatus(status);
            action.setLeaseUntil(null);
            action.setUpdatedAt(now);
            action.setCompletedAt(now);
            action.setExpiresAt(resolveRetentionExpiry(now));
            action.setLastError(error);
            persistLocked(now);
        }
    }

    private boolean isLeaseable(DelayedSessionAction action, Instant now) {
        if (action.getRunAt() == null || action.getRunAt().isAfter(now)) {
            return false;
        }
        if (action.getStatus() == DelayedActionStatus.SCHEDULED) {
            return true;
        }
        return action.getStatus() == DelayedActionStatus.LEASED
                && (action.getLeaseUntil() == null || !action.getLeaseUntil().isAfter(now));
    }

    private int countPendingForSessionLocked(String channelType, String conversationKey) {
        return (int) actions.values().stream()
                .filter(action -> !action.isTerminal())
                .filter(action -> matchesSession(action, channelType, conversationKey))
                .count();
    }

    private DelayedSessionAction findActiveByDedupeKeyLocked(String dedupeKey) {
        return actions.values().stream()
                .filter(action -> !action.isTerminal())
                .filter(action -> dedupeKey.equals(action.getDedupeKey()))
                .findFirst()
                .orElse(null);
    }

    private boolean isUserMutable(DelayedSessionAction action, String channelType, String conversationKey,
            Instant now) {
        if (action == null || action.isTerminal() || !matchesSession(action, channelType, conversationKey)) {
            return false;
        }
        if (action.getStatus() == DelayedActionStatus.SCHEDULED) {
            return true;
        }
        return action.getStatus() == DelayedActionStatus.LEASED
                && (action.getLeaseUntil() == null || !action.getLeaseUntil().isAfter(now));
    }

    private boolean matchesSession(DelayedSessionAction action, String channelType, String conversationKey) {
        if (action == null) {
            return false;
        }
        return Objects.equals(normalizeChannelType(action.getChannelType()), normalizeChannelType(channelType))
                && Objects.equals(normalizeConversationKey(action.getConversationKey()),
                        normalizeConversationKey(conversationKey));
    }

    private DelayedSessionAction normalizeForCreate(DelayedSessionAction candidate, Instant now) {
        DelayedSessionAction normalized = copyAction(candidate);
        normalized.setId(StringValueSupport.isBlank(normalized.getId())
                ? "delay-" + UUID.randomUUID().toString().substring(0, 8)
                : normalized.getId().trim());
        normalized.setChannelType(normalizeChannelType(normalized.getChannelType()));
        normalized.setConversationKey(normalizeConversationKey(normalized.getConversationKey()));
        normalized.setTransportChatId(normalizeTransportChatId(normalized.getTransportChatId()));
        normalized.setCreatedBy(
                StringValueSupport.isBlank(normalized.getCreatedBy()) ? "system" : normalized.getCreatedBy().trim());
        normalized.setCreatedAt(normalized.getCreatedAt() != null ? normalized.getCreatedAt() : now);
        normalized.setUpdatedAt(now);
        normalized.setStatus(DelayedActionStatus.SCHEDULED);
        normalized.setLeaseUntil(null);
        normalized.setCompletedAt(null);
        normalized.setLastError(null);
        if (normalized.getRunAt() == null) {
            throw new IllegalArgumentException("runAt is required");
        }
        if (normalized.getRunAt().isAfter(now.plus(runtimeConfigService.getDelayedActionsMaxDelay()))) {
            throw new IllegalArgumentException("Delayed action exceeds configured max delay");
        }
        if (normalized.getKind() == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (normalized.getDeliveryMode() == null) {
            throw new IllegalArgumentException("deliveryMode is required");
        }
        if (StringValueSupport.isBlank(normalized.getChannelType())
                || StringValueSupport.isBlank(normalized.getConversationKey())) {
            throw new IllegalArgumentException("channelType and conversationKey are required");
        }
        if (CHANNEL_WEBHOOK.equals(normalized.getChannelType())) {
            throw new IllegalStateException("Delayed actions are not supported for channel: " + CHANNEL_WEBHOOK);
        }
        if (normalized.getMaxAttempts() < 1) {
            normalized.setMaxAttempts(runtimeConfigService.getDelayedActionsDefaultMaxAttempts());
        }
        if (normalized.getPayload() == null) {
            normalized.setPayload(new LinkedHashMap<>());
        } else if (!(normalized.getPayload() instanceof LinkedHashMap<?, ?>)) {
            normalized.setPayload(new LinkedHashMap<>(normalized.getPayload()));
        }
        if (!StringValueSupport.isBlank(normalized.getDedupeKey())) {
            normalized.setDedupeKey(normalized.getDedupeKey().trim());
        }
        return normalized;
    }

    private String resolveConversationKey(Message inbound) {
        if (inbound == null) {
            return null;
        }
        if (inbound.getMetadata() != null) {
            Object metadataConversation = inbound.getMetadata()
                    .get(me.golemcore.bot.domain.model.ContextAttributes.CONVERSATION_KEY);
            if (metadataConversation instanceof String value && !value.isBlank()) {
                return normalizeConversationKey(value);
            }
        }
        return normalizeConversationKey(inbound.getChatId());
    }

    private String normalizeChannelType(String value) {
        if (StringValueSupport.isBlank(value)) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeConversationKey(String value) {
        if (StringValueSupport.isBlank(value)) {
            return null;
        }
        return ConversationKeyValidator.normalizeLegacyCompatibleOrThrow(value);
    }

    private String normalizeTransportChatId(String value) {
        if (StringValueSupport.isBlank(value)) {
            return null;
        }
        return value.trim();
    }

    private Instant resolveRetentionExpiry(Instant from) {
        return from.plus(runtimeConfigService.getDelayedActionsRetentionAfterCompletion());
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (lock) {
            if (loaded) {
                return;
            }
            loadLocked();
            loaded = true;
        }
    }

    private void loadLocked() {
        try {
            String json = storagePort.getText(AUTOMATION_DIR, ACTIONS_FILE).join();
            if (StringValueSupport.isBlank(json)) {
                return;
            }
            Registry registry = objectMapper.readValue(json, Registry.class);
            if (registry.getActions() != null) {
                for (DelayedSessionAction action : registry.getActions()) {
                    if (action != null && !StringValueSupport.isBlank(action.getId())) {
                        actions.put(action.getId(), action);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("[DelayedActions] Failed to load registry: {}", e.getMessage());
        }
    }

    private void persistLocked(Instant now) {
        try {
            Registry registry = new Registry();
            registry.setVersion(REGISTRY_VERSION);
            registry.setUpdatedAt(now.toString());
            registry.setActions(new ArrayList<>(actions.values()));
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(registry);
            storagePort.putTextAtomic(AUTOMATION_DIR, ACTIONS_FILE, json, true).join();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist delayed actions", e);
        }
    }

    private void pruneRetainedTerminalLocked(Instant now) {
        boolean changed = actions.values().removeIf(action -> action.isTerminal()
                && action.getExpiresAt() != null
                && !action.getExpiresAt().isAfter(now));
        if (changed) {
            persistLocked(now);
        }
    }

    private DelayedSessionAction copyAction(DelayedSessionAction source) {
        if (source == null) {
            return null;
        }
        return DelayedSessionAction.builder()
                .id(source.getId())
                .channelType(source.getChannelType())
                .conversationKey(source.getConversationKey())
                .transportChatId(source.getTransportChatId())
                .jobId(source.getJobId())
                .kind(source.getKind())
                .deliveryMode(source.getDeliveryMode())
                .status(source.getStatus())
                .runAt(source.getRunAt())
                .leaseUntil(source.getLeaseUntil())
                .attempts(source.getAttempts())
                .maxAttempts(source.getMaxAttempts())
                .dedupeKey(source.getDedupeKey())
                .cancelOnUserActivity(source.isCancelOnUserActivity())
                .createdBy(source.getCreatedBy())
                .createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt())
                .completedAt(source.getCompletedAt())
                .expiresAt(source.getExpiresAt())
                .lastError(source.getLastError())
                .payload(source.getPayload() != null ? new LinkedHashMap<>(source.getPayload()) : new LinkedHashMap<>())
                .build();
    }

    private void putIfNotBlank(Map<String, Object> payload, String key, String value) {
        if (!StringValueSupport.isBlank(value)) {
            payload.put(key, value.trim());
        }
    }

    private String buildJobReadyDedupeKey(DelayedJobReadyEvent event) {
        if (event == null || StringValueSupport.isBlank(event.jobId())) {
            return null;
        }
        String normalizedChannel = normalizeChannelType(event.channelType());
        String normalizedConversation = normalizeConversationKey(event.conversationKey());
        return "job-ready:" + normalizedChannel + ":" + normalizedConversation + ":" + event.jobId().trim();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Registry {
        private int version = REGISTRY_VERSION;
        private String updatedAt;
        private List<DelayedSessionAction> actions = new ArrayList<>();
    }
}
