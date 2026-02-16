package me.golemcore.bot.port.outbound;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Port for storing dynamic runtime module configs (plugin-like settings).
 *
 * Allows arbitrary JSON payload per module id to support NoSQL-style schema
 * flexibility.
 */
public interface RuntimeModuleConfigPort {

    Map<String, JsonNode> loadAll();

    void saveAll(Map<String, JsonNode> modules);
}
