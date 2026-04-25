package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.service.ContextCompactionPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextBudgetResolverAndGarbagePolicyTest {

    @Test
    void shouldResolveUnlimitedBudgetWhenCompactionPolicyIsUnlimited() {
        ContextCompactionPolicy policy = mock(ContextCompactionPolicy.class);
        AgentContext context = AgentContext.builder().build();
        when(policy.resolveFullRequestThreshold(context)).thenReturn(Integer.MAX_VALUE);

        ContextBudget budget = new DefaultContextBudgetResolver(policy).resolve(context, "model");

        assertTrue(budget.isUnlimited());
        assertEquals(Integer.MAX_VALUE, budget.inputTokens());
        assertEquals(Integer.MAX_VALUE, budget.toolResultTokens());
    }

    @Test
    void shouldResolveLimitedBudgetWithReservedSystemPromptTokens() {
        ContextCompactionPolicy policy = mock(ContextCompactionPolicy.class);
        AgentContext context = AgentContext.builder()
                .systemPrompt(repeated("system", 2_000))
                .build();
        when(policy.resolveFullRequestThreshold(context)).thenReturn(10_000);
        when(policy.resolveSystemPromptThreshold(context)).thenReturn(1_000);

        ContextBudget budget = new DefaultContextBudgetResolver(policy).resolve(context, "model");

        assertEquals(10_000, budget.inputTokens());
        assertEquals(1_000, budget.systemPromptTokens());
        assertEquals(4_500, budget.conversationTokens());
        assertEquals(1_000, budget.toolResultTokens());
        assertFalse(budget.isUnlimited());
    }

    @Test
    void shouldNotForceMinimumConversationBudgetWhenRequestOverheadConsumesWindow() {
        ContextCompactionPolicy policy = mock(ContextCompactionPolicy.class);
        AgentContext context = AgentContext.builder()
                .systemPrompt("")
                .build();
        when(policy.resolveFullRequestThreshold(context)).thenReturn(600);
        when(policy.resolveSystemPromptThreshold(context)).thenReturn(300);

        ContextBudget budget = new DefaultContextBudgetResolver(policy).resolve(context, "model");

        assertEquals(280, budget.conversationTokens());
        assertEquals(256, budget.toolResultTokens());
    }

    @Test
    void shouldReserveBudgetForProviderSerializedToolSchemas() {
        ContextCompactionPolicy policy = mock(ContextCompactionPolicy.class);
        AgentContext context = AgentContext.builder()
                .systemPrompt("")
                .availableTools(List.of(ToolDefinition.simple("browser", repeated("Search the web", 20_000))))
                .build();
        when(policy.resolveFullRequestThreshold(context)).thenReturn(6_000);
        when(policy.resolveSystemPromptThreshold(context)).thenReturn(1_000);

        ContextBudget budget = new DefaultContextBudgetResolver(policy).resolve(context, "model");

        assertTrue(budget.conversationTokens() < 512,
                "degraded mode should not hide request overhead behind the old minimum");
        assertTrue(budget.conversationTokens() >= 1);
    }

    @Test
    void shouldClassifyNoisyAndPlainToolResults() {
        ContextGarbagePolicy policy = new ContextGarbagePolicy();

        assertEquals(GarbageReason.BUDGET_EXCEEDED, policy.reasonFor(null));
        assertEquals(GarbageReason.BUDGET_EXCEEDED, policy.reasonFor(tool("")));
        assertEquals(GarbageReason.LARGE_JSON_OR_HTML, policy.reasonFor(tool("<!doctype html><body>hello</body>")));
        assertEquals(GarbageReason.LARGE_JSON_OR_HTML,
                policy.reasonFor(tool("  {\"items\":[" + repeated("1,", 2_200))));
        assertEquals(GarbageReason.BASE64_OR_BINARY, policy.reasonFor(tool(repeated("QUJD", 900))));
        assertEquals(GarbageReason.RAW_TOOL_BLOB, policy.reasonFor(tool(repeated("plain tool output", 2_200))));
        assertEquals(GarbageReason.BUDGET_EXCEEDED, policy.reasonFor(tool("small result")));

        assertTrue(policy.isNoisyToolResult(tool(repeated("plain tool output", 2_200))));
        assertFalse(policy.isNoisyToolResult(null));
        assertFalse(policy.isNoisyToolResult(assistant(repeated("plain assistant output", 2_200))));
        assertFalse(policy.isNoisyToolResult(tool("small result")));
    }

    private static Message tool(String content) {
        return Message.builder()
                .role("tool")
                .toolCallId("call-1")
                .toolName("tool")
                .content(content)
                .build();
    }

    private static Message assistant(String content) {
        return Message.builder()
                .role("assistant")
                .content(content)
                .build();
    }

    private static String repeated(String value, int targetChars) {
        StringBuilder builder = new StringBuilder(targetChars);
        while (builder.length() < targetChars) {
            builder.append(value);
        }
        return builder.toString();
    }
}
