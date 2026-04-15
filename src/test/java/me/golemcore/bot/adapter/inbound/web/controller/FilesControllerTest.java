package me.golemcore.bot.adapter.inbound.web.controller;

import me.golemcore.bot.adapter.inbound.web.dto.FileCreateRequest;
import me.golemcore.bot.adapter.inbound.web.dto.FileRenameRequest;
import me.golemcore.bot.adapter.inbound.web.dto.FileSaveRequest;
import me.golemcore.bot.domain.model.DashboardFileContent;
import me.golemcore.bot.domain.model.DashboardFileNode;
import me.golemcore.bot.domain.model.ToolArtifactDownload;
import me.golemcore.bot.domain.service.DashboardFileService;
import me.golemcore.bot.domain.service.ToolArtifactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FilesControllerTest {

    private DashboardFileService dashboardFileService;
    private ToolArtifactService toolArtifactService;
    private FilesController filesController;

    @BeforeEach
    void setUp() {
        dashboardFileService = mock(DashboardFileService.class);
        toolArtifactService = mock(ToolArtifactService.class);
        filesController = new FilesController(dashboardFileService, toolArtifactService);
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
                    assertStatus(response, HttpStatus.OK);
                    assertNotNull(response.getBody());
                    assertEquals(1, response.getBody().size());
                    assertEquals("src/App.tsx", response.getBody().get(0).getPath());
                })
                .verifyComplete();
    }

    @Test
    void shouldMapNestedTreeRecursively() {
        DashboardFileNode nestedChild = DashboardFileNode.builder()
                .path("src/components/Button.tsx")
                .name("Button.tsx")
                .type("file")
                .size(42L)
                .children(List.of())
                .build();

        DashboardFileNode dir = DashboardFileNode.builder()
                .path("src/components")
                .name("components")
                .type("directory")
                .children(List.of(nestedChild))
                .build();

        DashboardFileNode root = DashboardFileNode.builder()
                .path("src")
                .name("src")
                .type("directory")
                .children(List.of(dir))
                .build();

        when(dashboardFileService.getTree("src")).thenReturn(List.of(root));

        StepVerifier.create(filesController.getTree("src"))
                .assertNext(response -> {
                    assertStatus(response, HttpStatus.OK);
                    assertNotNull(response.getBody());
                    assertEquals(1, response.getBody().size());

                    assertEquals("src", response.getBody().get(0).getPath());
                    assertEquals(1, response.getBody().get(0).getChildren().size());
                    assertEquals("src/components", response.getBody().get(0).getChildren().get(0).getPath());
                    assertEquals(1, response.getBody().get(0).getChildren().get(0).getChildren().size());
                    assertEquals("src/components/Button.tsx",
                            response.getBody().get(0).getChildren().get(0).getChildren().get(0).getPath());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyTreePayloadWhenDirectoryHasNoChildren() {
        when(dashboardFileService.getTree("")).thenReturn(List.of());

        StepVerifier.create(filesController.getTree(""))
                .assertNext(response -> {
                    assertStatus(response, HttpStatus.OK);
                    assertNotNull(response.getBody());
                    assertTrue(response.getBody().isEmpty());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnBadRequestForInvalidTreePath() {
        when(dashboardFileService.getTree("../etc")).thenThrow(new IllegalArgumentException("Invalid path"));

        StepVerifier.create(filesController.getTree("../etc"))
                .assertNext(response -> assertStatus(response, HttpStatus.BAD_REQUEST))
                .verifyComplete();
    }

    @Test
    void shouldThrowWhenTreeEndpointHasUnexpectedServiceFailure() {
        when(dashboardFileService.getTree("src")).thenThrow(new IllegalStateException("Disk unavailable"));

        assertThrows(IllegalStateException.class, () -> filesController.getTree("src"));
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
                    assertStatus(response, HttpStatus.OK);
                    assertNotNull(response.getBody());
                    assertEquals("src/App.tsx", response.getBody().getPath());
                    assertEquals(31L, response.getBody().getSize());
                    assertEquals("2026-02-23T00:00:00Z", response.getBody().getUpdatedAt());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnBinaryDownloadWhenPathIsValid() {
        ToolArtifactDownload download = ToolArtifactDownload.builder()
                .path(".golemcore/tool-artifacts/session/test/report.pdf")
                .filename("report.pdf")
                .mimeType("application/pdf")
                .size(3L)
                .data(new byte[] { 1, 2, 3 })
                .build();

        when(toolArtifactService.getDownload(".golemcore/tool-artifacts/session/test/report.pdf"))
                .thenReturn(download);

        StepVerifier.create(filesController.download(".golemcore/tool-artifacts/session/test/report.pdf"))
                .assertNext(response -> {
                    assertStatus(response, HttpStatus.OK);
                    assertNotNull(response.getBody());
                    assertEquals(3, response.getBody().length);
                    assertEquals("application/pdf", response.getHeaders().getContentType().toString());
                    assertTrue(response.getHeaders().getFirst("Content-Disposition").contains("report.pdf"));
                })
                .verifyComplete();
    }

    @Test
    void shouldFallbackToOctetStreamWhenMimeTypeIsInvalid() {
        ToolArtifactDownload download = ToolArtifactDownload.builder()
                .path(".golemcore/tool-artifacts/session/test/report.bin")
                .filename("report.bin")
                .mimeType("not a mime type")
                .size(3L)
                .data(new byte[] { 1, 2, 3 })
                .build();

        when(toolArtifactService.getDownload(".golemcore/tool-artifacts/session/test/report.bin"))
                .thenReturn(download);

        StepVerifier.create(filesController.download(".golemcore/tool-artifacts/session/test/report.bin"))
                .assertNext(response -> {
                    assertStatus(response, HttpStatus.OK);
                    assertEquals("application/octet-stream", response.getHeaders().getContentType().toString());
                })
                .verifyComplete();
    }

    @Test
    void shouldFallbackToOctetStreamWhenMimeTypeIsBlank() {
        ToolArtifactDownload download = ToolArtifactDownload.builder()
                .path(".golemcore/tool-artifacts/session/test/report.bin")
                .filename("report.bin")
                .mimeType("   ")
                .size(3L)
                .data(new byte[] { 1, 2, 3 })
                .build();

        when(toolArtifactService.getDownload(".golemcore/tool-artifacts/session/test/report.bin"))
                .thenReturn(download);

        StepVerifier.create(filesController.download(".golemcore/tool-artifacts/session/test/report.bin"))
                .assertNext(response -> {
                    assertStatus(response, HttpStatus.OK);
                    assertEquals("application/octet-stream", response.getHeaders().getContentType().toString());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnBadRequestForInvalidDownloadPath() {
        when(dashboardFileService.getDownload("../etc/passwd")).thenThrow(new IllegalArgumentException("Invalid path"));

        StepVerifier.create(filesController.download("../etc/passwd"))
                .assertNext(response -> assertStatus(response, HttpStatus.BAD_REQUEST))
                .verifyComplete();
    }

    @Test
    void shouldThrowWhenDownloadEndpointHasUnexpectedServiceFailure() {
        when(dashboardFileService.getDownload("broken.bin"))
                .thenThrow(new IllegalStateException("Storage unavailable"));

        assertThrows(IllegalStateException.class, () -> filesController.download("broken.bin"));
    }

    @Test
    void shouldReturnBadRequestForInvalidContentPath() {
        when(dashboardFileService.getContent("../etc/passwd")).thenThrow(new IllegalArgumentException("Invalid path"));

        StepVerifier.create(filesController.getContent("../etc/passwd"))
                .assertNext(response -> assertStatus(response, HttpStatus.BAD_REQUEST))
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
                    assertStatus(response, HttpStatus.OK);
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
                .assertNext(response -> assertStatus(response, HttpStatus.BAD_REQUEST))
                .verifyComplete();
    }

    @Test
    void shouldReturnBadRequestWhenCreateRequestContainsNullPath() {
        FileCreateRequest request = FileCreateRequest.builder()
                .path(null)
                .content("x")
                .build();

        when(dashboardFileService.createContent(null, "x"))
                .thenThrow(new IllegalArgumentException("Path is required"));

        StepVerifier.create(filesController.createContent(request))
                .assertNext(response -> assertStatus(response, HttpStatus.BAD_REQUEST))
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
                    assertStatus(response, HttpStatus.OK);
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
                .assertNext(response -> assertStatus(response, HttpStatus.BAD_REQUEST))
                .verifyComplete();
    }

    @Test
    void shouldReturnBadRequestWhenSaveContentIsInvalid() {
        FileSaveRequest request = FileSaveRequest.builder()
                .path("src/App.tsx")
                .content(null)
                .build();

        when(dashboardFileService.saveContent("src/App.tsx", null))
                .thenThrow(new IllegalArgumentException("Content is required"));

        StepVerifier.create(filesController.saveContent(request))
                .assertNext(response -> assertStatus(response, HttpStatus.BAD_REQUEST))
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
                    assertStatus(response, HttpStatus.OK);
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
                .assertNext(response -> assertStatus(response, HttpStatus.BAD_REQUEST))
                .verifyComplete();
    }

    @Test
    void shouldDeletePathWhenPathExists() {
        doNothing().when(dashboardFileService).deletePath("src/App.tsx");

        StepVerifier.create(filesController.deletePath("src/App.tsx"))
                .assertNext(response -> assertStatus(response, HttpStatus.NO_CONTENT))
                .verifyComplete();
    }

    @Test
    void shouldReturnBadRequestWhenDeletePathIsInvalid() {
        doThrow(new IllegalArgumentException("Invalid path"))
                .when(dashboardFileService)
                .deletePath("../etc/passwd");

        StepVerifier.create(filesController.deletePath("../etc/passwd"))
                .assertNext(response -> assertStatus(response, HttpStatus.BAD_REQUEST))
                .verifyComplete();
    }

    @Test
    void shouldThrowWhenDeleteEndpointHasUnexpectedServiceFailure() {
        doThrow(new IllegalStateException("I/O failure"))
                .when(dashboardFileService)
                .deletePath("src/App.tsx");

        assertThrows(IllegalStateException.class, () -> filesController.deletePath("src/App.tsx"));
    }

    @Test
    void shouldReturnBadRequestForInvalidLazyTreePath() {
        when(dashboardFileService.getTree("../etc", 1, false))
                .thenThrow(new IllegalArgumentException("Invalid path"));

        StepVerifier.create(filesController.getTree("../etc", 1, false))
                .assertNext(response -> assertStatus(response, HttpStatus.BAD_REQUEST))
                .verifyComplete();
    }

    @Test
    void shouldReturnLazyTreePayloadWithMetadata() {
        DashboardFileNode node = DashboardFileNode.builder()
                .path("src")
                .name("src")
                .type("directory")
                .hasChildren(true)
                .children(List.of())
                .build();

        when(dashboardFileService.getTree("", 1, false)).thenReturn(List.of(node));

        StepVerifier.create(filesController.getTree("", 1, false))
                .assertNext(response -> {
                    assertStatus(response, HttpStatus.OK);
                    assertNotNull(response.getBody());
                    assertEquals(1, response.getBody().size());
                    assertEquals("src", response.getBody().get(0).getPath());
                    assertTrue(response.getBody().get(0).isHasChildren());
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnWorkspaceFileDownloadWhenPathIsValid() {
        ToolArtifactDownload download = ToolArtifactDownload.builder()
                .path("src/App.tsx")
                .filename("App.tsx")
                .mimeType("text/typescript")
                .size(4L)
                .data("test".getBytes(StandardCharsets.UTF_8))
                .build();

        when(dashboardFileService.getDownload("src/App.tsx")).thenReturn(download);

        StepVerifier.create(filesController.download("src/App.tsx"))
                .assertNext(response -> {
                    assertStatus(response, HttpStatus.OK);
                    assertNotNull(response.getBody());
                    assertEquals("test", new String(response.getBody(), StandardCharsets.UTF_8));
                    assertEquals("text/typescript", response.getHeaders().getContentType().toString());
                })
                .verifyComplete();
    }

    @Test
    void shouldUploadFileIntoWorkspaceDirectory() {
        FilePart filePart = mock(FilePart.class);
        DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();
        when(filePart.filename()).thenReturn("notes.txt");
        when(filePart.content()).thenReturn(Flux.just(bufferFactory.wrap("uploaded".getBytes(StandardCharsets.UTF_8))));

        DashboardFileContent uploaded = DashboardFileContent.builder()
                .path("docs/notes.txt")
                .content("uploaded")
                .size(8L)
                .mimeType("text/plain")
                .editable(true)
                .binary(false)
                .image(false)
                .updatedAt("2026-02-23T00:00:00Z")
                .build();

        when(dashboardFileService.uploadFile("docs", "notes.txt", "uploaded".getBytes(StandardCharsets.UTF_8), null))
                .thenReturn(uploaded);

        StepVerifier.create(filesController.upload("docs", filePart))
                .assertNext(response -> {
                    assertStatus(response, HttpStatus.OK);
                    assertNotNull(response.getBody());
                    assertEquals("docs/notes.txt", response.getBody().getPath());
                    assertEquals("uploaded", response.getBody().getContent());
                })
                .verifyComplete();
    }

    private void assertStatus(ResponseEntity<?> response, HttpStatus expected) {
        assertEquals(expected, response.getStatusCode());
    }
}
