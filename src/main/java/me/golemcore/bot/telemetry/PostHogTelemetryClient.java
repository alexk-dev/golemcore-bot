package me.golemcore.bot.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.infrastructure.config.BotProperties;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class PostHogTelemetryClient {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final BotProperties botProperties;

    public void capture(String eventName, String distinctId, Map<String, Object> properties) {
        BotProperties.TelemetryProperties telemetry = botProperties.getTelemetry();
        if (telemetry == null) {
            log.warn("Telemetry configuration is not set, skipping PostHog capture for event: {}", eventName);
            return;
        }
        String apiHost = normalizeApiHost(telemetry.getApiHost());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("api_key", telemetry.getApiKey());
        payload.put("event", eventName);
        payload.put("distinct_id", distinctId);
        payload.put("properties", properties != null ? properties : Map.of());

        try {
            RequestBody body = RequestBody.create(objectMapper.writeValueAsString(payload), JSON_MEDIA_TYPE);
            Request request = new Request.Builder()
                    .url(apiHost + "/capture/")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .post(body)
                    .build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IllegalStateException("PostHog capture failed with status " + response.code());
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to publish PostHog telemetry", exception);
        }
    }

    private String normalizeApiHost(String apiHost) {
        if (apiHost == null || apiHost.isBlank()) {
            return "https://us.i.posthog.com";
        }
        return apiHost.endsWith("/") ? apiHost.substring(0, apiHost.length() - 1) : apiHost;
    }
}
