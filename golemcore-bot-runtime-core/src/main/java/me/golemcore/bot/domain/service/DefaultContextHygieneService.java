package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributeSpec;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.ContextScope;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Conservative TTL/scope cleanup for transient {@link AgentContext} attributes.
 * <p>
 * Raw session history is intentionally untouched. Cleanup only removes attributes that are request/iteration artifacts
 * and can be rebuilt by later systems.
 */
public class DefaultContextHygieneService implements ContextHygieneService {

    private static final String TOOL_LOOP_EXECUTION_SYSTEM = "ToolLoopExecutionSystem";

    private final Map<String, ContextAttributeSpec> attributeSpecs;

    public DefaultContextHygieneService() {
        this.attributeSpecs = buildSpecs();
    }

    @Override
    public void afterSystem(AgentContext context, String systemName) {
        if (context == null || systemName == null) {
            return;
        }
        if (TOOL_LOOP_EXECUTION_SYSTEM.equals(systemName)) {
            remove(context, ContextAttributes.CONTEXT_SCOPED_TOOLS);
        }
    }

    @Override
    public void afterOuterIteration(AgentContext context) {
        if (context == null || context.getAttributes() == null) {
            return;
        }
        for (ContextAttributeSpec spec : attributeSpecs.values()) {
            if (spec.scope() == ContextScope.ITERATION) {
                remove(context, spec.key());
            }
        }
    }

    @Override
    public void beforePersist(AgentContext context) {
        if (context == null) {
            return;
        }
        remove(context, ContextAttributes.WEB_STREAM_SINK);
        remove(context, ContextAttributes.CONTEXT_SCOPED_TOOLS);
        remove(context, ContextAttributes.TURN_PROGRESS_BUFFER);
        remove(context, ContextAttributes.TURN_PROGRESS_INTENT_PUBLISHED);
    }

    @Override
    public Map<String, ContextAttributeSpec> specs() {
        return attributeSpecs;
    }

    private void remove(AgentContext context, String key) {
        if (context.getAttributes() != null) {
            context.getAttributes().remove(key);
        }
    }

    private Map<String, ContextAttributeSpec> buildSpecs() {
        Map<String, ContextAttributeSpec> values = new LinkedHashMap<>();
        add(values, ContextAttributes.CONTEXT_SCOPED_TOOLS, ContextScope.ITERATION, false, false);
        add(values, ContextAttributes.LLM_REQUEST_PREFLIGHT, ContextScope.ITERATION, false, true);
        add(values, ContextAttributes.LLM_CONTEXT_OVERFLOW_RECOVERY, ContextScope.ITERATION, false, true);
        add(values, ContextAttributes.CONTEXT_HYGIENE_REPORT, ContextScope.ITERATION, false, true);
        add(values, ContextAttributes.LLM_COMPAT_FLATTEN_FALLBACK_USED, ContextScope.ITERATION, false, true);
        add(values, ContextAttributes.TURN_PROGRESS_BUFFER, ContextScope.TURN, false, false);
        add(values, ContextAttributes.TURN_PROGRESS_INTENT_PUBLISHED, ContextScope.TURN, false, false);
        add(values, ContextAttributes.WEB_STREAM_SINK, ContextScope.TURN, false, false);
        add(values, ContextAttributes.ROUTING_OUTCOME, ContextScope.TURN, false, true);
        add(values, ContextAttributes.RUNTIME_EVENTS, ContextScope.TURN, false, true);
        add(values, ContextAttributes.MEMORY_PACK_DIAGNOSTICS, ContextScope.TURN, false, true);
        add(values, ContextAttributes.RAG_CONTEXT, ContextScope.TURN, true, true);
        add(values, ContextAttributes.ACTIVE_SKILL_NAME, ContextScope.TURN, true, true);
        add(values, ContextAttributes.MEMORY_PRESET_ID, ContextScope.SESSION, false, true);
        add(values, ContextAttributes.AUTO_GOAL_ID, ContextScope.SESSION, true, true);
        add(values, ContextAttributes.AUTO_TASK_ID, ContextScope.SESSION, true, true);
        add(values, ContextAttributes.AUTO_SCHEDULED_TASK_ID, ContextScope.SESSION, true, true);
        return Map.copyOf(values);
    }

    private void add(Map<String, ContextAttributeSpec> values, String key, ContextScope scope, boolean promptVisible,
            boolean traceVisible) {
        values.put(key, new ContextAttributeSpec(key, scope, promptVisible, traceVisible));
    }
}
