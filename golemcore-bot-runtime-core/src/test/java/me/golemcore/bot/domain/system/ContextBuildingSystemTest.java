package me.golemcore.bot.domain.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import me.golemcore.bot.domain.context.ContextAssembler;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import org.junit.jupiter.api.Test;

class ContextBuildingSystemTest {

    private final ContextAssembler assembler = mock(ContextAssembler.class);
    private final ContextBuildingSystem system = new ContextBuildingSystem(assembler);

    @Test
    void shouldExposeSystemMetadata() {
        assertEquals("ContextBuildingSystem", system.getName());
        assertEquals(20, system.getOrder());
    }

    @Test
    void shouldDelegateToContextAssembler() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-1").chatId("chat-1").build())
                .build();
        when(assembler.assemble(context)).thenReturn(context);

        AgentContext result = system.process(context);

        verify(assembler).assemble(context);
        assertEquals(context, result);
    }

    @Test
    void shouldReturnNullWhenAssemblerReturnsNull() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().id("session-2").chatId("chat-2").build())
                .build();
        when(assembler.assemble(context)).thenReturn(null);

        AgentContext result = system.process(context);

        verify(assembler).assemble(context);
        assertNull(result);
    }
}
