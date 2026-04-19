package me.golemcore.bot.adapter.inbound.web.inlineedit;

import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.service.DashboardFileService;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                .thenReturn(
                        CompletableFuture.completedFuture(LlmResponse.builder().content("const value = 1;").build()));

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
    void shouldCaptureInlineEditMetadataInLlmRequest() throws Exception {
        when(llmPort.chat(any(LlmRequest.class)))
                .thenReturn(
                        CompletableFuture.completedFuture(LlmResponse.builder().content("const value = 1;").build()));

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
}
