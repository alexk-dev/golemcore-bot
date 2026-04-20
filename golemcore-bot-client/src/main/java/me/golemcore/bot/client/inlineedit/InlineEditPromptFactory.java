package me.golemcore.bot.client.inlineedit;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class InlineEditPromptFactory {

    private static final List<String> QUICK_PREFIXES = List.of(
            "refactor",
            "rewrite",
            "fix",
            "improve",
            "rename",
            "extract",
            "optimize",
            "document",
            "comment",
            "clean up",
            "cleanup",
            "add validation",
            "add tests");

    public String buildPrompt(String selectedText, String instruction) {
        String safeSelection = selectedText != null ? selectedText.trim() : "";
        String safeInstruction = instruction != null ? instruction.trim() : "";
        String normalizedInstruction = safeInstruction.toLowerCase(Locale.ROOT);
        String instructionLine = QUICK_PREFIXES.stream().anyMatch(normalizedInstruction::startsWith)
                ? safeInstruction
                : "Refactor this code: " + safeInstruction;
        return String.join("\n",
                "You are an inline code editing assistant.",
                "Return only the replacement code for the selected snippet.",
                "Do not wrap the answer in markdown fences.",
                "Do not explain the change.",
                "Preserve the surrounding style and indentation when possible.",
                "",
                "Instruction:",
                instructionLine,
                "",
                "Selected code:",
                safeSelection);
    }
}
