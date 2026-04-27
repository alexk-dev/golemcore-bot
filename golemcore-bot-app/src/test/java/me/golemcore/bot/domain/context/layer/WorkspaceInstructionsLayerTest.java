package me.golemcore.bot.domain.context.layer;

import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.workspace.WorkspaceInstructionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceInstructionsLayerTest {

    private WorkspaceInstructionService workspaceInstructionService;
    private WorkspaceInstructionsLayer layer;

    @BeforeEach
    void setUp() {
        workspaceInstructionService = mock(WorkspaceInstructionService.class);
        layer = new WorkspaceInstructionsLayer(workspaceInstructionService);
    }

    @Test
    void shouldAlwaysApply() {
        assertTrue(layer.appliesTo(AgentContext.builder().build()));
    }

    @Test
    void shouldRenderWorkspaceInstructions() {
        when(workspaceInstructionService.getWorkspaceInstructionsContext())
                .thenReturn("## CLAUDE.md\nUse Java 17.");

        ContextLayerResult result = layer.assemble(AgentContext.builder().build());

        assertTrue(result.hasContent());
        assertTrue(result.getContent().contains("# Workspace Instructions"));
        assertTrue(result.getContent().contains("Use Java 17."));
    }

    @Test
    void shouldReturnEmptyWhenNoInstructions() {
        when(workspaceInstructionService.getWorkspaceInstructionsContext()).thenReturn("");

        ContextLayerResult result = layer.assemble(AgentContext.builder().build());

        assertFalse(result.hasContent());
    }

    @Test
    void shouldHaveCorrectNameAndOrder() {
        assertEquals("workspace_instructions", layer.getName());
        assertEquals(20, layer.getOrder());
    }
}
