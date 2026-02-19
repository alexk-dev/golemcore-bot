package me.golemcore.bot.adapter.inbound.web.logstream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import me.golemcore.bot.adapter.inbound.web.dto.LogEntryDto;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardLogServiceTest {

    @Test
    void shouldMaskSecretsInLogMessageAndException() {
        BotProperties props = baseProperties();
        DashboardLogService service = new DashboardLogService(props);

        service.append(loggingEvent(
                "me.golemcore.bot.Test",
                "Authorization: Bearer abc123 token=secretValue password:superSecret",
                new IllegalStateException("api_key=my-key")));

        DashboardLogService.LogsSlice slice = service.getLogsPage(null, 10);
        assertEquals(1, slice.items().size());

        LogEntryDto entry = slice.items().get(0);
        assertNotNull(entry.getMessage());
        assertTrue(entry.getMessage().contains("Bearer ***"));
        assertTrue(entry.getMessage().contains("token=***"));
        assertTrue(entry.getMessage().contains("password:***"));
        assertNotNull(entry.getException());
        assertTrue(entry.getException().contains("api_key=***"));
    }

    @Test
    void shouldReturnPagedHistoryFromTailToOlderEntries() {
        BotProperties props = baseProperties();
        props.getDashboard().getLogs().setMaxEntries(10);
        DashboardLogService service = new DashboardLogService(props);

        for (int i = 1; i <= 5; i++) {
            service.append(loggingEvent("me.golemcore.bot.Test", "message-" + i, null));
        }

        DashboardLogService.LogsSlice tail = service.getLogsPage(null, 2);
        assertEquals(List.of(4L, 5L), tail.items().stream().map(LogEntryDto::getSeq).toList());
        assertTrue(tail.hasMore());
        assertEquals(1L, tail.oldestSeq());
        assertEquals(5L, tail.newestSeq());

        DashboardLogService.LogsSlice older = service.getLogsPage(4L, 2);
        assertEquals(List.of(2L, 3L), older.items().stream().map(LogEntryDto::getSeq).toList());
        assertTrue(older.hasMore());

        DashboardLogService.LogsSlice oldest = service.getLogsPage(2L, 10);
        assertEquals(List.of(1L), oldest.items().stream().map(LogEntryDto::getSeq).toList());
        assertFalse(oldest.hasMore());
    }

    @Test
    void shouldRespectRingBufferSize() {
        BotProperties props = baseProperties();
        props.getDashboard().getLogs().setMaxEntries(3);
        DashboardLogService service = new DashboardLogService(props);

        for (int i = 1; i <= 5; i++) {
            service.append(loggingEvent("me.golemcore.bot.Test", "message-" + i, null));
        }

        DashboardLogService.LogsSlice slice = service.getLogsPage(null, 10);
        assertEquals(List.of(3L, 4L, 5L), slice.items().stream().map(LogEntryDto::getSeq).toList());
        assertEquals(3L, slice.oldestSeq());
        assertEquals(5L, slice.newestSeq());
    }

    @Test
    void shouldReturnEmptyDataWhenLogsDisabled() {
        BotProperties props = baseProperties();
        props.getDashboard().getLogs().setEnabled(false);
        DashboardLogService service = new DashboardLogService(props);

        service.append(loggingEvent("me.golemcore.bot.Test", "message", null));

        DashboardLogService.LogsSlice slice = service.getLogsPage(null, 10);
        assertTrue(slice.items().isEmpty());
        assertNull(slice.oldestSeq());
        assertNull(slice.newestSeq());
        assertFalse(slice.hasMore());

        StepVerifier.create(service.streamAfter(0))
                .verifyComplete();
    }

    @Test
    void shouldIgnoreInternalLogstreamLoggerEntries() {
        BotProperties props = baseProperties();
        DashboardLogService service = new DashboardLogService(props);

        service.append(loggingEvent(
                "me.golemcore.bot.adapter.inbound.web.logstream.DashboardLogService",
                "internal",
                null));
        service.append(loggingEvent("me.golemcore.bot.Test", "external", null));

        DashboardLogService.LogsSlice slice = service.getLogsPage(null, 10);
        assertEquals(1, slice.items().size());
        assertEquals("external", slice.items().get(0).getMessage());
    }

    @Test
    void shouldApplyPageSizeBounds() {
        BotProperties props = baseProperties();
        props.getDashboard().getLogs().setDefaultPageSize(2);
        props.getDashboard().getLogs().setMaxPageSize(3);
        DashboardLogService service = new DashboardLogService(props);

        for (int i = 1; i <= 5; i++) {
            service.append(loggingEvent("me.golemcore.bot.Test", "message-" + i, null));
        }

        DashboardLogService.LogsSlice withNegativeLimit = service.getLogsPage(null, -10);
        assertEquals(1, withNegativeLimit.items().size());
        assertEquals(5L, withNegativeLimit.items().get(0).getSeq());

        DashboardLogService.LogsSlice withTooLargeLimit = service.getLogsPage(null, 50);
        assertEquals(3, withTooLargeLimit.items().size());
        assertEquals(List.of(3L, 4L, 5L), withTooLargeLimit.items().stream().map(LogEntryDto::getSeq).toList());
    }

    @Test
    void shouldTruncateLongMessageAndException() {
        BotProperties props = baseProperties();
        props.getDashboard().getLogs().setMaxMessageChars(20);
        props.getDashboard().getLogs().setMaxExceptionChars(30);
        DashboardLogService service = new DashboardLogService(props);

        RuntimeException exception = new RuntimeException("this exception message is very long for truncation checks");
        service.append(loggingEvent(
                "me.golemcore.bot.Test",
                "this is a very long log line that must be truncated",
                exception));

        DashboardLogService.LogsSlice slice = service.getLogsPage(null, 10);
        LogEntryDto entry = slice.items().get(0);
        assertNotNull(entry.getMessage());
        assertTrue(entry.getMessage().endsWith("... [truncated]"));
        assertTrue(entry.getMessage().length() <= 20);
        assertNotNull(entry.getException());
        assertTrue(entry.getException().endsWith("... [truncated]"));
        assertTrue(entry.getException().length() <= 30);
    }

    @Test
    void shouldStreamOnlyEventsAfterRequestedSequence() {
        BotProperties props = baseProperties();
        DashboardLogService service = new DashboardLogService(props);

        service.append(loggingEvent("me.golemcore.bot.Test", "message-1", null));
        service.append(loggingEvent("me.golemcore.bot.Test", "message-2", null));
        service.append(loggingEvent("me.golemcore.bot.Test", "message-3", null));

        StepVerifier.create(service.streamAfter(2).take(1))
                .assertNext(entry -> {
                    assertEquals(3L, entry.getSeq());
                    assertEquals("message-3", entry.getMessage());
                })
                .verifyComplete();
    }

    private BotProperties baseProperties() {
        BotProperties props = new BotProperties();
        props.getDashboard().getLogs().setEnabled(true);
        props.getDashboard().getLogs().setMaxEntries(100);
        props.getDashboard().getLogs().setDefaultPageSize(10);
        props.getDashboard().getLogs().setMaxPageSize(100);
        props.getDashboard().getLogs().setMaxMessageChars(2000);
        props.getDashboard().getLogs().setMaxExceptionChars(2000);
        return props;
    }

    private ILoggingEvent loggingEvent(String loggerName, String message, Throwable throwable) {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getLoggerName()).thenReturn(loggerName);
        when(event.getFormattedMessage()).thenReturn(message);
        when(event.getThreadName()).thenReturn("main");
        when(event.getLevel()).thenReturn(Level.INFO);
        when(event.getTimeStamp()).thenReturn(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());
        if (throwable != null) {
            when(event.getThrowableProxy()).thenReturn(new ThrowableProxy(throwable));
        }
        return event;
    }
}
