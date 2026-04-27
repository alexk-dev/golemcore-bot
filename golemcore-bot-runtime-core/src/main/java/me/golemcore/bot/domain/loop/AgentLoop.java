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

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RateLimitResult;
import me.golemcore.bot.domain.service.AutoRunContextSupport;
import me.golemcore.bot.domain.service.ContextHygieneService;
import me.golemcore.bot.domain.service.MdcSupport;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.TraceService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.system.AgentSystem;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.RateLimitPort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.TraceSnapshotCodecPort;

/**
 * Top-level turn lifecycle facade. Detailed admission, context construction, pipeline execution, feedback guarantee,
 * and persistence live in named collaborators.
 */
@Slf4j
public class AgentLoop {

    private final SessionPort sessionService;
    private final RateLimitPort rateLimiter;
    private final TurnFeedbackCoordinator feedbackCoordinator;
    private final TurnPersistenceGuard persistenceGuard;
    private final TurnContextFactory contextFactory;
    private final AgentPipelineRunner pipelineRunner;
    private final TurnFeedbackGuarantee feedbackGuarantee;
    private final AutoRunOutcomeRecorder outcomeRecorder;

    public AgentLoop(SessionPort sessionService, RateLimitPort rateLimiter, List<AgentSystem> systems,
            ChannelRuntimePort channelRuntimePort, RuntimeConfigService runtimeConfigService,
            UserPreferencesService preferencesService, LlmPort llmPort, Clock clock, TraceService traceService,
            TraceSnapshotCodecPort traceSnapshotCodecPort, ContextHygieneService contextHygieneService) {
        this.sessionService = sessionService;
        this.rateLimiter = rateLimiter;
        ContextHygieneService safeContextHygieneService = Objects.requireNonNull(contextHygieneService,
                "contextHygieneService must not be null");
        this.feedbackCoordinator = new TurnFeedbackCoordinator(channelRuntimePort, preferencesService);
        this.persistenceGuard = new TurnPersistenceGuard(sessionService, runtimeConfigService, traceService,
                safeContextHygieneService, clock);
        this.contextFactory = new TurnContextFactory(runtimeConfigService, traceService, clock, traceSnapshotCodecPort);
        this.pipelineRunner = new AgentPipelineRunner(systems, runtimeConfigService, preferencesService, clock,
                traceService, safeContextHygieneService);
        this.feedbackGuarantee = new TurnFeedbackGuarantee(preferencesService, runtimeConfigService, llmPort, clock,
                pipelineRunner);
        this.outcomeRecorder = new AutoRunOutcomeRecorder();
    }

    @PreDestroy
    public void shutdown() {
        feedbackCoordinator.shutdown();
    }

    public AgentContext processMessage(Message message) {
        Objects.requireNonNull(message, "message must not be null");
        Message preparedMessage = contextFactory.prepareMessage(message);
        Map<String, String> mdcContext = contextFactory.buildMdcContext(preparedMessage);
        try (MdcSupport.Scope ignored = MdcSupport.withContext(mdcContext)) {
            logInbound(preparedMessage);
            if (!admit(preparedMessage)) {
                return null;
            }

            AgentSession session = sessionService.getOrCreate(preparedMessage.getChannelType(),
                    preparedMessage.getChatId());
            log.debug("Session: {}, messages in history: {}", session.getId(), session.getMessages().size());

            TurnContextFactory.PreparedTurn turn = contextFactory.create(session, preparedMessage);
            AgentContext context = turn.context();
            pipelineRunner.initializeRoutingSystem();
            log.debug("Starting agent loop (max iterations: {})", context.getMaxIterations());

            try {
                TurnFeedbackCoordinator.TypingHandle typingHandle = feedbackCoordinator.startTyping(preparedMessage);
                try (typingHandle) {
                    context = pipelineRunner.run(context);
                    context = feedbackGuarantee.ensure(context);
                    outcomeRecorder.record(preparedMessage, context);
                }
            } finally {
                AgentContextHolder.clear();
                persistenceGuard.persist(context, session,
                        context.getTraceContext() != null ? context.getTraceContext() : turn.rootTraceContext());
            }

            log.info("=== MESSAGE PROCESSING COMPLETE ===");
            return context;
        }
    }

    private boolean admit(Message message) {
        boolean auto = AutoRunContextSupport.isAutoMessage(message);
        boolean internal = message.isInternalMessage();
        if (auto || internal) {
            return true;
        }
        RateLimitResult rateLimit = rateLimiter.tryConsume();
        if (rateLimit.isAllowed()) {
            return true;
        }
        log.warn("Rate limit exceeded");
        feedbackCoordinator.notifyRateLimited(message);
        return false;
    }

    private void logInbound(Message message) {
        log.info("=== INCOMING MESSAGE ===");
        log.info("Channel: {}, Chat: {}, Sender: {}", message.getChannelType(), message.getChatId(),
                message.getSenderId());
        log.debug("Content: {}", truncate(message.getContent(), 200));
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "<null>";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    public record InboundMessageEvent(Message message, Instant timestamp) {

        public InboundMessageEvent(Message message) {
            this(message, Instant.now());
        }
    }
}
