package me.golemcore.bot.domain.context.layer;

import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.AutoModeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutoModeLayerTest {

    private AutoModeService autoModeService;
    private AutoModeLayer layer;

    @BeforeEach
    void setUp() {
        autoModeService = mock(AutoModeService.class);
        layer = new AutoModeLayer(autoModeService);
    }

    @Test
    void shouldApplyOnlyForAutoModeMessages() {
        Message autoMsg = Message.builder().role("user").content("auto task")
                .metadata(Map.of(ContextAttributes.AUTO_MODE, true)).build();
        AgentContext autoCtx = AgentContext.builder().messages(List.of(autoMsg)).build();
        assertTrue(layer.appliesTo(autoCtx));

        Message normalMsg = Message.builder().role("user").content("hello").build();
        AgentContext normalCtx = AgentContext.builder().messages(List.of(normalMsg)).build();
        assertFalse(layer.appliesTo(normalCtx));
    }

    @Test
    void shouldRenderAutoContext() {
        when(autoModeService.buildAutoContext()).thenReturn("# Goals\n- Fix bug #123");

        Message autoMsg = Message.builder().role("user").content("task")
                .metadata(Map.of(ContextAttributes.AUTO_MODE, true)).build();
        AgentContext context = AgentContext.builder().messages(List.of(autoMsg)).build();

        ContextLayerResult result = layer.assemble(context);

        assertTrue(result.hasContent());
        assertTrue(result.getContent().contains("Fix bug #123"));
        assertTrue(result.getContent().contains("Autonomous Execution Guidelines"));
        assertTrue(result.getContent().contains("Do NOT include follow-up questions"));
    }

    @Test
    void shouldIncludeReflectionModeHeader() {
        when(autoModeService.buildAutoContext()).thenReturn("# Goals\n- Retry");

        Message autoMsg = Message.builder().role("user").content("reflect")
                .metadata(Map.of(ContextAttributes.AUTO_MODE, true,
                        ContextAttributes.AUTO_REFLECTION_ACTIVE, true))
                .build();
        AgentContext context = AgentContext.builder().messages(List.of(autoMsg)).build();

        ContextLayerResult result = layer.assemble(context);

        assertTrue(result.getContent().contains("# Auto Reflection Mode"));
        assertTrue(result.getContent().contains("recovery"));
    }

    @Test
    void shouldIncludeGuidelinesEvenWhenAutoContextIsBlank() {
        when(autoModeService.buildAutoContext()).thenReturn("");

        Message autoMsg = Message.builder().role("user").content("task")
                .metadata(Map.of(ContextAttributes.AUTO_MODE, true)).build();
        AgentContext context = AgentContext.builder().messages(List.of(autoMsg)).build();

        ContextLayerResult result = layer.assemble(context);
        assertTrue(result.hasContent());
        assertTrue(result.getContent().contains("Autonomous Execution Guidelines"));
        assertFalse(result.getContent().contains("# Goals"));
    }

    @Test
    void shouldHaveCorrectNameAndOrder() {
        assertEquals("auto_mode", layer.getName());
        assertEquals(70, layer.getOrder());
    }
}
