package me.golemcore.bot.adapter.inbound.web.logstream;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardLogAppenderRegistrarTest {

    private static final String APPENDER_NAME = "DASHBOARD_LOG_STREAM";

    private Logger rootLogger;
    private Appender<ILoggingEvent> originalAppender;

    @BeforeEach
    void setUp() {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        @SuppressWarnings("unchecked")
        Appender<ILoggingEvent> existing = (Appender<ILoggingEvent>) rootLogger.getAppender(APPENDER_NAME);
        originalAppender = existing;
        if (originalAppender != null) {
            rootLogger.detachAppender(APPENDER_NAME);
        }
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(APPENDER_NAME);
        if (originalAppender != null) {
            rootLogger.addAppender(originalAppender);
        }
    }

    @Test
    void shouldRegisterAppenderWhenEnabledAndForwardEvents() {
        DashboardLogService service = mock(DashboardLogService.class);
        when(service.isEnabled()).thenReturn(true);
        DashboardLogAppenderRegistrar registrar = new DashboardLogAppenderRegistrar(service);

        registrar.registerAppender();

        @SuppressWarnings("unchecked")
        Appender<ILoggingEvent> appender = (Appender<ILoggingEvent>) rootLogger.getAppender(APPENDER_NAME);
        assertNotNull(appender);
        ILoggingEvent event = mock(ILoggingEvent.class);
        appender.doAppend(event);
        verify(service, atLeastOnce()).append(event);

        registrar.unregisterAppender();
        assertNull(rootLogger.getAppender(APPENDER_NAME));
    }

    @Test
    void shouldNotRegisterAppenderWhenDisabled() {
        DashboardLogService service = mock(DashboardLogService.class);
        when(service.isEnabled()).thenReturn(false);
        DashboardLogAppenderRegistrar registrar = new DashboardLogAppenderRegistrar(service);

        registrar.registerAppender();

        assertNull(rootLogger.getAppender(APPENDER_NAME));
    }

    @Test
    void shouldRegisterAppenderOnlyOnceOnRepeatedCalls() {
        DashboardLogService service = mock(DashboardLogService.class);
        when(service.isEnabled()).thenReturn(true);
        DashboardLogAppenderRegistrar registrar = new DashboardLogAppenderRegistrar(service);

        registrar.registerAppender();
        registrar.registerAppender();

        assertEquals(1, countAppendersByName(APPENDER_NAME));
    }

    @Test
    void shouldHandleUnregisterWhenAppenderWasNotRegistered() {
        DashboardLogService service = mock(DashboardLogService.class);
        when(service.isEnabled()).thenReturn(false);
        DashboardLogAppenderRegistrar registrar = new DashboardLogAppenderRegistrar(service);

        registrar.unregisterAppender();

        assertNull(rootLogger.getAppender(APPENDER_NAME));
    }

    private int countAppendersByName(String appenderName) {
        int count = 0;
        @SuppressWarnings("unchecked")
        Iterator<Appender<ILoggingEvent>> iterator = (Iterator<Appender<ILoggingEvent>>) (Iterator<?>) rootLogger
                .iteratorForAppenders();
        while (iterator.hasNext()) {
            Appender<ILoggingEvent> appender = iterator.next();
            if (appenderName.equals(appender.getName())) {
                count++;
            }
        }
        return count;
    }
}
