package me.golemcore.bot.infrastructure.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private static final String POSTHOG_API_HOST = "https://us.i.posthog.com";
    @SuppressWarnings("java:S6418") // Not a secret — public-facing, write-only PostHog ingest token
    private static final String POSTHOG_PROJECT_TOKEN = "phc_xHNGFVgA7U95Ec6nFg56cWNNbUHxDtMTpy8BXnjQRSP5";

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public void capture(String eventName, String distinctId, Map<String, Object> properties) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("api_key", POSTHOG_PROJECT_TOKEN);
        payload.put("event", eventName);
        payload.put("distinct_id", distinctId);
        payload.put("properties", properties != null ? properties : Map.of());

        try {
            RequestBody body = RequestBody.create(objectMapper.writeValueAsString(payload), JSON_MEDIA_TYPE);
            Request request = new Request.Builder()
                    .url(POSTHOG_API_HOST + "/capture/")
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

}
