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

package me.golemcore.bot.tools.mail;

import java.util.regex.Pattern;

/**
 * Strips HTML tags from email body for LLM consumption. Converts block-level
 * elements to newlines and decodes common HTML entities.
 */
public final class HtmlSanitizer {

    private static final Pattern BLOCK_TAGS = Pattern.compile("<(br|p|div|tr|li|h[1-6])[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ALL_TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern MULTI_NEWLINES = Pattern.compile("\n{3,}");
    private static final Pattern MULTI_SPACES = Pattern.compile(" {2,}");

    private HtmlSanitizer() {
    }

    /**
     * Strips HTML tags and returns plain text suitable for LLM consumption.
     *
     * @param html
     *            the HTML content
     * @return plain text with tags removed and entities decoded
     */
    public static String stripHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        // Replace block-level tags with newlines
        String result = BLOCK_TAGS.matcher(html).replaceAll("\n");

        // Strip all remaining tags
        result = ALL_TAGS.matcher(result).replaceAll("");

        // Decode common HTML entities
        result = decodeEntities(result);

        // Collapse multiple newlines and spaces
        result = MULTI_NEWLINES.matcher(result).replaceAll("\n\n");
        result = MULTI_SPACES.matcher(result).replaceAll(" ");

        return result.strip();
    }

    private static String decodeEntities(String text) {
        return text
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }
}
