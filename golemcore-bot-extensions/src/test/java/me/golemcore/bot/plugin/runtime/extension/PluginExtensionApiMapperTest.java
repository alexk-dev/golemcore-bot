package me.golemcore.bot.plugin.runtime.extension;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginExtensionApiMapperTest {

    private final PluginExtensionApiMapper mapper = new PluginExtensionApiMapper();

    @Test
    void shouldMapMessageWithDefensiveCopies() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("transportChatId", "42");
        metadata.put("nested", new LinkedHashMap<>(Map.of("channel", "telegram")));
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("planId", "plan-1");
        arguments.put("steps", new ArrayList<>(List.of("one", "two")));
        byte[] voiceData = new byte[] { 1, 2, 3 };

        me.golemcore.plugin.api.extension.model.Message source = me.golemcore.plugin.api.extension.model.Message
                .builder()
                .id("msg-1")
                .role("user")
                .content("hello")
                .channelType("telegram")
                .chatId("chat-1")
                .senderId("user-1")
                .toolCalls(List.of(me.golemcore.plugin.api.extension.model.Message.ToolCall.builder()
                        .id("tool-1")
                        .name("plan_get")
                        .arguments(arguments)
                        .build()))
                .metadata(metadata)
                .timestamp(Instant.parse("2026-03-06T12:00:00Z"))
                .voiceData(voiceData)
                .audioFormat(me.golemcore.plugin.api.extension.model.AudioFormat.OGG_OPUS)
                .build();

        me.golemcore.bot.domain.model.Message mapped = mapper.toHostMessage(source);

        assertEquals(source.getId(), mapped.getId());
        assertEquals(source.getRole(), mapped.getRole());
        assertEquals(source.getAudioFormat().name(), mapped.getAudioFormat().name());
        assertEquals("42", mapped.getMetadata().get("transportChatId"));
        assertEquals("telegram", ((Map<?, ?>) mapped.getMetadata().get("nested")).get("channel"));
        assertEquals("plan-1", mapped.getToolCalls().getFirst().getArguments().get("planId"));
        assertEquals("one", ((List<?>) mapped.getToolCalls().getFirst().getArguments().get("steps")).getFirst());
        assertArrayEquals(voiceData, mapped.getVoiceData());
        assertNotSame(source.getMetadata(), mapped.getMetadata());
        assertNotSame(metadata.get("nested"), mapped.getMetadata().get("nested"));
        assertNotSame(source.getToolCalls(), mapped.getToolCalls());
        assertNotSame(arguments.get("steps"), mapped.getToolCalls().getFirst().getArguments().get("steps"));
        assertNotSame(source.getVoiceData(), mapped.getVoiceData());
    }

    @Test
    void shouldMapSessionsAndVoiceContractsExplicitly() {
        me.golemcore.bot.domain.model.Message hostMessage = me.golemcore.bot.domain.model.Message.builder()
                .role("assistant")
                .content("done")
                .audioFormat(me.golemcore.bot.domain.model.AudioFormat.MP3)
                .build();
        me.golemcore.bot.domain.model.AgentSession session = me.golemcore.bot.domain.model.AgentSession.builder()
                .id("session-1")
                .channelType("telegram")
                .chatId("chat-1")
                .messages(List.of(hostMessage))
                .metadata(Map.of("transportChatId", "42"))
                .state(me.golemcore.bot.domain.model.AgentSession.SessionState.PAUSED)
                .createdAt(Instant.parse("2026-03-06T12:00:00Z"))
                .updatedAt(Instant.parse("2026-03-06T12:01:00Z"))
                .build();

        me.golemcore.plugin.api.extension.model.AgentSession pluginSession = mapper.toPluginAgentSession(session);
        assertEquals("session-1", pluginSession.getId());
        assertEquals(me.golemcore.plugin.api.extension.model.AgentSession.SessionState.PAUSED,
                pluginSession.getState());
        assertEquals("done", pluginSession.getMessages().getFirst().getContent());

        me.golemcore.plugin.api.extension.port.outbound.VoicePort.TranscriptionResult pluginResult = new me.golemcore.plugin.api.extension.port.outbound.VoicePort.TranscriptionResult(
                "hello",
                "en",
                0.9f,
                Duration.ofSeconds(2),
                List.of(new me.golemcore.plugin.api.extension.port.outbound.VoicePort.WordTimestamp(
                        "hello",
                        Duration.ZERO,
                        Duration.ofSeconds(1))));

        me.golemcore.bot.port.outbound.VoicePort.TranscriptionResult hostResult = mapper
                .toHostTranscriptionResult(pluginResult);

        assertEquals("hello", hostResult.text());
        assertEquals(Duration.ofSeconds(2), hostResult.duration());
        assertEquals("hello", hostResult.words().getFirst().word());
    }

    @Test
    void shouldDefensivelyCopyCommandResultPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nested", new LinkedHashMap<>(Map.of("status", "ok")));

        me.golemcore.bot.port.inbound.CommandPort.CommandResult source = new me.golemcore.bot.port.inbound.CommandPort.CommandResult(
                true,
                "done",
                payload);

        me.golemcore.plugin.api.extension.port.inbound.CommandPort.CommandResult mapped = mapper
                .toPluginCommandResult(source);

        assertEquals("ok", ((Map<?, ?>) ((Map<?, ?>) mapped.data()).get("nested")).get("status"));
        assertNotSame(payload, mapped.data());
        assertNotSame(payload.get("nested"), ((Map<?, ?>) mapped.data()).get("nested"));
    }

    @Test
    void shouldMapToolContractsAndAttachmentsExplicitly() {
        me.golemcore.plugin.api.extension.model.Attachment attachment = me.golemcore.plugin.api.extension.model.Attachment
                .builder()
                .type(me.golemcore.plugin.api.extension.model.Attachment.Type.IMAGE)
                .data(new byte[] { 9, 8, 7 })
                .filename("shot.png")
                .mimeType("image/png")
                .caption("Screenshot")
                .build();

        me.golemcore.plugin.api.extension.model.ToolResult pluginResult = me.golemcore.plugin.api.extension.model.ToolResult
                .builder()
                .success(true)
                .output("ok")
                .data(Map.of("attachment", attachment))
                .failureKind(me.golemcore.plugin.api.extension.model.ToolFailureKind.EXECUTION_FAILED)
                .build();

        me.golemcore.bot.domain.model.ToolResult hostResult = mapper.toHostToolResult(pluginResult);

        assertTrue(hostResult.isSuccess());
        assertEquals("ok", hostResult.getOutput());
        assertEquals(me.golemcore.bot.domain.model.ToolFailureKind.EXECUTION_FAILED, hostResult.getFailureKind());
        Object mappedAttachment = ((Map<?, ?>) hostResult.getData()).get("attachment");
        assertTrue(mappedAttachment instanceof me.golemcore.bot.domain.model.Attachment);
        assertArrayEquals(new byte[] { 9, 8, 7 },
                ((me.golemcore.bot.domain.model.Attachment) mappedAttachment).getData());
    }
}
