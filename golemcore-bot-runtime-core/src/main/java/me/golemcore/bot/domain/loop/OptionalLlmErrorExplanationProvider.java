package me.golemcore.bot.domain.loop;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FailureEvent;
import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.TurnOutcome;
import me.golemcore.bot.domain.runtimeconfig.ModelRoutingConfigView;
import me.golemcore.bot.port.outbound.LlmPort;

@Slf4j
final class OptionalLlmErrorExplanationProvider {

    private static final String ENABLED_PROPERTY = "golemcore.feedback.llm-error-explanation.enabled";
    private static final int MAX_ERROR_CHARS = 2000;

    private final ModelRoutingConfigView modelRoutingConfigView;
    private final LlmPort llmPort;
    private final Clock clock;

    OptionalLlmErrorExplanationProvider(ModelRoutingConfigView modelRoutingConfigView, LlmPort llmPort, Clock clock) {
        this.modelRoutingConfigView = modelRoutingConfigView;
        this.llmPort = llmPort;
        this.clock = clock;
    }

    Optional<String> explain(AgentContext context) {
        if (!Boolean.getBoolean(ENABLED_PROPERTY) || llmPort == null || !llmPort.isAvailable()) {
            return Optional.empty();
        }
        List<String> errors = collectSafeErrors(context);
        if (errors.isEmpty()) {
            return Optional.empty();
        }
        try {
            LlmRequest request = LlmRequest.builder().model(modelRoutingConfigView.getRoutingModel())
                    .reasoningEffort(modelRoutingConfigView.getRoutingModelReasoning())
                    .systemPrompt("Explain the sanitized error in one user-safe sentence. Do not reveal internals.")
                    .messages(List.of(Message.builder().role("user").content(String.join("\n", errors))
                            .timestamp(clock.instant()).build()))
                    .sessionId(context.getSession() != null ? context.getSession().getId() : null)
                    .traceId(context.getTraceContext() != null ? context.getTraceContext().getTraceId() : null)
                    .traceSpanId(context.getTraceContext() != null ? context.getTraceContext().getSpanId() : null)
                    .traceParentSpanId(
                            context.getTraceContext() != null ? context.getTraceContext().getParentSpanId() : null)
                    .traceRootKind(context.getTraceContext() != null ? context.getTraceContext().getRootKind() : null)
                    .build();
            LlmResponse response = llmPort.chat(request).get(3, TimeUnit.SECONDS);
            if (response != null && response.getContent() != null && !response.getContent().isBlank()) {
                return Optional.of(response.getContent());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("[OptionalLlmErrorExplanationProvider] LLM error explanation interrupted: {}", e.getMessage());
        } catch (ExecutionException | TimeoutException e) {
            log.debug("[OptionalLlmErrorExplanationProvider] LLM error explanation failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private List<String> collectSafeErrors(AgentContext context) {
        if (context == null) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        append(errors, context.getAttribute(ContextAttributes.LLM_ERROR));
        for (FailureEvent failure : context.getFailures()) {
            append(errors, failure.message());
        }
        TurnOutcome outcome = context.getTurnOutcome();
        if (outcome != null && outcome.getRoutingOutcome() != null) {
            append(errors, outcome.getRoutingOutcome().getErrorMessage());
        }
        return errors;
    }

    private void append(List<String> errors, String raw) {
        String redacted = redact(raw);
        if (redacted != null && !redacted.isBlank()) {
            errors.add(redacted);
        }
    }

    private String redact(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.replaceAll("(?i)(api[_-]?key|token|secret|password)=\\S+", "$1=<redacted>")
                .replaceAll("(?i)(bearer\\s+)[a-z0-9._~+/=-]+", "$1<redacted>");
        return normalized.length() <= MAX_ERROR_CHARS ? normalized : normalized.substring(0, MAX_ERROR_CHARS);
    }
}
