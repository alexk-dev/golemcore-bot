package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiveMetadataSupportTest {

    @Test
    void shouldCopyHiveMessageMetadataToContext() {
        AgentContext context = AgentContext.builder().build();
        Message message = Message.builder()
                .metadata(Map.of(
                        ContextAttributes.HIVE_THREAD_ID, "thread-1",
                        ContextAttributes.HIVE_CARD_ID, "card-1",
                        ContextAttributes.HIVE_COMMAND_ID, "cmd-1",
                        "ignored", "value"))
                .build();

        HiveMetadataSupport.copyMessageMetadataToContext(message, context);

        assertEquals("thread-1", context.getAttribute(ContextAttributes.HIVE_THREAD_ID));
        assertEquals("card-1", context.getAttribute(ContextAttributes.HIVE_CARD_ID));
        assertEquals("cmd-1", context.getAttribute(ContextAttributes.HIVE_COMMAND_ID));
        assertNull(context.getAttribute("ignored"));
    }

    @Test
    void shouldExtractAndStripHiveMetadata() {
        AgentContext context = AgentContext.builder().build();
        context.setAttribute(ContextAttributes.HIVE_THREAD_ID, "thread-1");
        context.setAttribute(ContextAttributes.HIVE_CARD_ID, "card-1");

        Map<String, Object> extracted = HiveMetadataSupport.extractContextAttributes(context);
        Map<String, Object> withExtra = new LinkedHashMap<>(extracted);
        withExtra.put("other", "value");

        Map<String, Object> stripped = HiveMetadataSupport.stripHiveMetadata(withExtra);

        assertEquals("thread-1", extracted.get(ContextAttributes.HIVE_THREAD_ID));
        assertEquals("card-1", extracted.get(ContextAttributes.HIVE_CARD_ID));
        assertFalse(stripped.containsKey(ContextAttributes.HIVE_THREAD_ID));
        assertFalse(stripped.containsKey(ContextAttributes.HIVE_CARD_ID));
        assertEquals("value", stripped.get("other"));
    }

    @Test
    void shouldReadAndCopyCanonicalHiveMetadataMap() {
        Map<String, Object> source = Map.of(
                ContextAttributes.HIVE_THREAD_ID, " thread-1 ",
                ContextAttributes.HIVE_RUN_ID, "run-1",
                "other", "value");
        Map<String, Object> target = new LinkedHashMap<>();

        HiveMetadataSupport.copyMetadataMap(source, target);

        assertEquals("thread-1", target.get(ContextAttributes.HIVE_THREAD_ID));
        assertEquals("run-1", target.get(ContextAttributes.HIVE_RUN_ID));
        assertFalse(target.containsKey("other"));
        assertEquals("thread-1", HiveMetadataSupport.readString(source, ContextAttributes.HIVE_THREAD_ID));
        HiveMetadataSupport.putIfPresent(target, ContextAttributes.HIVE_CARD_ID, "card-1");
        assertTrue(target.containsKey(ContextAttributes.HIVE_CARD_ID));
    }
}
