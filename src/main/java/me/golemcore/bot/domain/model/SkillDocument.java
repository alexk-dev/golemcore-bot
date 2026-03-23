package me.golemcore.bot.domain.model;

import java.util.Map;

/**
 * Parsed representation of a SKILL.md document. Frontmatter metadata is kept
 * separate from the markdown body so callers can normalize storage and UI
 * payloads without mixing YAML settings into instructions.
 */
public record SkillDocument(Map<String,Object>metadata,String body,boolean hasFrontmatter){}
