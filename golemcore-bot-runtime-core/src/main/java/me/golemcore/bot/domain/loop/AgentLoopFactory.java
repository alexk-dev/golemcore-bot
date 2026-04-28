package me.golemcore.bot.domain.loop;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import me.golemcore.bot.domain.events.RuntimeEventService;
import me.golemcore.bot.domain.context.hygiene.ContextHygieneService;
import me.golemcore.bot.domain.runtimeconfig.ModelRoutingConfigView;
import me.golemcore.bot.domain.runtimeconfig.TracingConfigView;
import me.golemcore.bot.domain.runtimeconfig.TurnRuntimeConfigView;
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

    public AgentLoop create(AgentLoopPorts ports, AgentLoopRuntimeServices services, List<AgentSystem> systems) {
        AgentLoopPorts safePorts = Objects.requireNonNull(ports, "ports must not be null");
        AgentLoopRuntimeServices safeServices = Objects.requireNonNull(services, "services must not be null");
        AgentPipelinePlan plan = new AgentPipelinePlanFactory().create(systems);
        AgentPipelineRunner pipelineRunner = new AgentPipelineRunner(plan, safeServices.modelRoutingConfigView(),
                safeServices.tracingConfigView(), safeServices.preferencesService(), safeServices.clock(),
                safeServices.traceService(), safeServices.contextHygieneService());
        return new AgentLoop(safePorts.sessionService(), safePorts.rateLimiter(), new AgentLoop.AgentLoopCollaborators(
                new TurnFeedbackCoordinator(safePorts.channelRuntimePort(), safeServices.preferencesService()),
                new TurnPersistenceGuard(safePorts.sessionService(), safeServices.tracingConfigView(),
                        safeServices.traceService(), safeServices.contextHygieneService(), safeServices.clock(),
                        safeServices.runtimeEventService()),
                new TurnContextFactory(safeServices.turnRuntimeConfigView(), safeServices.tracingConfigView(),
                        safeServices.traceService(), safeServices.clock(), safeServices.traceSnapshotCodecPort()),
                pipelineRunner,
                new TurnFeedbackGuarantee(new UnsentResponseDetector(),
                        new SafeErrorFeedbackRenderer(safeServices.preferencesService()),
                        new GenericFallbackRouter(pipelineRunner), new OptionalLlmErrorExplanationProvider(
                                safeServices.modelRoutingConfigView(), safePorts.llmPort(), safeServices.clock())),
                new AutoRunOutcomeRecorder()));
    }

    public record AgentLoopPorts(SessionPort sessionService, RateLimitPort rateLimiter,
            ChannelRuntimePort channelRuntimePort, LlmPort llmPort) {

        public AgentLoopPorts {
            Objects.requireNonNull(sessionService, "sessionService must not be null");
            Objects.requireNonNull(rateLimiter, "rateLimiter must not be null");
            Objects.requireNonNull(channelRuntimePort, "channelRuntimePort must not be null");
        }
    }

    public record AgentLoopRuntimeServices(ModelRoutingConfigView modelRoutingConfigView,
            TracingConfigView tracingConfigView, TurnRuntimeConfigView turnRuntimeConfigView,
            UserPreferencesService preferencesService, Clock clock, TraceService traceService,
            TraceSnapshotCodecPort traceSnapshotCodecPort, ContextHygieneService contextHygieneService,
            RuntimeEventService runtimeEventService) {

        public AgentLoopRuntimeServices {
            Objects.requireNonNull(modelRoutingConfigView, "modelRoutingConfigView must not be null");
            Objects.requireNonNull(tracingConfigView, "tracingConfigView must not be null");
            Objects.requireNonNull(turnRuntimeConfigView, "turnRuntimeConfigView must not be null");
            Objects.requireNonNull(preferencesService, "preferencesService must not be null");
            Objects.requireNonNull(clock, "clock must not be null");
            Objects.requireNonNull(traceService, "traceService must not be null");
            Objects.requireNonNull(traceSnapshotCodecPort, "traceSnapshotCodecPort must not be null");
            Objects.requireNonNull(contextHygieneService, "contextHygieneService must not be null");
            Objects.requireNonNull(runtimeEventService, "runtimeEventService must not be null");
        }
    }
}
