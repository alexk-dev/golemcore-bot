package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.*;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.inbound.ChannelPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResponseRoutingSystemTest {

    private ResponseRoutingSystem system;
    private ChannelPort channelPort;
    private UserPreferencesService preferencesService;

    @BeforeEach
    void setUp() {
        channelPort = mock(ChannelPort.class);
        when(channelPort.getChannelType()).thenReturn("telegram");
        when(channelPort.sendMessage(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(channelPort.sendPhoto(anyString(), any(byte[].class), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(channelPort.sendDocument(anyString(), any(byte[].class), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        preferencesService = mock(UserPreferencesService.class);
        when(preferencesService.getMessage(anyString())).thenReturn("Error occurred");

        system = new ResponseRoutingSystem(List.of(channelPort), preferencesService);
    }

    private AgentContext createContext() {
        AgentSession session = AgentSession.builder()
                .id("test-session")
                .chatId("chat1")
                .channelType("telegram")
                .messages(new ArrayList<>())
                .build();

        return AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .build();
    }

    @Test
    void sendsPendingImageAttachment() {
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("Here is the screenshot").build();
        context.setAttribute("llm.response", response);

        Attachment attachment = Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1, 2, 3 })
                .filename("screenshot.png")
                .mimeType("image/png")
                .caption("A screenshot")
                .build();

        List<Attachment> pending = new ArrayList<>();
        pending.add(attachment);
        context.setAttribute("pendingAttachments", pending);

        system.process(context);

        verify(channelPort).sendMessage(eq("chat1"), eq("Here is the screenshot"));
        verify(channelPort).sendPhoto(eq("chat1"), eq(new byte[] { 1, 2, 3 }), eq("screenshot.png"),
                eq("A screenshot"));
    }

    @Test
    void sendsPendingDocumentAttachment() {
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("Here is the file").build();
        context.setAttribute("llm.response", response);

        Attachment attachment = Attachment.builder()
                .type(Attachment.Type.DOCUMENT)
                .data(new byte[] { 4, 5, 6 })
                .filename("report.pdf")
                .mimeType("application/pdf")
                .caption("The report")
                .build();

        List<Attachment> pending = new ArrayList<>();
        pending.add(attachment);
        context.setAttribute("pendingAttachments", pending);

        system.process(context);

        verify(channelPort).sendDocument(eq("chat1"), eq(new byte[] { 4, 5, 6 }), eq("report.pdf"), eq("The report"));
    }

    @Test
    void sendsMultipleAttachments() {
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("Multiple files").build();
        context.setAttribute("llm.response", response);

        List<Attachment> pending = new ArrayList<>();
        pending.add(Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1 })
                .filename("img.png")
                .mimeType("image/png")
                .build());
        pending.add(Attachment.builder()
                .type(Attachment.Type.DOCUMENT)
                .data(new byte[] { 2 })
                .filename("doc.pdf")
                .mimeType("application/pdf")
                .build());
        context.setAttribute("pendingAttachments", pending);

        system.process(context);

        verify(channelPort).sendPhoto(eq("chat1"), any(byte[].class), eq("img.png"), isNull());
        verify(channelPort).sendDocument(eq("chat1"), any(byte[].class), eq("doc.pdf"), isNull());
    }

    @Test
    void pendingAttachmentsClearedAfterSending() {
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("Done").build();
        context.setAttribute("llm.response", response);

        List<Attachment> pending = new ArrayList<>();
        pending.add(Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1 })
                .filename("img.png")
                .mimeType("image/png")
                .build());
        context.setAttribute("pendingAttachments", pending);

        system.process(context);

        assertNull(context.getAttribute("pendingAttachments"));
    }

    @Test
    void shouldProcessWithPendingAttachmentsOnly() {
        AgentContext context = createContext();
        // No llm.response, no llm.error, but has pending attachments
        List<Attachment> pending = new ArrayList<>();
        pending.add(Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1 })
                .filename("img.png")
                .mimeType("image/png")
                .build());
        context.setAttribute("pendingAttachments", pending);

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldNotProcessWithNothing() {
        AgentContext context = createContext();
        assertFalse(system.shouldProcess(context));
    }

    @Test
    void attachmentsSentEvenOnLlmError() {
        AgentContext context = createContext();
        context.setAttribute("llm.error", "model crashed");

        List<Attachment> pending = new ArrayList<>();
        pending.add(Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1 })
                .filename("img.png")
                .mimeType("image/png")
                .caption("Before error")
                .build());
        context.setAttribute("pendingAttachments", pending);

        system.process(context);

        verify(channelPort).sendMessage(eq("chat1"), anyString()); // error message
        verify(channelPort).sendPhoto(eq("chat1"), any(byte[].class), eq("img.png"), eq("Before error"));
    }

    @Test
    void attachmentSendFailureDoesNotBreakResponse() {
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("text").build();
        context.setAttribute("llm.response", response);

        when(channelPort.sendPhoto(anyString(), any(byte[].class), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("upload failed")));

        List<Attachment> pending = new ArrayList<>();
        pending.add(Attachment.builder()
                .type(Attachment.Type.IMAGE)
                .data(new byte[] { 1 })
                .filename("img.png")
                .mimeType("image/png")
                .build());
        context.setAttribute("pendingAttachments", pending);

        // Should not throw
        assertDoesNotThrow(() -> system.process(context));
        verify(channelPort).sendMessage(eq("chat1"), eq("text"));
    }

    // ===== Edge Cases =====

    @Test
    void orderIsSixty() {
        assertEquals(60, system.getOrder());
    }

    @Test
    void nameIsResponseRoutingSystem() {
        assertEquals("ResponseRoutingSystem", system.getName());
    }

    @Test
    void skipsResponseWhenPipelineTransitionPending() {
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("response text").build();
        context.setAttribute("llm.response", response);
        context.setAttribute("skill.transition.target", "next-skill");

        system.process(context);

        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void autoModeMessageStoresInSessionOnly() {
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder().content("auto response").build();
        context.setAttribute("llm.response", response);

        Message autoMsg = Message.builder()
                .role("user")
                .content("auto task")
                .timestamp(Instant.now())
                .metadata(java.util.Map.of("auto.mode", true))
                .build();
        context.getMessages().add(autoMsg);

        system.process(context);

        verify(channelPort, never()).sendMessage(anyString(), anyString());
        // Should be added to session
        assertFalse(context.getSession().getMessages().isEmpty());
    }

    @Test
    void shouldProcessWithLlmError() {
        AgentContext context = createContext();
        context.setAttribute("llm.error", "model error");

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void shouldProcessWithLlmResponse() {
        AgentContext context = createContext();
        context.setAttribute("llm.response", LlmResponse.builder().content("hi").build());

        assertTrue(system.shouldProcess(context));
    }

    @Test
    void sendsLlmErrorToUser() {
        AgentContext context = createContext();
        context.setAttribute("llm.error", "model crashed");

        system.process(context);

        verify(channelPort).sendMessage(eq("chat1"), eq("Error occurred"));
    }

    @Test
    void nullResponseContentSkipsRouting() {
        AgentContext context = createContext();
        context.setAttribute("llm.response", LlmResponse.builder().content(null).build());

        system.process(context);

        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void blankResponseContentSkipsRouting() {
        AgentContext context = createContext();
        context.setAttribute("llm.response", LlmResponse.builder().content("   ").build());

        system.process(context);

        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void unknownChannelTypeDoesNotThrow() {
        AgentContext context = createContext();
        context.getSession().setChannelType("unknown_channel");
        context.setAttribute("llm.response", LlmResponse.builder().content("response").build());

        assertDoesNotThrow(() -> system.process(context));
        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void channelSendFailureSetsRoutingError() {
        when(channelPort.sendMessage(anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("send failed")));

        AgentContext context = createContext();
        context.setAttribute("llm.response", LlmResponse.builder().content("response").build());

        system.process(context);

        // CompletableFuture.get() wraps in ExecutionException, getMessage() includes
        // cause class
        String error = (String) context.getAttribute("routing.error");
        assertNotNull(error);
        assertTrue(error.contains("send failed"));
    }

    @Test
    void toolCallsPendingSkipsResponse() {
        AgentContext context = createContext();
        LlmResponse response = LlmResponse.builder()
                .content("Let me check")
                .toolCalls(List.of(Message.ToolCall.builder().id("tc1").name("shell").build()))
                .build();
        context.setAttribute("llm.response", response);
        context.setAttribute("tools.executed", true);

        system.process(context);

        verify(channelPort, never()).sendMessage(anyString(), anyString());
    }
}
