package me.golemcore.bot.domain.context.layer;

import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiveLayerTest {

    private final HiveLayer layer = new HiveLayer();

    @Test
    void shouldApplyOnlyForHiveSessions() {
        AgentContext hiveCtx = AgentContext.builder()
                .session(AgentSession.builder().channelType("hive").chatId("t1").build())
                .build();
        assertTrue(layer.appliesTo(hiveCtx));

        AgentContext webCtx = AgentContext.builder()
                .session(AgentSession.builder().channelType("web").chatId("t1").build())
                .build();
        assertFalse(layer.appliesTo(webCtx));
    }

    @Test
    void shouldNotApplyWithoutSession() {
        assertFalse(layer.appliesTo(AgentContext.builder().build()));
    }

    @Test
    void shouldRenderHiveLifecycleGuidance() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().channelType("hive").chatId("t1").build())
                .build();

        ContextLayerResult result = layer.assemble(context);

        assertTrue(result.hasContent());
        assertTrue(result.getContent().contains("# Hive Card Lifecycle"));
        assertTrue(result.getContent().contains("hive_lifecycle_signal"));
    }

    @Test
    void shouldHaveCorrectNameAndOrder() {
        assertEquals("hive", layer.getName());
        assertEquals(75, layer.getOrder());
    }
}
