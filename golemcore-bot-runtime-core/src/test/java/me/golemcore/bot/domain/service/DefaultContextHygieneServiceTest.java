package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.ContextScope;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultContextHygieneServiceTest {

    private final DefaultContextHygieneService service = new DefaultContextHygieneService();

    @Test
    void shouldExposeAttributeScopesForDiagnostics() {
        assertEquals(ContextScope.ITERATION, service.specs().get(ContextAttributes.CONTEXT_HYGIENE_REPORT).scope());
        assertEquals(ContextScope.SESSION, service.specs().get(ContextAttributes.AUTO_SCHEDULED_TASK_ID).scope());
        assertFalse(service.specs().get(ContextAttributes.CONTEXT_SCOPED_TOOLS).promptVisible());
        assertTrue(service.specs().get(ContextAttributes.RAG_CONTEXT).promptVisible());
        assertTrue(service.specs().get(ContextAttributes.AUTO_SCHEDULED_TASK_ID).promptVisible());
    }

    @Test
    void shouldClearIterationScopedAttributesBetweenOuterIterations() {
        AgentContext context = AgentContext.builder().build();
        context.setAttribute(ContextAttributes.CONTEXT_HYGIENE_REPORT, Map.of("rawTokens", 100));
        context.setAttribute(ContextAttributes.LLM_REQUEST_PREFLIGHT, Map.of("threshold", 50));
        context.setAttribute(ContextAttributes.ACTIVE_SKILL_NAME, "coding");

        service.afterOuterIteration(context);

        assertFalse(context.getAttributes().containsKey(ContextAttributes.CONTEXT_HYGIENE_REPORT));
        assertFalse(context.getAttributes().containsKey(ContextAttributes.LLM_REQUEST_PREFLIGHT));
        assertTrue(context.getAttributes().containsKey(ContextAttributes.ACTIVE_SKILL_NAME));
    }

    @Test
    void shouldClearNonPersistableTurnArtifactsBeforePersist() {
        AgentContext context = AgentContext.builder().build();
        context.setAttribute(ContextAttributes.WEB_STREAM_SINK, new Object());
        context.setAttribute(ContextAttributes.CONTEXT_SCOPED_TOOLS, Map.of("tool", new Object()));
        context.setAttribute(ContextAttributes.CONTEXT_HYGIENE_REPORT, Map.of("projectedTokens", 10));

        service.beforePersist(context);

        assertFalse(context.getAttributes().containsKey(ContextAttributes.WEB_STREAM_SINK));
        assertFalse(context.getAttributes().containsKey(ContextAttributes.CONTEXT_SCOPED_TOOLS));
        assertTrue(context.getAttributes().containsKey(ContextAttributes.CONTEXT_HYGIENE_REPORT));
    }
}
