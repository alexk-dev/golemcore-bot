package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.Message;

import java.util.List;

/**
 * Masks/normalizes tool-call artifacts for a target model/provider.
 */
public interface ToolMessageMasker {

    MaskingResult maskToolMessages(List<Message> rawMessages);

    record MaskingResult(List<Message> messages, List<String> diagnostics) {}
}
