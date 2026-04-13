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

package me.golemcore.bot.adapter.outbound.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.RequiredArgsConstructor;
import me.golemcore.bot.port.outbound.ResponseJsonSchemaValidatorPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class NetworkntResponseJsonSchemaValidatorAdapter implements ResponseJsonSchemaValidatorPort {

    private static final int MAX_REPORTED_SCHEMA_ERRORS = 8;
    private static final SchemaLocation DRAFT_2020_12_META_SCHEMA = SchemaLocation
            .of("https://json-schema.org/draft/2020-12/schema");

    private final ObjectMapper objectMapper;

    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final JsonSchema schemaDefinitionSchema = schemaFactory.getSchema(DRAFT_2020_12_META_SCHEMA);

    @Override
    public void validateResponseJsonSchema(Map<String, Object> responseJsonSchema) {
        if (responseJsonSchema == null) {
            return;
        }
        if (responseJsonSchema.isEmpty()) {
            throw new IllegalArgumentException("Invalid responseJsonSchema: schema must not be empty");
        }

        JsonNode schemaNode = objectMapper.valueToTree(responseJsonSchema);
        Set<ValidationMessage> validationMessages = schemaDefinitionSchema.validate(schemaNode);
        if (!validationMessages.isEmpty()) {
            List<String> errors = validationMessages.stream()
                    .map(ValidationMessage::getMessage)
                    .limit(MAX_REPORTED_SCHEMA_ERRORS)
                    .toList();
            throw new IllegalArgumentException("Invalid responseJsonSchema: " + String.join("; ", errors));
        }

        try {
            schemaFactory.getSchema(schemaNode);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid responseJsonSchema: " + e.getMessage(), e);
        }
    }
}
