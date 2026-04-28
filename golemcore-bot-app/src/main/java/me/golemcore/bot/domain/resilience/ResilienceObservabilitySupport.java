package me.golemcore.bot.domain.resilience;

/*
 * Copyright 2026 Aleksei Kuleshov
 * SPDX-License-Identifier: Apache-2.0
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.tracing.TraceRuntimeConfigSupport;
import me.golemcore.bot.domain.tracing.TraceService;
import me.golemcore.bot.domain.runtimeconfig.RuntimeConfigService;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.port.outbound.TraceSnapshotCodecPort;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Small shared helpers for resilience observability.
 *
 * <p>
 * Emits rollout counters as structured session-metric logs, mirrors them into
 * trace events when tracing is active, and captures sampled redacted payload
 * snapshots for classifier analysis.
 */
public final class ResilienceObservabilitySupport {

    private static final int SAMPLE_BUCKETS = 10_000;
    private static final int MAX_TEXT_LENGTH = 2_000;
    private static final String REDACTED = "[REDACTED]";
    private static final Set<String> SENSITIVE_KEY_FRAGMENTS = Set.of(
            "password",
            "secret",
            "token",
            "api_key",
            "apikey",
            "authorization",
            "auth",
            "cookie");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile(
            "(?i)(password\\s*[:=]\\s*['\"]?)[^'\"\\s]+");
    private static final Pattern API_KEY_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key\\s*[:=]\\s*['\"]?)[^'\"\\s]+");
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(secret\\s*[:=]\\s*['\"]?)[^'\"\\s]+");
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "(?i)(token\\s*[:=]\\s*['\"]?)[^'\"\\s]+");
    private static final Pattern AUTHORIZATION_BEARER_PATTERN = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*['\"]?bearer\\s+)[A-Za-z0-9\\-._~+/]+=*");
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)Bearer\\s+[A-Za-z0-9\\-._~+/]+=*");

    private ResilienceObservabilitySupport() {
    }

    public static void emitContextMetric(Logger log, TraceService traceService,
            RuntimeConfigService runtimeConfigService,
            Clock clock, AgentContext context, String metricName, Map<String, Object> attributes) {
        if (log == null || metricName == null || metricName.isBlank()) {
            return;
        }
        Map<String, Object> normalizedAttributes = normalizeAttributes(attributes);
        log.info("[SessionMetrics] metric={} value=1 sessionId={} traceId={} attrs={}",
                metricName,
                sessionId(context),
                traceId(context),
                normalizedAttributes);
        appendTraceEvent(traceService, runtimeConfigService, clock, context, metricName, normalizedAttributes, log);
    }

    public static void emitMessageMetric(Logger log, String metricName, Message message,
            Map<String, Object> attributes) {
        if (log == null || metricName == null || metricName.isBlank() || message == null) {
            return;
        }
        Map<String, Object> normalizedAttributes = normalizeAttributes(attributes);
        log.info("[SessionMetrics] metric={} value=1 channel={} chatId={} internalKind={} queueKind={} attrs={}",
                metricName,
                message.getChannelType(),
                message.getChatId(),
                readMetadataString(message, me.golemcore.bot.domain.model.ContextAttributes.MESSAGE_INTERNAL_KIND),
                readMetadataString(message, me.golemcore.bot.domain.model.ContextAttributes.TURN_QUEUE_KIND),
                normalizedAttributes);
    }

    public static void captureSampledPayload(Logger log, TraceService traceService,
            TraceSnapshotCodecPort traceSnapshotCodecPort,
            RuntimeConfigService runtimeConfigService,
            Clock clock,
            AgentContext context,
            String samplingKey,
            String role,
            Map<String, Object> payload) {
        if (log == null || traceService == null || traceSnapshotCodecPort == null || runtimeConfigService == null
                || clock == null || context == null || context.getSession() == null || context.getTraceContext() == null
                || role == null || role.isBlank()) {
            return;
        }
        double sampleRate = runtimeConfigService.getTraceResiliencePayloadSampleRate();
        if (sampleRate <= 0.0d) {
            return;
        }
        RuntimeConfig.TracingConfig tracingConfig = TraceRuntimeConfigSupport.resolve(runtimeConfigService,
                shouldForceTracingPayloadCapture(runtimeConfigService));
        if (tracingConfig == null || !Boolean.TRUE.equals(tracingConfig.getEnabled())) {
            return;
        }
        if (!shouldSample(context, samplingKey, sampleRate)) {
            return;
        }
        tracingConfig.setPayloadSnapshotsEnabled(true);
        byte[] encodedPayload = traceSnapshotCodecPort.encodeJson(sanitizePayload(payload));
        try {
            traceService.captureSnapshot(context.getSession(), context.getTraceContext(), tracingConfig,
                    role, "application/json", encodedPayload);
        } catch (RuntimeException exception) { // NOSONAR - observability must not break turn flow
            log.debug("[ResilienceObservability] failed to capture sampled payload for {}: {}",
                    role, exception.getMessage());
        }
    }

    public static boolean markObservedOnce(AgentContext context, String attributeKey) {
        if (context == null || attributeKey == null || attributeKey.isBlank()) {
            return false;
        }
        if (Boolean.TRUE.equals(context.getAttribute(attributeKey))) {
            return false;
        }
        context.setAttribute(attributeKey, true);
        return true;
    }

    private static void appendTraceEvent(TraceService traceService, RuntimeConfigService runtimeConfigService,
            Clock clock, AgentContext context, String metricName, Map<String, Object> attributes, Logger log) {
        if (traceService == null || runtimeConfigService == null || !runtimeConfigService.isTracingEnabled()
                || clock == null || context == null || context.getSession() == null
                || context.getTraceContext() == null) {
            return;
        }
        try {
            traceService.appendEvent(context.getSession(), context.getTraceContext(), metricName,
                    Instant.now(clock), attributes);
        } catch (RuntimeException exception) { // NOSONAR - observability must not break turn flow
            if (log != null) {
                log.debug("[ResilienceObservability] failed to append trace event {}: {}",
                        metricName, exception.getMessage());
            }
        }
    }

