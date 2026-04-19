package me.golemcore.bot.adapter.inbound.web.logstream;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import me.golemcore.bot.adapter.inbound.web.dto.LogEntryDto;
import me.golemcore.bot.infrastructure.config.BotProperties;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

@Service
public class DashboardLogService {

    private static final int MIN_PAGE_SIZE = 1;
    private static final String TRUNCATED_SUFFIX = "... [truncated]";
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._\\-+/=]+");
    private static final Pattern JSON_SECRET_PATTERN = Pattern.compile(
            "(?i)(\"(?:api[_-]?key|token|password|secret)\"\\s*:\\s*\")([^\"]+)(\")");
    private static final Pattern KEY_VALUE_SECRET_PATTERN = Pattern.compile(
            "(?i)((?:api[_-]?key|token|password|secret)\\s*[:=]\\s*)([^\\s,;]+)");

    private final Object lock = new Object();
    private final Deque<LogEntryDto> ringBuffer;
    private final AtomicLong sequence = new AtomicLong(0);
    private final Sinks.Many<LogEntryDto> liveStream;
    private final boolean enabled;
    private final int maxEntries;
    private final int defaultPageSize;
    private final int maxPageSize;
    private final int maxMessageChars;
    private final int maxExceptionChars;

    public DashboardLogService(BotProperties botProperties) {
        BotProperties.LogsProperties logsProperties = botProperties.getDashboard().getLogs();
        this.enabled = logsProperties.isEnabled();
        this.maxEntries = normalizePositive(logsProperties.getMaxEntries(), 10000);
        this.defaultPageSize = normalizePositive(logsProperties.getDefaultPageSize(), 200);
        this.maxPageSize = normalizePositive(logsProperties.getMaxPageSize(), 1000);
        this.maxMessageChars = normalizePositive(logsProperties.getMaxMessageChars(), 8000);
        this.maxExceptionChars = normalizePositive(logsProperties.getMaxExceptionChars(), 16000);
        this.ringBuffer = new ArrayDeque<>(this.maxEntries);
        this.liveStream = Sinks.many().replay().limit(this.maxEntries);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public LogsSlice getLogsPage(Long beforeSeq, Integer limit) {
        if (!enabled) {
            return new LogsSlice(List.of(), null, null, false);
        }

        int pageSize = normalizePageSize(limit);

        List<LogEntryDto> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(ringBuffer);
        }

        if (snapshot.isEmpty()) {
            return new LogsSlice(List.of(), null, null, false);
        }

        Long oldestSeq = snapshot.get(0).getSeq();
        Long newestSeq = snapshot.get(snapshot.size() - 1).getSeq();

        Deque<LogEntryDto> page = new ArrayDeque<>();
        for (int i = snapshot.size() - 1; i >= 0 && page.size() < pageSize; i--) {
            LogEntryDto entry = snapshot.get(i);
            if (beforeSeq != null && entry.getSeq() >= beforeSeq) {
                continue;
            }
            page.addFirst(entry);
        }

        List<LogEntryDto> items = List.copyOf(page);
        boolean hasMore = computeHasMore(beforeSeq, oldestSeq, items);
        return new LogsSlice(items, oldestSeq, newestSeq, hasMore);
    }

    public Flux<LogEntryDto> streamAfter(long afterSeq) {
        if (!enabled) {
            return Flux.empty();
        }
        return liveStream.asFlux()
                .filter(entry -> entry.getSeq() > afterSeq);
    }

    public void append(ILoggingEvent event) {
        if (!enabled || event == null) {
            return;
        }

        String loggerName = event.getLoggerName();
        if (loggerName != null && loggerName.startsWith("me.golemcore.bot.adapter.inbound.web.logstream")) {
            return;
        }

        LogEntryDto entry = LogEntryDto.builder()
                .seq(sequence.incrementAndGet())
                .timestamp(Instant.ofEpochMilli(event.getTimeStamp()).toString())
                .level(event.getLevel() != null ? event.getLevel().toString() : "INFO")
                .logger(loggerName)
                .thread(event.getThreadName())
                .message(truncate(sanitize(event.getFormattedMessage()), maxMessageChars))
                .exception(extractException(event.getThrowableProxy()))
                .build();

        synchronized (lock) {
            if (ringBuffer.size() >= maxEntries) {
                ringBuffer.removeFirst();
            }
            ringBuffer.addLast(entry);
        }

        liveStream.tryEmitNext(entry);
    }

    private boolean computeHasMore(Long beforeSeq, Long oldestSeq, List<LogEntryDto> items) {
        if (items.isEmpty()) {
            return beforeSeq != null && oldestSeq != null && oldestSeq < beforeSeq;
        }
        return oldestSeq != null && oldestSeq < items.get(0).getSeq();
    }

    private int normalizePageSize(Integer requested) {
        int candidate = requested != null ? requested : defaultPageSize;
        if (candidate < MIN_PAGE_SIZE) {
            return MIN_PAGE_SIZE;
        }
        return Math.min(candidate, maxPageSize);
    }

    private int normalizePositive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private String extractException(IThrowableProxy throwableProxy) {
        if (throwableProxy == null) {
            return null;
        }
        return truncate(sanitize(ThrowableProxyUtil.asString(throwableProxy)), maxExceptionChars);
    }

    private String sanitize(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        String sanitized = BEARER_TOKEN_PATTERN.matcher(input).replaceAll("$1***");
        sanitized = JSON_SECRET_PATTERN.matcher(sanitized).replaceAll("$1***$3");
        return KEY_VALUE_SECRET_PATTERN.matcher(sanitized).replaceAll("$1***");
    }

    private String truncate(String input, int maxLength) {
        if (input == null || input.length() <= maxLength) {
            return input;
        }
        int endIndex = Math.max(0, maxLength - TRUNCATED_SUFFIX.length());
        return input.substring(0, endIndex) + TRUNCATED_SUFFIX;
    }

    public record LogsSlice(List<LogEntryDto> items, Long oldestSeq, Long newestSeq, boolean hasMore) {
    }
}
