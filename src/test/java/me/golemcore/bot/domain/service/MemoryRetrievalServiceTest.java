package me.golemcore.bot.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.MemoryQuery;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryRetrievalServiceTest {

    private StoragePort storagePort;
    private RuntimeConfigService runtimeConfigService;
    private MemoryRetrievalService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        runtimeConfigService = mock(RuntimeConfigService.class);
        BotProperties properties = new BotProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        service = new MemoryRetrievalService(storagePort, properties, runtimeConfigService, objectMapper);

        when(storagePort.getText(anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(""));
        when(runtimeConfigService.isMemoryEnabled()).thenReturn(true);
        when(runtimeConfigService.getMemorySoftPromptBudgetTokens()).thenReturn(1800);
        when(runtimeConfigService.getMemoryMaxPromptBudgetTokens()).thenReturn(3500);
        when(runtimeConfigService.getMemoryWorkingTopK()).thenReturn(6);
        when(runtimeConfigService.getMemoryEpisodicTopK()).thenReturn(8);
        when(runtimeConfigService.getMemorySemanticTopK()).thenReturn(6);
        when(runtimeConfigService.getMemoryProceduralTopK()).thenReturn(4);
        when(runtimeConfigService.isMemoryDecayEnabled()).thenReturn(false);
    }

    @Test
    void shouldUseConfiguredRetrievalLookbackWhenDecayDisabled() {
        when(runtimeConfigService.getMemoryRetrievalLookbackDays()).thenReturn(3);

        assertTrue(service.retrieve(MemoryQuery.builder().queryText("incident").build()).isEmpty());

        verify(storagePort, times(5)).getText(eq("memory"), anyString());
    }

    @Test
    void shouldClampRetrievalLookbackToInternalMax() {
        when(runtimeConfigService.getMemoryRetrievalLookbackDays()).thenReturn(999);

        assertTrue(service.retrieve(MemoryQuery.builder().queryText("incident").build()).isEmpty());

        verify(storagePort, times(92)).getText(eq("memory"), anyString());
    }
}
