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

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple template engine for variable substitution in skill content and prompt
 * sections. Substitutes {{VAR}} placeholders with resolved values from variable
 * maps. Unresolved placeholders are left intact, allowing for partial template
 * rendering.
 */
@Component
public class SkillTemplateEngine {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");

    /**
     * Renders content by substituting {{VAR}} placeholders with values from the
     * variable map. Unresolved placeholders are preserved as-is.
     *
     * @param content
     *            the template content with {{VAR}} placeholders
     * @param variables
     *            the variable name-to-value mapping
     * @return the rendered content with substitutions applied
     */
    public String render(String content, Map<String, String> variables) {
        if (content == null) {
            return null;
        }
        if (variables == null || variables.isEmpty()) {
            return content;
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(content);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String varName = matcher.group(1);
            String value = variables.get(varName);
            if (value != null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(value));
            } else {
                // Leave unresolved placeholder as-is
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(result);

        return result.toString();
    }
}
