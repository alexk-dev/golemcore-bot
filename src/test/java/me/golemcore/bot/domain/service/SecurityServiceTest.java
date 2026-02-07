package me.golemcore.bot.domain.service;

import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.security.InjectionGuard;
import me.golemcore.bot.security.InputSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecurityServiceTest {

    private InputSanitizer inputSanitizer;
    private InjectionGuard injectionGuard;
    private BotProperties properties;
    private SecurityService service;

    @BeforeEach
    void setUp() {
        inputSanitizer = mock(InputSanitizer.class);
        injectionGuard = mock(InjectionGuard.class);
        properties = new BotProperties();
        service = new SecurityService(inputSanitizer, injectionGuard, properties);

        when(inputSanitizer.normalizeUnicode(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================== sanitizeInput ====================

    @Test
    void sanitizeInputReturnsEmptyForNull() {
        assertEquals("", service.sanitizeInput(null));
    }

    @Test
    void sanitizeInputReturnsEmptyStringForEmpty() {
        when(inputSanitizer.normalizeUnicode("")).thenReturn("");
        assertEquals("", service.sanitizeInput(""));
    }

    @Test
    void sanitizeInputTruncatesLongInput() {
        properties.getSecurity().setMaxInputLength(10);
        String longInput = "a".repeat(20);

        String result = service.sanitizeInput(longInput);

        assertEquals(10, result.length());
        verify(inputSanitizer).normalizeUnicode("a".repeat(10));
    }

    @Test
    void sanitizeInputPassesThroughShortInput() {
        properties.getSecurity().setMaxInputLength(100);

        service.sanitizeInput("hello");

        verify(inputSanitizer).normalizeUnicode("hello");
    }

    @Test
    void sanitizeInputAtExactMaxLength() {
        properties.getSecurity().setMaxInputLength(5);

        service.sanitizeInput("hello");

        verify(inputSanitizer).normalizeUnicode("hello");
    }

    @Test
    void sanitizeInputOneOverMaxLength() {
        properties.getSecurity().setMaxInputLength(5);

        service.sanitizeInput("hello!");

        verify(inputSanitizer).normalizeUnicode("hello");
    }

    // ==================== checkForInjection ====================

    @Test
    void checkForInjectionSafeInput() {
        var result = service.checkForInjection("hello world");

        assertTrue(result.isSafe());
        assertTrue(result.getThreats().isEmpty());
        assertEquals("hello world", result.getSanitizedInput());
    }

    @Test
    void checkForInjectionDetectsPromptInjection() {
        when(injectionGuard.detectPromptInjection("ignore previous")).thenReturn(true);

        var result = service.checkForInjection("ignore previous");

        assertFalse(result.isSafe());
        assertTrue(result.getThreats().contains("prompt_injection"));
    }

    @Test
    void checkForInjectionSkipsPromptInjectionWhenDisabled() {
        properties.getSecurity().setDetectPromptInjection(false);
        when(injectionGuard.detectPromptInjection(any())).thenReturn(true);

        var result = service.checkForInjection("ignore previous");

        assertTrue(result.isSafe());
        verify(injectionGuard, never()).detectPromptInjection(any());
    }

    @Test
    void checkForInjectionDetectsCommandInjection() {
        when(injectionGuard.detectCommandInjection("; rm -rf /")).thenReturn(true);

        var result = service.checkForInjection("; rm -rf /");

        assertFalse(result.isSafe());
        assertTrue(result.getThreats().contains("command_injection"));
    }

    @Test
    void checkForInjectionSkipsCommandInjectionWhenDisabled() {
        properties.getSecurity().setDetectCommandInjection(false);
        when(injectionGuard.detectCommandInjection(any())).thenReturn(true);

        var result = service.checkForInjection("; rm -rf /");

        verify(injectionGuard, never()).detectCommandInjection(any());
    }

    @Test
    void checkForInjectionDetectsSqlInjection() {
        when(injectionGuard.detectSqlInjection("' OR 1=1 --")).thenReturn(true);

        var result = service.checkForInjection("' OR 1=1 --");

        assertFalse(result.isSafe());
        assertTrue(result.getThreats().contains("sql_injection"));
    }

    @Test
    void checkForInjectionDetectsPathTraversal() {
        when(injectionGuard.detectPathTraversal("../../etc/passwd")).thenReturn(true);

        var result = service.checkForInjection("../../etc/passwd");

        assertFalse(result.isSafe());
        assertTrue(result.getThreats().contains("path_traversal"));
    }

    @Test
    void checkForInjectionDetectsMultipleThreats() {
        String malicious = "'; DROP TABLE users; --";
        when(injectionGuard.detectCommandInjection(malicious)).thenReturn(true);
        when(injectionGuard.detectSqlInjection(malicious)).thenReturn(true);

        var result = service.checkForInjection(malicious);

        assertFalse(result.isSafe());
        assertEquals(2, result.getThreats().size());
        assertTrue(result.getThreats().contains("command_injection"));
        assertTrue(result.getThreats().contains("sql_injection"));
    }

    @Test
    void checkForInjectionSanitizesInputWhenThreatsDetected() {
        when(injectionGuard.detectPromptInjection("bad input")).thenReturn(true);

        var result = service.checkForInjection("bad input");

        assertNotNull(result.getSanitizedInput());
        verify(inputSanitizer).normalizeUnicode(anyString());
    }

    @Test
    void checkForInjectionDoesNotSanitizeWhenSafe() {
        var result = service.checkForInjection("safe input");

        assertEquals("safe input", result.getSanitizedInput());
        verify(inputSanitizer, never()).normalizeUnicode(any());
    }

    @Test
    void checkForInjectionSqlAlwaysCheckedRegardlessOfConfig() {
        // SQL injection is always checked (not behind a config toggle)
        properties.getSecurity().setDetectPromptInjection(false);
        properties.getSecurity().setDetectCommandInjection(false);
        when(injectionGuard.detectSqlInjection("sql attack")).thenReturn(true);

        var result = service.checkForInjection("sql attack");

        assertFalse(result.isSafe());
        verify(injectionGuard).detectSqlInjection("sql attack");
    }

    @Test
    void checkForInjectionPathTraversalAlwaysChecked() {
        properties.getSecurity().setDetectPromptInjection(false);
        properties.getSecurity().setDetectCommandInjection(false);
        when(injectionGuard.detectPathTraversal("../secret")).thenReturn(true);

        var result = service.checkForInjection("../secret");

        assertFalse(result.isSafe());
    }

    // ==================== isAllowed ====================

    @Test
    void isAllowedReturnsTrueWhenAllowlistDisabled() {
        properties.getSecurity().getAllowlist().setEnabled(false);

        assertTrue(service.isAllowed("telegram", "user123"));
    }

    @Test
    void isAllowedReturnsFalseWhenChannelNotConfigured() {
        properties.getSecurity().getAllowlist().setEnabled(true);
        // No channel properties configured

        assertFalse(service.isAllowed("unknown_channel", "user123"));
    }

    @Test
    void isAllowedReturnsTrueWhenNoAllowFromList() {
        properties.getSecurity().getAllowlist().setEnabled(true);
        var channelProps = new BotProperties.ChannelProperties();
        channelProps.setAllowFrom(null);
        properties.getChannels().put("telegram", channelProps);

        assertTrue(service.isAllowed("telegram", "user123"));
    }

    @Test
    void isAllowedReturnsTrueWhenEmptyAllowFromList() {
        properties.getSecurity().getAllowlist().setEnabled(true);
        var channelProps = new BotProperties.ChannelProperties();
        channelProps.setAllowFrom(List.of());
        properties.getChannels().put("telegram", channelProps);

        assertTrue(service.isAllowed("telegram", "user123"));
    }

    @Test
    void isAllowedReturnsTrueWhenUserInList() {
        properties.getSecurity().getAllowlist().setEnabled(true);
        var channelProps = new BotProperties.ChannelProperties();
        channelProps.setAllowFrom(List.of("user123", "user456"));
        properties.getChannels().put("telegram", channelProps);

        assertTrue(service.isAllowed("telegram", "user123"));
    }

    @Test
    void isAllowedReturnsFalseWhenUserNotInList() {
        properties.getSecurity().getAllowlist().setEnabled(true);
        var channelProps = new BotProperties.ChannelProperties();
        channelProps.setAllowFrom(List.of("user123"));
        properties.getChannels().put("telegram", channelProps);

        assertFalse(service.isAllowed("telegram", "user999"));
    }
}
