package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeConfigServiceSelfEvolvingTest {

    private StoragePort storagePort;
    private RuntimeConfigService service;
    private ObjectMapper objectMapper;
    private Map<String, String> persistedSections;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        persistedSections = new ConcurrentHashMap<>();

        when(storagePort.putTextAtomic(anyString(), anyString(), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    String fileName = invocation.getArgument(1);
                    String content = invocation.getArgument(2);
                    persistedSections.put(fileName, content);
                    return CompletableFuture.completedFuture(null);
                });
        when(storagePort.getText(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String fileName = invocation.getArgument(1);
                    return CompletableFuture.completedFuture(persistedSections.get(fileName));
                });

        service = new RuntimeConfigService(storagePort);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void shouldDefaultSelfEvolvingToDisabledApprovalGatedMode() {
        RuntimeConfig config = service.getRuntimeConfig();

        assertNotNull(config.getSelfEvolving());
        assertFalse(config.getSelfEvolving().getEnabled());
        assertNotNull(config.getSelfEvolving().getPromotion());
        assertEquals("approval_gate", config.getSelfEvolving().getPromotion().getMode());
    }

    @Test
    void shouldPersistSelfEvolvingSection() throws Exception {
        RuntimeConfig config = service.getRuntimeConfig();
        config.setSelfEvolving(RuntimeConfig.SelfEvolvingConfig.builder()
                .enabled(true)
                .judge(RuntimeConfig.SelfEvolvingJudgeConfig.builder()
                        .enabled(true)
                        .primaryTier("smart")
                        .tiebreakerTier("deep")
                        .evolutionTier("coding")
                        .requireEvidenceAnchors(true)
                        .build())
                .promotion(RuntimeConfig.SelfEvolvingPromotionConfig.builder()
                        .mode("shadow_then_approval")
                        .allowAutoAccept(true)
                        .shadowRequired(true)
                        .canaryRequired(true)
                        .hiveApprovalPreferred(true)
                        .build())
                .build());

        service.updateRuntimeConfig(config);

        assertTrue(persistedSections.containsKey("self-evolving.json"));
        Map<?, ?> persistedSelfEvolving = objectMapper.readValue(persistedSections.get("self-evolving.json"),
                Map.class);
        assertEquals(true, persistedSelfEvolving.get("enabled"));
        assertEquals("smart", ((Map<?, ?>) persistedSelfEvolving.get("judge")).get("primaryTier"));
        assertEquals("shadow_then_approval", ((Map<?, ?>) persistedSelfEvolving.get("promotion")).get("mode"));
    }
}
