package me.golemcore.bot.auto;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.testsupport.http.OkHttpMockEngine;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleReportSenderTest {

    private ChannelRegistry channelRegistry;
    private OkHttpMockEngine mockEngine;
    private ScheduleReportSender sender;

    @BeforeEach
    void setUp() {
        channelRegistry = mock(ChannelRegistry.class);
        mockEngine = new OkHttpMockEngine();
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(mockEngine)
                .build();
        ObjectMapper objectMapper = new ObjectMapper();
        sender = new ScheduleReportSender(channelRegistry, client, objectMapper);
    }

    @Test
    void shouldSkipWhenNoReportChannelConfigured() {
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-1")
                .reportChannelType(null)
                .build();

        sender.sendReport(schedule, "header", "text", null);

        verify(channelRegistry, never()).get(anyString());
        assertEquals(0, mockEngine.getRequestCount());
    }

    @Test
    void shouldSkipWhenReportChannelTypeIsBlank() {
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-1")
                .reportChannelType("")
                .build();

        sender.sendReport(schedule, "header", "text", null);

        verify(channelRegistry, never()).get(anyString());
    }

    @Test
    void shouldSkipWhenAssistantTextIsBlank() {
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-1")
                .reportChannelType("telegram")
                .build();

        sender.sendReport(schedule, "header", "", null);

        verify(channelRegistry, never()).get(anyString());
    }

    @Test
    void shouldSendViaChannelWithExplicitChatId() {
        ChannelPort telegramChannel = mock(ChannelPort.class);
        when(telegramChannel.sendMessage(eq("12345"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(channelRegistry.get("telegram")).thenReturn(Optional.of(telegramChannel));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-1")
                .reportChannelType("telegram")
                .reportChatId("12345")
                .build();

        sender.sendReport(schedule, "header", "assistant reply", null);

        verify(telegramChannel).sendMessage(eq("12345"), eq("header\n\nassistant reply"));
    }

    @Test
    void shouldFallbackChatIdFromChannelInfo() {
        ChannelPort telegramChannel = mock(ChannelPort.class);
        when(telegramChannel.sendMessage(eq("99999"), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(channelRegistry.get("telegram")).thenReturn(Optional.of(telegramChannel));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-1")
                .reportChannelType("telegram")
                .reportChatId(null)
                .build();

        AutoModeScheduler.ChannelInfo channelInfo = new AutoModeScheduler.ChannelInfo("telegram", "session-1", "99999");

        sender.sendReport(schedule, "header", "text", channelInfo);

        verify(telegramChannel).sendMessage(eq("99999"), anyString());
    }

    @Test
    void shouldSkipChannelSendWhenChatIdCannotBeResolved() {
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-1")
                .reportChannelType("telegram")
                .reportChatId(null)
                .build();

        sender.sendReport(schedule, "header", "text", null);

        verify(channelRegistry, never()).get(anyString());
    }

    @Test
    void shouldSkipChannelSendWhenChannelNotFoundInRegistry() {
        when(channelRegistry.get("unknown")).thenReturn(Optional.empty());

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-1")
                .reportChannelType("unknown")
                .reportChatId("12345")
                .build();

        sender.sendReport(schedule, "header", "text", null);

        verify(channelRegistry).get("unknown");
    }

    @Test
    void shouldSendViaWebhookWithAuthHeader() throws Exception {
        mockEngine.enqueueJson(200, "{\"ok\":true}");

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-wh")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .reportChannelType("webhook")
                .reportWebhookUrl("https://example.com/hook")
                .reportWebhookSecret("secret-token")
                .build();

        sender.sendReport(schedule, "header", "assistant reply", null);

        assertEquals(1, mockEngine.getRequestCount());
        OkHttpMockEngine.CapturedRequest request = mockEngine.takeRequest();
        assertNotNull(request);
        assertEquals("POST", request.method());
        assertEquals("Bearer secret-token", request.headers().get("Authorization"));
        assertTrue(request.body().contains("\"text\":\"header\\n\\nassistant reply\""));
        assertTrue(request.body().contains("\"scheduleId\":\"sched-wh\""));
        assertTrue(request.body().contains("\"targetType\":\"GOAL\""));
    }

    @Test
    void shouldSendViaWebhookWithoutAuthWhenSecretIsBlank() throws Exception {
        mockEngine.enqueueJson(200, "{\"ok\":true}");

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-wh")
                .type(ScheduleEntry.ScheduleType.TASK)
                .reportChannelType("webhook")
                .reportWebhookUrl("https://example.com/hook")
                .reportWebhookSecret(null)
                .build();

        sender.sendReport(schedule, "header", "text", null);

        assertEquals(1, mockEngine.getRequestCount());
        OkHttpMockEngine.CapturedRequest request = mockEngine.takeRequest();
        assertNotNull(request);
        assertNull(request.headers().get("Authorization"));
    }

    @Test
    void shouldSkipWebhookWhenUrlIsBlank() {
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-wh")
                .reportChannelType("webhook")
                .reportWebhookUrl(null)
                .build();

        sender.sendReport(schedule, "header", "text", null);

        assertEquals(0, mockEngine.getRequestCount());
    }

    @Test
    void shouldHandleWebhookNetworkFailureGracefully() {
        mockEngine.enqueueFailure(new IOException("connection refused"));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-wh")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .reportChannelType("webhook")
                .reportWebhookUrl("https://example.com/hook")
                .build();

        // Should not throw
        sender.sendReport(schedule, "header", "text", null);

        assertEquals(1, mockEngine.getRequestCount());
    }
}
