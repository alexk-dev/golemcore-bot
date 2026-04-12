package me.golemcore.bot.domain.loop;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ChannelTypes;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FailureEvent;
import me.golemcore.bot.domain.model.FailureKind;
import me.golemcore.bot.domain.model.FailureSource;
import me.golemcore.bot.domain.model.FinishReason;
import me.golemcore.bot.domain.model.ModelTierCatalog;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.RoutingOutcome;
import me.golemcore.bot.domain.model.SessionIdentity;
import me.golemcore.bot.domain.model.SkillTransitionRequest;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.model.trace.TraceContext;
import me.golemcore.bot.domain.model.trace.TraceSpanKind;
import me.golemcore.bot.domain.model.trace.TraceStatusCode;
import me.golemcore.bot.domain.service.AutoRunContextSupport;
import me.golemcore.bot.domain.service.MdcSupport;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SessionIdentitySupport;
import me.golemcore.bot.domain.service.TraceContextSupport;
import me.golemcore.bot.domain.service.TraceMdcSupport;
import me.golemcore.bot.domain.service.TraceNamingSupport;
import me.golemcore.bot.domain.service.TraceRuntimeConfigSupport;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.domain.system.AgentSystem;
import me.golemcore.bot.domain.system.ResponseRoutingSystem;
import me.golemcore.bot.port.outbound.ChannelDeliveryPort;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.RateLimitPort;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.RateLimitResult;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Core orchestrator managing the agent processing pipeline for incoming
 * messages. Coordinates session management, rate limiting, system pipeline
 * execution, and channel responses. Systems are executed in order based on
 * their @Order annotation (InputSanitization -> SkillRouting -> ContextBuilding
 * -> LlmExecution -> ToolExecution -> MemoryPersist -> ResponseRouting).
 * Manages typing indicators and handles async message processing.
 */
@Slf4j
public class AgentLoop {

