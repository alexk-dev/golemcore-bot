package me.golemcore.bot.domain.model.telemetry;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UiTelemetryRollup {

    private String anonymousId;
    private Integer schemaVersion;
    private String release;
    private Instant periodStart;
    private Instant periodEnd;
    private Integer bucketMinutes;

    @Builder.Default
    private Usage usage = new Usage();

    @Builder.Default
    private Errors errors = new Errors();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Usage {
        @Builder.Default
        private Map<String, Long> counters = new LinkedHashMap<>();

        @Builder.Default
        private Map<String, Map<String, Long>> byRoute = new LinkedHashMap<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Errors {
        @Builder.Default
        private List<ErrorGroup> groups = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
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
