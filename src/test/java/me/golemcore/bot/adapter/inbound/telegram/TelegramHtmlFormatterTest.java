package me.golemcore.bot.adapter.inbound.telegram;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TelegramHtmlFormatterTest {

    @Test
    void nullAndBlank() {
        assertNull(TelegramHtmlFormatter.format(null));
        assertEquals("", TelegramHtmlFormatter.format(""));
        // Blank strings pass through as-is (isBlank check)
        assertEquals("   ", TelegramHtmlFormatter.format("   "));
    }

    @Test
    void plainText() {
        assertEquals("Hello world", TelegramHtmlFormatter.format("Hello world"));
    }

    @Test
    void escapesHtmlEntities() {
        assertEquals("a &lt; b &amp; c &gt; d", TelegramHtmlFormatter.format("a < b & c > d"));
    }

    @Test
    void boldDoubleAsterisk() {
        assertEquals("<b>bold</b> text", TelegramHtmlFormatter.format("**bold** text"));
    }

    @Test
    void boldDoubleUnderscore() {
        assertEquals("<b>bold</b>", TelegramHtmlFormatter.format("__bold__"));
    }

    @Test
    void italicAsterisk() {
        assertEquals("<i>italic</i> text", TelegramHtmlFormatter.format("*italic* text"));
    }

    @Test
    void italicUnderscore() {
        assertEquals("<i>italic</i> text", TelegramHtmlFormatter.format("_italic_ text"));
    }

    @Test
    void underscoreInsideWordsNotConverted() {
        assertEquals("file_name_path", TelegramHtmlFormatter.format("file_name_path"));
    }

    @Test
    void strikethrough() {
        assertEquals("<s>deleted</s>", TelegramHtmlFormatter.format("~~deleted~~"));
    }

    @Test
    void inlineCode() {
        assertEquals("use <code>git status</code> command",
                TelegramHtmlFormatter.format("use `git status` command"));
    }

    @Test
    void inlineCodeEscapesHtml() {
        assertEquals("<code>&lt;div&gt;</code>",
                TelegramHtmlFormatter.format("`<div>`"));
    }

    @Test
    void codeBlock() {
        String input = "```java\nSystem.out.println(\"hello\");\n```";
        String result = TelegramHtmlFormatter.format(input);
        assertTrue(result.contains("<pre>"));
        assertTrue(result.contains("System.out.println"));
        assertTrue(result.contains("</pre>"));
    }

    @Test
    void codeBlockEscapesHtml() {
        String input = "```\nList<String> x = new ArrayList<>();\n```";
        String result = TelegramHtmlFormatter.format(input);
        assertTrue(result.contains("&lt;String&gt;"));
        assertTrue(result.contains("<pre>"));
    }

    @Test
    void codeBlockPreservesFormatting() {
        String input = "```\n**not bold** _not italic_\n```";
        String result = TelegramHtmlFormatter.format(input);
        // Inside code block, markdown should NOT be converted
        assertFalse(result.contains("<b>"));
        assertFalse(result.contains("<i>"));
        assertTrue(result.contains("**not bold**"));
    }

    @Test
    void link() {
        assertEquals("click <a href=\"https://example.com\">here</a>",
                TelegramHtmlFormatter.format("click [here](https://example.com)"));
    }

    @Test
    void header() {
        assertEquals("<b>Title</b>", TelegramHtmlFormatter.format("# Title"));
        assertEquals("<b>Subtitle</b>", TelegramHtmlFormatter.format("## Subtitle"));
    }

    @Test
    void stripThinkBlock() {
        assertEquals("Hello!", TelegramHtmlFormatter.format("<think>internal reasoning</think>Hello!"));
    }

    @Test
    void stripThinkBlockMultiline() {
        String input = "<think>\nLet me think about this...\nOK, I know.\n</think>\nHere is the answer.";
        String result = TelegramHtmlFormatter.format(input);
        assertEquals("Here is the answer.", result);
    }

    @Test
    void stripMultipleThinkBlocks() {
        String input = "<think>first</think>Hello <think>second</think>world";
        assertEquals("Hello world", TelegramHtmlFormatter.format(input));
    }

    @Test
    void complexFormatting() {
        String input = "**Title**\n\n- *item1*: description\n- *item2*: `code`";
        String result = TelegramHtmlFormatter.format(input);
        assertTrue(result.contains("<b>Title</b>"));
        assertTrue(result.contains("<i>item1</i>"));
        assertTrue(result.contains("<code>code</code>"));
    }

    @Test
    void boldAndItalicTogether() {
        // **bold** and *italic* in same text
        String result = TelegramHtmlFormatter.format("**bold** and *italic*");
        assertEquals("<b>bold</b> and <i>italic</i>", result);
    }

    @Test
    void brTagsConvertedToNewlines() {
        assertEquals("line1\nline2", TelegramHtmlFormatter.format("line1<br>line2"));
        assertEquals("line1\nline2", TelegramHtmlFormatter.format("line1<br/>line2"));
        assertEquals("line1\nline2", TelegramHtmlFormatter.format("line1<br />line2"));
        assertEquals("line1\nline2", TelegramHtmlFormatter.format("line1<BR>line2"));
    }

    @Test
    void boldTextWithDollarSign() {
        // $ in bold text must not be interpreted as regex group reference
        assertEquals("<b>$3</b> per month", TelegramHtmlFormatter.format("**$3** per month"));
        assertEquals("<b>costs $10</b>", TelegramHtmlFormatter.format("**costs $10**"));
    }

    // --- Table tests ---

    @Test
    void smallTableRenderedAsPre() {
        String input = "| Name | Age |\n|------|-----|\n| Alice | 30 |\n| Bob | 25 |\n";
        String result = TelegramHtmlFormatter.format(input);
        assertTrue(result.contains("<pre>"), "should use <pre> for small tables");
        assertTrue(result.contains("Alice"), "should contain data");
        assertTrue(result.contains("│"), "should have column separators");
        assertTrue(result.contains("─"), "should have row separator");
        assertFalse(result.contains("|"), "pipe chars should be replaced with box-drawing");
    }

    @Test
    void wideTableRenderedAsList() {
        String input = "| Scenario | Model | Description |\n|----------|-------|-------------|\n"
                + "| Best quality | DeepSeek-V3.2 | Very long description that makes the table too wide for pre format |\n";
        String result = TelegramHtmlFormatter.format(input);
        assertTrue(result.contains("<b>"), "should use bold titles in list format");
        assertTrue(result.contains("▪"), "should have bullet markers");
        assertFalse(result.contains("<pre>"), "should NOT use <pre> for wide tables");
        assertTrue(result.contains("Model:"), "should show header as key");
        assertTrue(result.contains("Description:"), "should show header as key");
    }

    @Test
    void tableWithBrTagsRenderedAsList() {
        String input = "| Feature | Details |\n|---------|----------|\n"
                + "| Auth | Supports OAuth<br>Also supports SAML |\n";
        String result = TelegramHtmlFormatter.format(input);
        assertTrue(result.contains("▪"), "should use list format when cells have <br>");
        assertTrue(result.contains("Supports OAuth"), "should preserve content");
        assertTrue(result.contains("Also supports SAML"), "should preserve content after <br>");
        assertFalse(result.contains("<br"), "should not have raw <br> tags");
    }

    @Test
    void tableWithHtmlEntitiesInCells() {
        String input = "| Expr | Result |\n|------|--------|\n| a < b | true |\n";
        String result = TelegramHtmlFormatter.format(input);
        assertTrue(result.contains("&lt;"), "should escape < in cells");
    }

    @Test
    void textAroundTablePreserved() {
        String input = "Here is a table:\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\nEnd of text.";
        String result = TelegramHtmlFormatter.format(input);
        assertTrue(result.contains("Here is a table:"), "text before table preserved");
        assertTrue(result.contains("End of text."), "text after table preserved");
        assertTrue(result.contains("<pre>") || result.contains("▪"), "table should be converted");
    }

    @Test
    void thinkBlockWithMarkdown() {
        String input = "<think>reasoning here</think>**Answer**: use `git commit`";
        String result = TelegramHtmlFormatter.format(input);
        assertTrue(result.contains("<b>Answer</b>"));
        assertTrue(result.contains("<code>git commit</code>"));
        assertFalse(result.contains("think"));
    }

    @Test
    void noPlaceholderLeaksInOutput() {
        // Inline code placeholders should never appear in the formatted output
        String input = "Use `cmd1` and `cmd2` then `cmd3`";
        String result = TelegramHtmlFormatter.format(input);
        assertFalse(result.contains("\uE000"), "PUA placeholder chars should not leak");
        assertFalse(result.contains("\u0000"), "null placeholder chars should not leak");
        assertFalse(result.contains("IC"), "IC placeholder text should not leak");
        assertTrue(result.contains("<code>cmd1</code>"));
        assertTrue(result.contains("<code>cmd2</code>"));
        assertTrue(result.contains("<code>cmd3</code>"));
    }

    @Test
    void manyInlineCodesRestored() {
        // Test with 12 inline codes to verify index 10+ doesn't collide with index 1
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append("`code").append(i).append("` ");
        }
        String result = TelegramHtmlFormatter.format(sb.toString());
        for (int i = 0; i < 12; i++) {
            assertTrue(result.contains("<code>code" + i + "</code>"),
                    "code" + i + " should be wrapped in <code>");
        }
        assertFalse(result.contains("\uE000"), "no placeholder leaks");
    }

    @Test
    void boldWithBulletPoints() {
        // Reproduces the reported formatting issue
        String input = "▪️ **Погода в Доминикане**\nОписание: текст\n\n▪️ **Работа с файлами**\nДругой текст";
        String result = TelegramHtmlFormatter.format(input);
        assertTrue(result.contains("<b>Погода в Доминикане</b>"), "bold should be converted");
        assertTrue(result.contains("<b>Работа с файлами</b>"), "bold should be converted");
        assertFalse(result.contains("**"), "no raw markdown ** should remain");
    }

    @Test
    void mixedBoldAndInlineCode() {
        String input = "▪️ **Файлы**\nСоздан `file1.txt` (контент: `file2.txt`, `file3.txt`).";
        String result = TelegramHtmlFormatter.format(input);
        assertTrue(result.contains("<b>Файлы</b>"));
        assertTrue(result.contains("<code>file1.txt</code>"));
        assertTrue(result.contains("<code>file2.txt</code>"));
        assertTrue(result.contains("<code>file3.txt</code>"));
        assertFalse(result.contains("**"));
        assertFalse(result.contains("\uE000"));
    }
}
