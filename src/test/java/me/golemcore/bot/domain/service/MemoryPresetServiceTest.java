package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.MemoryPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryPresetServiceTest {

    private MemoryPresetService service;

    @BeforeEach
    void setUp() {
        service = new MemoryPresetService();
    }

    @Test
    void shouldReturnSevenPresets() {
        List<MemoryPreset> presets = service.getPresets();

        assertEquals(7, presets.size());
        assertTrue(presets.stream().anyMatch(preset -> "coding_fast".equals(preset.getId())));
        assertTrue(presets.stream().anyMatch(preset -> "coding_balanced".equals(preset.getId())));
        assertTrue(presets.stream().anyMatch(preset -> "coding_deep".equals(preset.getId())));
        assertTrue(presets.stream().anyMatch(preset -> "general_chat".equals(preset.getId())));
        assertTrue(presets.stream().anyMatch(preset -> "research_analyst".equals(preset.getId())));
        assertTrue(presets.stream().anyMatch(preset -> "ops_support".equals(preset.getId())));
        assertTrue(presets.stream().anyMatch(preset -> "disabled".equals(preset.getId())));
    }

    @Test
    void shouldReturnExpectedPresetComments() {
        Optional<MemoryPreset> codingFast = service.findById("coding_fast");
        Optional<MemoryPreset> codingBalanced = service.findById("coding_balanced");
        Optional<MemoryPreset> codingDeep = service.findById("coding_deep");
        Optional<MemoryPreset> generalChat = service.findById("general_chat");
        Optional<MemoryPreset> research = service.findById("research_analyst");
        Optional<MemoryPreset> ops = service.findById("ops_support");
        Optional<MemoryPreset> disabled = service.findById("disabled");

        assertTrue(codingFast.isPresent());
        assertEquals(
                "Fast and lightweight for short coding sessions: fewer episodic recalls, stronger focus on practical patterns.",
                codingFast.get().getComment());
        assertTrue(codingBalanced.isPresent());
        assertEquals("Recommended default for most developers.",
                codingBalanced.get().getComment());
        assertTrue(codingDeep.isPresent());
        assertEquals(
                "For long autonomous coding tracks: deeper recall and higher budget without aggressive context bloat.",
                codingDeep.get().getComment());
        assertTrue(generalChat.isPresent());
        assertEquals("Minimal memory footprint for everyday conversation to keep replies compact.",
                generalChat.get().getComment());
        assertTrue(research.isPresent());
        assertEquals("Semantic-heavy profile where facts and conclusions are prioritized over raw episodes.",
                research.get().getComment());
        assertTrue(ops.isPresent());
        assertEquals(
                "Incident-focused profile: higher procedural recall and code-aware extraction for logs, errors, and fixes.",
                ops.get().getComment());
        assertTrue(disabled.isPresent());
        assertEquals(
                "Memory is fully disabled for privacy-sensitive tasks and debugging without memory context.",
                disabled.get().getComment());
    }

    @Test
    void shouldReturnEmptyWhenPresetNotFound() {
        Optional<MemoryPreset> result = service.findById("unknown");

        assertFalse(result.isPresent());
    }

    @Test
    void shouldReturnDisabledPresetWithMemoryTurnedOff() {
        Optional<MemoryPreset> disabled = service.findById("disabled");

        assertTrue(disabled.isPresent());
        assertEquals(Boolean.FALSE, disabled.get().getMemory().getEnabled());
        assertEquals(21, disabled.get().getMemory().getRetrievalLookbackDays());
    }
}
