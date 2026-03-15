package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.PromptSectionDto;
import me.golemcore.bot.adapter.inbound.web.dto.PromptCreateRequest;
import me.golemcore.bot.domain.model.PromptSection;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.PromptSectionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import reactor.test.StepVerifier;

class PromptsControllerTest {

    private PromptSectionService promptSectionService;
    private UserPreferencesService preferencesService;
    private StoragePort storagePort;
    private PromptsController controller;

    @BeforeEach
    void setUp() {
        promptSectionService = mock(PromptSectionService.class);
        preferencesService = mock(UserPreferencesService.class);
        storagePort = mock(StoragePort.class);
        controller = new PromptsController(promptSectionService, preferencesService, storagePort);

        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(storagePort.deleteObject(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void shouldListSections() {
        PromptSection section = PromptSection.builder()
                .name("identity")
                .description("Bot identity")
                .order(10)
                .enabled(true)
                .content("You are a helpful bot")
                .build();
        PromptSection disabledSection = PromptSection.builder()
                .name("custom")
                .description("Custom section")
                .order(30)
                .enabled(false)
                .content("Disabled section")
                .build();
        when(promptSectionService.getAllSections()).thenReturn(List.of(section, disabledSection));
        when(promptSectionService.isProtectedSection("identity")).thenReturn(true);
        when(promptSectionService.isProtectedSection("custom")).thenReturn(false);

        StepVerifier.create(controller.listSections())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    List<PromptSectionDto> body = response.getBody();
                    assertNotNull(body);
                    assertEquals(2, body.size());
                    assertEquals("identity", body.get(0).getName());
                    assertEquals("custom", body.get(1).getName());
                    assertEquals(false, body.get(0).isDeletable());
                    assertEquals(true, body.get(1).isDeletable());
                })
                .verifyComplete();
    }

    @Test
    void shouldGetSection() {
        PromptSection section = PromptSection.builder()
                .name("identity")
                .content("You are a helpful bot")
                .order(10)
                .enabled(true)
                .build();
        when(promptSectionService.getSection("identity")).thenReturn(Optional.of(section));
        when(promptSectionService.isProtectedSection("identity")).thenReturn(true);

        StepVerifier.create(controller.getSection("identity"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("identity", response.getBody().getName());
                    assertEquals(false, response.getBody().isDeletable());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturn404ForMissingSection() {
        when(promptSectionService.getSection("unknown")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getSection("unknown"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("Prompt section 'unknown' not found", ex.getReason());
    }

    @Test
    void shouldPreviewSection() {
        PromptSection section = PromptSection.builder()
                .name("identity")
                .content("Hello {{lang}}")
                .build();
        when(promptSectionService.getSection("identity")).thenReturn(Optional.of(section));
        when(preferencesService.getPreferences()).thenReturn(new UserPreferences());
        when(promptSectionService.buildTemplateVariables(any())).thenReturn(Map.of("lang", "en"));
        when(promptSectionService.renderSection(any(), any())).thenReturn("Hello en");

        StepVerifier.create(controller.previewSection("identity", null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("Hello en", response.getBody().get("rendered"));
                })
                .verifyComplete();
    }

    @Test
    void shouldReturn404ForMissingPreview() {
        when(promptSectionService.getSection("unknown")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.previewSection("unknown", null));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("Prompt section 'unknown' not found", ex.getReason());
    }

    @Test
    void shouldPreviewUnsavedDraftContent() {
        PromptSection section = PromptSection.builder()
                .name("identity")
                .content("Saved {{lang}}")
                .build();
        PromptCreateRequest request = new PromptCreateRequest("identity", "draft", 10, true, "Draft {{lang}}");

        when(promptSectionService.getSection("identity")).thenReturn(Optional.of(section));
        when(preferencesService.getPreferences()).thenReturn(new UserPreferences());
        when(promptSectionService.buildTemplateVariables(any())).thenReturn(Map.of("lang", "en"));
        when(promptSectionService.renderSection(any(), any())).thenReturn("Draft en");

        StepVerifier.create(controller.previewSection("identity", request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("Draft en", response.getBody().get("rendered"));
                })
                .verifyComplete();
    }

    @Test
    void shouldCreateSection() {
        PromptSection section = PromptSection.builder()
                .name("custom")
                .description("Custom section")
                .order(40)
                .enabled(true)
                .content("Custom body")
                .build();
        PromptCreateRequest request = new PromptCreateRequest("custom", "Custom section", 40, true, "Custom body");

        when(promptSectionService.getSection("custom")).thenReturn(Optional.empty(), Optional.of(section));
        when(promptSectionService.isProtectedSection("custom")).thenReturn(false);

        StepVerifier.create(controller.createSection(request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.CREATED, response.getStatusCode());
                    assertEquals("custom", response.getBody().getName());
                    assertEquals(true, response.getBody().isDeletable());
                })
                .verifyComplete();

        verify(storagePort).putText(eq("prompts"), eq("CUSTOM.md"), anyString());
    }

    @Test
    void shouldDeleteSection() {
        PromptSection section = PromptSection.builder()
                .name("custom")
                .enabled(true)
                .build();

        when(promptSectionService.getSection("custom")).thenReturn(Optional.of(section));
        when(promptSectionService.isProtectedSection("custom")).thenReturn(false);

        StepVerifier.create(controller.deleteSection("custom"))
                .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode()))
                .verifyComplete();

        verify(storagePort).deleteObject("prompts", "CUSTOM.md");
    }

    @Test
    void shouldRejectProtectedSectionDeletion() {
        PromptSection section = PromptSection.builder()
                .name("identity")
                .enabled(true)
                .build();

        when(promptSectionService.getSection("identity")).thenReturn(Optional.of(section));
        when(promptSectionService.isProtectedSection("identity")).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteSection("identity"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Prompt section 'identity' cannot be deleted", ex.getReason());
    }

    @Test
    void shouldReload() {
        StepVerifier.create(controller.reload())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("reloaded", response.getBody().get("status"));
                })
                .verifyComplete();
    }

}
