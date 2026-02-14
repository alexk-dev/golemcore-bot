package me.golemcore.bot.domain.system;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.FinishReason;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.model.TurnOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Builds the canonical {@link TurnOutcome} from domain state accumulated during
 * the turn. Runs after side-effect systems (MemoryPersist, RagIndexing) and
 * before OutgoingResponsePreparationSystem/FeedbackGuaranteeSystem.
 *
 * <p>
 * Order 57: after MemoryPersist(50), RagIndexing(55), SkillPipeline(55); before
 * OutgoingResponsePreparation(58), FeedbackGuarantee(59), ResponseRouting(60).
 * </p>
 */
@Component
@Slf4j
public class TurnOutcomeFinalizationSystem implements AgentSystem {

    @Override
    public String getName() {
        return "TurnOutcomeFinalizationSystem";
    }

    @Override
    public int getOrder() {
        return 57;
    }

    @Override
    public boolean shouldProcess(AgentContext context) {
        if (context.getTurnOutcome() != null) {
            return false;
        }
        if (context.isFinalAnswerReady()) {
            return true;
        }
        if (context.getAttribute(ContextAttributes.LLM_ERROR) != null) {
            return true;
        }
        return context.getAttribute(ContextAttributes.OUTGOING_RESPONSE) != null;
    }

    @Override
    public AgentContext process(AgentContext context) {
        if (context.getTurnOutcome() != null) {
            return context;
        }

        FinishReason finishReason = determineFinishReason(context);
        String assistantText = extractAssistantText(context);
        OutgoingResponse outgoingResponse = context.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        String model = context.getAttribute(ContextAttributes.LLM_MODEL);
        boolean autoMode = isAutoModeContext(context);

        TurnOutcome outcome = TurnOutcome.builder()
                .finishReason(finishReason)
                .assistantText(assistantText)
                .outgoingResponse(outgoingResponse)
                .failures(context.getFailures())
                .model(model)
                .autoMode(autoMode)
                .build();

        context.setTurnOutcome(outcome);
        log.debug("[TurnOutcome] Finalized: finishReason={}, assistantText={}, model={}, failures={}",
                finishReason,
                assistantText != null ? assistantText.length() + " chars" : "null",
                model,
                context.getFailures().size());

        return context;
    }

    private FinishReason determineFinishReason(AgentContext context) {
        Boolean iterationLimit = context.getAttribute(ContextAttributes.ITERATION_LIMIT_REACHED);
        if (Boolean.TRUE.equals(iterationLimit)) {
            return FinishReason.ITERATION_LIMIT;
        }

        String llmError = context.getAttribute(ContextAttributes.LLM_ERROR);
        if (llmError != null) {
            return FinishReason.ERROR;
        }

        String planApproval = context.getAttribute(ContextAttributes.PLAN_APPROVAL_NEEDED);
        if (planApproval != null) {
            return FinishReason.PLAN_MODE;
        }

        if (context.isFinalAnswerReady()) {
            return FinishReason.SUCCESS;
        }

        return FinishReason.SUCCESS;
    }

    private String extractAssistantText(AgentContext context) {
        LlmResponse response = context.getAttribute(ContextAttributes.LLM_RESPONSE);
        if (response != null && response.getContent() != null) {
            return response.getContent();
        }
        return null;
    }

    private boolean isAutoModeContext(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return false;
        }
        Message last = context.getMessages().get(context.getMessages().size() - 1);
        return last.getMetadata() != null && Boolean.TRUE.equals(last.getMetadata().get("auto.mode"));
    }
}
