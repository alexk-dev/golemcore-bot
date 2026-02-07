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

package me.golemcore.bot.adapter.inbound.telegram;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Markdown text to Telegram-compatible HTML.
 *
 * <p>
 * This formatter handles:
 * <ul>
 * <li>Stripping {@code <think>...</think>} blocks from LLM responses
 * <li>Converting Markdown formatting (bold, italic, code blocks, links) to
 * Telegram HTML
 * <li>Converting Markdown tables to either formatted
 *
 * <pre>
 * blocks or bullet lists
 * <li>Escaping HTML entities to prevent injection
 * <li>Converting <br>
 * tags to newlines (Telegram doesn't support br)
 * </ul>
 *
 * <p>
 * Tables are rendered as:
 * <ul>
 * <li>Fixed-width
 *
 * <pre>
 * blocks if they fit within 55 characters
 * <li>Bullet lists otherwise (first column as title, remaining as key-value
 * pairs)
 * </ul>
 */
public final class TelegramHtmlFormatter {

    private TelegramHtmlFormatter() {
    }

    // <think>...</think> blocks (single-line and multi-line)
    private static final Pattern THINK_PATTERN = Pattern.compile(
            "<think>.*?</think>\\s*", Pattern.DOTALL);

    // <br>, <br/>, <br /> → newline (Telegram doesn't support <br>)
    private static final Pattern BR_PATTERN = Pattern.compile(
            "<br\\s*/?>", Pattern.CASE_INSENSITIVE);

    // ```lang\ncode\n``` or ```code```
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "```(?:\\w*\\n)?([\\s\\S]*?)```");

    // `inline code`
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile(
            "`([^`\n]+)`");

    // [text](url)
    private static final Pattern LINK_PATTERN = Pattern.compile(
            "\\[([^]]+)]\\(([^)]+)\\)");

    // **bold** or __bold__
    private static final Pattern BOLD_PATTERN = Pattern.compile(
            "\\*\\*(.+?)\\*\\*|__(.+?)__");

    // *italic* (not preceded/followed by word chars or *)
    private static final Pattern ITALIC_PATTERN = Pattern.compile(
            "(?<![\\w*])\\*([^*\n]+?)\\*(?![\\w*])");

    // _italic_ (not inside words like file_name_path)
    private static final Pattern ITALIC_UNDERSCORE_PATTERN = Pattern.compile(
            "(?<![\\w])_([^_\n]+?)_(?![\\w])");

    // ~~strikethrough~~
    private static final Pattern STRIKETHROUGH_PATTERN = Pattern.compile(
            "~~(.+?)~~");

    // # Header (1-6 levels)
    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "(?m)^#{1,6}\\s+(.+)$");

    // Markdown table: header | separator | data rows
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "(?m)^(\\|.+\\|)[ \\t]*\\n(\\|[-:| ]+\\|)[ \\t]*\\n((?:\\|.+\\|[ \\t]*\\n?)+)");

    private static final String CODE_BLOCK_PLACEHOLDER = "\uE000CB";
    private static final String INLINE_CODE_PLACEHOLDER = "\uE000IC";
    private static final String TABLE_PLACEHOLDER = "\uE000TB";

    private static final int PRE_TABLE_MAX_WIDTH = 55;

    /**
     * Convert Markdown to Telegram HTML. Strips think blocks, escapes HTML
     * entities, converts formatting.
     */
    public static String format(String text) {
        if (text == null || text.isBlank())
            return text;

        // Strip <think>...</think> blocks
        text = THINK_PATTERN.matcher(text).replaceAll("");

        // Extract code blocks to protect their content from formatting
        List<String> codeBlocks = new ArrayList<>();
        Matcher m = CODE_BLOCK_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            codeBlocks.add(m.group(1));
            m.appendReplacement(sb, CODE_BLOCK_PLACEHOLDER + (codeBlocks.size() - 1) + CODE_BLOCK_PLACEHOLDER);
        }
        m.appendTail(sb);
        text = sb.toString();

        // Extract inline code
        List<String> inlineCodes = new ArrayList<>();
        m = INLINE_CODE_PATTERN.matcher(text);
        sb = new StringBuilder();
        while (m.find()) {
            inlineCodes.add(m.group(1));
            m.appendReplacement(sb, INLINE_CODE_PLACEHOLDER + (inlineCodes.size() - 1) + INLINE_CODE_PLACEHOLDER);
        }
        m.appendTail(sb);
        text = sb.toString();

        // Extract and convert tables (before <br> conversion — cells may contain <br>)
        List<String> convertedTables = new ArrayList<>();
        m = TABLE_PATTERN.matcher(text);
        sb = new StringBuilder();
        while (m.find()) {
            List<String> headers = parseCells(m.group(1));
            String[] dataLines = m.group(3).split("\\n");
            List<List<String>> rows = new ArrayList<>();
            for (String line : dataLines) {
                if (!line.isBlank()) {
                    rows.add(parseCells(line));
                }
            }
            String converted = convertTable(headers, rows);
            convertedTables.add(converted);
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    TABLE_PLACEHOLDER + (convertedTables.size() - 1) + TABLE_PLACEHOLDER));
        }
        m.appendTail(sb);
        text = sb.toString();

        // Convert <br> tags to newlines (in remaining non-table text)
        text = BR_PATTERN.matcher(text).replaceAll("\n");

        // Escape HTML entities in the remaining text
        text = escapeHtml(text);

        // Convert Markdown to HTML (order matters: bold before italic)
        text = BOLD_PATTERN.matcher(text).replaceAll(mr -> {
            String content = mr.group(1) != null ? mr.group(1) : mr.group(2);
            return Matcher.quoteReplacement("<b>" + content + "</b>");
        });
        text = ITALIC_PATTERN.matcher(text).replaceAll("<i>$1</i>");
        text = ITALIC_UNDERSCORE_PATTERN.matcher(text).replaceAll("<i>$1</i>");
        text = STRIKETHROUGH_PATTERN.matcher(text).replaceAll("<s>$1</s>");
        text = LINK_PATTERN.matcher(text).replaceAll("<a href=\"$2\">$1</a>");
        text = HEADER_PATTERN.matcher(text).replaceAll("<b>$1</b>");

        // Restore inline code (with HTML escaping)
        for (int i = 0; i < inlineCodes.size(); i++) {
            text = text.replace(
                    INLINE_CODE_PLACEHOLDER + i + INLINE_CODE_PLACEHOLDER,
                    "<code>" + escapeHtml(inlineCodes.get(i)) + "</code>");
        }

        // Restore code blocks (with HTML escaping)
        for (int i = 0; i < codeBlocks.size(); i++) {
            text = text.replace(
                    CODE_BLOCK_PLACEHOLDER + i + CODE_BLOCK_PLACEHOLDER,
                    "<pre>" + escapeHtml(codeBlocks.get(i)) + "</pre>");
        }

        // Restore tables (already fully formatted as HTML)
        for (int i = 0; i < convertedTables.size(); i++) {
            text = text.replace(
                    TABLE_PLACEHOLDER + i + TABLE_PLACEHOLDER,
                    convertedTables.get(i));
        }

        return text.strip();
    }

    // --- Table conversion ---

    private static List<String> parseCells(String row) {
        String trimmed = row.trim();
        if (trimmed.startsWith("|"))
            trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|"))
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        String[] parts = trimmed.split("\\|");
        List<String> cells = new ArrayList<>();
        for (String part : parts) {
            cells.add(part.trim());
        }
        return cells;
    }

    private static String convertTable(List<String> headers, List<List<String>> rows) {
        boolean hasMultilineContent = rows.stream()
                .flatMap(List::stream)
                .anyMatch(cell -> BR_PATTERN.matcher(cell).find());

        if (hasMultilineContent) {
            return formatTableAsList(headers, rows);
        }

        int[] colWidths = calculateColumnWidths(headers, rows);
        int totalWidth = 0;
        for (int w : colWidths)
            totalWidth += w;
        totalWidth += (colWidths.length - 1) * 3; // " │ " separators

        if (totalWidth <= PRE_TABLE_MAX_WIDTH) {
            return formatTableAsPre(headers, rows, colWidths);
        }
        return formatTableAsList(headers, rows);
    }

    private static int[] calculateColumnWidths(List<String> headers, List<List<String>> rows) {
        int[] widths = new int[headers.size()];
        for (int i = 0; i < headers.size(); i++) {
            widths[i] = headers.get(i).length();
            for (List<String> row : rows) {
                if (i < row.size()) {
                    widths[i] = Math.max(widths[i], row.get(i).length());
                }
            }
        }
        return widths;
    }

    private static String formatTableAsPre(List<String> headers, List<List<String>> rows, int[] colWidths) {
        StringBuilder out = new StringBuilder("<pre>");
        // Header
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0)
                out.append(" │ ");
            out.append(escapeHtml(pad(headers.get(i), colWidths[i])));
        }
        out.append("\n");
        // Separator
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0)
                out.append("─┼─");
            out.append("─".repeat(colWidths[i]));
        }
        out.append("\n");
        // Data rows
        for (List<String> row : rows) {
            for (int i = 0; i < headers.size(); i++) {
                if (i > 0)
                    out.append(" │ ");
                String cell = i < row.size() ? row.get(i) : "";
                out.append(escapeHtml(pad(cell, colWidths[i])));
            }
            out.append("\n");
        }
        out.append("</pre>");
        return out.toString();
    }

    private static String formatTableAsList(List<String> headers, List<List<String>> rows) {
        StringBuilder out = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (r > 0)
                out.append("\n");
            // First column value as bold title
            String title = row.isEmpty() ? "" : row.get(0);
            title = BR_PATTERN.matcher(title).replaceAll("\n");
            out.append("▪ <b>").append(escapeHtml(title)).append("</b>\n");
            // Remaining columns as "header: value"
            for (int i = 1; i < headers.size(); i++) {
                String value = i < row.size() ? row.get(i) : "";
                value = BR_PATTERN.matcher(value).replaceAll("\n  ");
                out.append(escapeHtml(headers.get(i))).append(": ").append(escapeHtml(value)).append("\n");
            }
        }
        return out.toString().stripTrailing();
    }

    private static String pad(String text, int width) {
        if (text.length() >= width)
            return text;
        return text + " ".repeat(width - text.length());
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
