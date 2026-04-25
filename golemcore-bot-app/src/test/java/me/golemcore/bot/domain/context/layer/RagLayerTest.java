package me.golemcore.bot.domain.context.layer;

import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.RagPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagLayerTest {

    private RagPort ragPort;
    private RagLayer layer;

    @BeforeEach
    void setUp() {
        ragPort = mock(RagPort.class);
        layer = new RagLayer(ragPort);
    }

    @Test
    void shouldApplyOnlyWhenRagIsAvailable() {
        when(ragPort.isAvailable()).thenReturn(true);
        assertTrue(layer.appliesTo(AgentContext.builder().build()));

        when(ragPort.isAvailable()).thenReturn(false);
        assertFalse(layer.appliesTo(AgentContext.builder().build()));
    }

    @Test
    void shouldRenderRagContextWithHeader() {
        when(ragPort.isAvailable()).thenReturn(true);
        when(ragPort.query(any()))
                .thenReturn(CompletableFuture.completedFuture("Related: session about Java patterns."));

        Message userMsg = Message.builder().role("user").content("How do I use streams?").build();
        AgentContext context = AgentContext.builder().messages(List.of(userMsg)).build();

        ContextLayerResult result = layer.assemble(context);

        assertTrue(result.hasContent());
        assertTrue(result.getContent().contains("# Relevant Memory"));
        assertTrue(result.getContent().contains("Java patterns"));
    }

    @Test
    void shouldStoreRagContextInAttributes() {
        when(ragPort.isAvailable()).thenReturn(true);
        when(ragPort.query(any())).thenReturn(CompletableFuture.completedFuture("rag content"));

        Message userMsg = Message.builder().role("user").content("test").build();
        AgentContext context = AgentContext.builder().messages(List.of(userMsg)).build();

        layer.assemble(context);

        assertEquals("rag content", context.getAttribute(ContextAttributes.RAG_CONTEXT));
    }

    @Test
    void shouldReturnEmptyOnException() {
        when(ragPort.isAvailable()).thenReturn(true);
        when(ragPort.query(any())).thenThrow(new RuntimeException("fail"));

        Message userMsg = Message.builder().role("user").content("test").build();
        AgentContext context = AgentContext.builder().messages(List.of(userMsg)).build();

        ContextLayerResult result = layer.assemble(context);
        assertFalse(result.hasContent());
    }

    @Test
    void shouldHaveCorrectNameAndOrder() {
        assertEquals("rag", layer.getName());
        assertEquals(35, layer.getOrder());
    }
}
