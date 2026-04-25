package me.golemcore.bot.port.outbound;

/**
 * Query-side model tier resolution used by capability modules.
 */
public interface ModelSelectionQueryPort {

    ModelSelection resolveExplicitSelection(String tier);

    record ModelSelection(String model, String reasoning) {
    }
}
