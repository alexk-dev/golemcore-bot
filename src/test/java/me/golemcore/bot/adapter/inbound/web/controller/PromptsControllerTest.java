package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.PromptSectionDto;
import me.golemcore.bot.domain.model.PromptSection;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.domain.service.PromptSectionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
        when(promptSectionService.getEnabledSections()).thenReturn(List.of(section));

        StepVerifier.create(controller.listSections())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    List<PromptSectionDto> body = response.getBody();
                    assertNotNull(body);
                    assertEquals(1, body.size());
                    assertEquals("identity", body.get(0).getName());
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

        StepVerifier.create(controller.getSection("identity"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("identity", response.getBody().getName());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturn404ForMissingSection() {
        when(promptSectionService.getSection("unknown")).thenReturn(Optional.empty());

        StepVerifier.create(controller.getSection("unknown"))
                .assertNext(response -> assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode()))
                .verifyComplete();
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

        StepVerifier.create(controller.previewSection("identity"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("Hello en", response.getBody().get("rendered"));
                })
                .verifyComplete();
    }

    @Test
    void shouldReturn404ForMissingPreview() {
        when(promptSectionService.getSection("unknown")).thenReturn(Optional.empty());

        StepVerifier.create(controller.previewSection("unknown"))
                .assertNext(response -> assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode()))
                .verifyComplete();
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
