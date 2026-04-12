package me.golemcore.bot.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HiveSdlcDefaultSettingsIntegrationTest extends GolemCoreBotIntegrationTestBase {

    @Test
    void shouldEnableHiveSdlcDefaultsWhenHiveIntegrationIsActive() {
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
                .expectStatus().isOk();

        JsonNode runtimeConfig = getRuntimeConfig(accessToken);
        assertTrue(runtimeConfig.path("hive").path("enabled").asBoolean());
        assertTrue(runtimeConfig.path("selfEvolving").path("promotion").path("hiveApprovalPreferred").asBoolean());
        assertTrue(runtimeConfig.path("selfEvolving").path("hive").path("publishInspectionProjection").asBoolean());
        assertTrue(runtimeConfig.path("selfEvolving").path("hive").path("readonlyInspection").asBoolean());
    }
}
