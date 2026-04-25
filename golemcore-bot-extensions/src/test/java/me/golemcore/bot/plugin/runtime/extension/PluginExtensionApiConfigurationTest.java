package me.golemcore.bot.plugin.runtime.extension;

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.AudioFormat;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.inbound.CommandPort;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.port.outbound.VoicePort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PluginExtensionApiConfigurationTest {

    @Test
    void shouldAdaptCommandPortDelegates() {
        PluginExtensionApiConfiguration configuration = new PluginExtensionApiConfiguration();
        PluginExtensionApiMapper mapper = new PluginExtensionApiMapper();
        CommandPort delegate = mock(CommandPort.class);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nested", new LinkedHashMap<>(Map.of("status", "ok")));

        when(delegate.execute("status", List.of("now"), Map.of("chatId", "42")))
                .thenReturn(CompletableFuture.completedFuture(new CommandPort.CommandResult(
                        true,
                        "done",
                        payload)));
        when(delegate.hasCommand("status")).thenReturn(true);
        when(delegate.listCommands()).thenReturn(List.of(new CommandPort.CommandDefinition(
                "status",
                "System status",
                "/status")));

        me.golemcore.plugin.api.extension.port.inbound.CommandPort pluginPort = configuration.pluginCommandPort(
                delegate,
                mapper);
        me.golemcore.plugin.api.extension.port.inbound.CommandPort.CommandResult result = pluginPort
                .execute("status", List.of("now"), Map.of("chatId", "42"))
                .join();

        assertTrue(result.success());
        assertEquals("done", result.output());
        assertEquals("ok", ((Map<?, ?>) ((Map<?, ?>) result.data()).get("nested")).get("status"));
        assertNotSame(payload, result.data());
        assertTrue(pluginPort.hasCommand("status"));
        assertEquals("status", pluginPort.listCommands().getFirst().name());
    }

    @Test
    void shouldAdaptSessionPortDelegates() {
        PluginExtensionApiConfiguration configuration = new PluginExtensionApiConfiguration();
        PluginExtensionApiMapper mapper = new PluginExtensionApiMapper();
        SessionPort delegate = mock(SessionPort.class);

        Message hostMessage = Message.builder()
                .role("assistant")
                .content("done")
                .audioFormat(AudioFormat.MP3)
                .build();
        AgentSession hostSession = AgentSession.builder()
                .id("session-1")
                .channelType("telegram")
                .chatId("42")
                .messages(List.of(hostMessage))
                .metadata(Map.of("transportChatId", "42"))
                .state(AgentSession.SessionState.PAUSED)
                .createdAt(Instant.parse("2026-03-08T12:00:00Z"))
                .updatedAt(Instant.parse("2026-03-08T12:01:00Z"))
                .build();

        when(delegate.getOrCreate("telegram", "42")).thenReturn(hostSession);
        when(delegate.get("session-1")).thenReturn(Optional.of(hostSession));
        when(delegate.compactMessages("session-1", 3)).thenReturn(2);
        when(delegate.compactWithSummary(eq("session-1"), eq(3), any(Message.class))).thenReturn(1);
        when(delegate.getMessagesToCompact("session-1", 3)).thenReturn(List.of(hostMessage));
        when(delegate.getMessageCount("session-1")).thenReturn(7);
        when(delegate.listAll()).thenReturn(List.of(hostSession));
        when(delegate.listByChannelType("telegram")).thenReturn(List.of(hostSession));
        when(delegate.listByChannelTypeAndTransportChatId("telegram", "42")).thenReturn(List.of(hostSession));

        me.golemcore.plugin.api.extension.port.outbound.SessionPort pluginPort = configuration.pluginSessionPort(
                delegate,
                mapper);
        assertEquals("session-1", pluginPort.getOrCreate("telegram", "42").getId());
        assertEquals(me.golemcore.plugin.api.extension.model.AgentSession.SessionState.PAUSED,
                pluginPort.get("session-1").orElseThrow().getState());

        me.golemcore.plugin.api.extension.model.AgentSession pluginSession = me.golemcore.plugin.api.extension.model.AgentSession
                .builder()
                .id("session-2")
                .channelType("discord")
                .chatId("99")
                .messages(List.of(me.golemcore.plugin.api.extension.model.Message.builder()
                        .role("user")
                        .content("hello")
                        .build()))
                .build();
        pluginPort.save(pluginSession);

        ArgumentCaptor<AgentSession> sessionCaptor = ArgumentCaptor.forClass(AgentSession.class);
        verify(delegate).save(sessionCaptor.capture());
        assertEquals("session-2", sessionCaptor.getValue().getId());
        assertEquals("hello", sessionCaptor.getValue().getMessages().getFirst().getContent());

        pluginPort.delete("session-1");
        pluginPort.clearMessages("session-1");
        assertEquals(2, pluginPort.compactMessages("session-1", 3));
        assertEquals(1, pluginPort.compactWithSummary(
                "session-1",
                3,
                me.golemcore.plugin.api.extension.model.Message.builder()
                        .role("assistant")
                        .content("summary")
                        .build()));
        assertEquals("done", pluginPort.getMessagesToCompact("session-1", 3).getFirst().getContent());
        assertEquals(7, pluginPort.getMessageCount("session-1"));
        assertEquals(1, pluginPort.listAll().size());
        assertEquals(1, pluginPort.listByChannelType("telegram").size());
        assertEquals(1, pluginPort.listByChannelTypeAndTransportChatId("telegram", "42").size());

        ArgumentCaptor<Message> summaryCaptor = ArgumentCaptor.forClass(Message.class);
        verify(delegate).compactWithSummary(eq("session-1"), eq(3), summaryCaptor.capture());
        assertEquals("summary", summaryCaptor.getValue().getContent());
        verify(delegate).delete("session-1");
        verify(delegate).clearMessages("session-1");
    }

    @Test
    void shouldAdaptVoicePortResultsAndAvailability() {
        PluginExtensionApiConfiguration configuration = new PluginExtensionApiConfiguration();
        PluginExtensionApiMapper mapper = new PluginExtensionApiMapper();
        VoicePort delegate = mock(VoicePort.class);

        when(delegate.transcribe(any(byte[].class), eq(AudioFormat.WAV)))
                .thenReturn(CompletableFuture.completedFuture(new VoicePort.TranscriptionResult(
                        "hello",
                        "en",
                        0.95f,
                        Duration.ofSeconds(2),
                        List.of(new VoicePort.WordTimestamp(
                                "hello",
                                Duration.ZERO,
                                Duration.ofSeconds(1))))));
        when(delegate.synthesize(eq("hello"), any(VoicePort.VoiceConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(new byte[] { 1, 2, 3 }));
        when(delegate.isAvailable()).thenReturn(true);

        me.golemcore.plugin.api.extension.port.outbound.VoicePort pluginPort = configuration.pluginExtensionVoicePort(
                delegate,
                mapper);

        me.golemcore.plugin.api.extension.port.outbound.VoicePort.TranscriptionResult transcription = pluginPort
                .transcribe(new byte[] { 9, 8, 7 }, me.golemcore.plugin.api.extension.model.AudioFormat.WAV)
                .join();
        byte[] synthesized = pluginPort.synthesize(
                "hello",
                new me.golemcore.plugin.api.extension.port.outbound.VoicePort.VoiceConfig(
                        "voice-1",
                        "model-1",
                        1.25f,
                        me.golemcore.plugin.api.extension.model.AudioFormat.MP3))
                .join();

        assertEquals("hello", transcription.text());
        assertEquals("en", transcription.language());
        assertEquals("hello", transcription.words().getFirst().word());
        assertArrayEquals(new byte[] { 1, 2, 3 }, synthesized);
        assertTrue(pluginPort.isAvailable());

        ArgumentCaptor<VoicePort.VoiceConfig> configCaptor = ArgumentCaptor.forClass(VoicePort.VoiceConfig.class);
        verify(delegate).synthesize(eq("hello"), configCaptor.capture());
        assertEquals("voice-1", configCaptor.getValue().voiceId());
        assertEquals(AudioFormat.MP3, configCaptor.getValue().outputFormat());
    }

    @Test
    void shouldTranslateSynchronousVoiceQuotaFailures() {
        PluginExtensionApiConfiguration configuration = new PluginExtensionApiConfiguration();
        VoicePort delegate = mock(VoicePort.class);
        when(delegate.transcribe(any(byte[].class), any(AudioFormat.class)))
                .thenThrow(new VoicePort.QuotaExceededException("sync quota"));

        me.golemcore.plugin.api.extension.port.outbound.VoicePort pluginPort = configuration.pluginExtensionVoicePort(
                delegate,
                new PluginExtensionApiMapper());

        me.golemcore.plugin.api.extension.port.outbound.VoicePort.QuotaExceededException exception = assertThrows(
                me.golemcore.plugin.api.extension.port.outbound.VoicePort.QuotaExceededException.class,
                () -> pluginPort.transcribe(new byte[] { 1 }, me.golemcore.plugin.api.extension.model.AudioFormat.MP3));

        assertEquals("sync quota", exception.getMessage());
    }

    @Test
    void shouldTranslateAsyncVoiceQuotaFailures() {
        PluginExtensionApiConfiguration configuration = new PluginExtensionApiConfiguration();
        VoicePort delegate = mock(VoicePort.class);
        when(delegate.synthesize(anyString(), any(VoicePort.VoiceConfig.class)))
                .thenReturn(CompletableFuture.failedFuture(new CompletionException(
                        new VoicePort.QuotaExceededException("async quota"))));

        me.golemcore.plugin.api.extension.port.outbound.VoicePort pluginPort = configuration.pluginExtensionVoicePort(
                delegate,
                new PluginExtensionApiMapper());

        CompletionException exception = assertThrows(CompletionException.class,
                () -> pluginPort.synthesize(
                        "hello",
                        me.golemcore.plugin.api.extension.port.outbound.VoicePort.VoiceConfig.defaultConfig()).join());

        assertTrue(exception
                .getCause() instanceof me.golemcore.plugin.api.extension.port.outbound.VoicePort.QuotaExceededException);
        assertEquals("async quota", exception.getCause().getMessage());
    }
}
