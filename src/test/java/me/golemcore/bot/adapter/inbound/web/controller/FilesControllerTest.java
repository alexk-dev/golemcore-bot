package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.FileCreateRequest;
import me.golemcore.bot.adapter.inbound.web.dto.FileRenameRequest;
import me.golemcore.bot.adapter.inbound.web.dto.FileSaveRequest;
import me.golemcore.bot.domain.model.DashboardFileContent;
import me.golemcore.bot.domain.model.DashboardFileNode;
import me.golemcore.bot.domain.service.DashboardFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FilesControllerTest {

    private DashboardFileService dashboardFileService;
    private FilesController filesController;

    @BeforeEach
    void setUp() {
        dashboardFileService = mock(DashboardFileService.class);
        filesController = new FilesController(dashboardFileService);
    }

    @Test
    void shouldReturnFileTreeWhenPathIsValid() {
        DashboardFileNode node = DashboardFileNode.builder()
                .path("src/App.tsx")
                .name("App.tsx")
                .type("file")
                .size(128L)
                .children(List.of())
                .build();

        when(dashboardFileService.getTree("src")).thenReturn(List.of(node));

        StepVerifier.create(filesController.getTree("src"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals(1, response.getBody().size());
                    assertEquals("src/App.tsx", response.getBody().get(0).getPath());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnBadRequestForInvalidTreePath() {
        when(dashboardFileService.getTree("../etc")).thenThrow(new IllegalArgumentException("Invalid path"));

        StepVerifier.create(filesController.getTree("../etc"))
                .assertNext(response -> assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void shouldReturnFileContentWhenPathIsValid() {
        DashboardFileContent content = DashboardFileContent.builder()
                .path("src/App.tsx")
                .content("export default function App() {}")
                .size(31L)
                .updatedAt("2026-02-23T00:00:00Z")
                .build();

        when(dashboardFileService.getContent("src/App.tsx")).thenReturn(content);

        StepVerifier.create(filesController.getContent("src/App.tsx"))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("src/App.tsx", response.getBody().getPath());
                    assertEquals(31L, response.getBody().getSize());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnBadRequestForInvalidContentPath() {
        when(dashboardFileService.getContent("../etc/passwd")).thenThrow(new IllegalArgumentException("Invalid path"));

        StepVerifier.create(filesController.getContent("../etc/passwd"))
                .assertNext(response -> assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void shouldCreateFileContentWhenRequestIsValid() {
        FileCreateRequest request = FileCreateRequest.builder()
                .path("src/NewFile.ts")
                .content("console.log('hello')")
                .build();

        DashboardFileContent created = DashboardFileContent.builder()
                .path("src/NewFile.ts")
                .content("console.log('hello')")
                .size(20L)
                .updatedAt("2026-02-23T00:00:00Z")
                .build();

        when(dashboardFileService.createContent("src/NewFile.ts", "console.log('hello')")).thenReturn(created);

        StepVerifier.create(filesController.createContent(request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("src/NewFile.ts", response.getBody().getPath());
                    assertEquals("console.log('hello')", response.getBody().getContent());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnBadRequestWhenCreatePathIsInvalid() {
        FileCreateRequest request = FileCreateRequest.builder()
                .path("../etc/passwd")
                .content("x")
                .build();

        when(dashboardFileService.createContent("../etc/passwd", "x"))
                .thenThrow(new IllegalArgumentException("Invalid path"));

        StepVerifier.create(filesController.createContent(request))
                .assertNext(response -> assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void shouldSaveFileContentWhenRequestIsValid() {
        FileSaveRequest request = FileSaveRequest.builder()
                .path("src/App.tsx")
                .content("updated")
                .build();

        DashboardFileContent saved = DashboardFileContent.builder()
                .path("src/App.tsx")
                .content("updated")
                .size(7L)
                .updatedAt("2026-02-23T00:00:00Z")
                .build();

        when(dashboardFileService.saveContent("src/App.tsx", "updated")).thenReturn(saved);

        StepVerifier.create(filesController.saveContent(request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("src/App.tsx", response.getBody().getPath());
                    assertEquals("updated", response.getBody().getContent());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnBadRequestWhenSavePathIsInvalid() {
        FileSaveRequest request = FileSaveRequest.builder()
                .path("../etc/passwd")
                .content("x")
                .build();

        when(dashboardFileService.saveContent("../etc/passwd", "x"))
                .thenThrow(new IllegalArgumentException("Invalid path"));

        StepVerifier.create(filesController.saveContent(request))
                .assertNext(response -> assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void shouldRenamePathWhenRequestIsValid() {
        FileRenameRequest request = FileRenameRequest.builder()
                .sourcePath("src/Old.ts")
                .targetPath("src/New.ts")
                .build();

        doNothing().when(dashboardFileService).renamePath("src/Old.ts", "src/New.ts");

        StepVerifier.create(filesController.renamePath(request))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertNotNull(response.getBody());
                    assertEquals("src/Old.ts", response.getBody().getSourcePath());
                    assertEquals("src/New.ts", response.getBody().getTargetPath());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnBadRequestWhenRenamePathIsInvalid() {
        FileRenameRequest request = FileRenameRequest.builder()
                .sourcePath("../etc/passwd")
                .targetPath("x")
                .build();

        doThrow(new IllegalArgumentException("Invalid path"))
                .when(dashboardFileService)
                .renamePath("../etc/passwd", "x");

        StepVerifier.create(filesController.renamePath(request))
                .assertNext(response -> assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void shouldDeletePathWhenPathIsValid() {
        doNothing().when(dashboardFileService).deletePath("src/App.tsx");

        StepVerifier.create(filesController.deletePath("src/App.tsx"))
                .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void shouldReturnBadRequestWhenDeletePathIsInvalid() {
        doThrow(new IllegalArgumentException("Invalid path"))
                .when(dashboardFileService)
                .deletePath("../etc/passwd");

        StepVerifier.create(filesController.deletePath("../etc/passwd"))
                .assertNext(response -> assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode()))
                .verifyComplete();
    }
}
