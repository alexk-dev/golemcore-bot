package me.golemcore.bot.domain.system.toolloop;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultHistoryWriterTest {

    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_TOOL = "tool";
    private static final String TC_ID = "tc-1";
    private static final Instant FIXED_INSTANT = Instant.parse("2026-02-14T00:00:00Z");

    private DefaultHistoryWriter writer;
    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"));
        writer = new DefaultHistoryWriter(clock);
    }

    private AgentContext buildContext(boolean withSession) {
        AgentSession session = null;
        if (withSession) {
            session = AgentSession.builder()
                    .id("sess-1")
                    .build();
        }

        return AgentContext.builder()
                .session(session)
                .messages(new ArrayList<>())
                .maxIterations(1)
                .build();
    }

    // ==================== appendAssistantToolCalls ====================

    @Test
    void shouldAppendAssistantToolCallsWithContent() {
        AgentContext context = buildContext(true);
        LlmResponse response = LlmResponse.builder().content("thinking...").build();
        List<Message.ToolCall> toolCalls = List.of(
                Message.ToolCall.builder().id(TC_ID).name("test").build());

        writer.appendAssistantToolCalls(context, response, toolCalls);

        assertEquals(1, context.getMessages().size());
        Message msg = context.getMessages().get(0);
        assertEquals(ROLE_ASSISTANT, msg.getRole());
        assertEquals("thinking...", msg.getContent());
        assertEquals(1, msg.getToolCalls().size());
        assertEquals(FIXED_INSTANT, msg.getTimestamp());
        assertEquals(1, context.getSession().getMessages().size());
    }

    @Test
    void shouldAppendAssistantToolCallsWithNullLlmResponse() {
        AgentContext context = buildContext(true);
        List<Message.ToolCall> toolCalls = List.of(
                Message.ToolCall.builder().id(TC_ID).name("test").build());

        writer.appendAssistantToolCalls(context, null, toolCalls);

        assertEquals(1, context.getMessages().size());
        assertNull(context.getMessages().get(0).getContent());
    }

    @Test
    void shouldAppendAssistantToolCallsWithoutSession() {
        AgentContext context = buildContext(false);
        LlmResponse response = LlmResponse.builder().content("text").build();

        writer.appendAssistantToolCalls(context, response, List.of());

        assertEquals(1, context.getMessages().size());
    }

    @Test
    void shouldPersistProviderMetadataOnAssistantToolCalls() {
        AgentContext context = buildContext(true);
        LlmResponse response = LlmResponse.builder()
                .content("thinking...")
                .providerMetadata(Map.of("thinking_signature", "sig-123"))
                .build();

        writer.appendAssistantToolCalls(context, response, List.of(
                Message.ToolCall.builder().id(TC_ID).name("test").build()));

        Message msg = context.getMessages().get(0);
        assertEquals("sig-123", msg.getMetadata().get("thinking_signature"));
        assertEquals("sig-123", context.getSession().getMessages().get(0).getMetadata().get("thinking_signature"));
    }

    // ==================== appendToolResult ====================

    @Test
    void shouldAppendToolResultWithSession() {
        AgentContext context = buildContext(true);
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TC_ID, "test_tool", ToolResult.success("result"), "result", false, null);

        writer.appendToolResult(context, outcome);

        assertEquals(1, context.getMessages().size());
        Message msg = context.getMessages().get(0);
        assertEquals(ROLE_TOOL, msg.getRole());
        assertEquals("tc-1", msg.getToolCallId());
        assertEquals("test_tool", msg.getToolName());
        assertEquals("result", msg.getContent());
        assertEquals(FIXED_INSTANT, msg.getTimestamp());
        assertEquals(1, context.getSession().getMessages().size());
    }

    @Test
    void shouldAppendToolResultWithoutSession() {
        AgentContext context = buildContext(false);
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TC_ID, "test_tool", ToolResult.success("ok"), "ok", false, null);

        writer.appendToolResult(context, outcome);

        assertEquals(1, context.getMessages().size());
    }

    @Test
    void shouldPersistToolImageAttachmentMetadataOnToolResult() {
        AgentContext context = buildContext(true);
        ToolResult toolResult = ToolResult.builder()
                .success(true)
                .output("Saved screenshot")
                .data(Map.of(
                        "internal_file_kind", "image",
                        "internal_file_path", ".golemcore/tool-artifacts/session/tool/capture.png",
                        "internal_file_url", "/api/files/download?path=capture",
                        "internal_file_thumbnail_base64", "thumb-base64",
                        "internal_file_name", "capture.png",
                        "internal_file_mime_type", "image/png"))
                .build();
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                TC_ID, "pinchtab_screenshot", toolResult, "Saved screenshot", false, null);

        writer.appendToolResult(context, outcome);

        Message message = context.getMessages().get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments = (List<Map<String, Object>>) message.getMetadata()
                .get("toolAttachments");
        assertNotNull(attachments);
        assertEquals(1, attachments.size());
        assertEquals("image", attachments.get(0).get("type"));
        assertEquals(".golemcore/tool-artifacts/session/tool/capture.png", attachments.get(0).get("internalFilePath"));
        assertEquals("/api/files/download?path=capture", attachments.get(0).get("url"));
        assertEquals("thumb-base64", attachments.get(0).get("thumbnailBase64"));
        assertEquals("capture.png", attachments.get(0).get("name"));
        assertEquals("image/png", attachments.get(0).get("mimeType"));
    }

    // ==================== appendFinalAssistantAnswer ====================

    @Test
    void shouldAppendInternalRecoveryHint() {
        AgentContext context = buildContext(true);

        writer.appendInternalRecoveryHint(context, "Shell recovery note");

        assertEquals(1, context.getMessages().size());
        Message message = context.getMessages().get(0);
        assertEquals("user", message.getRole());
        assertEquals("Shell recovery note", message.getContent());
        assertEquals(Boolean.TRUE, message.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL));
        assertEquals(ContextAttributes.MESSAGE_INTERNAL_KIND_TOOL_RECOVERY,
                message.getMetadata().get(ContextAttributes.MESSAGE_INTERNAL_KIND));
        assertEquals(1, context.getSession().getMessages().size());
    }

    @Test
    void shouldAppendFinalAnswerWithLlmResponse() {
        AgentContext context = buildContext(true);
        LlmResponse response = LlmResponse.builder()
                .toolCalls(List.of(Message.ToolCall.builder().id(TC_ID).name("test").build()))
                .build();

        writer.appendFinalAssistantAnswer(context, response, "Final text");

        assertEquals(1, context.getMessages().size());
        Message msg = context.getMessages().get(0);
        assertEquals(ROLE_ASSISTANT, msg.getRole());
        assertEquals("Final text", msg.getContent());
        assertNotNull(msg.getToolCalls());
        assertEquals(1, context.getSession().getMessages().size());
    }

    @Test
    void shouldAppendFinalAnswerWithNullLlmResponse() {
        AgentContext context = buildContext(true);

        writer.appendFinalAssistantAnswer(context, null, "Fallback text");

        assertEquals(1, context.getMessages().size());
        Message msg = context.getMessages().get(0);
        assertEquals("Fallback text", msg.getContent());
        assertNull(msg.getToolCalls());
    }

    @Test
    void shouldPersistBalancedTierWhenContextTierIsMissing() {
        AgentContext context = buildContext(true);

        writer.appendFinalAssistantAnswer(context, null, "Fallback text");

        Message msg = context.getMessages().get(0);
        assertNotNull(msg.getMetadata());
        assertEquals("balanced", msg.getMetadata().get("modelTier"));
        assertTrue(context.getSession().getMessages().get(0).getMetadata().containsKey("modelTier"));
    }

    @Test
    void shouldPersistReasoningMetadataWhenAvailable() {
        AgentContext context = buildContext(true);
        context.setAttribute(ContextAttributes.LLM_REASONING, "high");

        writer.appendFinalAssistantAnswer(context, null, "Final text");

        Message message = context.getMessages().get(0);
        assertEquals("high", message.getMetadata().get("reasoning"));
    }

    @Test
    void shouldPersistActiveSkillMetadataWhenAvailable() {
        AgentContext context = buildContext(true);
        context.setActiveSkill(Skill.builder()
                .name("golemcore/superpowers/superpowers-systematic-debugging")
                .build());

        writer.appendFinalAssistantAnswer(context, null, "Final text");

        Message message = context.getMessages().get(0);
        assertEquals(
                "golemcore/superpowers/superpowers-systematic-debugging",
                message.getMetadata().get(ContextAttributes.ACTIVE_SKILL_NAME));
    }

    @Test
    void shouldSkipReasoningMetadataWhenDisabled() {
        AgentContext context = buildContext(true);
        context.setAttribute(ContextAttributes.LLM_REASONING, "none");

        writer.appendFinalAssistantAnswer(context, null, "Final text");

        Message message = context.getMessages().get(0);
        assertFalse(message.getMetadata().containsKey("reasoning"));
    }

    @Test
    void shouldCarryTurnAttachmentsIntoFinalAssistantAnswer() {
        AgentContext context = buildContext(true);
        ToolResult toolResult = ToolResult.builder()
                .success(true)
                .output("Saved screenshot")
                .data(Map.of(
                        "internal_file_kind", "image",
                        "internal_file_path", ".golemcore/tool-artifacts/session/tool/capture.png",
                        "internal_file_url", "/api/files/download?path=capture",
                        "internal_file_thumbnail_base64", "thumb-base64",
                        "internal_file_name", "capture.png",
                        "internal_file_mime_type", "image/png"))
                .build();
        writer.appendToolResult(context, new ToolExecutionOutcome(
                TC_ID, "pinchtab_screenshot", toolResult, "Saved screenshot", false, null));

        writer.appendFinalAssistantAnswer(context, null, "Here is the screenshot");

        Message message = context.getMessages().get(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments = (List<Map<String, Object>>) message.getMetadata().get("attachments");
        assertNotNull(attachments);
        assertEquals(1, attachments.size());
        assertEquals("/api/files/download?path=capture", attachments.get(0).get("url"));
        assertEquals("thumb-base64", attachments.get(0).get("thumbnailBase64"));
        assertEquals("capture.png", attachments.get(0).get("name"));
    }

    @Test
    void shouldAppendFinalAnswerWithoutSession() {
        AgentContext context = buildContext(false);
        LlmResponse response = LlmResponse.builder().content("text").build();

        writer.appendFinalAssistantAnswer(context, response, "Final");

        assertEquals(1, context.getMessages().size());
    }

    // ==================== Null clock ====================

    @Test
    void shouldFallbackToInstantNowWhenClockIsNull() {
        DefaultHistoryWriter nullClockWriter = new DefaultHistoryWriter(null);
        AgentContext context = buildContext(false);

        nullClockWriter.appendToolResult(context, new ToolExecutionOutcome(
                TC_ID, "tool", ToolResult.success("ok"), "ok", false, null));

        assertEquals(1, context.getMessages().size());
        assertNotNull(context.getMessages().get(0).getTimestamp());
    }
}
