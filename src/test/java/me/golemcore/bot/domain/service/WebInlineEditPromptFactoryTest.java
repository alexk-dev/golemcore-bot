package me.golemcore.bot.application.inlineedit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebInlineEditPromptFactoryTest {

    private final WebInlineEditPromptFactory promptFactory = new WebInlineEditPromptFactory();

    @Test
    void shouldBuildInlineEditPromptWithSelectedCodeAndInstruction() {
        String prompt = promptFactory.buildPrompt("const x = 1;", "refactor this code");

        assertTrue(prompt.contains("Return only the replacement code"));
        assertTrue(prompt.contains("Instruction:"));
        assertTrue(prompt.contains("refactor this code"));
        assertTrue(prompt.contains("Selected code:"));
        assertTrue(prompt.contains("const x = 1;"));
    }

    @Test
    void shouldPrefixFreeFormInstruction() {
        String prompt = promptFactory.buildPrompt("const x = 1;", "make this safer");

        assertTrue(prompt.contains("Refactor this code: make this safer"));
    }
}
