package me.golemcore.bot.domain.service;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.model.PromptSection;
import me.golemcore.bot.domain.model.UserPreferences;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for loading, caching, and rendering modular file-based system prompt
 * sections. Prompt sections are .md files in the "prompts" directory with
 * optional YAML frontmatter (same pattern as SKILL.md). Sections are assembled
 * in order to form the system prompt, with template variable substitution
 * ({{BOT_NAME}}, {{DATE}}, etc.). Enables customizable system prompts without
 * code changes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromptSectionService {

    static final String PROMPTS_DIR = "prompts";

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);

    private static final String DEFAULT_IDENTITY_CONTENT = """
            ---
            description: Bot identity and personality
            order: 10
            ---
            You are {{BOT_NAME}}, a helpful AI assistant.

            You communicate clearly and concisely. You are knowledgeable, patient, and focused on providing accurate, useful information. When uncertain, you say so rather than guessing.

            Current date: {{DATE}}
            Current time: {{TIME}} ({{USER_TIMEZONE}})
            User language: {{USER_LANG}}
            """;

    private static final String DEFAULT_RULES_CONTENT = """
            ---
            description: Behavioral rules and constraints
            order: 20
            ---
            ## Rules

            1. Provide factual, well-reasoned answers. If you are unsure, say so explicitly.
            2. Use markdown for readability — headings, lists, code blocks where appropriate.
            3. Never generate harmful, illegal, or deceptive content.
            4. When you have tools available, use them proactively. Do not ask for permission unless the action is destructive.
            5. Be thorough but avoid unnecessary verbosity. Match depth to question complexity.
            6. Reference memory context when relevant to the conversation.
            """;

    private static final String DEFAULT_VOICE_CONTENT = """
            ---
            description: Voice response capabilities and instructions
            order: 15
            ---
            ## Voice

            You have voice capabilities. You can receive and send voice messages.

            When to respond with voice (use the `voice_response` tool):
            - The user sent a voice message
            - The user explicitly asks you to respond with voice ("respond with voice", "say it out loud", etc.)

            When NOT to use voice:
            - Regular text conversations
            - Code, tables, or structured data (voice is for spoken content)

            Keep voice responses concise and natural — as if speaking to a person.
            Do NOT say you are a "text assistant" or that you "cannot produce audio".
            """;

    private final StoragePort storagePort;
    private final BotProperties properties;
    private final SkillTemplateEngine templateEngine;

    private final Map<String, PromptSection> sectionRegistry = new ConcurrentHashMap<>();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @PostConstruct
    public void init() {
        if (isEnabled()) {
            ensureDefaults();
            reload();
        }
    }

    public boolean isEnabled() {
        return properties.getPrompts().isEnabled();
    }

    /**
     * Reloads all prompt sections from storage, clearing the existing registry.
     * Scans the prompts directory for .md files and parses them.
     */
    public void reload() {
        sectionRegistry.clear();

        try {
            List<String> files = storagePort.listObjects(PROMPTS_DIR, "").join();
            for (String file : files) {
                if (file.toLowerCase(Locale.ROOT).endsWith(".md")) {
                    loadSection(file);
                }
            }
            log.info("Loaded {} prompt sections", sectionRegistry.size());
        } catch (Exception e) {
            log.warn("Failed to load prompt sections from storage", e);
        }
    }

    /**
     * Returns all enabled sections sorted by order (ascending), then by name.
     *
     * @return ordered list of enabled prompt sections
     */
    public List<PromptSection> getEnabledSections() {
        return sectionRegistry.values().stream()
                .filter(PromptSection::isEnabled)
                .sorted(Comparator.comparingInt(PromptSection::getOrder)
                        .thenComparing(PromptSection::getName))
                .toList();
    }

    /**
     * Looks up a section by name.
     *
     * @param name
     *            the section name
     * @return an Optional containing the section if found
     */
    public Optional<PromptSection> getSection(String name) {
        return Optional.ofNullable(sectionRegistry.get(name));
    }

    /**
     * Render a section's content by substituting template variables.
     */
    public String renderSection(PromptSection section, Map<String, String> vars) {
        if (section == null || section.getContent() == null) {
            return null;
        }
        return templateEngine.render(section.getContent(), vars);
    }

    /**
     * Build the template variables map from user preferences and config.
     */
    public Map<String, String> buildTemplateVariables(UserPreferences prefs) {
        Map<String, String> vars = new HashMap<>();

        // Bot name from config
        vars.put("BOT_NAME", properties.getPrompts().getBotName());

        // Date/time in user's timezone
        String timezone = prefs != null && prefs.getTimezone() != null ? prefs.getTimezone() : "UTC";
        String language = prefs != null && prefs.getLanguage() != null ? prefs.getLanguage() : "en";

        try {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(timezone));
            vars.put("DATE", now.format(DateTimeFormatter.ISO_LOCAL_DATE));
            vars.put("TIME", now.format(DateTimeFormatter.ofPattern("HH:mm")));
        } catch (Exception e) {
            log.warn("Invalid timezone '{}', falling back to UTC", timezone);
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("UTC"));
            vars.put("DATE", now.format(DateTimeFormatter.ISO_LOCAL_DATE));
            vars.put("TIME", now.format(DateTimeFormatter.ofPattern("HH:mm")));
            timezone = "UTC";
        }

        vars.put("USER_LANG", language);
        vars.put("USER_TIMEZONE", timezone);

        // Custom vars from config
        Map<String, String> customVars = properties.getPrompts().getCustomVars();
        if (customVars != null) {
            vars.putAll(customVars);
        }

        return vars;
    }

    /**
     * Ensure default prompt files (IDENTITY.md, RULES.md) exist.
     */
    void ensureDefaults() {
        ensureDefault("IDENTITY.md", DEFAULT_IDENTITY_CONTENT);
        ensureDefault("RULES.md", DEFAULT_RULES_CONTENT);
        if (properties.getVoice().isEnabled()) {
            ensureDefault("VOICE.md", DEFAULT_VOICE_CONTENT);
        }
    }

    private void ensureDefault(String filename, String content) {
        try {
            boolean exists = storagePort.exists(PROMPTS_DIR, filename).join();
            if (!exists) {
                storagePort.putText(PROMPTS_DIR, filename, content).join();
                log.info("Created default prompt section: {}", filename);
            }
        } catch (Exception e) {
            log.warn("Failed to create default prompt section: {}", filename, e);
        }
    }

    private void loadSection(String file) {
        try {
            String content = storagePort.getText(PROMPTS_DIR, file).join();
            if (content == null || content.isBlank()) {
                return;
            }

            PromptSection section = parseSection(content, file);
            if (section != null) {
                sectionRegistry.put(section.getName(), section);
                log.debug("Loaded prompt section: {} (order={})", section.getName(), section.getOrder());
            }
        } catch (Exception e) {
            log.warn("Failed to load prompt section: {}", file, e);
        }
    }

    private PromptSection parseSection(String content, String file) {
        String name = extractNameFromFile(file);
        String description = "";
        String body = content;
        int order = 100;
        boolean enabled = true;

        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (matcher.matches()) {
            String frontmatter = matcher.group(1);
            body = matcher.group(2);

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> yaml = yamlMapper.readValue(frontmatter, Map.class);

                description = (String) yaml.getOrDefault("description", "");
                if (yaml.containsKey("order")) {
                    order = ((Number) yaml.get("order")).intValue();
                }
                if (yaml.containsKey("enabled")) {
                    enabled = (Boolean) yaml.get("enabled");
                }
            } catch (Exception e) {
                log.warn("Failed to parse prompt section frontmatter: {}", file, e);
            }
        }

        return PromptSection.builder()
                .name(name)
                .content(body.trim())
                .description(description)
                .order(order)
                .enabled(enabled)
                .build();
    }

    private String extractNameFromFile(String file) {
        // "IDENTITY.md" → "identity", "sub/RULES.md" → "rules"
        String filename = file;
        int lastSlash = filename.lastIndexOf('/');
        if (lastSlash >= 0) {
            filename = filename.substring(lastSlash + 1);
        }
        if (filename.toLowerCase(Locale.ROOT).endsWith(".md")) {
            filename = filename.substring(0, filename.length() - 3);
        }
        return filename.toLowerCase(Locale.ROOT);
    }
}
