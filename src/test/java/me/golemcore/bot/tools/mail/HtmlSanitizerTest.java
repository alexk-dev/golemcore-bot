package me.golemcore.bot.tools.mail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class HtmlSanitizerTest {

    // ==================== Null / blank input ====================

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "\n\t " })
    void shouldReturnEmptyForNullOrBlankInput(String input) {
        assertEquals("", HtmlSanitizer.stripHtml(input));
    }

    // ==================== Plain text passthrough ====================

    @Test
    void shouldReturnPlainTextUnchanged() {
        assertEquals("Hello world", HtmlSanitizer.stripHtml("Hello world"));
    }

    // ==================== Block tags → newlines ====================

    @Test
    void shouldReplaceBreakTagWithNewline() {
        assertEquals("line1\nline2", HtmlSanitizer.stripHtml("line1<br>line2"));
    }

    @Test
    void shouldReplaceSelfClosingBreakWithNewline() {
        assertEquals("line1\nline2", HtmlSanitizer.stripHtml("line1<br/>line2"));
    }

    @Test
    void shouldReplaceParagraphTagsWithNewlines() {
        // Only opening <p> becomes newline, </p> is stripped
        assertEquals("First\nSecond", HtmlSanitizer.stripHtml("<p>First</p><p>Second</p>"));
    }

    @Test
    void shouldReplaceDivTagsWithNewlines() {
        assertEquals("block1\nblock2", HtmlSanitizer.stripHtml("<div>block1</div><div>block2</div>"));
    }

    @Test
    void shouldReplaceHeadingTagsWithNewlines() {
        assertEquals("Title\nSubtitle",
                HtmlSanitizer.stripHtml("<h1>Title</h1><h2>Subtitle</h2>"));
    }

    @Test
    void shouldReplaceListItemTagsWithNewlines() {
        assertEquals("item1\nitem2",
                HtmlSanitizer.stripHtml("<li>item1</li><li>item2</li>"));
    }

    @Test
    void shouldReplaceTableRowTagsWithNewlines() {
        assertEquals("row1\nrow2",
                HtmlSanitizer.stripHtml("<tr>row1</tr><tr>row2</tr>"));
    }

    @Test
    void shouldHandleBlockTagsWithAttributes() {
        assertEquals("text", HtmlSanitizer.stripHtml("<p class=\"highlight\" id=\"main\">text</p>"));
    }

    @Test
    void shouldHandleBlockTagsCaseInsensitive() {
        assertEquals("Upper\nLower",
                HtmlSanitizer.stripHtml("<P>Upper</P><p>Lower</p>"));
    }

    // ==================== Inline tags stripped ====================

    @Test
    void shouldStripInlineTags() {
        assertEquals("bold italic", HtmlSanitizer.stripHtml("<b>bold</b> <i>italic</i>"));
    }

    @Test
    void shouldStripAnchorTags() {
        assertEquals("click here", HtmlSanitizer.stripHtml("<a href=\"https://example.com\">click here</a>"));
    }

    @Test
    void shouldStripSpanTags() {
        assertEquals("styled text", HtmlSanitizer.stripHtml("<span style=\"color:red\">styled text</span>"));
    }

    // ==================== Entity decoding ====================

    @Test
    void shouldDecodeAmpersandEntity() {
        assertEquals("A & B", HtmlSanitizer.stripHtml("A &amp; B"));
    }

    @Test
    void shouldDecodeLessThanEntity() {
        assertEquals("a < b", HtmlSanitizer.stripHtml("a &lt; b"));
    }

    @Test
    void shouldDecodeGreaterThanEntity() {
        assertEquals("a > b", HtmlSanitizer.stripHtml("a &gt; b"));
    }

    @Test
    void shouldDecodeNonBreakingSpaceEntity() {
        assertEquals("word1 word2", HtmlSanitizer.stripHtml("word1&nbsp;word2"));
    }

    @Test
    void shouldDecodeQuoteEntity() {
        assertEquals("say \"hello\"", HtmlSanitizer.stripHtml("say &quot;hello&quot;"));
    }

    @Test
    void shouldDecodeNumericApostropheEntity() {
        assertEquals("it's", HtmlSanitizer.stripHtml("it&#39;s"));
    }

    @Test
    void shouldDecodeNamedApostropheEntity() {
        assertEquals("it's", HtmlSanitizer.stripHtml("it&apos;s"));
    }

    @Test
    void shouldDecodeMultipleEntitiesInOneString() {
        assertEquals("1 < 2 & 3 > 0", HtmlSanitizer.stripHtml("1 &lt; 2 &amp; 3 &gt; 0"));
    }

    // ==================== Whitespace collapsing ====================

    @Test
    void shouldCollapseMultipleNewlinesToTwo() {
        assertEquals("line1\n\nline2", HtmlSanitizer.stripHtml("line1\n\n\n\nline2"));
    }

    @Test
    void shouldCollapseMultipleSpacesToOne() {
        assertEquals("word1 word2 word3", HtmlSanitizer.stripHtml("word1  word2   word3"));
    }

    @Test
    void shouldStripLeadingAndTrailingWhitespace() {
        assertEquals("text", HtmlSanitizer.stripHtml("  \n  text  \n  "));
    }

    // ==================== Combined scenarios ====================

    @Test
    void shouldHandleCompleteHtmlEmail() {
        String html = "<html><body><h1>Welcome</h1><p>Dear user,</p>" +
                "<p>Your order &amp; invoice are ready.</p>" +
                "<br>Best regards,<br><b>Team</b></body></html>";

        String result = HtmlSanitizer.stripHtml(html);

        assertTrue(result.contains("Welcome"));
        assertTrue(result.contains("Dear user,"));
        assertTrue(result.contains("Your order & invoice are ready."));
        assertTrue(result.contains("Best regards,"));
        assertTrue(result.contains("Team"));
    }

    @Test
    void shouldHandleEscapedHtmlInEntities() {
        String result = HtmlSanitizer.stripHtml("&lt;script&gt;alert('xss')&lt;/script&gt;");
        assertEquals("<script>alert('xss')</script>", result);
    }

    @Test
    void shouldHandleNestedBlockTags() {
        String result = HtmlSanitizer.stripHtml("<div><p>nested</p></div>");
        assertTrue(result.contains("nested"));
    }
}