    private final SessionPort sessionService;
    private final RateLimitPort rateLimiter;
    private final List<AgentSystem> systems;
    private final RuntimeConfigService runtimeConfigService;
    private final UserPreferencesService preferencesService;
    private final LlmPort llmPort;
    private final Clock clock;
    private final TraceService traceService;
    private final ChannelRuntimePort channelRuntimePort;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ScheduledExecutorService typingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "typing-indicator");
        t.setDaemon(true);
        return t;
    });

    private static final long TYPING_INTERVAL_SECONDS = 4;

    private List<AgentSystem> sortedSystems;
    private AgentSystem routingSystem;

    @PreDestroy
    public void shutdown() {
        typingExecutor.shutdownNow();
        try {
            typingExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public AgentLoop(SessionPort sessionService, RateLimitPort rateLimiter,
            List<AgentSystem> systems,
            ChannelRuntimePort channelRuntimePort, RuntimeConfigService runtimeConfigService,
            UserPreferencesService preferencesService,
            LlmPort llmPort, Clock clock, TraceService traceService) {
        this.sessionService = sessionService;
        this.rateLimiter = rateLimiter;
        this.systems = systems;
        this.channelRuntimePort = channelRuntimePort;
        this.runtimeConfigService = runtimeConfigService;
        this.preferencesService = preferencesService;
        this.llmPort = llmPort;
        this.clock = clock;
        this.traceService = traceService;
    }

    public void processMessage(Message message) {
        Objects.requireNonNull(message, "message must not be null");
        message.setMetadata(TraceContextSupport.ensureRootMetadata(
                message.getMetadata(),
                message.isInternalMessage() || AutoRunContextSupport.isAutoMessage(message)
                        ? TraceSpanKind.INTERNAL
                        : TraceSpanKind.INGRESS,
                TraceNamingSupport.inboundMessage(message)));
        Map<String, String> mdcContext = new LinkedHashMap<>(AutoRunContextSupport.buildMdcContext(message));
        mdcContext.putAll(TraceMdcSupport.buildMdcContext(message));
        try (MdcSupport.Scope ignored = MdcSupport.withContext(mdcContext)) {
            log.info("=== INCOMING MESSAGE ===");
            log.info("Channel: {}, Chat: {}, Sender: {}",
                    message.getChannelType(), message.getChatId(), message.getSenderId());
            log.debug("Content: {}", truncate(message.getContent(), 200));

            boolean isAuto = AutoRunContextSupport.isAutoMessage(message);
            boolean isInternal = message.isInternalMessage();
            if (!isAuto && !isInternal) {
                RateLimitResult rateLimit = rateLimiter.tryConsume();
                if (!rateLimit.isAllowed()) {
                    log.warn("Rate limit exceeded");
                    notifyRateLimited(message);
                    return;
                }
            }

            AgentSession session = sessionService.getOrCreate(
                    message.getChannelType(),
                    message.getChatId());
            log.debug("Session: {}, messages in history: {}", session.getId(), session.getMessages().size());

            TraceContext rootTraceContext = initializeRootTrace(session, message);
            applySessionIdentityMetadata(session, message);
            if (!message.isInternalMessage()) {
                session.addMessage(message);
            }

            captureInboundSnapshot(session, rootTraceContext, message);

            AgentContext context = AgentContext.builder()
                    .session(session)
                    .messages(buildContextMessages(session, message))
                    .maxIterations(runtimeConfigService.getTurnMaxLlmCalls())
                    .currentIteration(0)
                    .build();
            context.setTraceContext(rootTraceContext);
            applyRuntimeAttributes(context, message, session);

            // Initialize sorted systems + routingSystem (used by feedback guarantee).
            getSortedSystems();
            log.info("[AgentLoop] routingSystem resolved: {}",
                    routingSystem != null ? routingSystem.getClass().getName() : "<null>");

            ChannelDeliveryPort channel = channelRuntimePort.findChannel(message.getChannelType()).orElse(null);
            ScheduledFuture<?> typingTask = null;
            String typingChatId = resolveTransportChatId(message);
            if (channel != null && typingChatId != null && !typingChatId.isBlank()) {
                String chatId = typingChatId;
                sendTypingIndicator(channel, chatId);
                typingTask = typingExecutor.scheduleAtFixedRate(
                        () -> sendTypingIndicator(channel, chatId),
                        TYPING_INTERVAL_SECONDS, TYPING_INTERVAL_SECONDS, TimeUnit.SECONDS);
            }

            log.debug("Starting agent loop (max iterations: {})", context.getMaxIterations());
            try {
                runLoop(context);
                ensureFeedback(context);
                recordAutoRunOutcome(message, context);
            } finally {
                if (typingTask != null) {
                    typingTask.cancel(false);
                }
                // Guarantee ThreadLocal cleanup even if systems throw unexpectedly
                AgentContextHolder.clear();

                // Persist the session even if loop/routing fails unexpectedly.
                // This prevents losing the last inbound message (and any history mutations
                // already applied)
                // on restart/crash.
                try {
                    saveSessionWithTracing(session, context.getTraceContext() != null
                            ? context.getTraceContext()
                            : rootTraceContext);
                } catch (Exception e) { // NOSONAR - last resort, must not break finally
                    log.error("Failed to persist session in finally: {}", session.getId(), e);
                }
            }

            log.info("=== MESSAGE PROCESSING COMPLETE ===");
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null)
            return "<null>";
        if (text.length() <= maxLen)
            return text;
        return text.substring(0, maxLen) + "...";
    }

    private void applySessionIdentityMetadata(AgentSession session, Message message) {
        if (session == null || message == null) {
            return;
        }

        String conversationKey = readMetadataString(message, ContextAttributes.CONVERSATION_KEY);
        if (conversationKey == null || conversationKey.isBlank()) {
            conversationKey = message.getChatId();
        }

        String transportChatId = readMetadataString(message, ContextAttributes.TRANSPORT_CHAT_ID);
        if (transportChatId == null || transportChatId.isBlank()) {
            transportChatId = message.getChatId();
        }

        if (conversationKey == null || conversationKey.isBlank() || transportChatId == null
                || transportChatId.isBlank()) {
            return;
        }

        SessionIdentitySupport.bindTransportAndConversation(session, transportChatId, conversationKey);
        String webClientInstanceId = readMetadataString(message, ContextAttributes.WEB_CLIENT_INSTANCE_ID);
        if (message.getChannelType() != null
                && ChannelTypes.WEB.equalsIgnoreCase(message.getChannelType())
                && webClientInstanceId != null
                && !webClientInstanceId.isBlank()) {
            SessionIdentitySupport.bindWebClientInstance(session, webClientInstanceId);
        }
    }

    private String resolveTransportChatId(Message message) {
        String transportChatId = readMetadataString(message, ContextAttributes.TRANSPORT_CHAT_ID);
        if (transportChatId != null && !transportChatId.isBlank()) {
            return transportChatId;
        }
        return message != null ? message.getChatId() : null;
    }

    private void sendTypingIndicator(ChannelDeliveryPort channel, String chatId) {
        try {
            channel.showTyping(chatId);
        } catch (Exception e) {
            log.debug("Typing indicator failed for chat {}: {}", chatId, e.getMessage());
        }
    }

    private void notifyRateLimited(Message message) {
        if (message == null || message.getChannelType() == null || message.getChannelType().isBlank()) {
            return;
        }

        String chatId = resolveTransportChatId(message);
        if (chatId == null || chatId.isBlank()) {
            return;
        }

        ChannelDeliveryPort channel = channelRuntimePort.findChannel(message.getChannelType()).orElse(null);
        if (channel == null) {
            return;
        }

        String text = preferencesService.getMessage("system.rate.limit");
        if (text == null || text.isBlank()) {
            text = "Rate limit exceeded. Please wait before sending more messages.";
        }

        try {
            channel.sendMessage(chatId, text);
        } catch (Exception e) { // NOSONAR - user feedback must not break request handling
            log.debug("Rate limit notification failed for chat {}: {}", chatId, e.getMessage());
        }
    }

    private void applyRuntimeAttributes(AgentContext context, Message message, AgentSession session) {
        if (context == null) {
            return;
        }

        SessionIdentity sessionIdentity = SessionIdentitySupport.resolveSessionIdentity(session);
        if (sessionIdentity != null) {
            context.setAttribute(ContextAttributes.SESSION_IDENTITY_CHANNEL, sessionIdentity.channelType());
            context.setAttribute(ContextAttributes.SESSION_IDENTITY_CONVERSATION, sessionIdentity.conversationKey());
        }

        String transportChatId = resolveTransportChatId(message);
        if (transportChatId != null && !transportChatId.isBlank()) {
            context.setAttribute(ContextAttributes.TRANSPORT_CHAT_ID, transportChatId);
        }
        if (sessionIdentity != null && sessionIdentity.conversationKey() != null) {
            context.setAttribute(ContextAttributes.CONVERSATION_KEY, sessionIdentity.conversationKey());
        }
        String webClientInstanceId = readMetadataString(message, ContextAttributes.WEB_CLIENT_INSTANCE_ID);
        if ((webClientInstanceId == null || webClientInstanceId.isBlank()) && session != null) {
            webClientInstanceId = SessionIdentitySupport.resolveWebClientInstanceId(session);
        }
        if (webClientInstanceId != null && !webClientInstanceId.isBlank()) {
            context.setAttribute(ContextAttributes.WEB_CLIENT_INSTANCE_ID, webClientInstanceId);
        }

        if (AutoRunContextSupport.isAutoMessage(message)) {
            context.setAttribute(ContextAttributes.AUTO_MODE, true);
        }
        if (message != null && message.isInternalMessage()) {
            context.setAttribute(ContextAttributes.TURN_INPUT_INTERNAL, true);
        }
        copyStringMetadataAttribute(message, context, ContextAttributes.AUTO_RUN_KIND);
        copyStringMetadataAttribute(message, context, ContextAttributes.AUTO_RUN_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.AUTO_SCHEDULE_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.AUTO_GOAL_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.AUTO_TASK_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.ACTIVE_SKILL_NAME);
        copyStringMetadataAttribute(message, context, ContextAttributes.AUTO_RUN_ACTIVE_SKILL);
        copyStringMetadataAttribute(message, context, ContextAttributes.AUTO_REFLECTION_TIER);
        copyStringMetadataAttribute(message, context, ContextAttributes.HIVE_CARD_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.HIVE_THREAD_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.HIVE_COMMAND_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.HIVE_RUN_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.HIVE_GOLEM_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.TRACE_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.TRACE_SPAN_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.TRACE_PARENT_SPAN_ID);
        copyStringMetadataAttribute(message, context, ContextAttributes.TRACE_ROOT_KIND);
        copyStringMetadataAttribute(message, context, ContextAttributes.TRACE_NAME);
        TraceContext traceContext = TraceContextSupport
                .readTraceContext(message != null ? message.getMetadata() : null);
        if (traceContext != null) {
            context.setTraceContext(traceContext);
        }

        boolean reflectionActive = readMetadataBoolean(message, ContextAttributes.AUTO_REFLECTION_ACTIVE);
        if (reflectionActive) {
            context.setAttribute(ContextAttributes.AUTO_REFLECTION_ACTIVE, true);
        }

        if (message != null && message.getMetadata() != null
                && message.getMetadata().containsKey(ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY)) {
            boolean reflectionTierPriority = readMetadataBoolean(message,
                    ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY);
            context.setAttribute(ContextAttributes.AUTO_REFLECTION_TIER_PRIORITY, reflectionTierPriority);
        }
    }

    private void copyStringMetadataAttribute(Message message, AgentContext context, String key) {
        String value = readMetadataString(message, key);
        if (value == null || value.isBlank()) {
            return;
        }
        context.setAttribute(key, value);
    }

    private List<Message> buildContextMessages(AgentSession session, Message inbound) {
        List<Message> messages = new ArrayList<>();
        if (session != null && session.getMessages() != null) {
            messages.addAll(session.getMessages());
        }
        if (inbound != null && inbound.isInternalMessage()) {
            messages.add(inbound);
        }
        return messages;
    }

    private String readMetadataString(Message message, String key) {
        if (message == null || message.getMetadata() == null || key == null || key.isBlank()) {
            return null;
        }
        return AutoRunContextSupport.readMetadataString(message.getMetadata(), key);
    }

    private boolean readMetadataBoolean(Message message, String key) {
        if (message == null || message.getMetadata() == null || key == null || key.isBlank()) {
            return false;
        }
        Object value = message.getMetadata().get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return Boolean.parseBoolean(stringValue.trim());
        }
        return false;
    }

    private TraceContext initializeRootTrace(AgentSession session, Message message) {
        if (!runtimeConfigService.isTracingEnabled() || session == null || message == null) {
            return TraceContextSupport.readTraceContext(message != null ? message.getMetadata() : null);
        }
        TraceContext seededContext = TraceContextSupport.readTraceContext(message.getMetadata());
        TraceSpanKind spanKind = message.isInternalMessage() || AutoRunContextSupport.isAutoMessage(message)
                ? TraceSpanKind.INTERNAL
                : TraceSpanKind.INGRESS;
        String traceName = TraceContextSupport.readTraceName(message.getMetadata());
        if (traceName == null || traceName.isBlank()) {
            traceName = TraceNamingSupport.inboundMessage(message);
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("session.id", session.getId());
        if (message.getChannelType() != null) {
            attributes.put("channel.type", message.getChannelType());
        }
        if (message.getChatId() != null) {
            attributes.put("chat.id", message.getChatId());
        }
        if (message.getSenderId() != null) {
            attributes.put("sender.id", message.getSenderId());
        }
        return traceService.startRootTrace(
                session,
                seededContext,
                traceName,
                spanKind,
                message.getTimestamp() != null ? message.getTimestamp() : clock.instant(),
                attributes,
                runtimeConfigService.getTraceMaxTracesPerSession());
    }

    private void captureInboundSnapshot(AgentSession session, TraceContext rootTraceContext, Message message) {
        RuntimeConfig.TracingConfig tracingConfig = TraceRuntimeConfigSupport.resolve(runtimeConfigService);
        if (session == null || rootTraceContext == null || tracingConfig == null
                || !Boolean.TRUE.equals(tracingConfig.getCaptureInboundPayloads())) {
            return;
        }
        traceService.captureSnapshot(session, rootTraceContext, tracingConfig,
                "request", "application/json", serializeSnapshotPayload(message));
    }

    private void recordAutoRunOutcome(Message inbound, AgentContext context) {
        if (!AutoRunContextSupport.isAutoMessage(inbound) || inbound == null || context == null) {
            return;
        }

        Map<String, Object> metadata = inbound.getMetadata();
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
            inbound.setMetadata(metadata);
        }

        String status = determineAutoRunStatus(context);
        String finishReason = determineAutoRunFinishReason(context);
        String assistantText = determineAssistantText(context);
        String activeSkillName = determineActiveSkillName(context);
        String failureSummary = determineFailureSummary(context);
        String failureFingerprint = buildFailureFingerprint(failureSummary);

        metadata.put(ContextAttributes.AUTO_RUN_STATUS, status);
        metadata.put(ContextAttributes.AUTO_RUN_FINISH_REASON, finishReason);
        if (assistantText != null && !assistantText.isBlank()) {
            metadata.put(ContextAttributes.AUTO_RUN_ASSISTANT_TEXT, assistantText);
        }
        if (activeSkillName != null && !activeSkillName.isBlank()) {
            metadata.put(ContextAttributes.AUTO_RUN_ACTIVE_SKILL, activeSkillName);
        }
        if (failureSummary != null && !failureSummary.isBlank()) {
            metadata.put(ContextAttributes.AUTO_RUN_FAILURE_SUMMARY, failureSummary);
            metadata.put(ContextAttributes.AUTO_RUN_FAILURE_FINGERPRINT, failureFingerprint);
        }
    }

    // Inbound messages are handled by SessionRunCoordinator via
    // InboundMessageListener.

    private void runLoop(AgentContext context) {
        int maxIterations = context.getMaxIterations();
        boolean reachedLimit = false;

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            context.setCurrentIteration(iteration);
            log.info("--- Iteration {}/{} ---", iteration + 1, maxIterations);

            for (AgentSystem system : getSortedSystems()) {
                if (!system.isEnabled()) {
                    log.debug("System '{}' is disabled, skipping", system.getName());
                    continue;
                }

                if (!system.shouldProcess(context)) {
                    log.debug("System '{}' shouldProcess=false, skipping", system.getName());
                    continue;
                }

                log.debug("Running system: {} (order={})", system.getName(), system.getOrder());
                long startMs = clock.millis();
                TraceStateSnapshot beforeTraceState = captureTraceState(context);
                TraceContext systemSpan = startChildSpan(context,
                        "system." + system.getName(),
                        TraceSpanKind.INTERNAL,
                        Map.of(
                                "system.name", system.getName(),
                                "system.order", system.getOrder(),
                                "iteration", iteration));
                try {
                    try (MdcSupport.Scope ignored = MdcSupport.withContext(buildTraceMdcContext(systemSpan, context))) {
                        context = system.process(context);
                    }
                    emitTraceStateEvents(context, system.getName(), systemSpan, beforeTraceState);
                    finishChildSpan(context, systemSpan, TraceStatusCode.OK, null);
                    log.debug("System '{}' completed in {}ms", system.getName(), clock.millis() - startMs);
                } catch (Exception e) {
                    finishChildSpan(context, systemSpan, TraceStatusCode.ERROR, e.getMessage());
                    log.error("System '{}' FAILED after {}ms: {}", system.getName(), clock.millis() - startMs,
                            e.getMessage(), e);
                    context.addFailure(new FailureEvent(
                            FailureSource.SYSTEM, system.getName(), FailureKind.EXCEPTION,
                            e.getMessage(), clock.instant()));
                }
            }

            if (!shouldContinueLoop(context)) {
                log.info("Agent loop completed after {} iteration(s)", iteration + 1);
                break;
            }

            if (iteration + 1 >= maxIterations) {
                reachedLimit = true;
                log.warn("Reached max iterations limit ({}), stopping", maxIterations);
                break;
            }

            log.debug("Continuing to next iteration");
            context.setAttribute(ContextAttributes.FINAL_ANSWER_READY, false);
            // Keep transition request for ContextBuildingSystem in the next iteration.
            // Clear transport contract from the previous iteration to avoid stale routing.
            context.setOutgoingResponse(null);
            context.getToolResults().clear();
        }

        if (reachedLimit) {
            context.setAttribute(ContextAttributes.ITERATION_LIMIT_REACHED, true);
            context.clearSkillTransitionRequest();
            String limitMessage = preferencesService.getMessage("system.iteration.limit", maxIterations);
            routeSyntheticAssistantResponse(context, limitMessage, "stop");
        }
    }

    private void routeResponse(AgentContext context) {
        // AgentLoop is an orchestrator. It must not perform transport directly,
        // but it can explicitly invoke the transport system when it generates
        // a synthetic response outside the main pipeline flow.
        if (routingSystem == null) {
            log.warn("[AgentLoop] routingSystem is null; cannot route response");
            return;
        }

        boolean should = routingSystem.shouldProcess(context);
        log.info("[AgentLoop] routingSystem.shouldProcess={}", should);
        if (should) {
            log.info("[AgentLoop] invoking routingSystem.process (OutgoingResponse present: {})",
                    context.getAttribute(ContextAttributes.OUTGOING_RESPONSE) != null);
            routingSystem.process(context);
        }
    }

    private void routeSyntheticAssistantResponse(AgentContext context, String content, String finishReason) {
        // Feedback guarantee must not mutate raw history.
        context.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly(content));
        routeResponse(context);
    }

    private boolean shouldContinueLoop(AgentContext context) {
        // SkillPipelineSystem sets skillTransitionRequest to request a new iteration.
        SkillTransitionRequest transition = context.getSkillTransitionRequest();
        return transition != null && transition.targetSkill() != null;
    }

    private void ensureFeedback(AgentContext context) {
        TurnOutcome outcome = context.getTurnOutcome();
        RoutingOutcome routingOutcome = outcome != null ? outcome.getRoutingOutcome() : null;
        if (isDeliverySuccessful(routingOutcome)) {
            return;
        }

        RoutingOutcome routingAttr = context.getAttribute(ContextAttributes.ROUTING_OUTCOME);
        if (isDeliverySuccessful(routingAttr)) {
            return;
        }

        if (isAutoModeContext(context)) {
            log.debug("[AgentLoop] Feedback guarantee skipped: auto mode context");
            return;
        }

        if (Boolean.TRUE.equals(context.getAttribute(ContextAttributes.TURN_INTERNAL_RETRY_SCHEDULED))) {
            log.debug("[AgentLoop] Feedback guarantee skipped: internal retry already scheduled");
            return;
        }

        if (tryUnsentLlmResponse(context)) {
            return;
        }

        if (tryErrorFeedback(context)) {
            return;
        }

        // Last resort: generic feedback.
        String generic = preferencesService.getMessage("system.error.generic.feedback");
        OutgoingResponse outgoing = context.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        log.warn(
                "[AgentLoop] Feedback guarantee fallback triggered: routing generic feedback "
                        + "(turnRoutingOutcome={}, attributeRoutingOutcome={}, outgoingResponse={}, llmErrorPresent={}, failures={})",
                describeRoutingOutcome(routingOutcome), describeRoutingOutcome(routingAttr),
                describeOutgoingResponse(outgoing), llmError != null && !llmError.isBlank(),
                context.getFailures().size());
        routeSyntheticAssistantResponse(context, generic, "stop");
    }

    private boolean isDeliverySuccessful(RoutingOutcome routingOutcome) {
        if (routingOutcome == null) {
            return false;
        }
        if (routingOutcome.isSentText()) {
            return true;
        }
        if (routingOutcome.isSentVoice()) {
            return true;
        }
        return routingOutcome.getSentAttachments() > 0;
    }

    private String describeRoutingOutcome(RoutingOutcome routingOutcome) {
        if (routingOutcome == null) {
            return "<null>";
        }
        return String.format("attempted=%s,sentText=%s,sentVoice=%s,sentAttachments=%d,error=%s",
                routingOutcome.isAttempted(), routingOutcome.isSentText(), routingOutcome.isSentVoice(),
                routingOutcome.getSentAttachments(), truncate(routingOutcome.getErrorMessage(), 160));
    }

    private String describeOutgoingResponse(OutgoingResponse outgoing) {
        if (outgoing == null) {
            return "<null>";
        }

        String text = outgoing.getText();
        int textLength = text != null ? text.length() : 0;
        int attachmentCount = outgoing.getAttachments() != null ? outgoing.getAttachments().size() : 0;
        return String.format("textLength=%d,voiceRequested=%s,voiceTextPresent=%s,attachments=%d",
                textLength, outgoing.isVoiceRequested(), outgoing.getVoiceText() != null, attachmentCount);
    }

    private boolean tryUnsentLlmResponse(AgentContext context) {
        OutgoingResponse outgoing = context.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        if (outgoing != null && outgoing.getText() != null && !outgoing.getText().isBlank()) {
            log.info("[AgentLoop] Feedback guarantee: routing unsent OutgoingResponse");
            routeResponse(context);
            return true;
        }
        return false;
    }

    private boolean tryErrorFeedback(AgentContext context) {
        List<String> errors = collectErrors(context);
        if (errors.isEmpty() || !llmPort.isAvailable()) {
            return false;
        }

        String interpretation = tryInterpretErrors(context, errors);
        if (interpretation != null) {
            String message = preferencesService.getMessage("system.error.feedback", interpretation);
            log.info("[AgentLoop] Feedback guarantee: routing interpreted error");
            routeSyntheticAssistantResponse(context, message, "stop");
            return true;
        }

        return false;
    }

    private List<String> collectErrors(AgentContext context) {
        List<String> errors = new ArrayList<>();
        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        if (llmError != null) {
            errors.add(llmError);
        }
        // Read typed FailureEvent list
        for (FailureEvent failure : context.getFailures()) {
            if (failure.message() != null) {
                errors.add(failure.message());
            }
        }
        // Check RoutingOutcome for transport errors
        TurnOutcome outcome = context.getTurnOutcome();
        if (outcome != null && outcome.getRoutingOutcome() != null
                && outcome.getRoutingOutcome().getErrorMessage() != null) {
            errors.add(outcome.getRoutingOutcome().getErrorMessage());
        }
        return errors;
    }

    private String tryInterpretErrors(AgentContext context, List<String> errors) {
        try {
            String errorSummary = String.join("\n", errors);
            LlmRequest request = LlmRequest.builder()
                    .model(runtimeConfigService.getRoutingModel())
                    .reasoningEffort(runtimeConfigService.getRoutingModelReasoning())
                    .systemPrompt(
                            "You are a helpful assistant. Explain the following error in 1-2 sentences for the user.")
                    .messages(List.of(Message.builder()
                            .role("user")
                            .content(errorSummary)
                            .timestamp(clock.instant())
                            .build()))
                    .sessionId(context.getSession() != null ? context.getSession().getId() : null)
                    .traceId(context.getTraceContext() != null ? context.getTraceContext().getTraceId() : null)
                    .traceSpanId(context.getTraceContext() != null ? context.getTraceContext().getSpanId() : null)
                    .traceParentSpanId(
                            context.getTraceContext() != null ? context.getTraceContext().getParentSpanId() : null)
                    .traceRootKind(context.getTraceContext() != null ? context.getTraceContext().getRootKind() : null)
                    .build();
            LlmResponse response = llmPort.chat(request).get(10, TimeUnit.SECONDS);
            if (response != null && response.getContent() != null && !response.getContent().isBlank()) {
                return response.getContent();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("[AgentLoop] LLM error interpretation interrupted: {}", e.getMessage());
        } catch (ExecutionException | TimeoutException e) {
            log.debug("[AgentLoop] LLM error interpretation failed: {}", e.getMessage());
        }
        return null;
    }

    private void saveSessionWithTracing(AgentSession session, TraceContext parentTraceContext) {
        TraceContext saveSpan = startSessionSaveSpan(session, parentTraceContext);
        try {
            sessionService.save(session);
            finishSessionSaveSpan(session, saveSpan, TraceStatusCode.OK, null);
        } catch (RuntimeException e) {
            finishSessionSaveSpan(session, saveSpan, TraceStatusCode.ERROR, e.getMessage());
            log.error("Failed to save session {} during traced persistence", session != null ? session.getId() : null,
                    e);
        }
    }

    private TraceContext startSessionSaveSpan(AgentSession session, TraceContext parentTraceContext) {
        if (!runtimeConfigService.isTracingEnabled() || session == null || parentTraceContext == null) {
            return null;
        }
        return traceService.startSpan(session, parentTraceContext, "session.save", TraceSpanKind.STORAGE,
                clock.instant(), Map.of("session.id", session.getId()));
    }

    private void finishSessionSaveSpan(AgentSession session, TraceContext spanContext, TraceStatusCode statusCode,
            String statusMessage) {
        if (session == null || spanContext == null) {
            return;
        }
        traceService.finishSpan(session, spanContext, statusCode, statusMessage, clock.instant());
    }

    private TraceContext startChildSpan(AgentContext context, String spanName, TraceSpanKind spanKind,
            Map<String, Object> attributes) {
        if (context == null || context.getSession() == null || context.getTraceContext() == null
                || !runtimeConfigService.isTracingEnabled()) {
            return null;
        }
        return traceService.startSpan(context.getSession(), context.getTraceContext(), spanName, spanKind,
                clock.instant(), attributes);
    }

    private void finishChildSpan(AgentContext context, TraceContext spanContext, TraceStatusCode statusCode,
            String statusMessage) {
        if (context == null || context.getSession() == null || spanContext == null) {
            return;
        }
        traceService.finishSpan(context.getSession(), spanContext, statusCode, statusMessage, clock.instant());
    }

    private TraceStateSnapshot captureTraceState(AgentContext context) {
        if (context == null) {
            return new TraceStateSnapshot(null, "balanced", null, null, null, null, null);
        }
        String skillName = context.getActiveSkill() != null ? context.getActiveSkill().getName() : null;
        if ((skillName == null || skillName.isBlank()) && context.getAttributes() != null) {
            Object activeSkill = context.getAttributes().get(ContextAttributes.ACTIVE_SKILL_NAME);
            if (activeSkill instanceof String activeSkillName && !activeSkillName.isBlank()) {
                skillName = activeSkillName;
            }
        }
        String skillSource = stringAttribute(context, ContextAttributes.ACTIVE_SKILL_SOURCE);
        String tier = normalizeTierForTrace(context.getModelTier());
        String modelId = stringAttribute(context, ContextAttributes.MODEL_TIER_MODEL_ID);
        if (modelId == null || modelId.isBlank()) {
            modelId = resolveRouterModelId(tier);
        }
        String reasoning = stringAttribute(context, ContextAttributes.MODEL_TIER_REASONING);
        if (reasoning == null || reasoning.isBlank()) {
            reasoning = resolveRouterReasoning(tier);
        }
        return new TraceStateSnapshot(
                skillName,
                tier,
                modelId,
                reasoning,
                stringAttribute(context, ContextAttributes.MODEL_TIER_SOURCE),
                skillSource,
                context.getSkillTransitionRequest());
    }

    private void emitTraceStateEvents(AgentContext context, String systemName, TraceContext systemSpan,
            TraceStateSnapshot beforeState) {
        if (context == null || context.getSession() == null || systemSpan == null) {
            return;
        }
        TraceStateSnapshot afterState = captureTraceState(context);

        if (!Objects.equals(beforeState.transitionRequest(), afterState.transitionRequest())
                && afterState.transitionRequest() != null) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            String requesterSkill = beforeState.skillName() != null ? beforeState.skillName() : afterState.skillName();
            putIfPresent(attributes, "from_skill", requesterSkill);
            putIfPresent(attributes, "to_skill", afterState.transitionRequest().targetSkill());
            putIfPresent(attributes, "source", formatTransitionReason(afterState.transitionRequest()));
            putIfPresent(attributes, "reason", formatTransitionReason(afterState.transitionRequest()));
            emitTraceEvent(context, systemSpan, "skill.transition.requested", attributes);
        }

        if (!Objects.equals(beforeState.skillName(), afterState.skillName()) && afterState.skillName() != null) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            putIfPresent(attributes, "from_skill", beforeState.skillName());
            putIfPresent(attributes, "to_skill", afterState.skillName());
            String skillSource = afterState.skillSource() != null && !afterState.skillSource().isBlank()
                    ? afterState.skillSource()
                    : formatTransitionReason(beforeState.transitionRequest());
            putIfPresent(attributes, "source", skillSource);
            putIfPresent(attributes, "reason", skillSource);
            emitTraceEvent(context, systemSpan, "skill.transition.applied", attributes);
        }

        if ("ContextBuildingSystem".equals(systemName)) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            putIfPresent(attributes, "skill", afterState.skillName());
            putIfPresent(attributes, "tier", afterState.tier());
            putIfPresent(attributes, "model_id", afterState.modelId());
            putIfPresent(attributes, "reasoning", afterState.reasoning());
            putIfPresent(attributes, "source", afterState.source());
            emitTraceEvent(context, systemSpan, "tier.resolved", attributes);
        }

        if (!Objects.equals(beforeState.tier(), afterState.tier())
                || !Objects.equals(beforeState.modelId(), afterState.modelId())) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            putIfPresent(attributes, "from_tier", beforeState.tier());
            putIfPresent(attributes, "to_tier", afterState.tier());
            putIfPresent(attributes, "from_model_id", beforeState.modelId());
            putIfPresent(attributes, "to_model_id", afterState.modelId());
            putIfPresent(attributes, "skill", afterState.skillName());
            putIfPresent(attributes, "source", afterState.source());
            emitTraceEvent(context, systemSpan, "tier.transition", attributes);
        }
    }

    private void emitTraceEvent(AgentContext context, TraceContext spanContext, String eventName,
            Map<String, Object> attributes) {
        if (traceService == null || context == null || context.getSession() == null || spanContext == null) {
            return;
        }
        traceService.appendEvent(context.getSession(), spanContext, eventName, clock.instant(), attributes);
    }

    private String normalizeTierForTrace(String tier) {
        if (tier == null || tier.isBlank() || "default".equalsIgnoreCase(tier)) {
            return "balanced";
        }
        String normalized = ModelTierCatalog.normalizeTierId(tier);
        return normalized != null ? normalized : tier;
    }

    private String stringAttribute(AgentContext context, String key) {
        if (context == null || context.getAttributes() == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = context.getAttributes().get(key);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue : null;
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (target == null || key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        target.put(key, value);
    }

    private String formatTransitionReason(SkillTransitionRequest request) {
        if (request == null || request.reason() == null) {
            return null;
        }
        return request.reason().name().toLowerCase(Locale.ROOT);
    }

    private String resolveRouterModelId(String tier) {
        if (runtimeConfigService == null) {
            return null;
        }
        return switch (tier) {
        case "smart" -> runtimeConfigService.getSmartModel();
        case "deep" -> runtimeConfigService.getDeepModel();
        case "coding" -> runtimeConfigService.getCodingModel();
        case "routing" -> runtimeConfigService.getRoutingModel();
        case "balanced" -> runtimeConfigService.getBalancedModel();
        default -> {
            RuntimeConfig.TierBinding binding = runtimeConfigService.getModelTierBinding(tier);
            yield binding != null ? binding.getModel() : null;
        }
        };
    }

    private String resolveRouterReasoning(String tier) {
        if (runtimeConfigService == null) {
            return null;
        }
        return switch (tier) {
        case "smart" -> runtimeConfigService.getSmartModelReasoning();
        case "deep" -> runtimeConfigService.getDeepModelReasoning();
        case "coding" -> runtimeConfigService.getCodingModelReasoning();
        case "routing" -> runtimeConfigService.getRoutingModelReasoning();
        case "balanced" -> runtimeConfigService.getBalancedModelReasoning();
        default -> {
            RuntimeConfig.TierBinding binding = runtimeConfigService.getModelTierBinding(tier);
            yield binding != null ? binding.getReasoning() : null;
        }
        };
    }

    private Map<String, String> buildTraceMdcContext(TraceContext spanContext, AgentContext context) {
        if (spanContext == null) {
            return Map.of();
        }
        Map<String, Object> source = context != null ? context.getAttributes() : Map.of();
        return TraceMdcSupport.buildMdcContext(spanContext, source);
    }

    private byte[] serializeSnapshotPayload(Object payload) {
        if (payload == null) {
            return new byte[0];
        }
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception e) { // NOSONAR - tracing must not break request handling
            return String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
        }
    }

    private boolean isAutoModeContext(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return false;
        }
        Message last = context.getMessages().get(context.getMessages().size() - 1);
        return last.getMetadata() != null && Boolean.TRUE.equals(last.getMetadata().get(ContextAttributes.AUTO_MODE));
    }

    private String determineAutoRunStatus(AgentContext context) {
        boolean reflectionActive = Boolean.TRUE.equals(context.getAttribute(ContextAttributes.AUTO_REFLECTION_ACTIVE));
        boolean failed = hasAutoRunFailure(context);
        if (reflectionActive) {
            return failed ? "REFLECTION_FAILED" : "REFLECTION_COMPLETED";
        }
        return failed ? "FAILED" : "COMPLETED";
    }

    private String determineAutoRunFinishReason(AgentContext context) {
        TurnOutcome outcome = context.getTurnOutcome();
        FinishReason finishReason = outcome != null ? outcome.getFinishReason() : null;
        if (finishReason != null) {
            return finishReason.name();
        }
        if (context.getAttribute(ContextAttributes.LLM_ERROR) != null) {
            return FinishReason.ERROR.name();
        }
        return FinishReason.SUCCESS.name();
    }

    private boolean hasAutoRunFailure(AgentContext context) {
        if (context == null) {
            return true;
        }
        if (context.getAttribute(ContextAttributes.LLM_ERROR) != null) {
            return true;
        }
        if (!context.getFailures().isEmpty()) {
            return true;
        }
        TurnOutcome outcome = context.getTurnOutcome();
        return outcome != null && outcome.getFinishReason() == FinishReason.ERROR;
    }

    private String determineAssistantText(AgentContext context) {
        if (context == null) {
            return null;
        }
        TurnOutcome outcome = context.getTurnOutcome();
        if (outcome != null && outcome.getAssistantText() != null && !outcome.getAssistantText().isBlank()) {
            return outcome.getAssistantText();
        }
        OutgoingResponse outgoingResponse = context.getOutgoingResponse();
        if (outgoingResponse != null && outgoingResponse.getText() != null && !outgoingResponse.getText().isBlank()) {
            return outgoingResponse.getText();
        }
        return null;
    }

    private String determineFailureSummary(AgentContext context) {
        if (context == null) {
            return null;
        }
        if (!context.getFailures().isEmpty()) {
            FailureEvent failure = context.getFailures().get(context.getFailures().size() - 1);
            return failure.message();
        }
        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        if (llmError != null && !llmError.isBlank()) {
            return llmError;
        }
        TurnOutcome outcome = context.getTurnOutcome();
        if (outcome != null && outcome.getRoutingOutcome() != null) {
            return outcome.getRoutingOutcome().getErrorMessage();
        }
        return null;
    }

    private String determineActiveSkillName(AgentContext context) {
        if (context == null) {
            return null;
        }
        String explicit = context.getAttribute(ContextAttributes.ACTIVE_SKILL_NAME);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if (context.getActiveSkill() != null && context.getActiveSkill().getName() != null
                && !context.getActiveSkill().getName().isBlank()) {
            return context.getActiveSkill().getName();
        }
        return null;
    }

    private String buildFailureFingerprint(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        return summary.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private List<AgentSystem> getSortedSystems() {
        if (sortedSystems == null) {
            sortedSystems = new ArrayList<>(systems);
            sortedSystems.sort(Comparator.comparingInt(AgentSystem::getOrder));

            log.info("[AgentLoop] systems in pipeline: {}",
                    sortedSystems.stream().map(AgentSystem::getName).toList());

            routingSystem = sortedSystems.stream()
                    .filter(ResponseRoutingSystem.class::isInstance)
                    .findFirst()
                    .orElse(null);
        }
        log.debug("[AgentLoop] routingSystem resolved: {}",
                routingSystem != null ? routingSystem.getClass().getName() : "<null>");
        return sortedSystems;
    }

    private record TraceStateSnapshot(
            String skillName,
            String tier,
            String modelId,
            String reasoning,
            String source,
            String skillSource,
            SkillTransitionRequest transitionRequest) {
    }

    public record InboundMessageEvent(Message message, Instant timestamp) {

        public InboundMessageEvent(Message message) {
            this(message, Instant.now());
        }
    }
}
