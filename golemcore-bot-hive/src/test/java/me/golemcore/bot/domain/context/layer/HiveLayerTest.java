package me.golemcore.bot.domain.context.layer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ChannelTypes;
import me.golemcore.bot.domain.model.ToolNames;
import me.golemcore.bot.domain.model.hive.HiveRuntimeContracts;
import org.junit.jupiter.api.Test;

class HiveLayerTest {

    private final HiveLayer layer = new HiveLayer();

    @Test
    void shouldApplyOnlyForHiveSessions() {
        AgentContext hiveCtx = AgentContext.builder()
                .session(AgentSession.builder().channelType(ChannelTypes.HIVE).chatId("t1").build())
                .build();
        assertTrue(layer.appliesTo(hiveCtx));

        AgentContext webCtx = AgentContext.builder()
                .session(AgentSession.builder().channelType(ChannelTypes.WEB).chatId("t1").build())
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
                .session(AgentSession.builder().channelType(ChannelTypes.HIVE).chatId("t1").build())
                .build();

        ContextLayerResult result = layer.assemble(context);

        assertTrue(result.hasContent());
        assertTrue(result.getContent().contains("# Hive Card Lifecycle"));
        assertTrue(result.getContent().contains(ToolNames.HIVE_LIFECYCLE_SIGNAL));
        assertTrue(result.getContent().contains(HiveRuntimeContracts.SIGNAL_TYPE_WORK_STARTED));
    }

    @Test
    void shouldHaveCorrectNameAndOrder() {
        assertEquals(ChannelTypes.HIVE, layer.getName());
        assertEquals(75, layer.getOrder());
    }
}
