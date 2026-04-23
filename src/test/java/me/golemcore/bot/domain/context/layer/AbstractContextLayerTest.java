package me.golemcore.bot.domain.context.layer;

import me.golemcore.bot.domain.context.ContextLayer;
import me.golemcore.bot.domain.context.ContextLayerLifecycle;
import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractContextLayerTest {

    @Test
    void shouldExposeConfiguredMetadata() {
        StubLayer layer = new StubLayer("test", 42, 70, ContextLayerLifecycle.SESSION, 512, false);

        assertEquals("test", layer.getName());
        assertEquals(42, layer.getOrder());
        assertEquals(70, layer.getPriority());
        assertEquals(ContextLayerLifecycle.SESSION, layer.getLifecycle());
        assertEquals(512, layer.getTokenBudget());
        assertFalse(layer.isRequired());
    }

    @Test
    void shouldTreatRequiredPriorityAsRequired() {
        StubLayer layer = new StubLayer("required", 1, ContextLayer.REQUIRED_PRIORITY,
                ContextLayerLifecycle.STATIC, ContextLayer.UNLIMITED_TOKEN_BUDGET, false);

        assertTrue(layer.isRequired());
    }

    @Test
    void shouldAllowExplicitRequiredLayerBelowRequiredPriority() {
        StubLayer layer = new StubLayer("forced", 1, 20,
                ContextLayerLifecycle.TURN, ContextLayer.UNLIMITED_TOKEN_BUDGET, true);

        assertTrue(layer.isRequired());
    }

    @Test
    void shouldBuildNamedResults() {
        StubLayer layer = new StubLayer("result", 1, 20,
                ContextLayerLifecycle.TURN, ContextLayer.UNLIMITED_TOKEN_BUDGET, false);

        ContextLayerResult result = layer.render("hello world");

        assertEquals("result", result.getLayerName());
        assertEquals("hello world", result.getContent());
        assertTrue(result.getEstimatedTokens() > 0);
    }

    @Test
    void shouldBuildNamedEmptyResults() {
        StubLayer layer = new StubLayer("empty", 1, 20,
                ContextLayerLifecycle.TURN, ContextLayer.UNLIMITED_TOKEN_BUDGET, false);

        ContextLayerResult result = layer.renderEmpty();

        assertEquals("empty", result.getLayerName());
        assertFalse(result.hasContent());
    }

    private static final class StubLayer extends AbstractContextLayer {

        private StubLayer(String name, int order, int priority,
                ContextLayerLifecycle lifecycle, int tokenBudget, boolean required) {
            super(name, order, priority, lifecycle, tokenBudget, required);
        }

        @Override
        public boolean appliesTo(AgentContext context) {
            return true;
        }

        @Override
        public ContextLayerResult assemble(AgentContext context) {
            return render("assembled");
        }

        private ContextLayerResult render(String content) {
            return result(content);
        }

        private ContextLayerResult renderEmpty() {
            return empty();
        }
    }
}
