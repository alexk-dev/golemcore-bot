package me.golemcore.bot.adapter.inbound.command;

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.*;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.CompactionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.port.outbound.SessionPort;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.domain.model.UsageStats;
import me.golemcore.bot.port.outbound.UsageTrackingPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandRouterTest {

    private SkillComponent skillComponent;
    private SessionPort sessionService;
    private UsageTrackingPort usageTracker;
    private UserPreferencesService preferencesService;
    private CompactionService compactionService;
    private AutoModeService autoModeService;
    private ApplicationEventPublisher eventPublisher;
    private CommandRouter router;

    private static final Map<String, Object> CTX = Map.of(
            "sessionId", "telegram:12345");

    @BeforeEach
    void setUp() {
        skillComponent = mock(SkillComponent.class);
        sessionService = mock(SessionPort.class);
        usageTracker = mock(UsageTrackingPort.class);
        compactionService = mock(CompactionService.class);

        // Default: pass through message key + args for easy assertion.
        preferencesService = mock(UserPreferencesService.class, inv -> {
            if ("getMessage".equals(inv.getMethod().getName())) {
                Object[] allArgs = inv.getArguments();
                String key = (String) allArgs[0];
                if (allArgs.length > 1) {
                    StringBuilder sb = new StringBuilder(key);
                    Object vararg = allArgs[1];
                    if (vararg instanceof Object[] arr) {
                        for (Object a : arr)
                            sb.append(" ").append(a);
                    } else {
                        for (int i = 1; i < allArgs.length; i++) {
                            sb.append(" ").append(allArgs[i]);
                        }
                    }
                    String result = sb.toString();
                    return result.equals(key) ? key : result;
                }
                return key;
            }
            return RETURNS_DEFAULTS.answer(inv);
        });

        autoModeService = mock(AutoModeService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        ToolComponent tool1 = mockTool("filesystem", "File system operations", true);
        ToolComponent tool2 = mockTool("shell", "Shell command execution", true);
        ToolComponent tool3 = mockTool("disabled-tool", "Disabled tool", false);

        BotProperties properties = new BotProperties();

        router = new CommandRouter(
                skillComponent,
                List.of(tool1, tool2, tool3),
                sessionService,
                usageTracker,
                preferencesService,
                compactionService,
                autoModeService,
                eventPublisher,
                properties);
    }

    private ToolComponent mockTool(String name, String description, boolean enabled) {
        ToolComponent tool = mock(ToolComponent.class);
        when(tool.getDefinition()).thenReturn(ToolDefinition.builder()
                .name(name)
                .description(description)
                .build());
        when(tool.isEnabled()).thenReturn(enabled);
        return tool;
    }

    @Test
    void hasCommand() {
        assertTrue(router.hasCommand("skills"));
        assertTrue(router.hasCommand("tools"));
        assertTrue(router.hasCommand("status"));
        assertTrue(router.hasCommand("new"));
        assertTrue(router.hasCommand("reset"));
        assertTrue(router.hasCommand("compact"));
        assertTrue(router.hasCommand("help"));
        assertTrue(router.hasCommand("auto"));
        assertTrue(router.hasCommand("goals"));
        assertTrue(router.hasCommand("goal"));
        assertTrue(router.hasCommand("diary"));
        assertTrue(router.hasCommand("tasks"));
        assertFalse(router.hasCommand("unknown"));
        assertFalse(router.hasCommand("settings"));
    }

    @Test
    void listCommands() {
        var commands = router.listCommands();
        assertEquals(7, commands.size());
    }

    @Test
    void skillsCommand() throws Exception {
        when(skillComponent.getAvailableSkills()).thenReturn(List.of(
                Skill.builder().name("greeting").description("Greets users").available(true).build(),
                Skill.builder().name("code-review").description("Reviews code").available(true).build()));

        var result = router.execute("skills", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("greeting"));
        assertTrue(result.output().contains("code-review"));
    }

    @Test
    void skillsCommandEmpty() throws Exception {
        when(skillComponent.getAvailableSkills()).thenReturn(List.of());

        var result = router.execute("skills", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.skills.empty"));
    }

    @Test
    void toolsCommand() throws Exception {
        var result = router.execute("tools", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("filesystem"));
        assertTrue(result.output().contains("shell"));
        assertFalse(result.output().contains("disabled-tool"));
    }

    @Test
    void statusCommand() throws Exception {
        when(sessionService.getMessageCount("telegram:12345")).thenReturn(42);

        Map<String, UsageStats> byModel = new LinkedHashMap<>();
        byModel.put("langchain4j/gpt-5.1", UsageStats.builder()
                .totalRequests(8).totalTokens(12300).build());
        byModel.put("langchain4j/gpt-5.2", UsageStats.builder()
                .totalRequests(2).totalTokens(8700).build());
        when(usageTracker.getStatsByModel(any(Duration.class)))
                .thenReturn(byModel);

        var result = router.execute("status", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("42"));
        assertTrue(result.output().contains("gpt-5.1"));
        assertTrue(result.output().contains("gpt-5.2"));
        assertTrue(result.output().contains("12.3K"));
        assertTrue(result.output().contains("8.7K"));
        assertTrue(result.output().contains("21.0K"));
    }

    @Test
    void statusCommandNoUsage() throws Exception {
        when(sessionService.getMessageCount("telegram:12345")).thenReturn(3);
        when(usageTracker.getStatsByModel(any(Duration.class)))
                .thenReturn(Collections.emptyMap());

        var result = router.execute("status", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("3"));
        assertTrue(result.output().contains("command.status.usage.empty"));
    }

    @Test
    void newCommand() throws Exception {
        var result = router.execute("new", List.of(), CTX).get();
        assertTrue(result.success());
        verify(sessionService).clearMessages("telegram:12345");
    }

    @Test
    void resetCommand() throws Exception {
        var result = router.execute("reset", List.of(), CTX).get();
        assertTrue(result.success());
        verify(sessionService).clearMessages("telegram:12345");
    }

    @Test
    void compactWithSummary() throws Exception {
        // Messages to be compacted
        List<Message> oldMessages = List.of(
                Message.builder().role("user").content("Hello").timestamp(Instant.now()).build(),
                Message.builder().role("assistant").content("Hi there!").timestamp(Instant.now()).build());
        when(sessionService.getMessagesToCompact("telegram:12345", 10)).thenReturn(oldMessages);

        // LLM returns a summary
        when(compactionService.summarize(oldMessages)).thenReturn("User greeted the bot.");
        Message summaryMsg = Message.builder().role("system").content("[Conversation summary]\nUser greeted the bot.")
                .build();
        when(compactionService.createSummaryMessage("User greeted the bot.")).thenReturn(summaryMsg);
        when(sessionService.compactWithSummary("telegram:12345", 10, summaryMsg)).thenReturn(20);

        var result = router.execute("compact", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("20"));
        assertTrue(result.output().contains("command.compact.done.summary"));
        verify(compactionService).summarize(oldMessages);
        verify(sessionService).compactWithSummary("telegram:12345", 10, summaryMsg);
    }

    @Test
    void compactFallbackWhenLlmUnavailable() throws Exception {
        // Messages exist to compact
        List<Message> oldMessages = List.of(
                Message.builder().role("user").content("Hello").timestamp(Instant.now()).build());
        when(sessionService.getMessagesToCompact("telegram:12345", 10)).thenReturn(oldMessages);

        // LLM returns null (unavailable)
        when(compactionService.summarize(oldMessages)).thenReturn(null);
        when(sessionService.compactMessages("telegram:12345", 10)).thenReturn(15);

        var result = router.execute("compact", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("15"));
        assertTrue(result.output().contains("command.compact.done "));
        verify(sessionService).compactMessages("telegram:12345", 10);
        verify(sessionService, never()).compactWithSummary(anyString(), anyInt(), any());
    }

    @Test
    void compactCommandWithArg() throws Exception {
        List<Message> oldMessages = List.of(
                Message.builder().role("user").content("Hi").timestamp(Instant.now()).build());
        when(sessionService.getMessagesToCompact("telegram:12345", 5)).thenReturn(oldMessages);
        when(compactionService.summarize(oldMessages)).thenReturn(null);
        when(sessionService.compactMessages("telegram:12345", 5)).thenReturn(15);

        var result = router.execute("compact", List.of("5"), CTX).get();
        assertTrue(result.success());
        verify(sessionService).compactMessages("telegram:12345", 5);
    }

    @Test
    void compactCommandNothingToCompact() throws Exception {
        when(sessionService.getMessagesToCompact("telegram:12345", 10)).thenReturn(List.of());
        when(sessionService.getMessageCount("telegram:12345")).thenReturn(5);

        var result = router.execute("compact", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.compact.nothing"));
    }

    @Test
    void helpCommand() throws Exception {
        var result = router.execute("help", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.help.text"));
    }

    @Test
    void unknownCommand() throws Exception {
        var result = router.execute("foobar", List.of(), CTX).get();
        assertFalse(result.success());
        assertTrue(result.output().contains("command.unknown"));
    }

    // ===== Auto mode commands =====

    private static final Map<String, Object> CTX_WITH_CHANNEL = Map.of(
            "sessionId", "telegram:12345",
            "channelType", "telegram",
            "chatId", "12345");

    @Test
    void autoOnEnablesAutoMode() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);

        var result = router.execute("auto", List.of("on"), CTX_WITH_CHANNEL).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.auto.enabled"));
        verify(autoModeService).enableAutoMode();
        verify(eventPublisher).publishEvent(new AutoModeChannelRegisteredEvent("telegram", "12345"));
    }

    @Test
    void autoOffDisablesAutoMode() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);

        var result = router.execute("auto", List.of("off"), CTX_WITH_CHANNEL).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.auto.disabled"));
        verify(autoModeService).disableAutoMode();
    }

    @Test
    void autoNoArgsShowsStatus() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.isAutoModeEnabled()).thenReturn(true);

        var result = router.execute("auto", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.auto.status"));
        assertTrue(result.output().contains("ON"));
    }

    @Test
    void autoReturnsNotAvailableWhenDisabled() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(false);

        var result = router.execute("auto", List.of("on"), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.auto.not-available"));
    }

    @Test
    void autoInvalidSubcommand() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);

        var result = router.execute("auto", List.of("invalid"), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.auto.usage"));
    }

    // ===== Goals command =====

    @Test
    void goalsListsGoals() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getGoals()).thenReturn(List.of(
                Goal.builder().title("Build API").description("REST API").status(Goal.GoalStatus.ACTIVE)
                        .tasks(List.of(AutoTask.builder().title("Design").status(AutoTask.TaskStatus.COMPLETED).order(1)
                                .build()))
                        .build()));

        var result = router.execute("goals", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("Build API"));
        assertTrue(result.output().contains("ACTIVE"));
    }

    @Test
    void goalsEmptyList() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getGoals()).thenReturn(List.of());

        var result = router.execute("goals", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.goals.empty"));
    }

    // ===== Goal command =====

    @Test
    void goalCreatesGoal() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.createGoal("Build REST API", null))
                .thenReturn(
                        Goal.builder().title("Build REST API").status(Goal.GoalStatus.ACTIVE).tasks(List.of()).build());

        var result = router.execute("goal", List.of("Build", "REST", "API"), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.goal.created"));
    }

    @Test
    void goalWithoutArgsShowsEmpty() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);

        var result = router.execute("goal", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.goals.empty"));
    }

    @Test
    void goalLimitExceeded() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.createGoal(anyString(), any()))
                .thenThrow(new IllegalStateException("Max goals reached"));

        var result = router.execute("goal", List.of("Another", "goal"), CTX).get();
        assertFalse(result.success());
        assertTrue(result.output().contains("command.goal.limit"));
    }

    // ===== Tasks command =====

    @Test
    void tasksShowsTasks() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getGoals()).thenReturn(List.of(
                Goal.builder().title("API").status(Goal.GoalStatus.ACTIVE).tasks(List.of(
                        AutoTask.builder().title("Design endpoints").status(AutoTask.TaskStatus.PENDING).order(1)
                                .build(),
                        AutoTask.builder().title("Implement").status(AutoTask.TaskStatus.IN_PROGRESS).order(2).build()))
                        .build()));

        var result = router.execute("tasks", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("Design endpoints"));
        assertTrue(result.output().contains("Implement"));
    }

    @Test
    void tasksEmpty() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getGoals()).thenReturn(List.of());

        var result = router.execute("tasks", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.tasks.empty"));
    }

    // ===== Diary command =====

    @Test
    void diaryShowsEntries() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getRecentDiary(10)).thenReturn(List.of(
                DiaryEntry.builder()
                        .type(DiaryEntry.DiaryType.OBSERVATION)
                        .content("Started working on API design")
                        .timestamp(Instant.parse("2026-02-07T10:00:00Z"))
                        .build()));

        var result = router.execute("diary", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("Started working on API design"));
        assertTrue(result.output().contains("OBSERVATION"));
    }

    @Test
    void diaryWithCustomCount() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getRecentDiary(5)).thenReturn(List.of());

        router.execute("diary", List.of("5"), CTX).get();
        verify(autoModeService).getRecentDiary(5);
    }

    @Test
    void diaryEmpty() throws Exception {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);
        when(autoModeService.getRecentDiary(10)).thenReturn(List.of());

        var result = router.execute("diary", List.of(), CTX).get();
        assertTrue(result.success());
        assertTrue(result.output().contains("command.diary.empty"));
    }

    // ===== Compact bounds =====

    @Test
    void compactClampsKeepToMinOne() throws Exception {
        when(sessionService.getMessagesToCompact("telegram:12345", 1)).thenReturn(List.of());
        when(sessionService.getMessageCount("telegram:12345")).thenReturn(3);

        router.execute("compact", List.of("0"), CTX).get();
        verify(sessionService).getMessagesToCompact("telegram:12345", 1);
    }

    @Test
    void compactClampsKeepToMax100() throws Exception {
        when(sessionService.getMessagesToCompact("telegram:12345", 100)).thenReturn(List.of());
        when(sessionService.getMessageCount("telegram:12345")).thenReturn(3);

        router.execute("compact", List.of("999"), CTX).get();
        verify(sessionService).getMessagesToCompact("telegram:12345", 100);
    }

    @Test
    void compactIgnoresNonNumericArg() throws Exception {
        when(sessionService.getMessagesToCompact("telegram:12345", 10)).thenReturn(List.of());
        when(sessionService.getMessageCount("telegram:12345")).thenReturn(3);

        router.execute("compact", List.of("abc"), CTX).get();
        // Falls back to default 10
        verify(sessionService).getMessagesToCompact("telegram:12345", 10);
    }

    // ===== listCommands includes auto when enabled =====

    @Test
    void listCommandsIncludesAutoWhenEnabled() {
        when(autoModeService.isFeatureEnabled()).thenReturn(true);

        var commands = router.listCommands();
        assertTrue(commands.stream().anyMatch(c -> "auto".equals(c.name())));
        assertTrue(commands.stream().anyMatch(c -> "goals".equals(c.name())));
        assertTrue(commands.stream().anyMatch(c -> "diary".equals(c.name())));
        assertEquals(12, commands.size());
    }

    // ===== formatTokens =====

    @Test
    void formatTokensMillions() {
        assertEquals("1.5M", CommandRouter.formatTokens(1_500_000));
    }

    @Test
    void formatTokensThousands() {
        assertEquals("12.3K", CommandRouter.formatTokens(12_300));
    }

    @Test
    void formatTokensSmall() {
        assertEquals("999", CommandRouter.formatTokens(999));
    }
}
