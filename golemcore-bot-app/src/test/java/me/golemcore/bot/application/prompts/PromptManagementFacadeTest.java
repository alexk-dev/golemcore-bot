package me.golemcore.bot.application.prompts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.golemcore.bot.domain.model.PromptSection;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.prompt.PromptSectionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PromptManagementFacadeTest {

    private PromptSectionService promptSectionService;
    private UserPreferencesService preferencesService;
    private StoragePort storagePort;
    private PromptManagementFacade facade;

    @BeforeEach
    void setUp() {
        promptSectionService = mock(PromptSectionService.class);
        preferencesService = mock(UserPreferencesService.class);
        storagePort = mock(StoragePort.class);
        facade = new PromptManagementFacade(promptSectionService, preferencesService, storagePort);

        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.deleteObject(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void shouldCreateSectionAndReload() {
        PromptSectionDraft request = new PromptSectionDraft("custom", "Custom section", 40, true, "Custom body");
        PromptSection created = PromptSection.builder()
                .name("custom")
                .description("Custom section")
                .order(40)
                .enabled(true)
                .content("Custom body")
                .build();
        when(promptSectionService.getSection("custom")).thenReturn(Optional.empty(), Optional.of(created));

        PromptSection result = facade.createSection(request);

        assertEquals("custom", result.getName());
        verify(storagePort).putText(eq("prompts"), eq("CUSTOM.md"), anyString());
        verify(promptSectionService).reload();
    }

    @Test
    void shouldOmitBlankDescriptionFromFileContent() {
        // A bare `description:` line round-trips through YAML as null, which breaks the
        // API string contract.
        // Skip the line entirely for null or blank descriptions so the file never
        // serializes to that shape.
        PromptSectionDraft request = new PromptSectionDraft("custom", "   ", 40, true, "Custom body");
        when(promptSectionService.getSection("custom")).thenReturn(
                Optional.empty(),
                Optional.of(PromptSection.builder().name("custom").build()));

        facade.createSection(request);

        ArgumentCaptor<String> fileContent = ArgumentCaptor.forClass(String.class);
        verify(storagePort).putText(eq("prompts"), eq("CUSTOM.md"), fileContent.capture());
        assertFalse(fileContent.getValue().contains("description:"),
                "blank description must not be written to frontmatter");
    }

    @Test
    void shouldRejectNullCreateRequest() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> facade.createSection(null));

        assertEquals("request body is required", exception.getMessage());
    }

    @Test
    void shouldRejectDuplicateCreate() {
        PromptSectionDraft request = new PromptSectionDraft("custom", "Custom section", 40, true, "Custom body");
        when(promptSectionService.getSection("custom"))
                .thenReturn(Optional.of(PromptSection.builder().name("custom").build()));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> facade.createSection(request));
        assertEquals("Prompt section 'custom' already exists", exception.getMessage());
    }

    @Test
    void shouldPreviewDraftContent() {
        PromptSection section = PromptSection.builder().name("identity").content("Saved {{lang}}").build();
        PromptSectionDraft request = new PromptSectionDraft("identity", "draft", 10, true, "Draft {{lang}}");
        when(promptSectionService.getSection("identity")).thenReturn(Optional.of(section));
        when(preferencesService.getPreferences()).thenReturn(new UserPreferences());
        when(promptSectionService.buildTemplateVariables(any())).thenReturn(Map.of("lang", "en"));
        when(promptSectionService.renderSection(any(), any())).thenReturn("Draft en");

        String result = facade.previewSection("identity", request);

        assertEquals("Draft en", result);
    }

    @Test
    void shouldPreviewSavedContentWhenDraftContentIsMissing() {
        PromptSection section = PromptSection.builder().name("identity").content("Saved {{lang}}").build();
        when(promptSectionService.getSection("identity")).thenReturn(Optional.of(section));
        when(preferencesService.getPreferences()).thenReturn(new UserPreferences());
        when(promptSectionService.buildTemplateVariables(any())).thenReturn(Map.of("lang", "en"));
        when(promptSectionService.renderSection(section, Map.of("lang", "en"))).thenReturn("Saved en");

        String result = facade.previewSection("identity", new PromptSectionDraft("identity", "draft", 10, true, null));

        assertEquals("Saved en", result);
    }

    @Test
    void shouldUpdateSectionAndReload() {
        PromptSectionDraft request = new PromptSectionDraft("ignored", "Updated section", 50, false, "Updated body");
        PromptSection updated = PromptSection.builder()
                .name("custom")
                .description("Updated section")
                .order(50)
                .enabled(false)
                .content("Updated body")
                .build();
        when(promptSectionService.getSection("custom")).thenReturn(
                Optional.of(PromptSection.builder().name("custom").build()),
                Optional.of(updated));

        PromptSection result = facade.updateSection("custom", request);

        assertEquals("custom", result.getName());
        verify(storagePort).putText(eq("prompts"), eq("CUSTOM.md"), anyString());
        verify(promptSectionService).reload();
    }

    @Test
    void shouldDeleteSectionAndReload() {
        when(promptSectionService.getSection("custom"))
                .thenReturn(Optional.of(PromptSection.builder().name("custom").build()));
        when(promptSectionService.isProtectedSection("custom")).thenReturn(false);

        facade.deleteSection("custom");

        verify(storagePort).deleteObject("prompts", "CUSTOM.md");
        verify(promptSectionService).reload();
    }

    @Test
    void shouldRejectDeletingProtectedSection() {
        when(promptSectionService.getSection("identity"))
                .thenReturn(Optional.of(PromptSection.builder().name("identity").build()));
        when(promptSectionService.isProtectedSection("identity")).thenReturn(true);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> facade.deleteSection("identity"));
        assertEquals("Prompt section 'identity' cannot be deleted", exception.getMessage());
    }

    @Test
    void shouldRejectDeletingMissingSection() {
        when(promptSectionService.getSection("missing")).thenReturn(Optional.empty());

        NoSuchElementException exception = assertThrows(NoSuchElementException.class,
                () -> facade.deleteSection("missing"));

        assertEquals("Prompt section 'missing' not found", exception.getMessage());
    }

    @Test
    void shouldRejectMissingSectionOnUpdate() {
        PromptSectionDraft request = new PromptSectionDraft("custom", "Custom section", 40, true, "Custom body");
        when(promptSectionService.getSection("custom")).thenReturn(Optional.empty());

        NoSuchElementException exception = assertThrows(NoSuchElementException.class,
                () -> facade.updateSection("custom", request));
        assertEquals("Prompt section 'custom' not found", exception.getMessage());
    }

    @Test
    void shouldListSectionsFromPromptService() {
        when(promptSectionService.getAllSections())
                .thenReturn(List.of(PromptSection.builder().name("identity").build()));

        assertEquals(1, facade.listSections().size());
        assertEquals("identity", facade.listSections().getFirst().getName());
    }

    @Test
    void shouldReportProtectedSectionsThroughPromptService() {
        when(promptSectionService.isProtectedSection("identity")).thenReturn(true);

        assertTrue(facade.isProtectedSection("identity"));
    }
}
