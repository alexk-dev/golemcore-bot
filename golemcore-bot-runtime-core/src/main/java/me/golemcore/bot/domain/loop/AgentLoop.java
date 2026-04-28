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
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.PersistenceOutcome;
import me.golemcore.bot.domain.model.RateLimitResult;
import me.golemcore.bot.domain.autorun.AutoRunContextSupport;
import me.golemcore.bot.domain.tracing.MdcSupport;
import me.golemcore.bot.port.outbound.RateLimitPort;
import me.golemcore.bot.port.outbound.SessionPort;

/**
 * Top-level turn lifecycle facade. Detailed admission, context construction, pipeline execution, feedback guarantee,
 * and persistence live in named collaborators.
 */
@Slf4j
public class AgentLoop {

    private final SessionPort sessionService;
    private final RateLimitPort rateLimiter;
    private final AgentLoopCollaborators collaborators;

    AgentLoop(SessionPort sessionService, RateLimitPort rateLimiter, AgentLoopCollaborators collaborators) {
        this.sessionService = Objects.requireNonNull(sessionService, "sessionService must not be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter must not be null");
        this.collaborators = Objects.requireNonNull(collaborators, "collaborators must not be null");
    }

    @PreDestroy
    public void shutdown() {
        collaborators.feedbackCoordinator().shutdown();
    }

    public AgentContext processMessage(Message message) {
        Objects.requireNonNull(message, "message must not be null");
        Message preparedMessage = collaborators.contextFactory().prepareMessage(message);
        Map<String, String> mdcContext = collaborators.contextFactory().buildMdcContext(preparedMessage);
        try (MdcSupport.Scope ignored = MdcSupport.withContext(mdcContext)) {
            logInbound(preparedMessage);
            if (!admit(preparedMessage)) {
                return null;
            }

            AgentSession session = sessionService.getOrCreate(preparedMessage.getChannelType(),
                    preparedMessage.getChatId());
            log.debug("Session: {}, messages in history: {}", session.getId(), session.getMessages().size());

            TurnContextFactory.PreparedTurn turn = collaborators.contextFactory().create(session, preparedMessage);
            AgentContext context = turn.context();
            collaborators.pipelineRunner().initializeRoutingSystem();
            log.debug("Starting agent loop (max iterations: {})", context.getMaxIterations());

            try {
                TurnFeedbackCoordinator.TypingHandle typingHandle = collaborators.feedbackCoordinator()
                        .startTyping(preparedMessage);
                try (typingHandle) {
                    context = collaborators.pipelineRunner().run(context);
                    context = collaborators.feedbackGuarantee().ensure(context);
                    collaborators.outcomeRecorder().record(preparedMessage, context);
                }
            } finally {
                AgentContextHolder.clear();
                PersistenceOutcome persistenceOutcome = collaborators.persistenceGuard().persist(context, session,
                        context.getTraceContext() != null ? context.getTraceContext() : turn.rootTraceContext());
                log.debug("Turn persistence outcome: saved={}, sessionId={}, errorCode={}", persistenceOutcome.saved(),
                        persistenceOutcome.sessionId(), persistenceOutcome.errorCode());
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
        collaborators.feedbackCoordinator().notifyRateLimited(message);
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

    record AgentLoopCollaborators(TurnFeedbackCoordinator feedbackCoordinator, TurnPersistenceGuard persistenceGuard,
            TurnContextFactory contextFactory, AgentPipelineRunner pipelineRunner,
            TurnFeedbackGuarantee feedbackGuarantee, AutoRunOutcomeRecorder outcomeRecorder) {

        AgentLoopCollaborators {
            Objects.requireNonNull(feedbackCoordinator, "feedbackCoordinator must not be null");
            Objects.requireNonNull(persistenceGuard, "persistenceGuard must not be null");
            Objects.requireNonNull(contextFactory, "contextFactory must not be null");
            Objects.requireNonNull(pipelineRunner, "pipelineRunner must not be null");
            Objects.requireNonNull(feedbackGuarantee, "feedbackGuarantee must not be null");
            Objects.requireNonNull(outcomeRecorder, "outcomeRecorder must not be null");
        }
    }

    public record InboundMessageEvent(Message message, Instant timestamp) {

        public InboundMessageEvent(Message message) {
            this(message, Instant.now());
        }
    }
}
