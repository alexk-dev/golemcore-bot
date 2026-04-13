package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.Secret;
import me.golemcore.bot.domain.model.hive.HiveCapabilitySnapshot;
import me.golemcore.bot.domain.model.hive.HivePolicyApplyResult;
import me.golemcore.bot.domain.model.hive.HivePolicyModelCatalog;
import me.golemcore.bot.domain.model.hive.HivePolicyPackage;
import me.golemcore.bot.port.outbound.HiveMachinePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HiveMachinePortAdapterTest {

    @Mock
    private HiveApiClient hiveApiClient;

    private HiveMachinePortAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new HiveMachinePortAdapter(hiveApiClient);
    }

    @Test
    void registerShouldMapAuthSession() {
        HiveCapabilitySnapshot capabilities = HiveCapabilitySnapshot.builder()
                .enabledAutonomyFeatures(Set.of("policy-sync-v1"))
                .supportedChannels(Set.of("web", "control"))
                .snapshotHash("hash-1")
                .defaultModel("openai/gpt-5.1")
                .build();
        HiveApiClient.GolemAuthResponse response = new HiveApiClient.GolemAuthResponse(
                "golem-1",
                "access",
                "refresh",
                Instant.parse("2026-04-08T00:00:00Z"),
                Instant.parse("2026-04-09T00:00:00Z"),
                "hive",
                "golems",
                "wss://hive/ws",
                30,
                List.of("golems:heartbeat", "golems:policy:write"));
        when(hiveApiClient.register(
                eq("https://hive.example.com"),
                eq("token-id.secret"),
                eq("Builder"),
                eq("lab-a"),
                eq("1.0.0"),
                eq("abc1234"),
                anySet(),
                eq(capabilities)))
                .thenReturn(response);

        HiveMachinePort.AuthSession session = adapter.register(
                "https://hive.example.com",
                "token-id.secret",
                "Builder",
                "lab-a",
                "1.0.0",
                "abc1234",
                Set.of("web", "control"),
                capabilities);

        assertEquals("golem-1", session.golemId());
        assertEquals("access", session.accessToken());
        assertEquals("refresh", session.refreshToken());
        assertEquals("wss://hive/ws", session.controlChannelUrl());
        assertEquals(30, session.heartbeatIntervalSeconds());
        assertEquals(List.of("golems:heartbeat", "golems:policy:write"), session.scopes());
    }

    @Test
    void registerShouldTranslateHiveApiExceptions() {
        when(hiveApiClient.register(any(), any(), any(), any(), any(), any(), anySet(), any()))
                .thenThrow(new HiveApiClient.HiveApiException(403, "forbidden"));

        HiveMachinePort.HiveMachineException exception = assertThrows(
                HiveMachinePort.HiveMachineException.class,
                () -> adapter.register(
                        "https://hive.example.com",
                        "token-id.secret",
                        "Builder",
                        "lab-a",
                        "1.0.0",
                        "abc1234",
                        Set.of("web"),
                        HiveCapabilitySnapshot.builder().build()));

        assertEquals(403, exception.getStatusCode());
        assertEquals("forbidden", exception.getMessage());
    }

    @Test
    void rotateShouldMapAuthSession() {
        HiveApiClient.GolemAuthResponse response = new HiveApiClient.GolemAuthResponse(
                "golem-1",
                "new-access",
                "new-refresh",
                Instant.parse("2026-04-08T00:00:00Z"),
                Instant.parse("2026-04-09T00:00:00Z"),
                "hive",
                "golems",
                "wss://hive/ws",
                45,
                List.of("golems:heartbeat"));
        when(hiveApiClient.rotate("https://hive.example.com", "golem-1", "refresh-1")).thenReturn(response);

        HiveMachinePort.AuthSession session = adapter.rotate("https://hive.example.com", "golem-1", "refresh-1");

        assertEquals("new-access", session.accessToken());
        assertEquals("new-refresh", session.refreshToken());
        assertEquals(45, session.heartbeatIntervalSeconds());
    }

    @Test
    void rotateShouldTranslateHiveApiExceptions() {
        when(hiveApiClient.rotate("https://hive.example.com", "golem-1", "refresh-1"))
                .thenThrow(new HiveApiClient.HiveApiException(401, "expired"));

        HiveMachinePort.HiveMachineException exception = assertThrows(
                HiveMachinePort.HiveMachineException.class,
                () -> adapter.rotate("https://hive.example.com", "golem-1", "refresh-1"));

        assertEquals(401, exception.getStatusCode());
        assertEquals("expired", exception.getMessage());
    }

    @Test
    void heartbeatShouldDelegatePayload() {
        adapter.heartbeat(
                "https://hive.example.com",
                "golem-1",
                "access",
                "CONNECTED",
                "healthy",
                "none",
                12L,
                "hash-1",
                "pg-1",
                4,
                3,
                "OUT_OF_SYNC",
                "provider-missing");

        verify(hiveApiClient).heartbeat(
                "https://hive.example.com",
                "golem-1",
                "access",
                "CONNECTED",
                "healthy",
                "none",
                12L,
                "hash-1",
                "pg-1",
                4,
                3,
                "OUT_OF_SYNC",
                "provider-missing");
    }

    @Test
    void heartbeatShouldTranslateHiveApiExceptions() {
        HiveApiClient.HiveApiException cause = new HiveApiClient.HiveApiException(500, "boom");
        doThrow(cause).when(hiveApiClient).heartbeat(
                eq("https://hive.example.com"),
                eq("golem-1"),
                eq("access"),
                eq("CONNECTED"),
                eq("healthy"),
                eq(null),
                eq(12L),
                eq("hash-1"),
                eq("pg-1"),
                eq(4),
                eq(3),
                eq("OUT_OF_SYNC"),
                eq("provider-missing"));

        HiveMachinePort.HiveMachineException exception = assertThrows(
                HiveMachinePort.HiveMachineException.class,
                () -> adapter.heartbeat(
                        "https://hive.example.com",
                        "golem-1",
                        "access",
                        "CONNECTED",
                        "healthy",
                        null,
                        12L,
                        "hash-1",
                        "pg-1",
                        4,
                        3,
                        "OUT_OF_SYNC",
                        "provider-missing"));

        assertEquals(500, exception.getStatusCode());
        assertSame(cause, exception.getCause());
    }

    @Test
    void getPolicyPackageShouldDelegateResponse() {
        HivePolicyPackage policyPackage = HivePolicyPackage.builder()
                .policyGroupId("pg-1")
                .targetVersion(4)
                .checksum("sha256:abcd")
                .llmProviders(Map.of("openai", RuntimeConfig.LlmProviderConfig.builder()
                        .apiKey(Secret.of("sk-test"))
                        .apiType("openai")
                        .build()))
                .modelCatalog(HivePolicyModelCatalog.builder()
                        .defaultModel("openai/gpt-5.1")
                        .build())
                .build();
        when(hiveApiClient.getPolicyPackage("https://hive.example.com", "golem-1", "access"))
                .thenReturn(policyPackage);

        HivePolicyPackage response = adapter.getPolicyPackage("https://hive.example.com", "golem-1", "access");

        assertSame(policyPackage, response);
    }

    @Test
    void getPolicyPackageShouldTranslateHiveApiExceptions() {
        when(hiveApiClient.getPolicyPackage("https://hive.example.com", "golem-1", "access"))
                .thenThrow(new HiveApiClient.HiveApiException(404, "missing"));

        HiveMachinePort.HiveMachineException exception = assertThrows(
                HiveMachinePort.HiveMachineException.class,
                () -> adapter.getPolicyPackage("https://hive.example.com", "golem-1", "access"));

        assertEquals(404, exception.getStatusCode());
    }

    @Test
    void reportPolicyApplyResultShouldDelegateResponse() {
        HivePolicyApplyResult applyResult = HivePolicyApplyResult.builder()
                .policyGroupId("pg-1")
                .targetVersion(4)
                .appliedVersion(4)
                .syncStatus("IN_SYNC")
                .checksum("sha256:abcd")
                .build();
        when(hiveApiClient.reportPolicyApplyResult("https://hive.example.com", "golem-1", "access", applyResult))
                .thenReturn(applyResult);

        HivePolicyApplyResult response = adapter.reportPolicyApplyResult(
                "https://hive.example.com",
                "golem-1",
                "access",
                applyResult);

        assertSame(applyResult, response);
    }

    @Test
    void reportPolicyApplyResultShouldTranslateHiveApiExceptions() {
        HivePolicyApplyResult applyResult = HivePolicyApplyResult.builder()
                .policyGroupId("pg-1")
                .targetVersion(4)
                .build();
        when(hiveApiClient.reportPolicyApplyResult("https://hive.example.com", "golem-1", "access", applyResult))
                .thenThrow(new HiveApiClient.HiveApiException(409, "conflict"));

        HiveMachinePort.HiveMachineException exception = assertThrows(
                HiveMachinePort.HiveMachineException.class,
                () -> adapter.reportPolicyApplyResult("https://hive.example.com", "golem-1", "access", applyResult));

        assertEquals(409, exception.getStatusCode());
        assertEquals("conflict", exception.getMessage());
    }
}
