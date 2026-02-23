package me.golemcore.bot.testsupport.http;

import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory OkHttp exchange engine for unit tests.
 * <p>
 * This interceptor never performs network I/O. Tests enqueue responses (or
 * failures), and every request is captured for assertions.
 */
public final class OkHttpMockEngine implements Interceptor {

    private static final String DEFAULT_CONTENT_TYPE = "application/json";

    private final ConcurrentLinkedQueue<PlannedResult> plannedResults = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<CapturedRequest> capturedRequests = new ConcurrentLinkedQueue<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    public void enqueueJson(int code, String body) {
        enqueue(code, body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0], DEFAULT_CONTENT_TYPE);
    }

    public void enqueueText(int code, String body, String contentType) {
        enqueue(code, body != null ? body.getBytes(StandardCharsets.UTF_8) : new byte[0], contentType);
    }

    public void enqueueBytes(int code, byte[] body, String contentType) {
        enqueue(code, body != null ? body : new byte[0], contentType);
    }

    public void enqueueFailure(IOException failure) {
        plannedResults.add(PlannedResult.failure(failure));
    }

    public CapturedRequest takeRequest() {
        return capturedRequests.poll();
    }

    public int getRequestCount() {
        return requestCount.get();
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        String requestBody = readRequestBody(request);
        Request normalizedRequest = normalizeCapturedRequest(request);
        capturedRequests.add(new CapturedRequest(normalizedRequest, requestBody));
        requestCount.incrementAndGet();

        PlannedResult plannedResult = plannedResults.poll();
        if (plannedResult == null) {
            throw new IOException("No planned response for request: " + request.method() + " " + request.url());
        }
        if (plannedResult.failure() != null) {
            throw plannedResult.failure();
        }

        MediaType mediaType = plannedResult.contentType() != null
                ? MediaType.parse(plannedResult.contentType())
                : null;
        ResponseBody responseBody = ResponseBody.create(plannedResult.body(), mediaType);

        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(plannedResult.code())
                .message("mock")
                .headers(Headers.of())
                .body(responseBody)
                .build();
    }

    private Request normalizeCapturedRequest(Request request) {
        if (request == null || request.body() == null || request.header("Content-Type") != null) {
            return request;
        }

        MediaType contentType = request.body().contentType();
        if (contentType == null) {
            return request;
        }

        return request.newBuilder()
                .header("Content-Type", contentType.toString())
                .build();
    }

    private void enqueue(int code, byte[] body, String contentType) {
        plannedResults.add(PlannedResult.response(code, body, contentType));
    }

    private String readRequestBody(Request request) throws IOException {
        RequestBody requestBody = request.body();
        if (requestBody == null) {
            return "";
        }
        Buffer buffer = new Buffer();
        requestBody.writeTo(buffer);
        return buffer.readString(StandardCharsets.UTF_8);
    }

    private record PlannedResult(int code, byte[] body, String contentType, IOException failure) {
        static PlannedResult response(int code, byte[] body, String contentType) {
            return new PlannedResult(code, body, contentType, null);
        }

        static PlannedResult failure(IOException failure) {
            return new PlannedResult(0, new byte[0], null, failure);
        }
    }

    public static final class CapturedRequest {
        private final Request request;
        private final String body;

        private CapturedRequest(Request request, String body) {
            this.request = request;
            this.body = body;
        }

        public String method() {
            return request.method();
        }

        public String target() {
            String encodedPath = request.url().encodedPath();
            String encodedQuery = request.url().encodedQuery();
            if (encodedQuery == null || encodedQuery.isBlank()) {
                return encodedPath;
            }
            return encodedPath + "?" + encodedQuery;
        }

        public Headers headers() {
            return request.headers();
        }

        public String body() {
            return body;
        }
    }
}
