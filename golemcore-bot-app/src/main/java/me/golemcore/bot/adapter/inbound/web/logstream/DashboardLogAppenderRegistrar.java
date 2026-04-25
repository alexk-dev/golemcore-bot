package me.golemcore.bot.adapter.inbound.web.logstream;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DashboardLogAppenderRegistrar {

    private static final String APPENDER_NAME = "DASHBOARD_LOG_STREAM";

    private final DashboardLogService dashboardLogService;
    private AppenderBase<ILoggingEvent> appender;

    @PostConstruct
    void registerAppender() {
        if (!dashboardLogService.isEnabled()) {
            return;
        }

        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        if (rootLogger.getAppender(APPENDER_NAME) != null) {
            return;
        }

        appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent eventObject) {
                dashboardLogService.append(eventObject);
            }
        };
        appender.setContext(loggerContext);
        appender.setName(APPENDER_NAME);
        appender.start();
        rootLogger.addAppender(appender);
    }

    @PreDestroy
    void unregisterAppender() {
        if (appender == null) {
            return;
        }
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.detachAppender(APPENDER_NAME);
        appender.stop();
    }
}
