package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.loop.AgentLoop;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.SessionPort;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SessionRunCoordinatorDelayedActionsTest {

    @Test
    void shouldCancelDelayedActionsForRegularUserInboundOnly() {
        SessionPort sessionPort = mock(SessionPort.class);
        AgentLoop agentLoop = mock(AgentLoop.class);
        RuntimeEventService runtimeEventService = mock(RuntimeEventService.class);
        RuntimeConfigService runtimeConfigService = mock(RuntimeConfigService.class);
        DelayedSessionActionService delayedActionService = mock(DelayedSessionActionService.class);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            SessionRunCoordinator coordinator = new SessionRunCoordinator(sessionPort, agentLoop, executor,
                    runtimeEventService, runtimeConfigService, delayedActionService);

            Message regular = Message.builder()
                    .role("user")
                    .content("hello")
                    .channelType("telegram")
                    .chatId("conv-1")
                    .build();
            coordinator.enqueue(regular);
            verify(delayedActionService).cancelOnUserActivity(regular);

            Message internal = Message.builder()
                    .role("user")
                    .content("internal")
                    .channelType("telegram")
                    .chatId("conv-1")
                    .metadata(Map.of(ContextAttributes.MESSAGE_INTERNAL, true))
                    .build();
            coordinator.enqueue(internal);
            verify(delayedActionService, never()).cancelOnUserActivity(internal);

            Message auto = Message.builder()
                    .role("user")
                    .content("auto")
                    .channelType("telegram")
                    .chatId("conv-1")
                    .metadata(new LinkedHashMap<>(Map.of(ContextAttributes.AUTO_MODE, true)))
                    .build();
            coordinator.enqueue(auto);
            verify(delayedActionService, never()).cancelOnUserActivity(auto);
        }
    }
}
