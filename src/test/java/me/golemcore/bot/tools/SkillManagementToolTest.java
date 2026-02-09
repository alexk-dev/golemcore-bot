package me.golemcore.bot.tools;

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillManagementToolTest {

    private static final String OPERATION = "operation";
    private static final String NAME = "name";
    private static final String DESCRIPTION = "description";
    private static final String CONTENT = "content";
    private static final String CREATE_SKILL = "create_skill";
    private static final String GREETING = "greeting";
    private static final String NONEXISTENT = "nonexistent";
    private static final String TEST = "test";

    private StoragePort storagePort;
    private SkillComponent skillComponent;
    private SkillManagementTool tool;

    @BeforeEach
    void setUp() {
        storagePort = mock(StoragePort.class);
        skillComponent = mock(SkillComponent.class);

        BotProperties properties = new BotProperties();
        properties.getTools().getSkillManagement().setEnabled(true);

        tool = new SkillManagementTool(properties, storagePort, skillComponent);
    }

    @Test
    void createSkill() throws Exception {
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        ToolResult result = tool.execute(Map.of(
                OPERATION, CREATE_SKILL,
                NAME, GREETING,
                DESCRIPTION, "Responds with a greeting",
                CONTENT, "When the user says hello, respond warmly.")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains(GREETING));
        verify(storagePort).putText(eq("skills"), eq("greeting/SKILL.md"), contains("name: greeting"));
        verify(skillComponent).reload();
    }

    @Test
    void createSkillWithHyphenatedName() throws Exception {
        when(storagePort.putText(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        ToolResult result = tool.execute(Map.of(
                OPERATION, CREATE_SKILL,
                NAME, "code-review",
                DESCRIPTION, "Reviews code",
                CONTENT, "Review the code carefully.")).get();

        assertTrue(result.isSuccess());
    }

    @Test
    void createSkillInvalidName() throws Exception {
        // Uppercase not allowed
        ToolResult result1 = tool.execute(Map.of(
                OPERATION, CREATE_SKILL,
                NAME, "MySkill",
                DESCRIPTION, TEST,
                CONTENT, TEST)).get();
        assertFalse(result1.isSuccess());
        assertTrue(result1.getError().contains("Invalid skill name"));

        // Spaces not allowed
        ToolResult result2 = tool.execute(Map.of(
                OPERATION, CREATE_SKILL,
                NAME, "my skill",
                DESCRIPTION, TEST,
                CONTENT, TEST)).get();
        assertFalse(result2.isSuccess());

        // Starting with hyphen not allowed
        ToolResult result3 = tool.execute(Map.of(
                OPERATION, CREATE_SKILL,
                NAME, "-bad",
                DESCRIPTION, TEST,
                CONTENT, TEST)).get();
        assertFalse(result3.isSuccess());
    }

    @Test
    void createSkillMissingFields() throws Exception {
        // Missing name
        ToolResult r1 = tool.execute(Map.of(
                OPERATION, CREATE_SKILL,
                DESCRIPTION, TEST,
                CONTENT, TEST)).get();
        assertFalse(r1.isSuccess());

        // Missing description
        ToolResult r2 = tool.execute(Map.of(
                OPERATION, CREATE_SKILL,
                NAME, TEST,
                CONTENT, TEST)).get();
        assertFalse(r2.isSuccess());

        // Missing content
        ToolResult r3 = tool.execute(Map.of(
                OPERATION, CREATE_SKILL,
                NAME, TEST,
                DESCRIPTION, TEST)).get();
        assertFalse(r3.isSuccess());
    }

    @Test
    void listSkills() throws Exception {
        when(skillComponent.getAvailableSkills()).thenReturn(List.of(
                Skill.builder().name(GREETING).description("Greets users").build(),
                Skill.builder().name("code-review").description("Reviews code").build()));

        ToolResult result = tool.execute(Map.of(OPERATION, "list_skills")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains(GREETING));
        assertTrue(result.getOutput().contains("code-review"));
        assertTrue(result.getOutput().contains("2"));
    }

    @Test
    void listSkillsEmpty() throws Exception {
        when(skillComponent.getAvailableSkills()).thenReturn(List.of());

        ToolResult result = tool.execute(Map.of(OPERATION, "list_skills")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("No skills"));
    }

    @Test
    void getSkill() throws Exception {
        Skill skill = Skill.builder()
                .name(GREETING)
                .description("Greets users")
                .content("Say hello warmly.")
                .available(true)
                .build();
        when(skillComponent.findByName(GREETING)).thenReturn(Optional.of(skill));

        ToolResult result = tool.execute(Map.of(
                OPERATION, "get_skill",
                NAME, GREETING)).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains(GREETING));
        assertTrue(result.getOutput().contains("Say hello warmly."));
    }

    @Test
    void getSkillNotFound() throws Exception {
        when(skillComponent.findByName(NONEXISTENT)).thenReturn(Optional.empty());

        ToolResult result = tool.execute(Map.of(
                OPERATION, "get_skill",
                NAME, NONEXISTENT)).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("not found"));
    }

    @Test
    void deleteSkill() throws Exception {
        Skill skill = Skill.builder().name(GREETING).description("Greets").build();
        when(skillComponent.findByName(GREETING)).thenReturn(Optional.of(skill));
        when(storagePort.deleteObject(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        ToolResult result = tool.execute(Map.of(
                OPERATION, "delete_skill",
                NAME, GREETING)).get();

        assertTrue(result.isSuccess());
        verify(storagePort).deleteObject("skills", "greeting/SKILL.md");
        verify(skillComponent).reload();
    }

    @Test
    void deleteSkillNotFound() throws Exception {
        when(skillComponent.findByName(NONEXISTENT)).thenReturn(Optional.empty());

        ToolResult result = tool.execute(Map.of(
                OPERATION, "delete_skill",
                NAME, NONEXISTENT)).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("not found"));
    }

    @Test
    void disabledTool() throws Exception {
        BotProperties props = new BotProperties();
        props.getTools().getSkillManagement().setEnabled(false);
        SkillManagementTool disabledTool = new SkillManagementTool(props, storagePort, skillComponent);

        ToolResult result = disabledTool.execute(Map.of(OPERATION, "list_skills")).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("disabled"));
    }

    @Test
    void missingOperation() throws Exception {
        ToolResult result = tool.execute(Map.of(NAME, TEST)).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(OPERATION));
    }

    @Test
    void unknownOperation() throws Exception {
        ToolResult result = tool.execute(Map.of(OPERATION, "unknown_op")).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Unknown operation"));
    }

    @Test
    void formatSkillMd() {
        String result = SkillManagementTool.formatSkillMd(GREETING, "Greets users", "Say hello.");
        assertTrue(result.startsWith("---\n"));
        assertTrue(result.contains("name: greeting"));
        assertTrue(result.contains("description: Greets users"));
        assertTrue(result.contains("Say hello."));
    }
}
