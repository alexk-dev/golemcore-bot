package me.golemcore.bot.domain.selfevolving.tactic;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import me.golemcore.bot.port.outbound.ModelSelectionQueryPort;
import me.golemcore.bot.port.outbound.SelfEvolvingRuntimeConfigPort;
import me.golemcore.bot.port.outbound.LlmPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TacticQueryExpansionServiceTest {

    private SelfEvolvingRuntimeConfigPort runtimeConfigPort;
    private ModelSelectionQueryPort modelSelectionQueryPort;
    private LlmPort llmPort;
    private TacticQueryExpansionService service;

    @BeforeEach
    void setUp() {
        runtimeConfigPort = mock(SelfEvolvingRuntimeConfigPort.class);
        modelSelectionQueryPort = mock(ModelSelectionQueryPort.class);
        llmPort = mock(LlmPort.class);
        when(runtimeConfigPort.isTacticQueryExpansionEnabled()).thenReturn(false);
        service = new TacticQueryExpansionService(runtimeConfigPort, modelSelectionQueryPort, llmPort,
                new ObjectMapper());
    }

    @Test
    void shouldExpandIntentDomainToolAndFailureViewsFromRawQuery() {
        TacticSearchQuery query = service.expand("Fix Docker Maven failure with git rollback");

        assertEquals("fix docker maven failure with git rollback", query.getRawQuery());
        assertEquals("fix docker maven failure with git rollback", query.getViewQueries().get("intent"));
        assertEquals("fix docker maven failure git rollback", query.getViewQueries().get("domain"));
        assertEquals("docker maven git", query.getViewQueries().get("tool"));
        assertEquals("fix failure rollback", query.getViewQueries().get("failure-recovery"));
        assertTrue(query.getViewQueries().containsKey("phase"));
        assertTrue(query.getQueryViews().containsAll(List.of(
                "fix",
                "docker",
                "maven",
                "failure",
                "git",
                "rollback")));
    }

    @Test
    void shouldExpandFromLastUserMessageAndAttachToolsAndGolemId() {
        ToolDefinition shellTool = ToolDefinition.builder().name("shell").build();
        ToolDefinition mavenTool = ToolDefinition.builder().name("maven").build();
        AgentContext context = AgentContext.builder()
                .messages(List.of(
                        Message.builder().role("system").content("ignore").build(),
                        Message.builder().role("user").content("first prompt").build(),
                        Message.builder().role("assistant").content("working").build(),
                        Message.builder().role("user").content("Recover git failure with shell").build()))
                .availableTools(List.of(shellTool, mavenTool))
                .build();
        context.setAttribute(ContextAttributes.HIVE_GOLEM_ID, "golem-42");

        TacticSearchQuery query = service.expand(context);

        assertEquals("recover git failure with shell", query.getRawQuery());
        assertEquals(List.of("shell", "maven"), query.getAvailableTools());
        assertEquals("golem-42", query.getGolemId());
        assertEquals("git shell", query.getViewQueries().get("tool"));
        assertEquals("recover failure", query.getViewQueries().get("failure-recovery"));
        assertEquals("recovery", query.getExecutionPhase());
    }

    @Test
    void shouldFallbackToBlankQueryWhenContextHasNoUsableUserMessage() {
        Message internalUserMessage = Message.builder()
                .role("user")
                .content("internal guidance")
                .metadata(Map.of(ContextAttributes.MESSAGE_INTERNAL, true))
                .build();
        AgentContext context = AgentContext.builder()
                .messages(List.of(
                        Message.builder().role("assistant").content("working").build(),
                        internalUserMessage))
                .build();

        TacticSearchQuery query = service.expand(context);

        assertEquals("", query.getRawQuery());
        assertTrue(query.getQueryViews().isEmpty());
        assertTrue(query.getViewQueries().containsKey("intent"));
        assertEquals("", query.getViewQueries().get("intent"));
    }

    @Test
    void shouldDetectRecoveryPhaseFromFailureTerms() {
        TacticSearchQuery query = service.expand("Fix Docker Maven failure with git rollback");

        assertEquals("recovery", query.getExecutionPhase());
        assertTrue(query.getViewQueries().containsKey("phase"));
        assertTrue(query.getQueryViews().contains("recovery"));
    }

    @Test
    void shouldDetectPlanningPhaseFromPlanningTerms() {
        TacticSearchQuery query = service.expand("Plan the architecture for new service design");

        assertEquals("planning", query.getExecutionPhase());
        assertTrue(query.getViewQueries().containsKey("phase"));
    }

    @Test
    void shouldDetectOptimizationPhaseFromOptimizationTerms() {
        TacticSearchQuery query = service.expand("Optimize and refactor the search performance");

        assertEquals("optimization", query.getExecutionPhase());
        assertTrue(query.getViewQueries().containsKey("phase"));
    }

    @Test
    void shouldDefaultToExecutionPhaseWhenNoStrongSignal() {
        TacticSearchQuery query = service.expand("Run the deploy script for production");

        assertEquals("execution", query.getExecutionPhase());
    }

    @Test
    void shouldReturnEmptyQueryViewsWhenInputIsBlank() {
        TacticSearchQuery query = service.expand("");

        assertEquals("", query.getRawQuery());
        assertTrue(query.getQueryViews().isEmpty());
        assertNull(query.getExecutionPhase());
    }

    @Test
    void shouldReturnEmptyQueryViewsWhenInputIsNull() {
        TacticSearchQuery query = service.expand((String) null);

        assertEquals("", query.getRawQuery());
        assertTrue(query.getQueryViews().isEmpty());
    }

    @Test
    void shouldProduceOnlyPhaseInQueryViewsWhenAllTokensAreStopWordsOrShort() {
        TacticSearchQuery query = service.expand("a the is to");

        assertEquals("a the is to", query.getRawQuery());
        // All tokens are stop words or short, so domainTerms/toolTerms/failureTerms
        // empty
        // But detectPhase returns "execution" since tokens are non-empty and no phase
        // matches
        assertEquals("execution", query.getExecutionPhase());
        assertTrue(query.getQueryViews().contains("execution"));
    }

    @Test
    void shouldExpandContextWithNullContext() {
        TacticSearchQuery query = service.expand((AgentContext) null);

        assertEquals("", query.getRawQuery());
        assertNull(query.getGolemId());
    }

    @Test
    void shouldExpandContextWithNullMessages() {
        AgentContext context = AgentContext.builder()
                .messages(null)
                .build();

        TacticSearchQuery query = service.expand(context);

        assertEquals("", query.getRawQuery());
    }

    @Test
    void shouldExpandContextWithEmptyMessages() {
        AgentContext context = AgentContext.builder()
                .messages(List.of())
                .build();

        TacticSearchQuery query = service.expand(context);

        assertEquals("", query.getRawQuery());
    }

    @Test
    void shouldExpandContextWithNullAvailableTools() {
        AgentContext context = AgentContext.builder()
                .messages(List.of(
                        Message.builder().role("user").content("test query").build()))
                .availableTools(null)
                .build();

        TacticSearchQuery query = service.expand(context);

        assertEquals("test query", query.getRawQuery());
        assertTrue(query.getAvailableTools().isEmpty());
    }

    @Test
    void shouldExpandContextWithEmptyAvailableTools() {
        AgentContext context = AgentContext.builder()
                .messages(List.of(
                        Message.builder().role("user").content("test query").build()))
                .availableTools(List.of())
                .build();

        TacticSearchQuery query = service.expand(context);

        assertTrue(query.getAvailableTools().isEmpty());
    }

    @Test
    void shouldFilterBlankToolNamesFromContext() {
        ToolDefinition blankTool = ToolDefinition.builder().name("  ").build();
        ToolDefinition nullTool = ToolDefinition.builder().name(null).build();
        ToolDefinition validTool = ToolDefinition.builder().name("shell").build();
        AgentContext context = AgentContext.builder()
                .messages(List.of(
                        Message.builder().role("user").content("test query").build()))
                .availableTools(List.of(blankTool, nullTool, validTool))
                .build();

        TacticSearchQuery query = service.expand(context);

        assertEquals(List.of("shell"), query.getAvailableTools());
    }

    @Test
    void shouldSkipNullMessagesWhenFindingLastUserMessage() {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().role("user").content("first").build());
        messages.add(null);
        AgentContext context = AgentContext.builder()
                .messages(messages)
                .build();

        TacticSearchQuery query = service.expand(context);

        assertEquals("first", query.getRawQuery());
    }

    @Test
    void shouldSkipUserMessagesWithBlankContent() {
        AgentContext context = AgentContext.builder()
                .messages(List.of(
                        Message.builder().role("user").content("first real message").build(),
                        Message.builder().role("user").content("  ").build()))
                .build();

        TacticSearchQuery query = service.expand(context);

        assertEquals("first real message", query.getRawQuery());
    }

    @Test
    void shouldNotIncludeDomainViewWhenAllTokensAreShortOrStopWords() {
        TacticSearchQuery query = service.expand("is at by");

        assertFalse(query.getViewQueries().containsKey("domain"));
    }

    @Test
    void shouldNotIncludeToolViewWhenNoToolTermsPresent() {
        TacticSearchQuery query = service.expand("deploy the application");

        assertFalse(query.getViewQueries().containsKey("tool"));
    }

    @Test
    void shouldNotIncludeFailureViewWhenNoFailureTermsPresent() {
        TacticSearchQuery query = service.expand("deploy the application");

        assertFalse(query.getViewQueries().containsKey("failure-recovery"));
    }

    @Test
    void shouldAddFailureMarkerWhenFailureTermsDetected() {
        TacticSearchQuery query = service.expand("retry the broken build");

        assertTrue(query.getViewQueries().containsKey("failure-recovery"));
        String failureView = query.getViewQueries().get("failure-recovery");
        assertTrue(failureView.contains("failure"));
        assertTrue(failureView.contains("retry"));
        assertTrue(failureView.contains("broken"));
    }

    @Test
    void shouldExpandViaLlmWhenEnabled() {
        when(runtimeConfigPort.isTacticQueryExpansionEnabled()).thenReturn(true);
        when(runtimeConfigPort.getTacticQueryExpansionTier()).thenReturn("fast");
        when(modelSelectionQueryPort.resolveExplicitSelection("fast"))
                .thenReturn(new ModelSelectionQueryPort.ModelSelection("gpt-4", "low"));
        LlmResponse llmResponse = LlmResponse.builder()
                .content("[\"docker deployment tactic\", \"container orchestration\"]")
                .build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(llmResponse));

        TacticSearchQuery query = service.expand("deploy docker containers");

        assertTrue(query.getViewQueries().containsKey("llm-expansion-0"));
        assertTrue(query.getViewQueries().containsKey("llm-expansion-1"));
        assertEquals("docker deployment tactic", query.getViewQueries().get("llm-expansion-0"));
    }

    @Test
    void shouldReturnCachedLlmExpansionOnSecondCall() {
        when(runtimeConfigPort.isTacticQueryExpansionEnabled()).thenReturn(true);
        when(runtimeConfigPort.getTacticQueryExpansionTier()).thenReturn("fast");
        when(modelSelectionQueryPort.resolveExplicitSelection("fast"))
                .thenReturn(new ModelSelectionQueryPort.ModelSelection("gpt-4", "low"));
        LlmResponse llmResponse = LlmResponse.builder()
                .content("[\"cached expansion\"]")
                .build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(llmResponse));

        TacticSearchQuery firstResult = service.expand("unique query for caching");
        TacticSearchQuery secondResult = service.expand("unique query for caching");

        assertEquals(firstResult.getViewQueries().get("llm-expansion-0"),
                secondResult.getViewQueries().get("llm-expansion-0"));
    }

    @Test
    void shouldHandleNullLlmResponse() {
        when(runtimeConfigPort.isTacticQueryExpansionEnabled()).thenReturn(true);
        when(runtimeConfigPort.getTacticQueryExpansionTier()).thenReturn("fast");
        when(modelSelectionQueryPort.resolveExplicitSelection("fast"))
                .thenReturn(new ModelSelectionQueryPort.ModelSelection("gpt-4", "low"));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(null));

        TacticSearchQuery query = service.expand("test null response");

        assertFalse(query.getViewQueries().containsKey("llm-expansion-0"));
    }

    @Test
    void shouldHandleBlankLlmResponseContent() {
        when(runtimeConfigPort.isTacticQueryExpansionEnabled()).thenReturn(true);
        when(runtimeConfigPort.getTacticQueryExpansionTier()).thenReturn("fast");
        when(modelSelectionQueryPort.resolveExplicitSelection("fast"))
                .thenReturn(new ModelSelectionQueryPort.ModelSelection("gpt-4", "low"));
        LlmResponse llmResponse = LlmResponse.builder().content("  ").build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(llmResponse));

        TacticSearchQuery query = service.expand("test blank response");

        assertFalse(query.getViewQueries().containsKey("llm-expansion-0"));
    }

    @Test
    void shouldHandleLlmExceptionGracefully() {
        when(runtimeConfigPort.isTacticQueryExpansionEnabled()).thenReturn(true);
        when(runtimeConfigPort.getTacticQueryExpansionTier()).thenReturn("fast");
        when(modelSelectionQueryPort.resolveExplicitSelection("fast"))
                .thenReturn(new ModelSelectionQueryPort.ModelSelection("gpt-4", "low"));
        when(llmPort.chat(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("LLM error")));

        TacticSearchQuery query = service.expand("test exception handling");

        assertNotNull(query);
        assertFalse(query.getViewQueries().containsKey("llm-expansion-0"));
    }

    @Test
    void shouldHandleMalformedJsonFromLlm() {
        when(runtimeConfigPort.isTacticQueryExpansionEnabled()).thenReturn(true);
        when(runtimeConfigPort.getTacticQueryExpansionTier()).thenReturn("fast");
        when(modelSelectionQueryPort.resolveExplicitSelection("fast"))
                .thenReturn(new ModelSelectionQueryPort.ModelSelection("gpt-4", "low"));
        LlmResponse llmResponse = LlmResponse.builder().content("not valid json at all").build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(llmResponse));

        TacticSearchQuery query = service.expand("test malformed json");

        assertFalse(query.getViewQueries().containsKey("llm-expansion-0"));
    }

    @Test
    void shouldParseJsonWithSurroundingText() {
        when(runtimeConfigPort.isTacticQueryExpansionEnabled()).thenReturn(true);
        when(runtimeConfigPort.getTacticQueryExpansionTier()).thenReturn("fast");
        when(modelSelectionQueryPort.resolveExplicitSelection("fast"))
                .thenReturn(new ModelSelectionQueryPort.ModelSelection("gpt-4", "low"));
        LlmResponse llmResponse = LlmResponse.builder()
                .content("Here are the queries: [\"expanded query one\", \"expanded query two\"] hope this helps")
                .build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(llmResponse));

        TacticSearchQuery query = service.expand("test json extraction");

        assertTrue(query.getViewQueries().containsKey("llm-expansion-0"));
        assertEquals("expanded query one", query.getViewQueries().get("llm-expansion-0"));
    }

    @Test
    void shouldLimitLlmExpansionsToThree() {
        when(runtimeConfigPort.isTacticQueryExpansionEnabled()).thenReturn(true);
        when(runtimeConfigPort.getTacticQueryExpansionTier()).thenReturn("fast");
        when(modelSelectionQueryPort.resolveExplicitSelection("fast"))
                .thenReturn(new ModelSelectionQueryPort.ModelSelection("gpt-4", "low"));
        LlmResponse llmResponse = LlmResponse.builder()
                .content("[\"one\", \"two\", \"three\", \"four\", \"five\"]")
                .build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(llmResponse));

        TacticSearchQuery query = service.expand("test limit expansions");

        assertTrue(query.getViewQueries().containsKey("llm-expansion-0"));
        assertTrue(query.getViewQueries().containsKey("llm-expansion-1"));
        assertTrue(query.getViewQueries().containsKey("llm-expansion-2"));
        assertFalse(query.getViewQueries().containsKey("llm-expansion-3"));
    }

    @Test
    void shouldFilterBlankExpansionsFromLlmResponse() {
        when(runtimeConfigPort.isTacticQueryExpansionEnabled()).thenReturn(true);
        when(runtimeConfigPort.getTacticQueryExpansionTier()).thenReturn("fast");
        when(modelSelectionQueryPort.resolveExplicitSelection("fast"))
                .thenReturn(new ModelSelectionQueryPort.ModelSelection("gpt-4", "low"));
        LlmResponse llmResponse = LlmResponse.builder()
                .content("[\"valid query\", \"\", \"  \", \"another valid\"]")
                .build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(llmResponse));

        TacticSearchQuery query = service.expand("test blank filter");

        assertEquals("valid query", query.getViewQueries().get("llm-expansion-0"));
        assertEquals("another valid", query.getViewQueries().get("llm-expansion-1"));
        assertFalse(query.getViewQueries().containsKey("llm-expansion-2"));
    }

    @Test
    void shouldNormalizeWhitespaceInQuery() {
        TacticSearchQuery query = service.expand("  deploy   the   application  ");

        assertEquals("deploy the application", query.getRawQuery());
    }

    @Test
    void shouldDetectNullPhaseWhenTokensAreEmpty() {
        TacticSearchQuery query = service.expand("");

        assertNull(query.getExecutionPhase());
    }

    @Test
    void shouldIncludePhaseInQueryViewsWhenDetected() {
        TacticSearchQuery query = service.expand("plan the new architecture design");

        assertEquals("planning", query.getExecutionPhase());
        assertTrue(query.getQueryViews().contains("planning"));
    }

    @Test
    void shouldAddLlmExpansionTokensToQueryViews() {
        when(runtimeConfigPort.isTacticQueryExpansionEnabled()).thenReturn(true);
        when(runtimeConfigPort.getTacticQueryExpansionTier()).thenReturn("fast");
        when(modelSelectionQueryPort.resolveExplicitSelection("fast"))
                .thenReturn(new ModelSelectionQueryPort.ModelSelection("gpt-4", "low"));
        LlmResponse llmResponse = LlmResponse.builder()
                .content("[\"kubernetes deployment strategy\"]")
                .build();
        when(llmPort.chat(any())).thenReturn(CompletableFuture.completedFuture(llmResponse));

        TacticSearchQuery query = service.expand("deploy containers");

        assertTrue(query.getQueryViews().contains("kubernetes"));
        assertTrue(query.getQueryViews().contains("deployment"));
        assertTrue(query.getQueryViews().contains("strategy"));
    }

    @Test
    void shouldHandleInterruptedExceptionFromLlm() {
        when(runtimeConfigPort.isTacticQueryExpansionEnabled()).thenReturn(true);
        when(runtimeConfigPort.getTacticQueryExpansionTier()).thenReturn("fast");
        when(modelSelectionQueryPort.resolveExplicitSelection("fast"))
                .thenReturn(new ModelSelectionQueryPort.ModelSelection("gpt-4", "low"));
        CompletableFuture<LlmResponse> interruptedFuture = new CompletableFuture<>();
        interruptedFuture.completeExceptionally(new InterruptedException("interrupted"));
        when(llmPort.chat(any())).thenReturn(interruptedFuture);

        TacticSearchQuery query = service.expand("test interrupted");

        assertNotNull(query);
        assertFalse(query.getViewQueries().containsKey("llm-expansion-0"));
    }
}
