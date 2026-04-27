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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import me.golemcore.bot.port.outbound.ResponseJsonSchemaValidatorPort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class NetworkntResponseJsonSchemaValidatorAdapter implements ResponseJsonSchemaValidatorPort {

    private static final int MAX_REPORTED_SCHEMA_ERRORS = 8;
    private static final SchemaLocation DRAFT_2020_12_META_SCHEMA = SchemaLocation
            .of("https://json-schema.org/draft/2020-12/schema");

    private final ObjectMapper objectMapper;

    private final SchemaRegistry schemaRegistry = SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private final Schema schemaDefinitionSchema = schemaRegistry.getSchema(DRAFT_2020_12_META_SCHEMA);

    public NetworkntResponseJsonSchemaValidatorAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void validateResponseJsonSchema(Map<String, Object> responseJsonSchema) {
        if (responseJsonSchema == null) {
            return;
        }
        if (responseJsonSchema.isEmpty()) {
            throw new IllegalArgumentException("Invalid responseJsonSchema: schema must not be empty");
        }

        String schemaJson = writeJson(responseJsonSchema);
        List<Error> validationErrors = schemaDefinitionSchema.validate(schemaJson, InputFormat.JSON);
        if (!validationErrors.isEmpty()) {
            List<String> errors = validationErrors.stream()
                    .map(Error::getMessage)
                    .limit(MAX_REPORTED_SCHEMA_ERRORS)
                    .toList();
            throw new IllegalArgumentException("Invalid responseJsonSchema: " + String.join("; ", errors));
        }

        try {
            schemaRegistry.getSchema(schemaJson, InputFormat.JSON);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid responseJsonSchema: " + e.getMessage(), e);
        }
    }

    private String writeJson(Map<String, Object> responseJsonSchema) {
        try {
            return objectMapper.writeValueAsString(responseJsonSchema);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid responseJsonSchema: " + e.getMessage(), e);
        }
    }
}