    private static boolean shouldForceTracingPayloadCapture(RuntimeConfigService runtimeConfigService) {
        return runtimeConfigService != null
                && runtimeConfigService.isSelfEvolvingEnabled()
                && runtimeConfigService.isSelfEvolvingTracePayloadOverrideEnabled();
    }

    private static boolean shouldSample(AgentContext context, String samplingKey, double sampleRate) {
        if (sampleRate >= 1.0d) {
            return true;
        }
        String seed = buildSamplingSeed(context, samplingKey);
        if (seed == null || seed.isBlank()) {
            return false;
        }
        long hash = Integer.toUnsignedLong(seed.hashCode());
        double bucket = (double) (hash % SAMPLE_BUCKETS) / SAMPLE_BUCKETS;
        return bucket < sampleRate;
    }

    private static String buildSamplingSeed(AgentContext context, String samplingKey) {
        String prefix = samplingKey != null && !samplingKey.isBlank() ? samplingKey : "resilience";
        if (context != null && context.getTraceContext() != null && context.getTraceContext().getTraceId() != null
                && !context.getTraceContext().getTraceId().isBlank()) {
            return prefix + ":" + context.getTraceContext().getTraceId();
        }
        if (context != null && context.getSession() != null && context.getSession().getId() != null
                && !context.getSession().getId().isBlank()) {
            return prefix + ":" + context.getSession().getId();
        }
        if (context != null && context.getSession() != null && context.getSession().getChatId() != null
                && !context.getSession().getChatId().isBlank()) {
            return prefix + ":" + context.getSession().getChatId();
        }
        return null;
    }

    private static Object sanitizePayload(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                String key = entry.getKey() != null ? String.valueOf(entry.getKey()) : "null";
                if (isSensitiveKey(key)) {
                    sanitized.put(key, REDACTED);
                } else {
                    sanitized.put(key, sanitizePayload(entry.getValue()));
                }
            }
            return sanitized;
        }
        if (value instanceof List<?> listValue) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : listValue) {
                sanitized.add(sanitizePayload(item));
            }
            return sanitized;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name().toLowerCase(Locale.ROOT);
        }
        if (value instanceof CharSequence sequence) {
            return redactString(sequence.toString());
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return redactString(String.valueOf(value));
    }

    private static Map<String, Object> normalizeAttributes(Map<String, Object> attributes) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (attributes == null) {
            return normalized;
        }
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            if (isSensitiveKey(entry.getKey())) {
                normalized.put(entry.getKey(), REDACTED);
            } else {
                normalized.put(entry.getKey(), sanitizePayload(entry.getValue()));
            }
        }
        return normalized;
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (String fragment : SENSITIVE_KEY_FRAGMENTS) {
            if (lower.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static String redactString(String value) {
        if (value == null) {
            return null;
        }
        String redacted = value;
        redacted = PASSWORD_PATTERN.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = API_KEY_PATTERN.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = SECRET_PATTERN.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = TOKEN_PATTERN.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = AUTHORIZATION_BEARER_PATTERN.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = BEARER_PATTERN.matcher(redacted).replaceAll("Bearer " + REDACTED);
        redacted = redactEmails(redacted);
        if (redacted.length() > MAX_TEXT_LENGTH) {
            return redacted.substring(0, MAX_TEXT_LENGTH - 3) + "...";
        }
        return redacted;
    }

    private static String redactEmails(String value) {
        StringBuilder builder = new StringBuilder(value);
        int cursor = 0;
        while (cursor < builder.length()) {
            int atIndex = builder.indexOf("@", cursor);
            if (atIndex < 0) {
                break;
            }
            int start = atIndex;
            while (start > 0 && isEmailLocalPartChar(builder.charAt(start - 1))) {
                start--;
            }
            int end = atIndex + 1;
            while (end < builder.length() && isEmailDomainChar(builder.charAt(end))) {
                end++;
            }
            if (isEmailCandidate(builder, start, atIndex, end)) {
                builder.replace(start, end, REDACTED);
                cursor = start + REDACTED.length();
            } else {
                cursor = atIndex + 1;
            }
        }
        return builder.toString();
    }

    private static boolean isEmailCandidate(CharSequence value, int start, int atIndex, int end) {
        if (start >= atIndex || atIndex + 1 >= end) {
            return false;
        }
        String domain = value.subSequence(atIndex + 1, end).toString();
        int lastDot = domain.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == domain.length() - 1) {
            return false;
        }
        for (int index = lastDot + 1; index < domain.length(); index++) {
            if (!Character.isLetter(domain.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isEmailLocalPartChar(char value) {
        return Character.isLetterOrDigit(value)
                || value == '.'
                || value == '_'
                || value == '%'
                || value == '+'
                || value == '-';
    }

    private static boolean isEmailDomainChar(char value) {
        return Character.isLetterOrDigit(value) || value == '.' || value == '-';
    }

    private static String sessionId(AgentContext context) {
        if (context == null || context.getSession() == null) {
            return null;
        }
        return context.getSession().getId();
    }

    private static String traceId(AgentContext context) {
        if (context == null || context.getTraceContext() == null) {
            return null;
        }
        return context.getTraceContext().getTraceId();
    }

    private static String readMetadataString(Message message, String key) {
        if (message == null || message.getMetadata() == null || key == null || key.isBlank()) {
            return null;
        }
        Object value = message.getMetadata().get(key);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue : null;
    }
}
