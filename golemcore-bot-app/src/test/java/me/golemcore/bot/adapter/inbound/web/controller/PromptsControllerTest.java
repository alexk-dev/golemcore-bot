package me.golemcore.bot.adapter.inbound.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import me.golemcore.bot.adapter.inbound.web.dto.PromptCreateRequest;
import me.golemcore.bot.adapter.inbound.web.dto.PromptSectionDto;
import me.golemcore.bot.application.prompts.PromptManagementFacade;
import me.golemcore.bot.application.prompts.PromptSectionDraft;
import me.golemcore.bot.domain.model.PromptSection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.test.StepVerifier;

class PromptsControllerTest {

    private PromptManagementFacade promptManagementFacade;
    private PromptsController controller;

    @BeforeEach
    void setUp() {
        promptManagementFacade = mock(PromptManagementFacade.class);
        controller = new PromptsController(promptManagementFacade);
    }

    @Test
    void shouldListSections() {
        PromptSection identity = PromptSection.builder()
                .name("identity")
                .description("Bot identity")
                .order(10)
                .enabled(true)
                .content("You are a helpful bot")
                .build();
        PromptSection custom = PromptSection.builder()
                .name("custom")
                .description("Custom section")
                .order(30)
                .enabled(false)
                .content("Disabled section")
                .build();
        when(promptManagementFacade.listSections()).thenReturn(List.of(identity, custom));
        when(promptManagementFacade.isProtectedSection("identity")).thenReturn(true);
        when(promptManagementFacade.isProtectedSection("custom")).thenReturn(false);

        StepVerifier.create(controller.listSections())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    List<PromptSectionDto> body = response.getBody();
                    assertNotNull(body);
                    assertEquals(2, body.size());
                    assertEquals("identity", body.get(0).getName());
                    assertEquals(false, body.get(0).isDeletable());
                    assertEquals("custom", body.get(1).getName());
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
        when(promptManagementFacade.getSection("identity")).thenReturn(section);
        when(promptManagementFacade.isProtectedSection("identity")).thenReturn(true);

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
        when(promptManagementFacade.getSection("unknown"))
                .thenThrow(new NoSuchElementException("Prompt section 'unknown' not found"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getSection("unknown"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("Prompt section 'unknown' not found", ex.getReason());
    }

    @Test
    void shouldPreviewSection() {
        when(promptManagementFacade.previewSection("identity", null)).thenReturn("Hello en");

        StepVerifier.create(controller.previewSection("identity", null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("Hello en", response.getBody().get("rendered"));
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
        when(promptManagementFacade.createSection(new PromptSectionDraft("custom", "Custom section", 40, true,
                "Custom body"))).thenReturn(section);
        when(promptManagementFacade.isProtectedSection("custom")).thenReturn(false);

        StepVerifier.create(controller.createSection(request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.CREATED, response.getStatusCode());
                    assertEquals("custom", response.getBody().getName());
                    assertEquals(true, response.getBody().isDeletable());
                })
                .verifyComplete();
    }

    @Test
    void shouldMapCreateValidationErrors() {
        PromptCreateRequest request = new PromptCreateRequest("Custom Prompt", "Custom section", 40, true,
                "Custom body");
        when(promptManagementFacade.createSection(new PromptSectionDraft("Custom Prompt", "Custom section", 40, true,
                "Custom body")))
                .thenThrow(new IllegalArgumentException("Prompt name is required and must match [a-z0-9][a-z0-9-]*"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.createSection(request));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Prompt name is required and must match [a-z0-9][a-z0-9-]*", ex.getReason());
    }

    @Test
    void shouldMapCreateConflicts() {
        PromptCreateRequest request = new PromptCreateRequest("custom", "Custom section", 40, true, "Custom body");
        when(promptManagementFacade.createSection(new PromptSectionDraft("custom", "Custom section", 40, true,
                "Custom body")))
                .thenThrow(new IllegalStateException("Prompt section 'custom' already exists"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.createSection(request));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Prompt section 'custom' already exists", ex.getReason());
    }

    @Test
    void shouldUpdateSection() {
        PromptSection updated = PromptSection.builder()
                .name("custom")
                .description("Updated section")
                .order(50)
                .enabled(false)
                .content("Updated body")
                .build();
        PromptCreateRequest request = new PromptCreateRequest("ignored-body-name", "Updated section", 50, false,
                "Updated body");
        when(promptManagementFacade.updateSection("custom",
                new PromptSectionDraft("ignored-body-name", "Updated section", 50, false, "Updated body")))
                .thenReturn(updated);
        when(promptManagementFacade.isProtectedSection("custom")).thenReturn(false);

        StepVerifier.create(controller.updateSection("custom", request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("custom", response.getBody().getName());
                    assertEquals("Updated section", response.getBody().getDescription());
                    assertEquals(false, response.getBody().isEnabled());
                })
                .verifyComplete();
    }

    @Test
    void shouldMapUpdateValidationErrors() {
        PromptCreateRequest request = new PromptCreateRequest("ignored-body-name", "Updated section", 50, false,
                "Updated body");
        when(promptManagementFacade.updateSection("custom",
                new PromptSectionDraft("ignored-body-name", "Updated section", 50, false, "Updated body")))
                .thenThrow(new IllegalArgumentException("Prompt name is required and must match [a-z0-9][a-z0-9-]*"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSection("custom", request));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Prompt name is required and must match [a-z0-9][a-z0-9-]*", ex.getReason());
    }

    @Test
    void shouldMapUpdateMissingSection() {
        PromptCreateRequest request = new PromptCreateRequest("ignored-body-name", "Updated section", 50, false,
                "Updated body");
        when(promptManagementFacade.updateSection("custom",
                new PromptSectionDraft("ignored-body-name", "Updated section", 50, false, "Updated body")))
                .thenThrow(new NoSuchElementException("Prompt section 'custom' not found"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.updateSection("custom", request));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("Prompt section 'custom' not found", ex.getReason());
    }

    @Test
    void shouldMapPreviewMissingSection() {
        when(promptManagementFacade.previewSection("missing", null))
                .thenThrow(new NoSuchElementException("Prompt section 'missing' not found"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.previewSection("missing", null));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("Prompt section 'missing' not found", ex.getReason());
    }

    @Test
    void shouldDeleteSection() {
        StepVerifier.create(controller.deleteSection("custom"))
                .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode()))
                .verifyComplete();

        verify(promptManagementFacade).deleteSection("custom");
    }

    @Test
    void shouldMapDeleteConflicts() {
        doThrow(new IllegalStateException("Prompt section 'identity' cannot be deleted"))
                .when(promptManagementFacade).deleteSection("identity");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteSection("identity"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("Prompt section 'identity' cannot be deleted", ex.getReason());
    }

    @Test
    void shouldMapDeleteMissingSection() {
        doThrow(new NoSuchElementException("Prompt section 'missing' not found"))
                .when(promptManagementFacade).deleteSection("missing");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteSection("missing"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("Prompt section 'missing' not found", ex.getReason());
    }

    @Test
    void shouldReload() {
        StepVerifier.create(controller.reload())
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(Map.of("status", "reloaded"), response.getBody());
                })
                .verifyComplete();

        verify(promptManagementFacade).reload();
    }
}
