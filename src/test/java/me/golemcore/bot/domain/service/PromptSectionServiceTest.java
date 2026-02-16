package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.PromptSection;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PromptSectionServiceTest {

    private static final String PROMPTS_DIR = "prompts";
    private static final String IDENTITY_FILE = "IDENTITY.md";
    private static final String RULES_FILE = "RULES.md";
    private static final String VOICE_FILE = "VOICE.md";
    private static final String IDENTITY_NAME = "identity";
    private static final String VAR_BOT_NAME = "BOT_NAME";
    private static final String VAR_DATE = "DATE";
    private static final String BAD_FILE = "BAD.md";

    private StoragePort storagePort;
    private BotProperties properties;
    private RuntimeConfigService runtimeConfigService;
    private SkillTemplateEngine templateEngine;
    private PromptSectionService service;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        properties = new BotProperties();
        runtimeConfigService = mock(RuntimeConfigService.class);
        when(runtimeConfigService.isVoiceEnabled()).thenReturn(false);
        templateEngine = new SkillTemplateEngine();

        // Default: both files exist (skip ensureDefaults writes)
        when(storagePort.exists(eq(PROMPTS_DIR), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        service = new PromptSectionService(storagePort, properties, runtimeConfigService, templateEngine);
    }

    @Test
    void reload_loadsAllMdFiles() {
        String identity = "---\ndescription: Identity\norder: 10\n---\nYou are a bot.";
        String rules = "---\ndescription: Rules\norder: 20\n---\nBe nice.";

        when(storagePort.listObjects(PROMPTS_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of(IDENTITY_FILE, RULES_FILE)));
        when(storagePort.getText(PROMPTS_DIR, IDENTITY_FILE))
                .thenReturn(CompletableFuture.completedFuture(identity));
        when(storagePort.getText(PROMPTS_DIR, RULES_FILE))
                .thenReturn(CompletableFuture.completedFuture(rules));

        service.reload();

        assertEquals(2, service.getEnabledSections().size());
    }

    @Test
    void reload_ignoresNonMdFiles() {
        when(storagePort.listObjects(PROMPTS_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of(IDENTITY_FILE, "notes.txt")));
        when(storagePort.getText(PROMPTS_DIR, IDENTITY_FILE))
                .thenReturn(CompletableFuture.completedFuture("Hello"));

        service.reload();

        assertEquals(1, service.getEnabledSections().size());
        assertEquals(IDENTITY_NAME, service.getEnabledSections().get(0).getName());
    }

    @Test
    void reload_parsesFrontmatter() {
        String content = "---\ndescription: Bot ID\norder: 10\n---\nYou are a bot.";

        when(storagePort.listObjects(PROMPTS_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of(IDENTITY_FILE)));
        when(storagePort.getText(PROMPTS_DIR, IDENTITY_FILE))
                .thenReturn(CompletableFuture.completedFuture(content));

        service.reload();

        PromptSection section = service.getSection(IDENTITY_NAME).orElseThrow();
        assertEquals(10, section.getOrder());
        assertEquals("Bot ID", section.getDescription());
        assertEquals("You are a bot.", section.getContent());
    }

    @Test
    void reload_noFrontmatter() {
        when(storagePort.listObjects(PROMPTS_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("CUSTOM.md")));
        when(storagePort.getText(PROMPTS_DIR, "CUSTOM.md"))
                .thenReturn(CompletableFuture.completedFuture("Just plain markdown."));

        service.reload();

        PromptSection section = service.getSection("custom").orElseThrow();
        assertEquals(100, section.getOrder());
        assertTrue(section.isEnabled());
        assertEquals("Just plain markdown.", section.getContent());
    }

    @Test
    void reload_disabledSection() {
        String content = "---\nenabled: false\norder: 10\n---\nDisabled content.";

        when(storagePort.listObjects(PROMPTS_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("DISABLED.md")));
        when(storagePort.getText(PROMPTS_DIR, "DISABLED.md"))
                .thenReturn(CompletableFuture.completedFuture(content));

        service.reload();

        // Exists in registry but not in enabled list
        assertTrue(service.getSection("disabled").isPresent());
        assertFalse(service.getSection("disabled").get().isEnabled());
        assertTrue(service.getEnabledSections().isEmpty());
    }

    @Test
    void reload_emptyDirectory() {
        when(storagePort.listObjects(PROMPTS_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        service.reload();

        assertTrue(service.getEnabledSections().isEmpty());
    }

    @Test
    void getEnabledSections_sortedByOrder() {
        String s1 = "---\norder: 20\n---\nSecond";
        String s2 = "---\norder: 10\n---\nFirst";
        String s3 = "---\norder: 30\n---\nThird";

        when(storagePort.listObjects(PROMPTS_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("B.md", "A.md", "C.md")));
        when(storagePort.getText(PROMPTS_DIR, "B.md")).thenReturn(CompletableFuture.completedFuture(s1));
        when(storagePort.getText(PROMPTS_DIR, "A.md")).thenReturn(CompletableFuture.completedFuture(s2));
        when(storagePort.getText(PROMPTS_DIR, "C.md")).thenReturn(CompletableFuture.completedFuture(s3));

        service.reload();

        List<PromptSection> sections = service.getEnabledSections();
        assertEquals(3, sections.size());
        assertEquals("a", sections.get(0).getName());
        assertEquals("b", sections.get(1).getName());
        assertEquals("c", sections.get(2).getName());
    }

    @Test
    void getSection_byName() {
        String content = "---\norder: 10\n---\nIdentity content";

        when(storagePort.listObjects(PROMPTS_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of(IDENTITY_FILE)));
        when(storagePort.getText(PROMPTS_DIR, IDENTITY_FILE))
                .thenReturn(CompletableFuture.completedFuture(content));

        service.reload();

        // Lowercase lookup
        Optional<PromptSection> section = service.getSection(IDENTITY_NAME);
        assertTrue(section.isPresent());
        assertEquals("Identity content", section.get().getContent());

        // Non-existent
        assertFalse(service.getSection("nonexistent").isPresent());
    }

    @Test
    void renderSection_substitution() {
        PromptSection section = PromptSection.builder()
                .name("test")
                .content("Hello {{BOT_NAME}}, today is {{DATE}}")
                .build();

        Map<String, String> vars = Map.of(VAR_BOT_NAME, "TestBot", VAR_DATE, "2026-02-05");

        String rendered = service.renderSection(section, vars);

        assertEquals("Hello TestBot, today is 2026-02-05", rendered);
    }

    @Test
    void renderSection_unresolvedLeft() {
        PromptSection section = PromptSection.builder()
                .name("test")
                .content("Hello {{UNKNOWN}}")
                .build();

        String rendered = service.renderSection(section, Map.of());

        assertEquals("Hello {{UNKNOWN}}", rendered);
    }

    @Test
    void renderSection_nullSection() {
        assertNull(service.renderSection(null, Map.of()));
    }

    @Test
    void buildTemplateVariables_withPrefs() {
        UserPreferences prefs = UserPreferences.builder()
                .language("ru")
                .timezone("Europe/Moscow")
                .build();

        Map<String, String> vars = service.buildTemplateVariables(prefs);

        assertEquals("AI Assistant", vars.get(VAR_BOT_NAME));
        assertEquals("ru", vars.get("USER_LANG"));
        assertEquals("Europe/Moscow", vars.get("USER_TIMEZONE"));
        assertNotNull(vars.get(VAR_DATE));
        assertNotNull(vars.get("TIME"));
    }

    @Test
    void buildTemplateVariables_nullPrefs() {
        Map<String, String> vars = service.buildTemplateVariables(null);

        assertEquals("AI Assistant", vars.get(VAR_BOT_NAME));
        assertEquals("en", vars.get("USER_LANG"));
        assertEquals("UTC", vars.get("USER_TIMEZONE"));
        assertNotNull(vars.get(VAR_DATE));
        assertNotNull(vars.get("TIME"));
    }

    @Test
    void buildTemplateVariables_customVars() {
        Map<String, String> customVars = new HashMap<>();
        customVars.put("COMPANY", "Acme");
        customVars.put("VERSION", "1.0");
        properties.getPrompts().setCustomVars(customVars);

        Map<String, String> vars = service.buildTemplateVariables(null);

        assertEquals("Acme", vars.get("COMPANY"));
        assertEquals("1.0", vars.get("VERSION"));
    }

    @Test
    void buildTemplateVariables_customBotName() {
        properties.getPrompts().setBotName("MyBot");

        Map<String, String> vars = service.buildTemplateVariables(null);

        assertEquals("MyBot", vars.get(VAR_BOT_NAME));
    }

    @Test
    void ensureDefaults_createsWhenMissing() {
        when(storagePort.exists(PROMPTS_DIR, IDENTITY_FILE))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(storagePort.exists(PROMPTS_DIR, RULES_FILE))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(storagePort.putText(eq(PROMPTS_DIR), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        service.ensureDefaults();

        verify(storagePort).putText(eq(PROMPTS_DIR), eq(IDENTITY_FILE), contains(VAR_BOT_NAME));
        verify(storagePort).putText(eq(PROMPTS_DIR), eq(RULES_FILE), contains("Rules"));
    }

    @Test
    void ensureDefaults_skipsExisting() {
        when(storagePort.exists(PROMPTS_DIR, IDENTITY_FILE))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(storagePort.exists(PROMPTS_DIR, RULES_FILE))
                .thenReturn(CompletableFuture.completedFuture(true));

        service.ensureDefaults();

        verify(storagePort, never()).putText(eq(PROMPTS_DIR), eq(IDENTITY_FILE), anyString());
        verify(storagePort, never()).putText(eq(PROMPTS_DIR), eq(RULES_FILE), anyString());
    }

    @Test
    void isEnabled_reflectsConfig() {
        assertTrue(service.isEnabled());

        properties.getPrompts().setEnabled(false);
        assertFalse(service.isEnabled());
    }

    // ===== Voice section filtering =====

    @Test
    void getEnabledSections_filtersVoiceWhenDisabled() {
        String voiceSection = "---\ndescription: Voice\norder: 15\n---\nVoice instructions.";
        String identitySection = "---\ndescription: Identity\norder: 10\n---\nYou are a bot.";

        when(storagePort.listObjects(PROMPTS_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of(IDENTITY_FILE, VOICE_FILE)));
        when(storagePort.getText(PROMPTS_DIR, IDENTITY_FILE))
                .thenReturn(CompletableFuture.completedFuture(identitySection));
        when(storagePort.getText(PROMPTS_DIR, VOICE_FILE))
                .thenReturn(CompletableFuture.completedFuture(voiceSection));

        when(runtimeConfigService.isVoiceEnabled()).thenReturn(false);
        service.reload();

        List<PromptSection> sections = service.getEnabledSections();
        assertEquals(1, sections.size());
        assertEquals(IDENTITY_NAME, sections.get(0).getName());
    }

    @Test
    void getEnabledSections_includesVoiceWhenEnabled() {
        String voiceSection = "---\ndescription: Voice\norder: 15\n---\nVoice instructions.";
        String identitySection = "---\ndescription: Identity\norder: 10\n---\nYou are a bot.";

        when(storagePort.listObjects(PROMPTS_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of(IDENTITY_FILE, VOICE_FILE)));
        when(storagePort.getText(PROMPTS_DIR, IDENTITY_FILE))
                .thenReturn(CompletableFuture.completedFuture(identitySection));
        when(storagePort.getText(PROMPTS_DIR, VOICE_FILE))
                .thenReturn(CompletableFuture.completedFuture(voiceSection));

        when(runtimeConfigService.isVoiceEnabled()).thenReturn(true);
        service.reload();

        List<PromptSection> sections = service.getEnabledSections();
        assertEquals(2, sections.size());
        assertEquals(IDENTITY_NAME, sections.get(0).getName());
        assertEquals("voice", sections.get(1).getName());
    }

    @Test
    void ensureDefaults_createsVoiceMdWhenVoiceEnabled() {
        when(runtimeConfigService.isVoiceEnabled()).thenReturn(true);
        when(storagePort.exists(PROMPTS_DIR, IDENTITY_FILE))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(storagePort.exists(PROMPTS_DIR, RULES_FILE))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(storagePort.exists(PROMPTS_DIR, VOICE_FILE))
                .thenReturn(CompletableFuture.completedFuture(false));
        when(storagePort.putText(eq(PROMPTS_DIR), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        service.ensureDefaults();

        verify(storagePort).putText(eq(PROMPTS_DIR), eq(VOICE_FILE), contains("Voice"));
    }

    @Test
    void ensureDefaults_skipsVoiceMdWhenVoiceDisabled() {
        when(runtimeConfigService.isVoiceEnabled()).thenReturn(false);
        when(storagePort.exists(PROMPTS_DIR, IDENTITY_FILE))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(storagePort.exists(PROMPTS_DIR, RULES_FILE))
                .thenReturn(CompletableFuture.completedFuture(true));

        service.ensureDefaults();

        verify(storagePort, never()).exists(eq(PROMPTS_DIR), eq(VOICE_FILE));
        verify(storagePort, never()).putText(eq(PROMPTS_DIR), eq(VOICE_FILE), anyString());
    }

    @Test
    void buildTemplateVariables_invalidTimezone() {
        UserPreferences prefs = UserPreferences.builder()
                .timezone("Invalid/Zone")
                .build();

        Map<String, String> vars = service.buildTemplateVariables(prefs);

        // Should fall back to UTC
        assertEquals("UTC", vars.get("USER_TIMEZONE"));
        assertNotNull(vars.get(VAR_DATE));
    }

    // ===== Edge cases for loadSection null guard =====

    @Test
    void reload_skipsNullContentFromStorage() {
        when(storagePort.listObjects(PROMPTS_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of(BAD_FILE)));
        when(storagePort.getText(PROMPTS_DIR, BAD_FILE))
                .thenReturn(CompletableFuture.completedFuture(null));

        service.reload();

        assertTrue(service.getEnabledSections().isEmpty());
    }

    @Test
    void reload_skipsBlankContentFromStorage() {
        when(storagePort.listObjects(PROMPTS_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("EMPTY.md")));
        when(storagePort.getText(PROMPTS_DIR, "EMPTY.md"))
                .thenReturn(CompletableFuture.completedFuture("   "));

        service.reload();

        assertTrue(service.getEnabledSections().isEmpty());
    }

    @Test
    void reload_handlesGetTextException() {
        when(storagePort.listObjects(PROMPTS_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("CRASH.md")));
        when(storagePort.getText(PROMPTS_DIR, "CRASH.md"))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("storage read error")));

        assertDoesNotThrow(() -> service.reload());
        assertTrue(service.getEnabledSections().isEmpty());
    }

    @Test
    void reload_malformedFrontmatterStillCreatesSection() {
        String content = "---\norder: [bad yaml!!!\n---\nContent body after bad yaml";

        when(storagePort.listObjects(PROMPTS_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("MALFORMED.md")));
        when(storagePort.getText(PROMPTS_DIR, "MALFORMED.md"))
                .thenReturn(CompletableFuture.completedFuture(content));

        service.reload();

        // Should still load with default order (100) from the body portion
        Optional<PromptSection> section = service.getSection("malformed");
        assertTrue(section.isPresent());
        assertEquals(100, section.get().getOrder());
        assertEquals("Content body after bad yaml", section.get().getContent());
    }

    @Test
    void reload_continuesAfterSingleFileFailure() {
        String goodContent = "---\norder: 10\n---\nGood content";

        when(storagePort.listObjects(PROMPTS_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of(BAD_FILE, "GOOD.md")));
        when(storagePort.getText(PROMPTS_DIR, BAD_FILE))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("read error")));
        when(storagePort.getText(PROMPTS_DIR, "GOOD.md"))
                .thenReturn(CompletableFuture.completedFuture(goodContent));

        service.reload();

        assertEquals(1, service.getEnabledSections().size());
        assertEquals("good", service.getEnabledSections().get(0).getName());
    }

    @Test
    void renderSection_sectionWithNullContent() {
        PromptSection section = PromptSection.builder()
                .name("test")
                .content(null)
                .build();

        assertNull(service.renderSection(section, Map.of()));
    }

    @Test
    void reload_extractsNameFromSubdirectoryPath() {
        String content = "---\norder: 10\n---\nContent";

        when(storagePort.listObjects(PROMPTS_DIR, ""))
                .thenReturn(CompletableFuture.completedFuture(List.of("subdir/CUSTOM.md")));
        when(storagePort.getText(PROMPTS_DIR, "subdir/CUSTOM.md"))
                .thenReturn(CompletableFuture.completedFuture(content));

        service.reload();

        assertTrue(service.getSection("custom").isPresent());
    }

    @Test
    void reload_handlesListObjectsFailure() {
        when(storagePort.listObjects(PROMPTS_DIR, ""))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("list failed")));

        assertDoesNotThrow(() -> service.reload());
        assertTrue(service.getEnabledSections().isEmpty());
    }
}
