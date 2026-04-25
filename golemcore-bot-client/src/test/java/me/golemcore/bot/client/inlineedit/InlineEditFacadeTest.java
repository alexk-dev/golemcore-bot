package me.golemcore.bot.client.inlineedit;

import me.golemcore.bot.client.dto.InlineEditRequest;
import me.golemcore.bot.client.dto.InlineEditResponse;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.port.outbound.LlmPort;
import me.golemcore.bot.port.outbound.WorkspaceEditorPort;
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

class InlineEditFacadeTest {

    private WorkspaceEditorPort workspaceEditorPort;
    private LlmPort llmPort;
    private InlineEditPromptFactory promptFactory;
    private InlineEditFacade inlineEditFacade;

    @BeforeEach
    void setUp() {
        workspaceEditorPort = mock(WorkspaceEditorPort.class);
        llmPort = mock(LlmPort.class);
        promptFactory = new InlineEditPromptFactory();
        inlineEditFacade = new InlineEditFacade(workspaceEditorPort, llmPort, promptFactory);
    }

    @Test
    void shouldCreateInlineEditReplacement() throws Exception {
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(
                        CompletableFuture.completedFuture(LlmResponse.builder().content("const value = 1;").build()));

        InlineEditResponse response = inlineEditFacade.createInlineEdit(buildRequest(), "client-1");

        assertEquals("src/App.tsx", response.getPath());
        assertEquals("const value = 1;", response.getReplacement());
        verify(workspaceEditorPort).validateEditablePath("src/App.tsx");
    }

    @Test
    void shouldUseCurrentModelWhenAvailable() throws Exception {
        when(llmPort.getCurrentModel()).thenReturn("openai/gpt-4.1-mini");
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(
                        CompletableFuture.completedFuture(LlmResponse.builder().content("const value = 1;").build()));

        inlineEditFacade.createInlineEdit(buildRequest(), "client-1");

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

        InlineEditResponse response = inlineEditFacade.createInlineEdit(buildRequest(), "client-1");

        assertEquals("const value = 1;", response.getReplacement());
    }

    @Test
    void shouldOmitClientInstanceIdWhenBlank() throws Exception {
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(
                        CompletableFuture.completedFuture(LlmResponse.builder().content("const value = 1;").build()));

        inlineEditFacade.createInlineEdit(buildRequest(), " ");

        org.mockito.ArgumentCaptor<LlmRequest> captor = org.mockito.ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmPort).chat(captor.capture());
        Map<String, Object> metadata = captor.getValue().getMessages().get(0).getMetadata();
        assertFalse(metadata.containsKey("session.web.client.instance.id"));
    }

    @Test
    void shouldRejectNullRequest() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> inlineEditFacade.createInlineEdit(null, "client-1"));

        assertEquals("Request is required", exception.getMessage());
    }

    @Test
    void shouldRejectInvalidSelectionRange() {
        InlineEditRequest request = buildRequest();
        request.setSelectionTo(0);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> inlineEditFacade.createInlineEdit(request, "client-1"));

        assertTrue(exception.getMessage().contains("Selection range is invalid"));
    }

    @Test
    void shouldRejectSelectionMismatch() {
        InlineEditRequest request = buildRequest();
        request.setSelectedText("wrong");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> inlineEditFacade.createInlineEdit(request, "client-1"));

        assertTrue(exception.getMessage().contains("Selected text does not match file content"));
    }

    @Test
    void shouldRejectSelectionThatExceedsContent() {
        InlineEditRequest request = buildRequest();
        request.setSelectionTo(99);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> inlineEditFacade.createInlineEdit(request, "client-1"));

        assertTrue(exception.getMessage().contains("Selection range exceeds file content"));
    }

    @Test
    void shouldRejectBlankPath() {
        InlineEditRequest request = buildRequest();
        request.setPath(" ");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> inlineEditFacade.createInlineEdit(request, "client-1"));

        assertEquals("Path is required", exception.getMessage());
    }

    @Test
    void shouldRejectNullContent() {
        InlineEditRequest request = buildRequest();
        request.setContent(null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> inlineEditFacade.createInlineEdit(request, "client-1"));

        assertEquals("Content is required", exception.getMessage());
    }

    @Test
    void shouldRejectBlankInstruction() {
        InlineEditRequest request = buildRequest();
        request.setInstruction(" ");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> inlineEditFacade.createInlineEdit(request, "client-1"));

        assertEquals("Instruction is required", exception.getMessage());
    }

    @Test
    void shouldTranslateExecutionExceptionMessage() {
        CompletableFuture<LlmResponse> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("llm offline"));
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(failed);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> inlineEditFacade.createInlineEdit(buildRequest(), "client-1"));

        assertTrue(exception.getMessage().contains("llm offline"));
    }

    @Test
    void shouldTranslateTimeoutMessage() {
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(new TimeoutFuture());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> inlineEditFacade.createInlineEdit(buildRequest(), "client-1"));

        assertTrue(exception.getMessage().contains("timed out"));
    }

    @Test
    void shouldRestoreInterruptStatusOnInterruptedInlineEdit() {
        when(llmPort.chat(any(LlmRequest.class))).thenReturn(new InterruptedFuture());

        try {
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> inlineEditFacade.createInlineEdit(buildRequest(), "client-1"));

            assertTrue(exception.getMessage().contains("interrupted"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shouldCaptureInlineEditMetadataInLlmRequest() throws Exception {
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(
                        CompletableFuture.completedFuture(LlmResponse.builder().content("const value = 1;").build()));

        inlineEditFacade.createInlineEdit(buildRequest(), "client-1");

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

    private InlineEditRequest buildRequest() {
        return InlineEditRequest.builder()
                .path("src/App.tsx")
                .content("const x = 1;")
                .selectionFrom(0)
                .selectionTo(11)
                .selectedText("const x = 1")
                .instruction("refactor this")
                .build();
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
