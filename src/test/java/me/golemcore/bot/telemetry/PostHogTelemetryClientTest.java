package me.golemcore.bot.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.infrastructure.config.BotProperties;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostHogTelemetryClientTest {

    private OkHttpClient okHttpClient;
    private Call call;
    private BotProperties properties;
    private PostHogTelemetryClient client;

    @BeforeEach
    void setUp() throws IOException {
        okHttpClient = mock(OkHttpClient.class);
        call = mock(Call.class);
        when(okHttpClient.newCall(any(Request.class))).thenReturn(call);

        properties = new BotProperties();
        properties.getTelemetry().setApiHost("https://us.i.posthog.com");
        properties.getTelemetry().setApiKey("phc_test_key");
        properties.getTelemetry().setProjectId("371821");

        client = new PostHogTelemetryClient(okHttpClient, new ObjectMapper(), properties);
    }

    @Test
    void shouldSendCaptureRequestToPostHog() throws Exception {
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://us.i.posthog.com/capture/").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create("{}", MediaType.parse("application/json; charset=utf-8")))
                .build();
        when(call.execute()).thenReturn(response);

        client.capture("ui_usage_rollup", "ui:anon-123", Map.of("bucket_minutes", 15));

        Request request = captureRequest();
        assertEquals("https://us.i.posthog.com/capture/", request.url().toString());
        assertEquals("application/json; charset=utf-8", request.header("Content-Type"));

        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        String json = buffer.readUtf8();
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"event\":\"ui_usage_rollup\""));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"distinct_id\":\"ui:anon-123\""));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("\"api_key\":\"phc_test_key\""));
    }

    @Test
    void shouldThrowWhenPostHogReturnsAnError() throws Exception {
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://us.i.posthog.com/capture/").build())
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Server Error")
                .body(ResponseBody.create("{\"detail\":\"error\"}", MediaType.parse("application/json; charset=utf-8")))
                .build();
        when(call.execute()).thenReturn(response);

        assertThrows(IllegalStateException.class,
                () -> client.capture("ui_usage_rollup", "ui:anon-123", Map.of("bucket_minutes", 15)));
    }

    private Request captureRequest() throws IOException {
        return org.mockito.Mockito.mockingDetails(okHttpClient)
                .getInvocations()
                .stream()
                .filter(invocation -> invocation.getMethod().getName().equals("newCall"))
                .map(invocation -> invocation.getArgument(0, Request.class))
                .findFirst()
                .orElseThrow();
    }
}
