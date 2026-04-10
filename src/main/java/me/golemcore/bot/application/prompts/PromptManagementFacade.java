package me.golemcore.bot.application.prompts;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;
import me.golemcore.bot.domain.model.PromptSection;
import me.golemcore.bot.domain.service.PromptSectionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.outbound.StoragePort;

public class PromptManagementFacade {

    private static final String PROMPTS_DIR = "prompts";
    private static final Pattern VALID_NAME = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    private final PromptSectionService promptSectionService;
    private final UserPreferencesService preferencesService;
    private final StoragePort storagePort;

    public PromptManagementFacade(
            PromptSectionService promptSectionService,
            UserPreferencesService preferencesService,
            StoragePort storagePort) {
        this.promptSectionService = promptSectionService;
        this.preferencesService = preferencesService;
        this.storagePort = storagePort;
    }

    public List<PromptSection> listSections() {
        return promptSectionService.getAllSections();
    }

    public PromptSection getSection(String name) {
        return promptSectionService.getSection(name)
                .orElseThrow(() -> new NoSuchElementException("Prompt section '" + name + "' not found"));
    }

    public boolean isProtectedSection(String name) {
        return promptSectionService.isProtectedSection(name);
    }

    public PromptSection createSection(PromptSectionDraft request) {
        requireRequest(request);
        String normalizedName = requireValidName(request.name());
        if (promptSectionService.getSection(normalizedName).isPresent()) {
            throw new IllegalStateException("Prompt section '" + normalizedName + "' already exists");
        }

        persistSection(filenameFor(normalizedName), buildFileContent(request));
        return promptSectionService.getSection(normalizedName)
                .orElseThrow(() -> new NoSuchElementException(
                        "Prompt section '" + normalizedName + "' not found after create"));
    }

    public PromptSection updateSection(String name, PromptSectionDraft request) {
        requireRequest(request);
        String normalizedName = requireValidName(name);
        if (promptSectionService.getSection(normalizedName).isEmpty()) {
            throw new NoSuchElementException("Prompt section '" + normalizedName + "' not found");
        }

        persistSection(filenameFor(normalizedName), buildFileContent(request));
        return promptSectionService.getSection(normalizedName)
                .orElseThrow(() -> new NoSuchElementException(
                        "Prompt section '" + normalizedName + "' not found after update"));
    }

    public String previewSection(String name, PromptSectionDraft request) {
        String normalizedName = requireValidName(name);
        PromptSection section = promptSectionService.getSection(normalizedName)
                .orElseThrow(() -> new NoSuchElementException("Prompt section '" + normalizedName + "' not found"));
        Map<String, String> vars = promptSectionService.buildTemplateVariables(preferencesService.getPreferences());
        return promptSectionService.renderSection(resolvePreviewSection(section, request), vars);
    }

    public void deleteSection(String name) {
        String normalizedName = requireValidName(name);
        if (promptSectionService.getSection(normalizedName).isEmpty()) {
            throw new NoSuchElementException("Prompt section '" + normalizedName + "' not found");
        }
        if (promptSectionService.isProtectedSection(normalizedName)) {
            throw new IllegalStateException("Prompt section '" + normalizedName + "' cannot be deleted");
        }
        storagePort.deleteObject(PROMPTS_DIR, filenameFor(normalizedName)).join();
        promptSectionService.reload();
    }

    public void reload() {
        promptSectionService.reload();
    }

    private void persistSection(String filename, String fileContent) {
        storagePort.putText(PROMPTS_DIR, filename, fileContent).join();
        promptSectionService.reload();
    }

    private PromptSection resolvePreviewSection(PromptSection section, PromptSectionDraft request) {
        if (request == null || request.content() == null) {
            return section;
        }
        return PromptSection.builder()
                .name(section.getName())
                .description(request.description())
                .order(request.order())
                .enabled(request.enabled())
                .content(request.content())
                .build();
    }

    private void requireRequest(PromptSectionDraft request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
    }

    private String buildFileContent(PromptSectionDraft request) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        if (request.description() != null) {
            sb.append("description: ").append(request.description()).append("\n");
        }
        sb.append("order: ").append(request.order()).append("\n");
        sb.append("enabled: ").append(request.enabled()).append("\n");
        sb.append("---\n");
        sb.append(request.content() != null ? request.content() : "");
        return sb.toString();
    }

    private String requireValidName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Prompt name is required and must match [a-z0-9][a-z0-9-]*");
        }
        String normalizedName = name.trim().toLowerCase(Locale.ROOT);
        if (!VALID_NAME.matcher(normalizedName).matches()) {
            throw new IllegalArgumentException("Prompt name is required and must match [a-z0-9][a-z0-9-]*");
        }
        return normalizedName;
    }

    private String filenameFor(String normalizedName) {
        return normalizedName.toUpperCase(Locale.ROOT) + ".md";
    }
}
