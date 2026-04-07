package me.golemcore.bot.adapter.inbound.web.dto;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class TelemetryRollupRequest {

    private String anonymousId;
    private Integer schemaVersion;
    private String release;
    private Instant periodStart;
    private Instant periodEnd;
    private Integer bucketMinutes;
    private Usage usage = new Usage();
    private Errors errors = new Errors();

    @Data
    public static class Usage {
        private Map<String, Long> counters = new LinkedHashMap<>();
        private Map<String, Map<String, Long>> byRoute = new LinkedHashMap<>();
    }

    @Data
    public static class Errors {
        private List<ErrorGroup> groups = new ArrayList<>();
    }

    @Data
    public static class ErrorGroup {
        private String fingerprint;
        private String route;
        private String errorName;
        private String message;
        private String source;
        private String componentStack;
        private Integer count;
    }
}
