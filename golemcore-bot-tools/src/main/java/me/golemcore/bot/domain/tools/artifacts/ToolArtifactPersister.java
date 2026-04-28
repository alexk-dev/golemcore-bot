package me.golemcore.bot.domain.tools.artifacts;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.Attachment;
import me.golemcore.bot.domain.model.ToolArtifact;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.tools.execution.ToolExecutionContext;

@Slf4j
public class ToolArtifactPersister {

    private final ToolArtifactService toolArtifactService;

    public ToolArtifactPersister(ToolArtifactService toolArtifactService) {
        this.toolArtifactService = toolArtifactService;
    }

    public ToolResult enrich(ToolExecutionContext executionContext, ToolResult result, String toolName,
            Attachment attachment) {
        if (result == null) {
            return null;
        }

        Map<String, Object> dataMap = mutableDataMap(result);
        boolean resultHadDataMap = result.getData() instanceof Map<?, ?>;
        boolean mutated = resultHadDataMap && stripBinaryPayload(dataMap);

        ToolArtifact storedFile = persistAttachment(executionContext, toolName, attachment);
        String output = result.getOutput();
        if (storedFile != null) {
            enrichAttachment(attachment, storedFile);
            addStoredFileMetadata(dataMap, attachment, storedFile);
            output = appendInternalFileLink(output, storedFile);
            mutated = true;
        }

        if (!mutated) {
            return result;
        }

        return ToolResult.builder().success(result.isSuccess()).output(output).data(dataMap).error(result.getError())
                .failureKind(result.getFailureKind()).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableDataMap(ToolResult result) {
        if (result.getData() instanceof Map<?, ?> rawMap) {
            return new LinkedHashMap<>((Map<String, Object>) rawMap);
        }
        return new LinkedHashMap<>();
    }

    private ToolArtifact persistAttachment(ToolExecutionContext executionContext, String toolName,
            Attachment attachment) {
        if (attachment == null) {
            return null;
        }
        try {
            return toolArtifactService.saveArtifact(resolveSessionId(executionContext), toolName,
                    attachment.getFilename(), attachment.getData(), attachment.getMimeType());
        } catch (RuntimeException ex) {
            log.warn("[Tools] Failed to persist attachment for '{}': {}", toolName, ex.getMessage());
            return null;
        }
    }

    private void enrichAttachment(Attachment attachment, ToolArtifact storedFile) {
        attachment.setDownloadUrl(storedFile.getDownloadUrl());
        attachment.setInternalFilePath(storedFile.getPath());
        attachment.setFilename(storedFile.getFilename());
        attachment.setMimeType(storedFile.getMimeType());
        if (attachment.getType() == Attachment.Type.IMAGE) {
            attachment.setThumbnailBase64(toolArtifactService.buildThumbnailBase64(storedFile.getPath()));
        }
    }

    private void addStoredFileMetadata(Map<String, Object> dataMap, Attachment attachment, ToolArtifact storedFile) {
        dataMap.put("internal_file_path", storedFile.getPath());
        dataMap.put("internal_file_url", storedFile.getDownloadUrl());
        dataMap.put("internal_file_name", storedFile.getFilename());
        dataMap.put("internal_file_mime_type", storedFile.getMimeType());
        dataMap.put("internal_file_size", storedFile.getSize());
        if (attachment.getType() != null) {
            dataMap.put("internal_file_kind", attachment.getType().name().toLowerCase(Locale.ROOT));
        }
        if (attachment.getThumbnailBase64() != null && !attachment.getThumbnailBase64().isBlank()) {
            dataMap.put("internal_file_thumbnail_base64", attachment.getThumbnailBase64());
        }
    }

    private boolean stripBinaryPayload(Map<String, Object> dataMap) {
        boolean mutated = false;
        mutated = dataMap.remove("attachment") != null || mutated;
        mutated = dataMap.remove("screenshot_base64") != null || mutated;
        mutated = dataMap.remove("file_bytes") != null || mutated;
        return mutated;
    }

    private String resolveSessionId(ToolExecutionContext executionContext) {
        if (executionContext == null || executionContext.sessionId() == null
                || executionContext.sessionId().isBlank()) {
            return "session";
        }
        return executionContext.sessionId();
    }

    private String appendInternalFileLink(String output, ToolArtifact storedFile) {
        String linkBlock = "Internal file: [" + storedFile.getFilename() + "](" + storedFile.getDownloadUrl() + ")\n"
                + "Workspace path: `" + storedFile.getPath() + "`";
        if (output == null || output.isBlank()) {
            return linkBlock;
        }
        if (output.contains(storedFile.getDownloadUrl())) {
            return output;
        }
        return output + "\n\n" + linkBlock;
    }
}
