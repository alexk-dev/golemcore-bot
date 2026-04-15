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

import me.golemcore.bot.domain.context.ContextLayer;
import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;

public class WebhookResponseSchemaLayer implements ContextLayer {

    @Override
    public String getName() {
        return "webhook_response_schema";
    }

    @Override
    public int getOrder() {
        return 76;
    }

    @Override
    public boolean appliesTo(AgentContext context) {
        return readSchemaText(context) != null;
    }

    @Override
    public ContextLayerResult assemble(AgentContext context) {
        String schemaText = readSchemaText(context);
        if (schemaText == null) {
            return ContextLayerResult.empty(getName());
        }

        String content = ("# Webhook Response JSON Contract%n"
                + "The caller requires the final response to be valid JSON matching this JSON Schema.%n"
                + "Return only the JSON payload. Do not include markdown fences, prose, or fields not allowed by the schema.%n%n"
                + "```json%n%s%n```")
                .formatted(schemaText);

        return ContextLayerResult.builder()
                .layerName(getName())
                .content(content)
                .estimatedTokens((int) Math.ceil(content.length() / 3.5))
                .build();
    }

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
