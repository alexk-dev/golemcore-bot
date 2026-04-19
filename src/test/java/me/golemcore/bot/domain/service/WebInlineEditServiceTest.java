package me.golemcore.bot.adapter.inbound.web.inlineedit;

import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.service.DashboardFileService;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebInlineEditServiceTest {

    private DashboardFileService dashboardFileService;
    private LlmPort llmPort;
    private WebInlineEditPromptFactory promptFactory;
    private WebInlineEditService webInlineEditService;

    @BeforeEach
    void setUp() {
        dashboardFileService = mock(DashboardFileService.class);
        llmPort = mock(LlmPort.class);
        promptFactory = new WebInlineEditPromptFactory();
        webInlineEditService = new WebInlineEditService(dashboardFileService, llmPort, promptFactory);
    }

    @Test
    void shouldCreateInlineEditReplacement() throws Exception {
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder().content("const value = 1;").build()));

        WebInlineEditService.InlineEditResult result = webInlineEditService.createInlineEdit(
                "src/App.tsx",
                "const x = 1;",
                0,
                11,
                "const x = 1",
                "refactor this",
                "client-1");

        assertEquals("src/App.tsx", result.path());
        assertEquals("const value = 1;", result.replacement());
        verify(dashboardFileService).validateEditablePath("src/App.tsx");
    }

    @Test
    void shouldUseCurrentModelWhenAvailable() throws Exception {
        when(llmPort.getCurrentModel()).thenReturn("openai/gpt-4.1-mini");
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder().content("const value = 1;").build()));

        webInlineEditService.createInlineEdit(
                "src/App.tsx",
                "const x = 1;",
                0,
                11,
                "const x = 1",
                "refactor this",
                "client-1");

        org.mockito.ArgumentCaptor<LlmRequest> captor = org.mockito.ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmPort).chat(captor.capture());
        assertEquals("openai/gpt-4.1-mini", captor.getValue().getModel());
    }

    @Test
    void shouldStripMarkdownCodeFencesFromReplacement() throws Exception {
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder()
                        .content("```java\nconst value = 1;\n```")
                        .build()));

        WebInlineEditService.InlineEditResult result = webInlineEditService.createInlineEdit(
                "src/App.tsx",
                "const x = 1;",
                0,
                11,
                "const x = 1",
                "refactor this",
                "client-1");

        assertEquals("const value = 1;", result.replacement());
    }

    @Test
    void shouldOmitClientInstanceIdWhenBlank() throws Exception {
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder().content("const value = 1;").build()));

        webInlineEditService.createInlineEdit(
                "src/App.tsx",
                "const x = 1;",
                0,
                11,
                "const x = 1",
                "refactor this",
                " ");

        org.mockito.ArgumentCaptor<LlmRequest> captor = org.mockito.ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmPort).chat(captor.capture());
        Map<String, Object> metadata = captor.getValue().getMessages().get(0).getMetadata();
        assertFalse(metadata.containsKey("session.web.client.instance.id"));
    }

    @Test
    void shouldRejectInvalidSelectionRange() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> webInlineEditService.createInlineEdit(
                        "src/App.tsx",
                        "const x = 1;",
                        4,
                        4,
                        "",
                        "refactor this",
                        "client-1"));

        assertTrue(exception.getMessage().contains("Selection range is invalid"));
    }

    @Test
    void shouldRejectSelectionMismatch() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> webInlineEditService.createInlineEdit(
                        "src/App.tsx",
                        "const x = 1;",
                        0,
                        11,
                        "wrong",
                        "refactor this",
                        "client-1"));

        assertTrue(exception.getMessage().contains("Selected text does not match file content"));
    }

    @Test
    void shouldRejectSelectionThatExceedsContent() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> webInlineEditService.createInlineEdit(
                        "src/App.tsx",
                        "const x = 1;",
                        0,
                        99,
                        "const x = 1",
                        "refactor this",
                        "client-1"));

        assertTrue(exception.getMessage().contains("Selection range exceeds file content"));
    }

    @Test
    void shouldRejectBlankPath() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> webInlineEditService.createInlineEdit(
                        " ",
                        "const x = 1;",
                        0,
                        11,
                        "const x = 1",
                        "refactor this",
                        "client-1"));

        assertEquals("Path is required", exception.getMessage());
    }

    @Test
    void shouldRejectNullContent() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> webInlineEditService.createInlineEdit(
                        "src/App.tsx",
                        null,
                        0,
                        11,
                        "const x = 1",
                        "refactor this",
                        "client-1"));

        assertEquals("Content is required", exception.getMessage());
    }

    @Test
    void shouldRejectBlankInstruction() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> webInlineEditService.createInlineEdit(
                        "src/App.tsx",
                        "const x = 1;",
                        0,
                        11,
                        "const x = 1",
                        " ",
                        "client-1"));

        assertEquals("Instruction is required", exception.getMessage());
    }

    @Test
    void shouldTranslateExecutionExceptionMessage() {
        CompletableFuture<LlmResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("llm offline"));
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(failed);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> webInlineEditService.createInlineEdit(
                        "src/App.tsx",
                        "const x = 1;",
                        0,
                        11,
                        "const x = 1",
                        "refactor this",
                        "client-1"));

        assertTrue(exception.getMessage().contains("llm offline"));
    }

    @Test
    void shouldTranslateTimeoutMessage() {
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(new TimeoutFuture());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> webInlineEditService.createInlineEdit(
                        "src/App.tsx",
                        "const x = 1;",
                        0,
                        11,
                        "const x = 1",
                        "refactor this",
                        "client-1"));

        assertTrue(exception.getMessage().contains("timed out"));
    }

    @Test
    void shouldRestoreInterruptStatusOnInterruptedInlineEdit() {
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(new InterruptedFuture());

        try {
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> webInlineEditService.createInlineEdit(
                            "src/App.tsx",
                            "const x = 1;",
                            0,
                            11,
                            "const x = 1",
                            "refactor this",
                            "client-1"));

            assertTrue(exception.getMessage().contains("interrupted"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shouldCaptureInlineEditMetadataInLlmRequest() throws Exception {
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(LlmResponse.builder().content("const value = 1;").build()));

        webInlineEditService.createInlineEdit(
                "src/App.tsx",
                "const x = 1;",
                0,
                11,
                "const x = 1",
                "refactor this",
                "client-1");

        org.mockito.ArgumentCaptor<LlmRequest> captor = org.mockito.ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmPort).chat(captor.capture());
        LlmRequest request = captor.getValue();
        assertEquals(1, request.getMessages().size());
        Map<String, Object> metadata = request.getMessages().get(0).getMetadata();
        assertEquals("src/App.tsx", metadata.get("session.web.inlineEdit.path"));
        assertEquals(0, metadata.get("session.web.selection.from"));
        assertEquals(11, metadata.get("session.web.selection.to"));
        assertEquals("const x = 1", metadata.get("session.web.selection.text"));
        assertEquals("refactor this", metadata.get("session.web.inlineEdit.instruction"));
        assertEquals("client-1", metadata.get("session.web.client.instance.id"));
    }

    private static final class TimeoutFuture extends CompletableFuture<LlmResponse> {

        @Override
        public LlmResponse get(long timeout, TimeUnit unit) throws ExecutionException, TimeoutException {
            throw new TimeoutException("timed out");
        }
    }

    private static final class InterruptedFuture extends CompletableFuture<LlmResponse> {

        @Override
        public LlmResponse get(long timeout, TimeUnit unit) throws InterruptedException {
            throw new InterruptedException("interrupted");
        }
    }
}
