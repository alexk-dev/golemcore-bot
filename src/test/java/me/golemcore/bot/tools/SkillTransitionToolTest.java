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

    private static final String TARGET_SKILL = "target_skill";
    private static final String CODE_WRITER = "code-writer";
    private static final String ANALYZER = "analyzer";

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
                .name(CODE_WRITER)
                .description("Write code")
                .available(true)
                .build();
        when(skillComponent.findByName(CODE_WRITER)).thenReturn(Optional.of(targetSkill));

        ToolResult result = tool.execute(Map.of(
                TARGET_SKILL, CODE_WRITER,
                "reason", "Need to generate code")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains(CODE_WRITER));

        // Verify the context was updated
        AgentContext ctx = AgentContextHolder.get();
        var req = ctx.getSkillTransitionRequest();
        assertEquals(CODE_WRITER, req.targetSkill());
    }

    @Test
    void transitionWithoutReason() throws Exception {
        setUpAgentContext();

        Skill targetSkill = Skill.builder()
                .name(ANALYZER)
                .description("Analyze things")
                .available(true)
                .build();
        when(skillComponent.findByName(ANALYZER)).thenReturn(Optional.of(targetSkill));

        ToolResult result = tool.execute(Map.of(
                TARGET_SKILL, ANALYZER)).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains(ANALYZER));
    }

    @Test
    void skillNotFound() throws Exception {
        setUpAgentContext();

        when(skillComponent.findByName("nonexistent")).thenReturn(Optional.empty());

        ToolResult result = tool.execute(Map.of(
                TARGET_SKILL, "nonexistent")).get();

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
                TARGET_SKILL, "broken-skill")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("unavailable"));
    }

    @Test
    void missingTargetSkill() throws Exception {
        setUpAgentContext();

        ToolResult result = tool.execute(Map.of(
                "reason", "just because")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(TARGET_SKILL));
    }

    @Test
    void blankTargetSkill() throws Exception {
        setUpAgentContext();

        ToolResult result = tool.execute(Map.of(
                TARGET_SKILL, "   ")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(TARGET_SKILL));
    }

    @Test
    void noAgentContext() throws Exception {
        // Don't set up context — AgentContextHolder is empty
        Skill targetSkill = Skill.builder()
                .name(CODE_WRITER)
                .description("Write code")
                .available(true)
                .build();
        when(skillComponent.findByName(CODE_WRITER)).thenReturn(Optional.of(targetSkill));

        ToolResult result = tool.execute(Map.of(
                TARGET_SKILL, CODE_WRITER)).get();

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
