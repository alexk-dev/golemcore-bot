package me.golemcore.bot.domain.selfevolving.tactic;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TacticQueryExpansionServiceTest {

    private TacticQueryExpansionService service;

    @BeforeEach
    void setUp() {
        service = new TacticQueryExpansionService();
    }

    @Test
    void shouldExpandIntentDomainToolAndFailureViewsFromRawQuery() {
        TacticSearchQuery query = service.expand("Fix Docker Maven failure with git rollback");

        assertEquals("fix docker maven failure with git rollback", query.getRawQuery());
        assertEquals("fix docker maven failure with git rollback", query.getViewQueries().get("intent"));
        assertEquals("fix docker maven failure git rollback", query.getViewQueries().get("domain"));
        assertEquals("docker maven git", query.getViewQueries().get("tool"));
        assertEquals("fix failure rollback", query.getViewQueries().get("failure-recovery"));
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
}
