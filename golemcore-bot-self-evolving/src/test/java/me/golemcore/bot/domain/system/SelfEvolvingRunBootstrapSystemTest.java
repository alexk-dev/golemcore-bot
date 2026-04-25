package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.selfevolving.RunRecord;
import me.golemcore.bot.domain.selfevolving.run.SelfEvolvingRunService;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SelfEvolvingRunBootstrapSystemTest {

    private SelfEvolvingRuntimeConfigPort runtimeConfigPort;
    private SelfEvolvingRunService selfEvolvingRunService;
    private SelfEvolvingRunBootstrapSystem system;

    @BeforeEach
    void setUp() {
        runtimeConfigPort = mock(SelfEvolvingRuntimeConfigPort.class);
        selfEvolvingRunService = mock(SelfEvolvingRunService.class);
        system = new SelfEvolvingRunBootstrapSystem(runtimeConfigPort, selfEvolvingRunService);
    }

    @Test
    void shouldStartRunWhenEnabledAndSessionExists() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-1").chatId("chat-1").build())
                .build();
        when(runtimeConfigPort.isSelfEvolvingEnabled()).thenReturn(true);
        when(selfEvolvingRunService.startRun(context)).thenReturn(RunRecord.builder()
                .id("run-1")
                .artifactBundleId("bundle-1")
                .build());

        AgentContext result = system.process(context);

        verify(selfEvolvingRunService).startRun(context);
        assertEquals("run-1", result.getAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID));
        assertEquals("bundle-1", result.getAttribute(ContextAttributes.SELF_EVOLVING_ARTIFACT_BUNDLE_ID));
        assertEquals(Boolean.FALSE, result.getAttribute(ContextAttributes.SELF_EVOLVING_ANALYSIS_COMPLETED));
    }

    @Test
    void shouldSkipWhenFeatureIsDisabled() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-2").chatId("chat-2").build())
                .build();
        when(runtimeConfigPort.isSelfEvolvingEnabled()).thenReturn(false);

        AgentContext result = system.process(context);

        verify(selfEvolvingRunService, never()).startRun(context);
        assertNull(result.getAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID));
        assertFalse(system.shouldProcess(context));
    }

    @Test
    void shouldSkipWhenSessionIsMissing() {
        AgentContext context = AgentContext.builder().build();
        when(runtimeConfigPort.isSelfEvolvingEnabled()).thenReturn(true);

        AgentContext result = system.process(context);

        verify(selfEvolvingRunService, never()).startRun(context);
        assertNull(result.getAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID));
    }

    @Test
    void shouldNotRestartExistingRun() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-3").chatId("chat-3").build())
                .build();
        context.setAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID, "run-existing");
        when(runtimeConfigPort.isSelfEvolvingEnabled()).thenReturn(true);

        AgentContext result = system.process(context);

        verify(selfEvolvingRunService, never()).startRun(context);
        assertEquals("run-existing", result.getAttribute(ContextAttributes.SELF_EVOLVING_RUN_ID));
    }
}
