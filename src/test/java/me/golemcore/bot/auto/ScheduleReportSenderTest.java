package me.golemcore.bot.auto;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.ScheduleEntry;
import me.golemcore.bot.domain.model.ScheduleReportConfig;
import me.golemcore.bot.plugin.runtime.ChannelRegistry;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.testsupport.http.OkHttpMockEngine;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
    private List<Long> recordedBackoffs;

    @BeforeEach
    void setUp() {
        channelRegistry = mock(ChannelRegistry.class);
        mockEngine = new OkHttpMockEngine();
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(mockEngine)
                .build();
        ObjectMapper objectMapper = new ObjectMapper();
        recordedBackoffs = new ArrayList<>();
        sender = new ScheduleReportSender(channelRegistry, client, objectMapper, recordedBackoffs::add);
    }

    @Test
    void shouldSkipWhenNoReportChannelConfigured() {
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-1")
                .build();

        sender.sendReport(schedule, "header", "text", null);

        verify(channelRegistry, never()).get(anyString());
        assertEquals(0, mockEngine.getRequestCount());
    }

    @Test
    void shouldSkipWhenReportChannelTypeIsBlank() {
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-1")
                .report(channelReport("", null))
                .build();

        sender.sendReport(schedule, "header", "text", null);

        verify(channelRegistry, never()).get(anyString());
    }

    @Test
    void shouldSkipWhenAssistantTextIsBlank() {
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-1")
                .report(channelReport("telegram", null))
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
                .report(channelReport("telegram", "12345"))
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
                .report(channelReport("telegram", null))
                .build();

        ScheduleDeliveryContext channelInfo = new ScheduleDeliveryContext("telegram", "session-1", "99999");

        sender.sendReport(schedule, "header", "text", channelInfo);

        verify(telegramChannel).sendMessage(eq("99999"), anyString());
    }

    @Test
    void shouldSkipChannelSendWhenChatIdCannotBeResolved() {
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-1")
                .report(channelReport("telegram", null))
                .build();

        sender.sendReport(schedule, "header", "text", null);

        verify(channelRegistry, never()).get(anyString());
    }

    @Test
    void shouldSkipChannelSendWhenChannelNotFoundInRegistry() {
        when(channelRegistry.get("unknown")).thenReturn(Optional.empty());

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-1")
                .report(channelReport("unknown", "12345"))
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
                .report(webhookReport("https://example.com/hook", "secret-token"))
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
                .report(webhookReport("https://example.com/hook", null))
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
                .report(webhookReport(null, null))
                .build();

        sender.sendReport(schedule, "header", "text", null);

        assertEquals(0, mockEngine.getRequestCount());
    }

    @Test
    void shouldHandleWebhookNetworkFailureGracefully() {
        mockEngine.enqueueFailure(new IOException("connection refused"));
        mockEngine.enqueueFailure(new IOException("connection refused"));
        mockEngine.enqueueFailure(new IOException("connection refused"));
        mockEngine.enqueueFailure(new IOException("connection refused"));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-wh")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .report(webhookReport("https://example.com/hook", null))
                .build();

        sender.sendReport(schedule, "header", "text", null);

        assertEquals(4, mockEngine.getRequestCount());
        assertEquals(List.of(100L, 200L, 400L), recordedBackoffs);
    }

    @Test
    void shouldRetryWebhookOnHttpErrorWithExponentialBackoff() throws Exception {
        mockEngine.enqueueJson(500, "{\"ok\":false}");
        mockEngine.enqueueJson(502, "{\"ok\":false}");
        mockEngine.enqueueJson(200, "{\"ok\":true}");

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-wh")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .report(webhookReport("https://example.com/hook", null))
                .build();

        sender.sendReport(schedule, "header", "text", null);

        assertEquals(3, mockEngine.getRequestCount());
        assertEquals(List.of(100L, 200L), recordedBackoffs);
    }

    @Test
    void shouldStopAfterThreeWebhookRetriesAndTreatMissionAsComplete() {
        mockEngine.enqueueFailure(new IOException("connection refused"));
        mockEngine.enqueueFailure(new IOException("connection refused"));
        mockEngine.enqueueFailure(new IOException("connection refused"));
        mockEngine.enqueueFailure(new IOException("connection refused"));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-wh")
                .type(ScheduleEntry.ScheduleType.GOAL)
                .report(webhookReport("https://example.com/hook", null))
                .build();

        sender.sendReport(schedule, "header", "text", null);

        assertEquals(4, mockEngine.getRequestCount());
        assertEquals(List.of(100L, 200L, 400L), recordedBackoffs);
    }

    @Test
    void shouldHandleChannelSendTimeout() {
        ChannelPort telegramChannel = mock(ChannelPort.class);
        CompletableFuture<Void> slowFuture = new CompletableFuture<>();
        slowFuture.completeExceptionally(new java.util.concurrent.TimeoutException("send timeout"));
        when(telegramChannel.sendMessage(eq("12345"), anyString())).thenReturn(slowFuture);
        when(channelRegistry.get("telegram")).thenReturn(Optional.of(telegramChannel));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-1")
                .report(channelReport("telegram", "12345"))
                .build();

        sender.sendReport(schedule, "header", "text", null);

        verify(telegramChannel).sendMessage(eq("12345"), anyString());
    }

    @Test
    void shouldHandleChannelSendInterruption() {
        ChannelPort telegramChannel = mock(ChannelPort.class);
        CompletableFuture<Void> interruptedFuture = new CompletableFuture<>();
        interruptedFuture.completeExceptionally(new RuntimeException("interrupted"));
        when(telegramChannel.sendMessage(eq("12345"), anyString())).thenReturn(interruptedFuture);
        when(channelRegistry.get("telegram")).thenReturn(Optional.of(telegramChannel));

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-1")
                .report(channelReport("telegram", "12345"))
                .build();

        sender.sendReport(schedule, "header", "text", null);

        verify(telegramChannel).sendMessage(eq("12345"), anyString());
    }

    @Test
    void shouldNotFallbackChatIdWhenChannelTypeMismatch() {
        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-1")
                .report(channelReport("telegram", null))
                .build();

        ScheduleDeliveryContext ctx = new ScheduleDeliveryContext("slack", "session-1", "99999");

        sender.sendReport(schedule, "header", "text", ctx);

        verify(channelRegistry, never()).get(anyString());
    }

    @Test
    void shouldSendWebhookWithNullTargetType() {
        mockEngine.enqueueJson(200, "{\"ok\":true}");

        ScheduleEntry schedule = ScheduleEntry.builder()
                .id("sched-wh")
                .type(null)
                .report(webhookReport("https://example.com/hook", null))
                .build();

        sender.sendReport(schedule, "header", "text", null);

        assertEquals(1, mockEngine.getRequestCount());
    }

    private static ScheduleReportConfig channelReport(String channelType, String chatId) {
        return ScheduleReportConfig.builder()
                .channelType(channelType)
                .chatId(chatId)
                .build();
    }

    private static ScheduleReportConfig webhookReport(String webhookUrl, String webhookBearerToken) {
        return ScheduleReportConfig.builder()
                .channelType("webhook")
                .webhookUrl(webhookUrl)
                .webhookBearerToken(webhookBearerToken)
                .build();
    }
}
