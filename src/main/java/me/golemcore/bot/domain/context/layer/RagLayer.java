package me.golemcore.bot.domain.context.layer;

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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.context.ContextLayer;
import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.outbound.RagPort;
import org.springframework.stereotype.Component;

/**
 * Retrieves relevant context from the RAG (Retrieval-Augmented Generation)
 * index and injects it as a "# Relevant Memory" section.
 *
 * <p>
 * Only applies when the RAG port is available. Queries the index with the last
 * user message text and stores the result both in context attributes and in the
 * prompt.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RagLayer implements ContextLayer {

    private final RagPort ragPort;

    @Override
    public String getName() {
        return "rag";
    }

    @Override
    public int getOrder() {
        return 35;
    }

    @Override
    public boolean appliesTo(AgentContext context) {
        return ragPort.isAvailable();
    }

    @Override
    public ContextLayerResult assemble(AgentContext context) {
        String userQuery = getLastUserMessageText(context);
        if (userQuery == null || userQuery.isBlank()) {
            return ContextLayerResult.empty(getName());
        }

        try {
            String ragContext = ragPort.query(userQuery).join();
            if (ragContext == null || ragContext.isBlank()) {
                return ContextLayerResult.empty(getName());
            }

            context.setAttribute(ContextAttributes.RAG_CONTEXT, ragContext);

            String content = "# Relevant Memory\n" + ragContext;
            return ContextLayerResult.builder()
                    .layerName(getName())
                    .content(content)
                    .estimatedTokens((int) Math.ceil(content.length() / 3.5))
                    .build();
        } catch (Exception e) { // NOSONAR — best-effort RAG retrieval
            log.warn("[RagLayer] RAG query failed: {}", e.getMessage());
            return ContextLayerResult.empty(getName());
        }
    }

    private String getLastUserMessageText(AgentContext context) {
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return null;
        }
        for (int i = context.getMessages().size() - 1; i >= 0; i--) {
            Message msg = context.getMessages().get(i);
            if (msg.isUserMessage() && !msg.isInternalMessage()) {
                return msg.getContent();
            }
        }
        return null;
    }
}
