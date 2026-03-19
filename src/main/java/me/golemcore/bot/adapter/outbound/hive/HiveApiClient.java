package me.golemcore.bot.adapter.outbound.hive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.service.HiveJoinCodeParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HiveApiClient {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final Integer HIVE_EVENT_SCHEMA_VERSION = 1;

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public GolemAuthResponse register(
            String serverUrl,
            String enrollmentToken,
            String displayName,
            String hostLabel,
            String runtimeVersion,
            String buildVersion,
            Set<String> supportedChannels) {
        RegisterRequest request = new RegisterRequest(
                enrollmentToken,
                displayName,
                hostLabel,
                runtimeVersion,
                buildVersion,
                supportedChannels);
        return postJson(serverUrl, "/api/v1/golems/register", request, null, GolemAuthResponse.class);
    }

    public GolemAuthResponse rotate(String serverUrl, String golemId, String refreshToken) {
        return postJson(serverUrl,
                "/api/v1/golems/" + golemId + "/auth:rotate",
                new RefreshTokenRequest(refreshToken),
                null,
                GolemAuthResponse.class);
    }

    public void heartbeat(
            String serverUrl,
            String golemId,
            String accessToken,
            String status,
            String healthSummary,
            String lastErrorSummary,
            Long uptimeSeconds) {
        HeartbeatRequest request = new HeartbeatRequest(
                status,
                null,
                null,
                null,
                null,
                0L,
                0L,
                0L,
                0,
                healthSummary,
                lastErrorSummary,
                uptimeSeconds,
                null);
        postJson(serverUrl,
                "/api/v1/golems/" + golemId + "/heartbeat",
                request,
                accessToken,
                JsonNode.class);
    }

    public void publishEventsBatch(
            String serverUrl,
            String golemId,
            String accessToken,
            List<HiveEventPayload> events) {
        postJson(serverUrl,
                "/api/v1/golems/" + golemId + "/events:batch",
                new HiveEventBatchRequest(HIVE_EVENT_SCHEMA_VERSION, golemId, events),
                accessToken,
                JsonNode.class);
    }

    private <T> T postJson(
            String serverUrl,
            String path,
            Object payload,
            String bearerToken,
            Class<T> responseType) {
        String normalizedServerUrl = HiveJoinCodeParser.normalizeServerUrl(serverUrl);
        String url = normalizedServerUrl + path;
        try {
            String json = objectMapper.writeValueAsString(payload);
            RequestBody body = RequestBody.create(json, JSON_MEDIA_TYPE);
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .post(body)
                    .header("Accept", "application/json");
            if (bearerToken != null && !bearerToken.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + bearerToken);
            }
            try (Response response = okHttpClient.newCall(requestBuilder.build()).execute()) {
                String responseBody = response.body().string();
                if (!response.isSuccessful()) {
                    throw new HiveApiException(response.code(), extractMessage(responseBody));
                }
                return objectMapper.readValue(responseBody, responseType);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to call Hive API at " + url, exception);
        }
    }

    private String extractMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "Hive API request failed";
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode message = root.path("message");
            if (message.isTextual() && !message.asText().isBlank()) {
                return message.asText();
            }
        } catch (IOException ignored) {
            // Fall back to the raw response body.
        }
        return responseBody;
    }

    private record RegisterRequest(
            String enrollmentToken,
            String displayName,
            String hostLabel,
            String runtimeVersion,
            String buildVersion,
            Set<String> supportedChannels) {
    }

    private record RefreshTokenRequest(String refreshToken) {
    }

    private record HeartbeatRequest(
            String status,
            String currentRunState,
            String currentCardId,
            String currentThreadId,
            String modelTier,
            Long inputTokens,
            Long outputTokens,
            Long accumulatedCostMicros,
            Integer queueDepth,
            String healthSummary,
            String lastErrorSummary,
            Long uptimeSeconds,
            String capabilitySnapshotHash) {
    }

    public record GolemAuthResponse(
            String golemId,
            String accessToken,
            String refreshToken,
            java.time.Instant accessTokenExpiresAt,
            java.time.Instant refreshTokenExpiresAt,
            String issuer,
            String audience,
            String controlChannelUrl,
            int heartbeatIntervalSeconds,
            List<String> scopes) {
    }

    public static class HiveApiException extends IllegalStateException {

        private final int statusCode;

        public HiveApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
