package me.golemcore.bot.tools;

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.loop.AgentContextHolder;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillTransitionToolTest {

    private SkillComponent skillComponent;
    private SkillTransitionTool tool;

    @BeforeEach
    void setUp() {
        skillComponent = mock(SkillComponent.class);
        BotProperties properties = new BotProperties();
        properties.getTools().getSkillTransition().setEnabled(true);
        ObjectMapper objectMapper = new ObjectMapper();

        tool = new SkillTransitionTool(skillComponent, properties, objectMapper);
    }

    @AfterEach
    void tearDown() {
        AgentContextHolder.clear();
    }

    private void setUpAgentContext() {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().chatId("ch1").build())
                .build();
        AgentContextHolder.set(context);
    }

    @Test
    void getDefinition() {
        var def = tool.getDefinition();
        assertEquals("skill_transition", def.getName());
        assertNotNull(def.getInputSchema());
        assertTrue(def.getDescription().contains("Transition"));
    }

    @Test
    void validTransition() throws Exception {
        setUpAgentContext();

        Skill targetSkill = Skill.builder()
                .name("code-writer")
                .description("Write code")
                .available(true)
                .build();
        when(skillComponent.findByName("code-writer")).thenReturn(Optional.of(targetSkill));

        ToolResult result = tool.execute(Map.of(
                "target_skill", "code-writer",
                "reason", "Need to generate code")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("code-writer"));

        // Verify the context was updated
        AgentContext ctx = AgentContextHolder.get();
        assertEquals("code-writer", ctx.getAttribute("skill.transition.target"));
    }

    @Test
    void transitionWithoutReason() throws Exception {
        setUpAgentContext();

        Skill targetSkill = Skill.builder()
                .name("analyzer")
                .description("Analyze things")
                .available(true)
                .build();
        when(skillComponent.findByName("analyzer")).thenReturn(Optional.of(targetSkill));

        ToolResult result = tool.execute(Map.of(
                "target_skill", "analyzer")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("analyzer"));
    }

    @Test
    void skillNotFound() throws Exception {
        setUpAgentContext();

        when(skillComponent.findByName("nonexistent")).thenReturn(Optional.empty());

        ToolResult result = tool.execute(Map.of(
                "target_skill", "nonexistent")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("not found"));
    }

    @Test
    void skillUnavailable() throws Exception {
        setUpAgentContext();

        Skill unavailableSkill = Skill.builder()
                .name("broken-skill")
                .description("Broken")
                .available(false)
                .build();
        when(skillComponent.findByName("broken-skill")).thenReturn(Optional.of(unavailableSkill));

        ToolResult result = tool.execute(Map.of(
                "target_skill", "broken-skill")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("unavailable"));
    }

    @Test
    void missingTargetSkill() throws Exception {
        setUpAgentContext();

        ToolResult result = tool.execute(Map.of(
                "reason", "just because")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("target_skill"));
    }

    @Test
    void blankTargetSkill() throws Exception {
        setUpAgentContext();

        ToolResult result = tool.execute(Map.of(
                "target_skill", "   ")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("target_skill"));
    }

    @Test
    void noAgentContext() throws Exception {
        // Don't set up context — AgentContextHolder is empty
        Skill targetSkill = Skill.builder()
                .name("code-writer")
                .description("Write code")
                .available(true)
                .build();
        when(skillComponent.findByName("code-writer")).thenReturn(Optional.of(targetSkill));

        ToolResult result = tool.execute(Map.of(
                "target_skill", "code-writer")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("No agent context"));
    }

    @Test
    void isEnabled() {
        assertTrue(tool.isEnabled());
    }

    @Test
    void disabledTool() {
        BotProperties properties = new BotProperties();
        properties.getTools().getSkillTransition().setEnabled(false);
        SkillTransitionTool disabledTool = new SkillTransitionTool(
                skillComponent, properties, new ObjectMapper());

        assertFalse(disabledTool.isEnabled());
    }

    @Test
    void toolName() {
        assertEquals("skill_transition", tool.getToolName());
    }
}
