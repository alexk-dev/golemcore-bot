package me.golemcore.bot.adapter.outbound.hive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import me.golemcore.bot.domain.model.hive.HiveCapabilitySnapshot;
import me.golemcore.bot.domain.model.hive.HiveCardDetail;
import me.golemcore.bot.domain.model.hive.HiveCardSearchRequest;
import me.golemcore.bot.domain.model.hive.HiveCardSummary;
import me.golemcore.bot.domain.model.hive.HiveCreateCardRequest;
import me.golemcore.bot.domain.model.hive.HivePolicyApplyResult;
import me.golemcore.bot.domain.model.hive.HivePolicyPackage;
import me.golemcore.bot.domain.model.hive.HiveRequestReviewRequest;
import me.golemcore.bot.domain.model.hive.HiveThreadMessage;
import me.golemcore.bot.domain.service.HiveJoinCodeParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

@Component
public class HiveApiClient {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final Integer HIVE_EVENT_SCHEMA_VERSION = 1;

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public HiveApiClient(
            OkHttpClient okHttpClient,
            ObjectMapper objectMapper) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
    }

    public GolemAuthResponse register(
            String serverUrl,
            String enrollmentToken,
            String displayName,
            String hostLabel,
            String runtimeVersion,
            String buildVersion,
            Set<String> supportedChannels,
            HiveCapabilitySnapshot capabilities) {
        RegisterRequest request = new RegisterRequest(
                enrollmentToken,
                displayName,
                hostLabel,
                runtimeVersion,
                buildVersion,
                supportedChannels,
                capabilities);
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
            Long uptimeSeconds,
            String capabilitySnapshotHash,
            String policyGroupId,
            Integer targetPolicyVersion,
            Integer appliedPolicyVersion,
            String syncStatus,
            String lastPolicyErrorDigest,
            String dashboardBaseUrl) {
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
                capabilitySnapshotHash,
                policyGroupId,
                targetPolicyVersion,
                appliedPolicyVersion,
                syncStatus,
                lastPolicyErrorDigest,
                dashboardBaseUrl);
        postJson(serverUrl,
                "/api/v1/golems/" + golemId + "/heartbeat",
                request,
                accessToken,
                JsonNode.class);
    }

    public HivePolicyPackage getPolicyPackage(String serverUrl, String golemId, String accessToken) {
        return getJson(serverUrl, "/api/v1/golems/" + golemId + "/policy-package", accessToken,
                HivePolicyPackage.class);
    }

    public HivePolicyApplyResult reportPolicyApplyResult(
            String serverUrl,
            String golemId,
            String accessToken,
            HivePolicyApplyResult applyResult) {
        return postJson(serverUrl,
                "/api/v1/golems/" + golemId + "/policy-apply-result",
                new PolicyApplyResultRequest(
                        applyResult.getPolicyGroupId(),
                        applyResult.getTargetVersion(),
                        applyResult.getAppliedVersion(),
                        applyResult.getSyncStatus(),
                        applyResult.getChecksum(),
                        applyResult.getErrorDigest(),
                        applyResult.getErrorDetails()),
                accessToken,
                HivePolicyApplyResult.class);
    }

    public OAuth2TokenResponse exchangeSsoCode(
            String serverUrl,
            String code,
            String clientId,
            String redirectUri,
            String codeVerifier) {
        return postJson(
                serverUrl,
                "/api/v1/oauth2/token",
                new OAuth2TokenRequest(code, clientId, redirectUri, codeVerifier),
                null,
                OAuth2TokenResponse.class);
    }

    public void publishEventsBatch(
            String serverUrl,
            String golemId,
            String accessToken,
            List<HiveEventPayload> events) {
        postJson(serverUrl,
                "/api/v1/golems/" + encodePathSegment(golemId) + "/events:batch",
                new HiveEventBatchRequest(HIVE_EVENT_SCHEMA_VERSION, golemId, events),
                accessToken,
                JsonNode.class);
    }

    public HiveCardDetail getCard(String serverUrl, String golemId, String accessToken, String cardId) {
        return getJson(serverUrl,
                "/api/v1/golems/" + encodePathSegment(golemId) + "/sdlc/cards/" + encodePathSegment(cardId),
                accessToken,
                HiveCardDetail.class);
    }

    public List<HiveCardSummary> searchCards(
            String serverUrl,
            String golemId,
            String accessToken,
            HiveCardSearchRequest request) {
        HiveCardSummary[] response = getJson(serverUrl,
                "/api/v1/golems/" + encodePathSegment(golemId) + "/sdlc/cards" + buildCardSearchQuery(request),
                accessToken,
                HiveCardSummary[].class);
        return response != null ? List.of(response) : List.of();
    }

    public HiveCardDetail createCard(String serverUrl, String golemId, String accessToken,
            HiveCreateCardRequest request) {
        return postJson(serverUrl,
                "/api/v1/golems/" + encodePathSegment(golemId) + "/sdlc/cards",
                request,
                accessToken,
                HiveCardDetail.class);
    }

    public HiveThreadMessage postThreadMessage(
            String serverUrl,
            String golemId,
            String accessToken,
            String threadId,
            String body) {
        return postJson(serverUrl,
                "/api/v1/golems/" + encodePathSegment(golemId) + "/sdlc/threads/" + encodePathSegment(threadId)
                        + "/messages",
                new ThreadMessageRequest(body),
                accessToken,
                HiveThreadMessage.class);
    }

    public HiveCardDetail requestReview(
            String serverUrl,
            String golemId,
            String accessToken,
            String cardId,
            HiveRequestReviewRequest request) {
        return postJson(serverUrl,
                "/api/v1/golems/" + encodePathSegment(golemId) + "/sdlc/cards/" + encodePathSegment(cardId)
                        + ":request-review",
                request,
                accessToken,
                HiveCardDetail.class);
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

    private <T> T getJson(
            String serverUrl,
            String path,
            String bearerToken,
            Class<T> responseType) {
        String normalizedServerUrl = HiveJoinCodeParser.normalizeServerUrl(serverUrl);
        String url = normalizedServerUrl + path;
        try {
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .get()
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

    private String buildCardSearchQuery(HiveCardSearchRequest request) {
        if (request == null) {
            return "?includeArchived=false";
        }
        List<String> queryParts = new ArrayList<>();
        appendQueryParam(queryParts, "serviceId", request.serviceId());
        appendQueryParam(queryParts, "boardId", request.boardId());
        appendQueryParam(queryParts, "kind", request.kind());
        appendQueryParam(queryParts, "parentCardId", request.parentCardId());
        appendQueryParam(queryParts, "epicCardId", request.epicCardId());
        appendQueryParam(queryParts, "reviewOfCardId", request.reviewOfCardId());
        appendQueryParam(queryParts, "objectiveId", request.objectiveId());
        appendQueryParam(queryParts, "includeArchived", Boolean.toString(request.includeArchived()));
        return queryParts.isEmpty() ? "" : "?" + String.join("&", queryParts);
    }

    private void appendQueryParam(List<String> queryParts, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        queryParts.add(encodeQueryParam(name) + "=" + encodeQueryParam(value));
    }

    private String encodePathSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Hive API path segment is required");
        }
        return encodeQueryParam(value.trim()).replace("+", "%20");
    }

    private String encodeQueryParam(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
            JsonNode error = root.path("error");
            if (error.isTextual() && !error.asText().isBlank()) {
                return error.asText();
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
            Set<String> supportedChannels,
            HiveCapabilitySnapshot capabilities) {
    }

    private record RefreshTokenRequest(String refreshToken) {
    }

    private record OAuth2TokenRequest(String code, String clientId, String redirectUri, String codeVerifier) {
    }

    public record OperatorResponse(String id, String username, String displayName, List<String> roles) {
    }

    public record LoginResponse(String accessToken, OperatorResponse operator) {
    }

    public record OAuth2TokenResponse(LoginResponse login, String code) {
    }

    private record ThreadMessageRequest(String body) {
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
            String capabilitySnapshotHash,
            String policyGroupId,
            Integer targetPolicyVersion,
            Integer appliedPolicyVersion,
            String syncStatus,
            String lastPolicyErrorDigest,
            String dashboardBaseUrl) {
    }

    private record PolicyApplyResultRequest(
            String policyGroupId,
            Integer targetVersion,
            Integer appliedVersion,
            String syncStatus,
            String checksum,
            String errorDigest,
            String errorDetails) {
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
        private static final long serialVersionUID = 1L;

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
