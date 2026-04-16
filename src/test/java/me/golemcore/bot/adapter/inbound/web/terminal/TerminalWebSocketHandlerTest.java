package me.golemcore.bot.adapter.inbound.web.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.infrastructure.security.JwtTokenProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.reactivestreams.Publisher;
import reactor.core.Scannable;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TerminalWebSocketHandlerTest {

    @TempDir
    private Path tempDir;

    private BotProperties botProperties;
    private JwtTokenProvider tokenProvider;
    private TerminalSessionLimiter limiter;
    private TerminalWebSocketHandler handler;
    private Path sandboxRoot;

    @BeforeEach
    void setUp() throws Exception {
        sandboxRoot = Files.createDirectories(tempDir.resolve("sandbox"));
        botProperties = new BotProperties();
        botProperties.getDashboard()
                .setJwtSecret("test-secret-key-that-is-long-enough-for-hmac-sha256-algorithm");
        botProperties.getDashboard().getTerminal().setEnabled(true);
        botProperties.getDashboard().getTerminal().setMaxSessionsPerUser(1);
        botProperties.getDashboard().getTerminal().setIdleTimeout(Duration.ofMillis(100));
        botProperties.getDashboard().getTerminal().setMaxSessionDuration(Duration.ofSeconds(2));
        botProperties.getTools().getShell().setWorkspace(sandboxRoot.toString());

        tokenProvider = new JwtTokenProvider(botProperties);
        tokenProvider.init();

        limiter = new TerminalSessionLimiter(botProperties);
        handler = new TerminalWebSocketHandler(tokenProvider, new ObjectMapper(), limiter, botProperties);
    }

    @Test
    void shouldRejectWhenTerminalFeatureDisabled() {
        botProperties.getDashboard().getTerminal().setEnabled(false);
        String accessToken = tokenProvider.generateAccessToken("admin");
        WebSocketSession session = mockSession("ws://localhost/ws/terminal?token=" + accessToken);
        when(session.close()).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session)).verifyComplete();

        verify(session).close();
        assertEquals(0, limiter.activeCount("admin"), "disabled feature should not register any lease");
    }

    @Test
    void shouldRejectWhenTokenMissing() {
        WebSocketSession session = mockSession("ws://localhost/ws/terminal");
        when(session.close()).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session)).verifyComplete();

        verify(session).close();
    }

    @Test
    void shouldRejectWhenRefreshTokenProvided() {
        String refreshToken = tokenProvider.generateRefreshToken("admin");
        WebSocketSession session = mockSession("ws://localhost/ws/terminal?token=" + refreshToken);
        when(session.close()).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session)).verifyComplete();

        verify(session).close();
    }

    @Test
    void shouldRejectWhenPerUserCapReached() {
        // Pre-fill the single allowed slot for this user.
        limiter.tryAcquire("admin").orElseThrow();

        String accessToken = tokenProvider.generateAccessToken("admin");
        WebSocketSession session = mockSession("ws://localhost/ws/terminal?token=" + accessToken);
        when(session.close()).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session)).verifyComplete();

        verify(session).close();
        assertEquals(1, limiter.activeCount("admin"), "cap-rejected connection must not consume a new slot");
    }

    @Test
    void shouldReleaseLeaseOnClose() {
        String accessToken = tokenProvider.generateAccessToken("admin");
        WebSocketSession session = mockStreamingSession(
                "ws://localhost/ws/terminal?token=" + accessToken);

        StepVerifier.create(handler.handle(session))
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        awaitLeaseReleased("admin");
        assertEquals(0, limiter.activeCount("admin"), "lease should be released after connection completes");
    }

    @Test
    void shouldCompleteWhenIdleTimeoutElapses() {
        String accessToken = tokenProvider.generateAccessToken("admin");
        // Force immediate idle: inbound never emits anything.
        WebSocketSession session = mockStreamingSession(
                "ws://localhost/ws/terminal?token=" + accessToken,
                Flux.never());

        StepVerifier.create(handler.handle(session))
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        awaitLeaseReleased("admin");
        assertEquals(0, limiter.activeCount("admin"));
    }

    @Test
    void shouldResolveRequestedWorkingDirectoryFromHandshake() throws Exception {
        Path subdir = Files.createDirectories(sandboxRoot.resolve("src/main"));
        String accessToken = tokenProvider.generateAccessToken("admin");

        Path resolved = handler.resolveWorkingDirectory(
                mockSession("ws://localhost/ws/terminal?token=" + accessToken + "&cwd=src/main"));

        assertSameNormalizedPath(subdir.toRealPath(), resolved);
    }

    @Test
    void shouldResolveUrlEncodedWorkingDirectoryFromHandshake() throws Exception {
        Path subdir = Files.createDirectories(sandboxRoot.resolve("src/main"));
        String accessToken = tokenProvider.generateAccessToken("admin");

        Path resolved = handler.resolveWorkingDirectory(
                mockSession("ws://localhost/ws/terminal?token=" + accessToken + "&cwd=src%2Fmain"));

        assertSameNormalizedPath(subdir.toRealPath(), resolved);
    }

    @Test
    void shouldResolvePlusEncodedWorkingDirectoryFromHandshake() throws Exception {
        Path subdir = Files.createDirectories(sandboxRoot.resolve("with space"));
        String accessToken = tokenProvider.generateAccessToken("admin");

        Path resolved = handler.resolveWorkingDirectory(
                mockSession("ws://localhost/ws/terminal?token=" + accessToken + "&cwd=with+space"));

        assertSameNormalizedPath(subdir.toRealPath(), resolved);
    }

    @Test
    void shouldResolveEscapedPlusWorkingDirectoryFromHandshake() throws Exception {
        Path subdir = Files.createDirectories(sandboxRoot.resolve("with+plus"));
        String accessToken = tokenProvider.generateAccessToken("admin");

        Path resolved = handler.resolveWorkingDirectory(
                mockSession("ws://localhost/ws/terminal?token=" + accessToken + "&cwd=with%2Bplus"));

        assertSameNormalizedPath(subdir.toRealPath(), resolved);
    }

    @Test
    void shouldClampTraversalWorkingDirectoryToSandboxRoot() throws Exception {
        String accessToken = tokenProvider.generateAccessToken("admin");

        Path resolved = handler.resolveWorkingDirectory(
                mockSession("ws://localhost/ws/terminal?token=" + accessToken + "&cwd=../../etc"));

        assertSameNormalizedPath(sandboxRoot.toRealPath(), resolved);
    }

    @Test
    void shouldUseSandboxRootWhenWorkingDirectoryIsMissing() throws Exception {
        String accessToken = tokenProvider.generateAccessToken("admin");

        Path resolved = handler.resolveWorkingDirectory(mockSession("ws://localhost/ws/terminal?token=" + accessToken));

        assertSameNormalizedPath(sandboxRoot.toRealPath(), resolved);
    }

    @Test
    void shouldUseSandboxRootWhenWorkingDirectoryIsBlank() throws Exception {
        String accessToken = tokenProvider.generateAccessToken("admin");

        Path resolved = handler.resolveWorkingDirectory(
                mockSession("ws://localhost/ws/terminal?token=" + accessToken + "&cwd=+"));

        assertSameNormalizedPath(sandboxRoot.toRealPath(), resolved);
    }

    @Test
    void shouldUseSandboxRootWhenWorkingDirectoryHasNoEqualsSign() throws Exception {
        String accessToken = tokenProvider.generateAccessToken("admin");

        Path resolved = handler.resolveWorkingDirectory(
                mockSession("ws://localhost/ws/terminal?token=" + accessToken + "&cwd"));

        assertSameNormalizedPath(sandboxRoot.toRealPath(), resolved);
    }

    @Test
    void shouldUseSandboxRootWhenWorkingDirectoryIsInvalidPath() throws Exception {
        String accessToken = tokenProvider.generateAccessToken("admin");

        Path resolved = handler.resolveWorkingDirectory(
                mockSession("ws://localhost/ws/terminal?token=" + accessToken + "&cwd=%00"));

        assertSameNormalizedPath(sandboxRoot.toRealPath(), resolved);
    }

    @Test
    void shouldClampAbsoluteWorkingDirectoryToSandboxRoot() throws Exception {
        String accessToken = tokenProvider.generateAccessToken("admin");

        Path resolved = handler.resolveWorkingDirectory(
                mockSession("ws://localhost/ws/terminal?token=" + accessToken + "&cwd=%2Ftmp"));

        assertSameNormalizedPath(sandboxRoot.toRealPath(), resolved);
    }

    @Test
    void shouldUseSandboxRootWhenWorkingDirectoryDoesNotExist() throws Exception {
        String accessToken = tokenProvider.generateAccessToken("admin");

        Path resolved = handler.resolveWorkingDirectory(
                mockSession("ws://localhost/ws/terminal?token=" + accessToken + "&cwd=missing"));

        assertSameNormalizedPath(sandboxRoot.toRealPath(), resolved);
    }

    @Test
    void shouldUseSandboxRootWhenWorkingDirectoryIsFile() throws Exception {
        Files.writeString(sandboxRoot.resolve("file.txt"), "content");
        String accessToken = tokenProvider.generateAccessToken("admin");

        Path resolved = handler.resolveWorkingDirectory(
                mockSession("ws://localhost/ws/terminal?token=" + accessToken + "&cwd=file.txt"));

        assertSameNormalizedPath(sandboxRoot.toRealPath(), resolved);
    }

    @Test
    void shouldClampSymlinkEscapingWorkspaceToSandboxRoot() throws Exception {
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path link = sandboxRoot.resolve("outside-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("symbolic links are not supported by this filesystem");
        }
        String accessToken = tokenProvider.generateAccessToken("admin");

        Path resolved = handler.resolveWorkingDirectory(
                mockSession("ws://localhost/ws/terminal?token=" + accessToken + "&cwd=outside-link"));

        assertSameNormalizedPath(sandboxRoot.toRealPath(), resolved);
    }

    @Test
    void shouldFallBackToNormalizedWorkspaceWhenWorkspaceRootCannotBePrepared() throws Exception {
        Path workspaceFile = Files.writeString(tempDir.resolve("workspace-file"), "not a directory");
        botProperties.getTools().getShell().setWorkspace(workspaceFile.toString());
        String accessToken = tokenProvider.generateAccessToken("admin");

        Path resolved = handler.resolveWorkingDirectory(mockSession("ws://localhost/ws/terminal?token=" + accessToken));

        assertSameNormalizedPath(workspaceFile.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void shouldStopOutboundCompletionOnTerminalSinkStates() {
        for (Sinks.EmitResult result : new Sinks.EmitResult[] {
                Sinks.EmitResult.OK,
                Sinks.EmitResult.FAIL_TERMINATED,
                Sinks.EmitResult.FAIL_CANCELLED }) {
            ScriptedSink sink = new ScriptedSink(result);

            handler.completeOutbound(sink);

            assertEquals(1, sink.attempts());
        }
    }

    @Test
    void shouldStopOutboundCompletionOnNonRetryableFailure() {
        ScriptedSink sink = new ScriptedSink(Sinks.EmitResult.FAIL_OVERFLOW);

        handler.completeOutbound(sink);

        assertEquals(1, sink.attempts());
    }

    @Test
    void shouldRetryNonSerializedOutboundCompletion() {
        ScriptedSink sink = new ScriptedSink(Sinks.EmitResult.FAIL_NON_SERIALIZED, Sinks.EmitResult.OK);

        handler.completeOutbound(sink);

        assertEquals(2, sink.attempts());
    }

    @Test
    void shouldStopRetryingOutboundCompletionAfterDeadline() {
        ScriptedSink sink = new ScriptedSink(Sinks.EmitResult.FAIL_NON_SERIALIZED).repeatLastResult();

        handler.completeOutbound(sink);

        assertTrue(sink.attempts() > 1);
    }

    private void awaitLeaseReleased(String username) {
        // The handler releases the lease from a reactor doFinally that may run on a
        // parallel scheduler thread, so by the time StepVerifier.verify() returns the
        // side effect can still be in flight. Poll briefly so the assertion observes
        // the post-finally state without depending on scheduler timing.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && limiter.activeCount(username) > 0) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private WebSocketSession mockSession(String uri) {
        WebSocketSession session = mock(WebSocketSession.class);
        HandshakeInfo handshakeInfo = mock(HandshakeInfo.class);
        when(session.getHandshakeInfo()).thenReturn(handshakeInfo);
        when(handshakeInfo.getUri()).thenReturn(URI.create(uri));
        return session;
    }

    private WebSocketSession mockStreamingSession(String uri) {
        return mockStreamingSession(uri, Flux.empty());
    }

    private WebSocketSession mockStreamingSession(String uri, Flux<WebSocketMessage> inbound) {
        WebSocketSession session = mockSession(uri);
        when(session.receive()).thenReturn(inbound);
        when(session.textMessage(anyString())).thenAnswer(invocation -> {
            String payload = invocation.getArgument(0, String.class);
            WebSocketMessage message = mock(WebSocketMessage.class);
            when(message.getPayloadAsText()).thenReturn(payload);
            return message;
        });
        when(session.send(ArgumentMatchers.<Publisher<WebSocketMessage>>any()))
                .thenAnswer(invocation -> {
                    Publisher<?> publisher = invocation.getArgument(0);
                    return Flux.from(publisher).cast(WebSocketMessage.class).then();
                });
        when(session.close()).thenReturn(Mono.empty());
        return session;
    }

    private void assertSameNormalizedPath(Path expectedPath, Path actualPath) {
        assertEquals(normalizeMacTempPath(expectedPath.toString()), normalizeMacTempPath(actualPath.toString()));
    }

    private String normalizeMacTempPath(String value) {
        return value.replace("/private/var/", "/var/");
    }

    private static final class ScriptedSink implements Sinks.Many<String> {
        private final Deque<Sinks.EmitResult> results = new ArrayDeque<>();
        private int emitAttempts;
        private Sinks.EmitResult lastResult = Sinks.EmitResult.OK;
        private boolean shouldRepeatLastResult;

        private ScriptedSink(Sinks.EmitResult... results) {
            this.results.addAll(java.util.List.of(results));
        }

        private ScriptedSink repeatLastResult() {
            shouldRepeatLastResult = true;
            return this;
        }

        int attempts() {
            return emitAttempts;
        }

        @Override
        public Sinks.EmitResult tryEmitNext(String s) {
            return Sinks.EmitResult.OK;
        }

        @Override
        public Sinks.EmitResult tryEmitComplete() {
            emitAttempts += 1;
            if (results.isEmpty()) {
                return shouldRepeatLastResult ? lastResult : Sinks.EmitResult.OK;
            }
            lastResult = results.removeFirst();
            return lastResult;
        }

        @Override
        public Sinks.EmitResult tryEmitError(Throwable error) {
            return Sinks.EmitResult.OK;
        }

        @Override
        public void emitNext(String s, Sinks.EmitFailureHandler failureHandler) {
        }

        @Override
        public void emitComplete(Sinks.EmitFailureHandler failureHandler) {
        }

        @Override
        public void emitError(Throwable error, Sinks.EmitFailureHandler failureHandler) {
        }

        @Override
        public int currentSubscriberCount() {
            return 0;
        }

        @Override
        public Flux<String> asFlux() {
            return Flux.empty();
        }

        @Override
        public Object scanUnsafe(Scannable.Attr key) {
            return null;
        }
    }
}
