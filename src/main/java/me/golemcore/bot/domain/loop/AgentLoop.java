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

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.domain.system.AgentSystem;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.RateLimitPort;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.RateLimitResult;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Core orchestrator managing the agent processing pipeline for incoming
 * messages. Coordinates session management, rate limiting, system pipeline
 * execution, and channel responses. Systems are executed in order based on
 * their @Order annotation (InputSanitization -> SkillRouting -> ContextBuilding
 * -> LlmExecution -> ToolExecution -> MemoryPersist -> ResponseRouting).
 * Manages typing indicators and handles async message processing.
 */
@Component
@Slf4j
public class AgentLoop {

    private final SessionPort sessionService;
    private final RateLimitPort rateLimiter;
    private final BotProperties properties;
    private final List<AgentSystem> systems;
    private final UserPreferencesService preferencesService;
    private final LlmPort llmPort;
    private final Clock clock;
    private final Map<String, ChannelPort> channelRegistry = new ConcurrentHashMap<>();
    private final ScheduledExecutorService typingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "typing-indicator");
        t.setDaemon(true);
        return t;
    });

    private static final long TYPING_INTERVAL_SECONDS = 4;

    private List<AgentSystem> sortedSystems;

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
            BotProperties properties, List<AgentSystem> systems,
            List<ChannelPort> channelPorts, UserPreferencesService preferencesService,
            LlmPort llmPort, Clock clock) {
        this.sessionService = sessionService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.systems = systems;
        this.preferencesService = preferencesService;
        this.llmPort = llmPort;
        this.clock = clock;
        for (ChannelPort port : channelPorts) {
            channelRegistry.put(port.getChannelType(), port);
        }
    }

    public void processMessage(Message message) {
        log.info("=== INCOMING MESSAGE ===");
        log.info("Channel: {}, Chat: {}, Sender: {}",
                message.getChannelType(), message.getChatId(), message.getSenderId());
        log.debug("Content: {}", truncate(message.getContent(), 200));

        boolean isAuto = message.getMetadata() != null && Boolean.TRUE.equals(message.getMetadata().get("auto.mode"));
        if (!isAuto) {
            RateLimitResult rateLimit = rateLimiter.tryConsume();
            if (!rateLimit.isAllowed()) {
                log.warn("Rate limit exceeded");
                return;
            }
        }

        AgentSession session = sessionService.getOrCreate(
                message.getChannelType(),
                message.getChatId());
        log.debug("Session: {}, messages in history: {}", session.getId(), session.getMessages().size());

        session.addMessage(message);

        AgentContext context = AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>(session.getMessages()))
                .maxIterations(properties.getAgent().getMaxIterations())
                .currentIteration(0)
                .build();

        ChannelPort channel = channelRegistry.get(message.getChannelType());
        ScheduledFuture<?> typingTask = null;
        if (channel != null && message.getChatId() != null) {
            String chatId = message.getChatId();
            typingTask = typingExecutor.scheduleAtFixedRate(
                    () -> channel.showTyping(chatId),
                    0, TYPING_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }

        log.debug("Starting agent loop (max iterations: {})", context.getMaxIterations());
        try {
            runLoop(context);
            ensureFeedback(context);
        } finally {
            if (typingTask != null) {
                typingTask.cancel(false);
            }
            // Guarantee ThreadLocal cleanup even if systems throw unexpectedly
            AgentContextHolder.clear();
        }

        sessionService.save(session);
        log.info("=== MESSAGE PROCESSING COMPLETE ===");
    }

    private String truncate(String text, int maxLen) {
        if (text == null)
            return "<null>";
        if (text.length() <= maxLen)
            return text;
        return text.substring(0, maxLen) + "...";
    }

    @EventListener
    public void onInboundMessage(InboundMessageEvent event) {
        processMessage(event.getMessage());
    }

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
                try {
                    context = system.process(context);
                    log.debug("System '{}' completed in {}ms", system.getName(), clock.millis() - startMs);
                } catch (Exception e) {
                    log.error("System '{}' FAILED after {}ms: {}", system.getName(), clock.millis() - startMs,
                            e.getMessage(), e);
                    context.setAttribute(ContextAttributes.SYSTEM_ERROR + system.getName(), e.getMessage());
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
            context.setFinalAnswerReady(false);
            context.clearSkillTransitionRequest(); // reset transition
            context.getToolResults().clear();
        }

        if (reachedLimit) {
            context.setAttribute(ContextAttributes.ITERATION_LIMIT_REACHED, true);
            String limitMessage = preferencesService.getMessage("system.iteration.limit", maxIterations);

            context.getSession().addMessage(Message.builder()
                    .role("assistant")
                    .content(limitMessage)
                    .channelType(context.getSession().getChannelType())
                    .chatId(context.getSession().getChatId())
                    .timestamp(clock.instant())
                    .build());
            notifyIterationLimit(context, limitMessage);
        }
    }

    private void notifyIterationLimit(AgentContext context, String message) {
        AgentSession session = context.getSession();
        ChannelPort channel = channelRegistry.get(session.getChannelType());
        if (channel != null) {
            try {
                channel.sendMessage(session.getChatId(), message).get();
                context.setAttribute(ContextAttributes.RESPONSE_SENT, true);
                log.info("[AgentLoop] Sent iteration limit notification");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[AgentLoop] Failed to send iteration limit notification: {}", e.getMessage());
            } catch (ExecutionException e) {
                log.error("[AgentLoop] Failed to send iteration limit notification: {}", e.getMessage());
            }
        }
    }

    private boolean shouldContinueLoop(AgentContext context) {
        // SkillPipelineSystem sets skillTransitionRequest to request a new iteration.
        var transition = context.getSkillTransitionRequest();
        return transition != null && transition.targetSkill() != null;
    }

    private void ensureFeedback(AgentContext context) {
        Boolean responseSent = context.getAttribute(ContextAttributes.RESPONSE_SENT);
        if (Boolean.TRUE.equals(responseSent)) {
            return;
        }

        if (isAutoModeContext(context)) {
            return;
        }

        AgentSession session = context.getSession();
        ChannelPort channel = channelRegistry.get(session.getChannelType());
        if (channel == null) {
            return;
        }

        if (tryUnsentLlmResponse(context, channel, session)) {
            return;
        }

        if (tryErrorFeedback(context, channel, session)) {
            return;
        }

        // Last resort: generic feedback
        String generic = preferencesService.getMessage("system.error.generic.feedback");
        log.info("[AgentLoop] Feedback guarantee: sending generic feedback");
        trySendAndRecord(channel, session, generic);
    }

    private boolean tryUnsentLlmResponse(AgentContext context, ChannelPort channel, AgentSession session) {
        LlmResponse llmResponse = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        if (llmResponse != null && llmResponse.getContent() != null && !llmResponse.getContent().isBlank()) {
            log.info("[AgentLoop] Feedback guarantee: sending unsent LLM response");
            return trySendAndRecord(channel, session, llmResponse.getContent());
        }
        return false;
    }

    private boolean tryErrorFeedback(AgentContext context, ChannelPort channel, AgentSession session) {
        List<String> errors = collectErrors(context);
        if (errors.isEmpty() || !llmPort.isAvailable()) {
            return false;
        }

        String interpretation = tryInterpretErrors(errors);
        if (interpretation != null) {
            String message = preferencesService.getMessage("system.error.feedback", interpretation);
            log.info("[AgentLoop] Feedback guarantee: sending LLM-interpreted error");
            if (trySendAndRecord(channel, session, message)) {
                return true;
            }
        }

        // LLM interpretation failed — send formatted error
        String formatted = preferencesService.getMessage("system.error.feedback", String.join("; ", errors));
        log.info("[AgentLoop] Feedback guarantee: sending formatted error");
        return trySendAndRecord(channel, session, formatted);
    }

    private List<String> collectErrors(AgentContext context) {
        List<String> errors = new ArrayList<>();
        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        if (llmError != null) {
            errors.add(llmError);
        }
        for (Map.Entry<String, Object> entry : context.getAttributes().entrySet()) {
            if (entry.getKey().startsWith(ContextAttributes.SYSTEM_ERROR)
                    && entry.getValue() instanceof String errorMsg) {
                errors.add(errorMsg);
            }
        }
        String routingError = context.getAttribute(ContextAttributes.ROUTING_ERROR);
        if (routingError != null) {
            errors.add(routingError);
        }
        return errors;
    }

    private String tryInterpretErrors(List<String> errors) {
        try {
            String errorSummary = String.join("\n", errors);
            LlmRequest request = LlmRequest.builder()
                    .model(properties.getRouter().getBalancedModel())
                    .systemPrompt(
                            "You are a helpful assistant. Explain the following error in 1-2 sentences for the user.")
                    .messages(List.of(Message.builder()
                            .role("user")
                            .content(errorSummary)
                            .timestamp(clock.instant())
                            .build()))
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

    private boolean trySendAndRecord(ChannelPort channel, AgentSession session, String content) {
        try {
            channel.sendMessage(session.getChatId(), content).get(10, TimeUnit.SECONDS);
            addFeedbackMessage(session, content);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[AgentLoop] Feedback send interrupted: {}", e.getMessage());
            return false;
        } catch (ExecutionException | TimeoutException e) {
            log.error("[AgentLoop] Feedback send failed: {}", e.getMessage());
            return false;
        }
    }

    private void addFeedbackMessage(AgentSession session, String content) {
        session.addMessage(Message.builder()
                .role("assistant")
                .content(content)
                .channelType(session.getChannelType())
                .chatId(session.getChatId())
                .timestamp(clock.instant())
                .build());
    }

    private boolean isAutoModeContext(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return false;
        }
        Message last = context.getMessages().get(context.getMessages().size() - 1);
        return last.getMetadata() != null && Boolean.TRUE.equals(last.getMetadata().get("auto.mode"));
    }

    private List<AgentSystem> getSortedSystems() {
        if (sortedSystems == null) {
            sortedSystems = new ArrayList<>(systems);
            sortedSystems.sort(Comparator.comparingInt(AgentSystem::getOrder));
        }
        return sortedSystems;
    }

    public record InboundMessageEvent(Message message, Instant timestamp) {
        public InboundMessageEvent(Message message) {
            this(message, Instant.now());
        }

        public Message getMessage() {
            return message;
        }
    }
}
