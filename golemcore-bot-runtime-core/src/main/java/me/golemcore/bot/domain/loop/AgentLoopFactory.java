package me.golemcore.bot.domain.loop;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import me.golemcore.bot.domain.events.RuntimeEventService;
import me.golemcore.bot.domain.context.hygiene.ContextHygieneService;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import me.golemcore.bot.domain.tracing.TraceService;
import me.golemcore.bot.domain.runtimeconfig.UserPreferencesService;
import me.golemcore.bot.domain.system.AgentSystem;
import me.golemcore.bot.port.outbound.ChannelRuntimePort;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.RateLimitPort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.TraceSnapshotCodecPort;

/**
 * Assembles the internal runtime-core collaborator graph for {@link AgentLoop}.
 */
public final class AgentLoopFactory {

    public AgentLoop create(SessionPort sessionService, RateLimitPort rateLimiter, List<AgentSystem> systems,
            ChannelRuntimePort channelRuntimePort, RuntimeConfigService runtimeConfigService,
            UserPreferencesService preferencesService, LlmPort llmPort, Clock clock, TraceService traceService,
            TraceSnapshotCodecPort traceSnapshotCodecPort, ContextHygieneService contextHygieneService,
            RuntimeEventService runtimeEventService) {
        ContextHygieneService safeContextHygieneService = Objects.requireNonNull(contextHygieneService,
                "contextHygieneService must not be null");
        AgentPipelinePlan plan = new AgentPipelinePlanFactory().create(systems);
        AgentPipelineRunner pipelineRunner = new AgentPipelineRunner(plan, runtimeConfigService, runtimeConfigService,
                preferencesService, clock, traceService, safeContextHygieneService);
        return new AgentLoop(sessionService, rateLimiter,
                new TurnFeedbackCoordinator(channelRuntimePort, preferencesService),
                new TurnPersistenceGuard(sessionService, runtimeConfigService, traceService, safeContextHygieneService,
                        clock, runtimeEventService),
                new TurnContextFactory(
                        runtimeConfigService, runtimeConfigService, traceService, clock, traceSnapshotCodecPort),
                pipelineRunner,
                new TurnFeedbackGuarantee(new UnsentResponseDetector(),
                        new SafeErrorFeedbackRenderer(preferencesService), new GenericFallbackRouter(pipelineRunner),
                        new OptionalLlmErrorExplanationProvider(runtimeConfigService, llmPort, clock)),
                new AutoRunOutcomeRecorder());
    }
}
