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

package me.golemcore.bot.domain.context.layer;

import me.golemcore.bot.domain.context.ContextLayerLifecycle;
import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;

/**
 * Adds the JSON-only response contract for schema-backed synchronous webhooks
 * to the main agent prompt.
 *
 * <p>
 * The canonical contract source is {@code AgentContext#getAttributes()} via
 * {@link ContextAttributes#WEBHOOK_RESPONSE_JSON_SCHEMA_TEXT}. Reading from
 * message metadata is retained only as a compatibility fallback. This keeps the
 * schema instruction available across multi-iteration flows where the last
 * conversation message may become an intermediate assistant message rather than
 * the original webhook request.
 * </p>
 */
public class WebhookResponseSchemaLayer extends AbstractContextLayer {

    public WebhookResponseSchemaLayer() {
        super("webhook_response_schema", 76, REQUIRED_PRIORITY, ContextLayerLifecycle.TURN);
    }

    @Override
    public boolean appliesTo(AgentContext context) {
        return readSchemaText(context) != null;
    }

    /**
     * Renders a prompt section instructing the main agent to return only JSON
     * matching the caller-provided schema.
     *
     * @param context
     *            current agent context
     * @return context layer result containing schema instructions, or empty result
     */
    @Override
    public ContextLayerResult assemble(AgentContext context) {
        String schemaText = readSchemaText(context);
        if (schemaText == null) {
            return empty();
        }

        String content = ("# Webhook Response JSON Contract%n"
                + "The caller requires the final response to be valid JSON matching this JSON Schema.%n"
                + "Return only the JSON payload. Do not include markdown fences, prose, or fields not allowed by the schema.%n%n"
                + "```json%n%s%n```")
                .formatted(schemaText);

        return result(content);
    }

    /**
     * Reads schema text from canonical context attributes first, then falls back to
     * the latest message metadata for legacy callers/tests that construct a context
     * without running through {@code AgentLoop.applyRuntimeAttributes}.
     */
    private String readSchemaText(AgentContext context) {
        if (context == null) {
            return null;
        }
        String attributeSchemaText = readText(
                context.getAttribute(ContextAttributes.WEBHOOK_RESPONSE_JSON_SCHEMA_TEXT));
        if (attributeSchemaText != null) {
            return attributeSchemaText;
        }
        if (context.getMessages() == null || context.getMessages().isEmpty()) {
            return null;
        }
        Message last = context.getMessages().get(context.getMessages().size() - 1);
        if (last.getMetadata() == null) {
            return null;
        }
        return readText(last.getMetadata().get(ContextAttributes.WEBHOOK_RESPONSE_JSON_SCHEMA_TEXT));
    }

    private String readText(Object value) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }
}
