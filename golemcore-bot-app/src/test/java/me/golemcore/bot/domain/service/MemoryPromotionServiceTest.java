package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.MemoryItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryPromotionServiceTest {

    private RuntimeConfigService runtimeConfigService;
    private MemoryPromotionService service;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        service = new MemoryPromotionService(runtimeConfigService);
        when(runtimeConfigService.isMemoryPromotionEnabled()).thenReturn(true);
        when(runtimeConfigService.getMemoryPromotionMinConfidence()).thenReturn(0.80);
    }

    @Test
    void shouldDelegatePromotionEnabledFlag() {
        assertTrue(service.isPromotionEnabled());
        when(runtimeConfigService.isMemoryPromotionEnabled()).thenReturn(false);
        assertFalse(service.isPromotionEnabled());
    }

    @Test
    void shouldPromoteSemanticTypesWhenConfidenceMeetsThreshold() {
        assertTrue(service.shouldPromoteToSemantic(item(MemoryItem.Type.CONSTRAINT, 0.80)));
        assertTrue(service.shouldPromoteToSemantic(item(MemoryItem.Type.PREFERENCE, 0.95)));
        assertTrue(service.shouldPromoteToSemantic(item(MemoryItem.Type.PROJECT_FACT, 0.81)));
        assertTrue(service.shouldPromoteToSemantic(item(MemoryItem.Type.DECISION, 1.0)));
    }

    @Test
    void shouldNotPromoteSemanticTypesWhenConfidenceTooLowOrTypeUnsupported() {
        assertFalse(service.shouldPromoteToSemantic(item(MemoryItem.Type.CONSTRAINT, 0.79)));
        assertFalse(service.shouldPromoteToSemantic(item(MemoryItem.Type.FAILURE, 0.99)));
        assertFalse(service.shouldPromoteToSemantic(item(MemoryItem.Type.DECISION, null)));
        assertFalse(service.shouldPromoteToSemantic(null));
    }

    @Test
    void shouldPromoteProceduralTypesWhenConfidenceMeetsThreshold() {
        assertTrue(service.shouldPromoteToProcedural(item(MemoryItem.Type.FAILURE, 0.80)));
        assertTrue(service.shouldPromoteToProcedural(item(MemoryItem.Type.FIX, 0.90)));
        assertTrue(service.shouldPromoteToProcedural(item(MemoryItem.Type.COMMAND_RESULT, 0.99)));
    }

    @Test
    void shouldNotPromoteProceduralTypesWhenConfidenceTooLowOrTypeUnsupported() {
        assertFalse(service.shouldPromoteToProcedural(item(MemoryItem.Type.FIX, 0.79)));
        assertFalse(service.shouldPromoteToProcedural(item(MemoryItem.Type.PROJECT_FACT, 0.95)));
        assertFalse(service.shouldPromoteToProcedural(item(MemoryItem.Type.FAILURE, null)));
        assertFalse(service.shouldPromoteToProcedural(null));
    }

    private MemoryItem item(MemoryItem.Type type, Double confidence) {
        return MemoryItem.builder()
                .type(type)
                .confidence(confidence)
                .build();
    }
}
