package me.golemcore.bot.infrastructure.telemetry;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Sends events to Google Analytics 4 using the same collection endpoint as
 * gtag.js. No api_secret required — uses the public {@code /g/collect} protocol
 * (v2).
 */
@Component
@Slf4j
public class GaTelemetryClient {

    private static final String GA_COLLECT_URL = "https://www.google-analytics.com/g/collect";
    @SuppressWarnings("java:S6418") // Not a secret — public GA4 measurement ID (same as gtag.js snippet)
    private static final String GA_MEASUREMENT_ID = "G-ZB1YDYV2MB";

    private final OkHttpClient okHttpClient;

    public GaTelemetryClient(OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
    }

    /**
     * Send a single event to GA4.
     *
     * @param clientId
     *            persistent client identifier (UUID stored in RuntimeConfig)
     * @param sessionId
     *            numeric session identifier (epoch seconds at app start)
     * @param eventName
     *            GA4 event name (max 40 chars)
     * @param params
     *            event parameters — strings become {@code ep.key}, numbers become
     *            {@code epn.key}
     */
    public void sendEvent(String clientId, long sessionId, String eventName, Map<String, Object> params) {
        StringBuilder payload = new StringBuilder();
        payload.append("v=2");
        payload.append("&tid=").append(GA_MEASUREMENT_ID);
        payload.append("&cid=").append(encode(clientId));
        payload.append("&sid=").append(sessionId);
        payload.append("&sct=1");
        payload.append("&seg=1");
        payload.append("&en=").append(encode(eventName));
        payload.append("&_et=1");

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = truncate(entry.getKey(), 40);
            Object value = entry.getValue();
            if (value instanceof Number) {
                payload.append("&epn.").append(encode(key)).append("=").append(value);
            } else if (value != null) {
                payload.append("&ep.").append(encode(key)).append("=")
                        .append(encode(truncate(String.valueOf(value), 100)));
            }
        }

        RequestBody body = RequestBody.create(payload.toString(), null);
        Request request = new Request.Builder()
                .url(GA_COLLECT_URL)
                .header("Content-Type", "text/plain")
                .post(body)
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.debug("[GA4] Collection returned status {}", response.code());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to send GA4 event", exception);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String truncate(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
