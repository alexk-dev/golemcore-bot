package me.golemcore.bot.domain.component;

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

import me.golemcore.bot.domain.model.Skill;

import java.util.List;
import java.util.Optional;

/**
 * Component for skill loading, management, and progressive content delivery.
 * Skills are loaded from SKILL.md files with YAML frontmatter and provide
 * specialized system prompts for different agent capabilities. Supports
 * progressive loading where only summaries are shown in the general prompt,
 * with full content loaded on demand.
 */
public interface SkillComponent extends Component {

    @Override
    default String getComponentType() {
        return "skill";
    }

    /**
     * Returns all loaded skills including those with unmet requirements.
     *
     * @return list of all skills
     */
    List<Skill> getAllSkills();

    /**
     * Returns only skills that are available for use (requirements met).
     *
     * @return list of available skills
     */
    List<Skill> getAvailableSkills();

    /**
     * Finds a skill by its unique name.
     *
     * @param name
     *            the skill name
     * @return an Optional containing the skill if found
     */
    Optional<Skill> findByName(String name);

    /**
     * Returns a concise summary of all available skills for the system prompt. Uses
     * progressive loading: only names and descriptions, not full content.
     *
     * @return formatted skills summary text
     */
    String getSkillsSummary();

    /**
     * Retrieves the full content (prompt template) of a specific skill.
     *
     * @param name
     *            the skill name
     * @return the skill's full content
     */
    String getSkillContent(String name);

    /**
     * Reloads all skills from storage, re-parsing SKILL.md files. Used when skills
     * are created or modified at runtime.
     */
    void reload();
}
