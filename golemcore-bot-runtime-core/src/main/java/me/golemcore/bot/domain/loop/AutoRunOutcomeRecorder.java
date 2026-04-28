package me.golemcore.bot.domain.loop;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FailureEvent;
import me.golemcore.bot.domain.model.FinishReason;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.service.AutoRunContextSupport;

/**
 * Copies observable turn outcome metadata back to auto-run inbound messages.
 */
final class AutoRunOutcomeRecorder {

    void record(Message inbound, AgentContext context) {
        if (!AutoRunContextSupport.isAutoMessage(inbound) || inbound == null || context == null) {
            return;
        }

        Map<String, Object> metadata = inbound.getMetadata();
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
            inbound.setMetadata(metadata);
        }

        String status = determineAutoRunStatus(context);
        String finishReason = determineAutoRunFinishReason(context);
        String assistantText = determineAssistantText(context);
        String activeSkillName = determineActiveSkillName(context);
        String failureSummary = determineFailureSummary(context);
        String failureFingerprint = buildFailureFingerprint(failureSummary);

        metadata.put(ContextAttributes.AUTO_RUN_STATUS, status);
        metadata.put(ContextAttributes.AUTO_RUN_FINISH_REASON, finishReason);
        if (assistantText != null && !assistantText.isBlank()) {
            metadata.put(ContextAttributes.AUTO_RUN_ASSISTANT_TEXT, assistantText);
        }
        if (activeSkillName != null && !activeSkillName.isBlank()) {
            metadata.put(ContextAttributes.AUTO_RUN_ACTIVE_SKILL, activeSkillName);
        }
        if (failureSummary != null && !failureSummary.isBlank()) {
            metadata.put(ContextAttributes.AUTO_RUN_FAILURE_SUMMARY, failureSummary);
            metadata.put(ContextAttributes.AUTO_RUN_FAILURE_FINGERPRINT, failureFingerprint);
        }
    }

    private String determineAutoRunStatus(AgentContext context) {
        boolean reflectionActive = Boolean.TRUE.equals(context.getAttribute(ContextAttributes.AUTO_REFLECTION_ACTIVE));
        boolean failed = hasAutoRunFailure(context);
        if (reflectionActive) {
            return failed ? "REFLECTION_FAILED" : "REFLECTION_COMPLETED";
        }
        return failed ? "FAILED" : "COMPLETED";
    }

    private String determineAutoRunFinishReason(AgentContext context) {
        TurnOutcome outcome = context.getTurnOutcome();
        FinishReason finishReason = outcome != null ? outcome.getFinishReason() : null;
        if (finishReason != null) {
            return finishReason.name();
        }
        if (context.getAttribute(ContextAttributes.LLM_ERROR) != null) {
            return FinishReason.ERROR.name();
        }
        return FinishReason.SUCCESS.name();
    }

    private boolean hasAutoRunFailure(AgentContext context) {
        if (context == null) {
            return true;
        }
        if (context.getAttribute(ContextAttributes.LLM_ERROR) != null) {
            return true;
        }
        if (!context.getFailures().isEmpty()) {
            return true;
        }
        TurnOutcome outcome = context.getTurnOutcome();
        return outcome != null && outcome.getFinishReason() == FinishReason.ERROR;
    }

    private String determineAssistantText(AgentContext context) {
        if (context == null) {
            return null;
        }
        TurnOutcome outcome = context.getTurnOutcome();
        if (outcome != null && outcome.getAssistantText() != null && !outcome.getAssistantText().isBlank()) {
            return outcome.getAssistantText();
        }
        OutgoingResponse outgoingResponse = context.getOutgoingResponse();
        if (outgoingResponse != null && outgoingResponse.getText() != null && !outgoingResponse.getText().isBlank()) {
            return outgoingResponse.getText();
        }
        return null;
    }

    private String determineFailureSummary(AgentContext context) {
        if (context == null) {
            return null;
        }
        if (!context.getFailures().isEmpty()) {
            FailureEvent failure = context.getFailures().get(context.getFailures().size() - 1);
            return failure.message();
        }
        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        if (llmError != null && !llmError.isBlank()) {
            return llmError;
        }
        TurnOutcome outcome = context.getTurnOutcome();
        if (outcome != null && outcome.getRoutingOutcome() != null) {
            return outcome.getRoutingOutcome().getErrorMessage();
        }
        return null;
    }

    private String determineActiveSkillName(AgentContext context) {
        if (context == null) {
            return null;
        }
        String explicit = context.getAttribute(ContextAttributes.ACTIVE_SKILL_NAME);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if (context.getActiveSkill() != null && context.getActiveSkill().getName() != null
                && !context.getActiveSkill().getName().isBlank()) {
            return context.getActiveSkill().getName();
        }
        return null;
    }

    private String buildFailureFingerprint(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        return summary.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
