package me.golemcore.bot.adapter.inbound.web.logstream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import me.golemcore.bot.adapter.inbound.web.dto.LogEntryDto;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
