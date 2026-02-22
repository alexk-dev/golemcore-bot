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
        assertEquals("Быстро и экономно для коротких coding-сессий: меньше эпизодов, больше упора на практичные паттерны.",
                codingFast.get().getComment());
        assertTrue(codingBalanced.isPresent());
        assertEquals("Универсальный дефолт для большинства разработчиков.",
                codingBalanced.get().getComment());
        assertTrue(codingDeep.isPresent());
        assertEquals("Для длинных автономных веток: повышенный бюджет и глубина, но без агрессивного раздувания контекста.",
                codingDeep.get().getComment());
        assertTrue(generalChat.isPresent());
        assertEquals("Минимум памяти, чтобы не раздувать ответы в обычном диалоге.",
                generalChat.get().getComment());
        assertTrue(research.isPresent());
        assertEquals("Смещён в семантику: факты и выводы важнее сырых эпизодов.",
                research.get().getComment());
        assertTrue(ops.isPresent());
        assertEquals("Для инцидентов и поддержки: больше procedural (как чинили) и включена code-aware экстракция из логов/ошибок.",
                ops.get().getComment());
        assertTrue(disabled.isPresent());
        assertEquals("Память полностью отключена: подходит для privacy-чувствительных задач и отладки поведения без памяти.",
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
