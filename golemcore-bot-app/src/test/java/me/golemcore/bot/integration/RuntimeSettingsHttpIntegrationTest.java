package me.golemcore.bot.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSettingsHttpIntegrationTest extends GolemCoreBotIntegrationTestBase {

    RuntimeSettingsHttpIntegrationTest(@LocalServerPort int port, ObjectMapper objectMapper) {
        super(port, objectMapper);
    }

    @Test
    void shouldPersistHiveAndSelfEvolvingSettingsThroughHttpApi() throws Exception {
        String accessToken = loginAndExtractAccessToken();

        authenticatedPut("/api/settings/runtime/hive", accessToken, """
                {
                  "enabled": true,
                  "serverUrl": "https://hive.example.com",
                  "displayName": "QA Bot",
                  "hostLabel": "qa-host",
                  "autoConnect": true,
                  "managedByProperties": false
                }
                """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.hive.enabled").isEqualTo(true)
                .jsonPath("$.hive.serverUrl").isEqualTo("https://hive.example.com")
                .jsonPath("$.hive.autoConnect").isEqualTo(true);

        JsonNode runtimeConfig = getRuntimeConfig(accessToken);
        assertTrue(runtimeConfig.path("hive").path("enabled").asBoolean());
        assertTrue(runtimeConfig.path("plan").has("modelTier"));
        assertTrue(runtimeConfig.path("plan").path("modelTier").isNull());
        assertTrue(runtimeConfig.path("selfEvolving").path("promotion").has("hiveApprovalPreferred"));
        assertTrue(runtimeConfig.path("selfEvolving").path("hive").has("publishInspectionProjection"));
        assertTrue(runtimeConfig.path("selfEvolving").path("hive").has("readonlyInspection"));

        JsonNode persistedHive = readPersistedPreferenceSection("hive.json");
        assertEquals("https://hive.example.com", persistedHive.path("serverUrl").asText());
        assertTrue(persistedHive.path("autoConnect").asBoolean());
    }

    @Test
    void shouldAllowDisablingHiveAdjacentSdlcFeatureTogglesIndependently() {
        String accessToken = loginAndExtractAccessToken();

        authenticatedPut("/api/settings/runtime/hive", accessToken, """
                {
                  "enabled": true,
                  "serverUrl": "https://hive.example.com",
                  "displayName": "QA Bot",
                  "hostLabel": "qa-host",
                  "autoConnect": false,
                  "managedByProperties": false
                }
                """)
                .exchange()
                .expectStatus().isOk();

        JsonNode current = getRuntimeConfig(accessToken);
        ObjectNode selfEvolvingPatch = current.path("selfEvolving").deepCopy();
        ((ObjectNode) selfEvolvingPatch.path("hive"))
                .put("publishInspectionProjection", false)
                .put("readonlyInspection", false);
        ((ObjectNode) selfEvolvingPatch.path("promotion"))
                .put("hiveApprovalPreferred", false);

        authenticatedPut("/api/settings/runtime", accessToken,
                objectMapper.createObjectNode().set("selfEvolving", selfEvolvingPatch).toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.hive.enabled").isEqualTo(true)
                .jsonPath("$.selfEvolving.promotion.hiveApprovalPreferred").isEqualTo(false)
                .jsonPath("$.selfEvolving.hive.publishInspectionProjection").isEqualTo(false)
                .jsonPath("$.selfEvolving.hive.readonlyInspection").isEqualTo(false);
    }

    @Test
    void shouldPersistSessionRetentionSettingsThroughHttpApi() throws Exception {
        String accessToken = loginAndExtractAccessToken();

        authenticatedPut("/api/settings/runtime/session-retention", accessToken, """
                {
                  "enabled": true,
                  "maxAge": "P14D",
                  "cleanupInterval": "PT12H",
                  "protectActiveSessions": true,
                  "protectSessionsWithPlans": true,
                  "protectSessionsWithDelayedActions": false
                }
                """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sessionRetention.maxAge").isEqualTo("P14D")
                .jsonPath("$.sessionRetention.cleanupInterval").isEqualTo("PT12H")
                .jsonPath("$.sessionRetention.protectSessionsWithDelayedActions").isEqualTo(false);

        JsonNode persistedSessionRetention = readPersistedPreferenceSection("session-retention.json");
        assertEquals("P14D", persistedSessionRetention.path("maxAge").asText());
        assertEquals("PT12H", persistedSessionRetention.path("cleanupInterval").asText());
        assertFalse(persistedSessionRetention.path("protectSessionsWithDelayedActions").asBoolean());
    }

    @Test
    void shouldPersistShellEnvironmentVariableLifecycleThroughHttpApi() throws Exception {
        String accessToken = loginAndExtractAccessToken();

        authenticatedPut("/api/settings/runtime/tools", accessToken, """
                {
                  "filesystemEnabled": true,
                  "shellEnabled": true,
                  "skillManagementEnabled": true,
                  "skillTransitionEnabled": true,
                  "tierEnabled": true,
                  "goalManagementEnabled": true,
                  "shellEnvironmentVariables": [
                    { "name": " QA_TOKEN ", "value": "secret-value" }
                  ]
                }
                """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tools.shellEnvironmentVariables[0].name").isEqualTo("QA_TOKEN")
                .jsonPath("$.tools.shellEnvironmentVariables[0].value").isEqualTo("secret-value");

        authenticatedPut("/api/settings/runtime/tools/shell/env/QA_TOKEN", accessToken, """
                { "name": "QA_TOKEN", "value": "updated-value" }
                """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tools.shellEnvironmentVariables[0].value").isEqualTo("updated-value");

        JsonNode persistedTools = readPersistedPreferenceSection("tools.json");
        assertEquals("QA_TOKEN", persistedTools.path("shellEnvironmentVariables").get(0).path("name").asText());
        assertEquals("updated-value", persistedTools.path("shellEnvironmentVariables").get(0).path("value").asText());
    }

    @Test
    void shouldRejectInvalidRuntimeSettingsWithClientErrorStatus() {
        String accessToken = loginAndExtractAccessToken();

        authenticatedPut("/api/settings/runtime/hive", accessToken, """
                { "enabled": true, "serverUrl": "not-a-url", "managedByProperties": false }
                """)
                .exchange()
                .expectStatus().is4xxClientError();

        authenticatedPut("/api/settings/runtime/turn", accessToken, """
                { "maxLlmCalls": 0, "maxToolExecutions": 1, "deadline": "PT1M" }
                """)
                .exchange()
                .expectStatus().is4xxClientError();

        authenticatedPut("/api/settings/runtime/tools", accessToken, """
                {
                  "shellEnvironmentVariables": [
                    { "name": "1INVALID", "value": "bad" }
                  ]
                }
                """)
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void shouldPreserveSecretsWhenRuntimeUpdateSendsRedactedSecretObjects() throws Exception {
        String accessToken = loginAndExtractAccessToken();

        authenticatedPut("/api/settings/runtime/llm", accessToken, """
                {
                  "providers": {
                    "openai": {
                      "apiKey": { "value": "real-secret", "encrypted": false, "present": true },
                      "baseUrl": "https://api.openai.com",
                      "apiType": "openai",
                      "requestTimeoutSeconds": 30
                    }
                  }
                }
                """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.llm.providers.openai.apiKey.present").isEqualTo(true)
                .jsonPath("$.llm.providers.openai.apiKey.value").doesNotExist();

        authenticatedPut("/api/settings/runtime/llm", accessToken, """
                {
                  "providers": {
                    "openai": {
                      "apiKey": { "value": null, "encrypted": false, "present": true },
                      "baseUrl": "https://api.openai.com/v1",
                      "apiType": "openai",
                      "requestTimeoutSeconds": 60
                    }
                  }
                }
                """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.llm.providers.openai.apiKey.present").isEqualTo(true)
                .jsonPath("$.llm.providers.openai.baseUrl").isEqualTo("https://api.openai.com/v1");

        JsonNode persistedLlm = readPersistedPreferenceSection("llm.json");
        assertEquals("real-secret",
                persistedLlm.path("providers").path("openai").path("apiKey").path("value").asText());
        assertEquals("https://api.openai.com/v1",
                persistedLlm.path("providers").path("openai").path("baseUrl").asText());
    }
}
