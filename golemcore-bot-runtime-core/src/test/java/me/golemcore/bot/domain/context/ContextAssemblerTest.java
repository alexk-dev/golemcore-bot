package me.golemcore.bot.domain.context;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Skill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextAssemblerTest {

    private ContextResolver skillResolver;
    private ContextResolver tierResolver;
    private PromptComposer promptComposer;

    @BeforeEach
    void setUp() {
        skillResolver = mock(ContextResolver.class);
        tierResolver = mock(ContextResolver.class);
        promptComposer = new PromptComposer();
    }

    @Test
    void shouldInvokeResolversBeforeLayers() {
        ContextLayer layer = stubLayer("test", 10, true, "test content");

        ContextAssembler assembler = new ContextAssembler(skillResolver, tierResolver, List.of(layer), promptComposer,
                null);

        AgentContext context = AgentContext.builder().build();
        assembler.assemble(context);

        verify(skillResolver).resolve(context);
        verify(tierResolver).resolve(context);
    }

    @Test
    void shouldAssembleLayersInOrderAndSetSystemPrompt() {
        ContextLayer layerB = stubLayer("second", 20, true, "# Second");
        ContextLayer layerA = stubLayer("first", 10, true, "# First");

        ContextAssembler assembler = new ContextAssembler(skillResolver, tierResolver, List.of(layerB, layerA),
                promptComposer, null);

        AgentContext context = AgentContext.builder().build();
        assembler.assemble(context);

        assertNotNull(context.getSystemPrompt());
        assertTrue(context.getSystemPrompt().indexOf("# First") < context.getSystemPrompt().indexOf("# Second"),
                "First layer should appear before second in prompt");
    }

    @Test
    void shouldSkipLayersThatDoNotApply() {
        ContextLayer applicable = stubLayer("yes", 10, true, "included");
        ContextLayer skipped = stubLayer("no", 20, false, "excluded");

        ContextAssembler assembler = new ContextAssembler(skillResolver, tierResolver, List.of(applicable, skipped),
                promptComposer, null);

        AgentContext context = AgentContext.builder().build();
        assembler.assemble(context);

        assertTrue(context.getSystemPrompt().contains("included"));
        assertTrue(!context.getSystemPrompt().contains("excluded"));
    }

    @Test
    void shouldHandleLayerExceptionGracefully() {
        ContextLayer failing = mock(ContextLayer.class);
        when(failing.getName()).thenReturn("broken");
        when(failing.getOrder()).thenReturn(10);
        when(failing.appliesTo(any())).thenReturn(true);
        when(failing.assemble(any())).thenThrow(new RuntimeException("boom"));

        ContextLayer healthy = stubLayer("ok", 20, true, "healthy content");

        ContextAssembler assembler = new ContextAssembler(skillResolver, tierResolver, List.of(failing, healthy),
                promptComposer, null);

        AgentContext context = AgentContext.builder().build();
        assembler.assemble(context);

        assertTrue(context.getSystemPrompt().contains("healthy content"));
    }

    @Test
    void shouldUseFallbackPromptWhenNoLayersContribute() {
        ContextLayer empty = stubLayer("empty", 10, true, null);

        ContextAssembler assembler = new ContextAssembler(skillResolver, tierResolver, List.of(empty), promptComposer,
                null);

        AgentContext context = AgentContext.builder().build();
        assembler.assemble(context);

        assertEquals("You are a helpful AI assistant.", context.getSystemPrompt());
    }

    @Test
    void shouldPublishActiveSkillNameToAttributes() {
        ContextLayer layer = stubLayer("test", 10, true, "content");

        ContextAssembler assembler = new ContextAssembler(skillResolver, tierResolver, List.of(layer), promptComposer,
                null);

        Skill skill = Skill.builder().name("coding").description("Code skill").build();
        AgentContext context = AgentContext.builder().activeSkill(skill).build();
        assembler.assemble(context);

        assertEquals("coding", context.getAttribute(ContextAttributes.ACTIVE_SKILL_NAME));
    }

    private ContextLayer stubLayer(String name, int order, boolean applies, String content) {
        ContextLayer layer = mock(ContextLayer.class);
        when(layer.getName()).thenReturn(name);
        when(layer.getOrder()).thenReturn(order);
        when(layer.appliesTo(any())).thenReturn(applies);

        ContextLayerResult result;
        if (content != null) {
            result = ContextLayerResult.builder().layerName(name).content(content)
                    .estimatedTokens((int) Math.ceil(content.length() / 3.5)).build();
        } else {
            result = ContextLayerResult.empty(name);
        }
        when(layer.assemble(any())).thenReturn(result);
        return layer;
    }
}
