package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.component.MessageAggregator;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillMatchResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.outbound.SkillMatcherPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillRoutingSystemTest {

    private SkillMatcherPort skillMatcher;
    private SkillComponent skillComponent;
    private BotProperties properties;
    private MessageAggregator messageAggregator;
    private SkillRoutingSystem system;

    @BeforeEach
    void setUp() {
        skillMatcher = mock(SkillMatcherPort.class);
        skillComponent = mock(SkillComponent.class);
        properties = new BotProperties();
        properties.getRouter().getSkillMatcher().setEnabled(true);
        properties.getRouter().getSkillMatcher().setRoutingTimeoutMs(5000);
        messageAggregator = mock(MessageAggregator.class);

        when(skillMatcher.isEnabled()).thenReturn(true);
        when(skillMatcher.isReady()).thenReturn(true);
        when(messageAggregator.analyze(anyList()))
                .thenReturn(new MessageAggregator.AggregationAnalysis(false, List.of(), ""));

        system = new SkillRoutingSystem(skillMatcher, skillComponent, properties, messageAggregator);
    }

    private AgentContext createContext(String userMessage) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().role("user").content(userMessage).timestamp(Instant.now()).build());
        return AgentContext.builder()
                .session(AgentSession.builder().id("test").chatId("ch1").channelType("telegram").build())
                .messages(messages)
                .currentIteration(0)
                .build();
    }

    // ===== Basic ordering & metadata =====

    @Test
    void orderIsFifteen() {
        assertEquals(15, system.getOrder());
    }

    @Test
    void nameIsSkillRoutingSystem() {
        assertEquals("SkillRoutingSystem", system.getName());
    }

    // ===== shouldProcess =====

    @Test
    void skipsOnNonZeroIteration() {
        AgentContext ctx = createContext("hello");
        ctx.setCurrentIteration(1);
        assertFalse(system.shouldProcess(ctx));
    }

    @Test
    void skipsAutoModeMessages() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("auto.mode", true);
        List<Message> messages = new ArrayList<>();
        messages.add(
                Message.builder().role("user").content("auto check").timestamp(Instant.now()).metadata(meta).build());
        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().id("test").chatId("ch1").channelType("telegram").build())
                .messages(messages)
                .currentIteration(0)
                .build();

        assertFalse(system.shouldProcess(ctx));
    }

    @Test
    void processesNormalFirstIteration() {
        AgentContext ctx = createContext("hello");
        assertTrue(system.shouldProcess(ctx));
    }

    // ===== process =====

    @Test
    void setsActiveSkillAndModelTierOnMatch() {
        AgentContext ctx = createContext("review my code");
        when(messageAggregator.buildRoutingQuery(anyList())).thenReturn("review my code");

        Skill codeSkill = Skill.builder().name("code-review").description("Reviews code").content("You are a reviewer")
                .available(true).build();
        when(skillComponent.getAvailableSkills()).thenReturn(List.of(codeSkill));
        when(skillComponent.findByName("code-review")).thenReturn(Optional.of(codeSkill));

        SkillMatchResult matchResult = SkillMatchResult.builder()
                .selectedSkill("code-review")
                .confidence(0.92)
                .modelTier("coding")
                .reason("Code review detected")
                .llmClassifierUsed(true)
                .latencyMs(250)
                .build();
        when(skillMatcher.match(anyString(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(matchResult));

        system.process(ctx);

        assertEquals(codeSkill, ctx.getActiveSkill());
        assertEquals("coding", ctx.getModelTier());
        assertEquals("code-review", ctx.getAttribute("routing.skill"));
        assertEquals(0.92, (double) ctx.getAttribute("routing.confidence"), 0.01);
    }

    @Test
    void setsNoSkillOnNoMatch() {
        AgentContext ctx = createContext("what time is it?");
        when(messageAggregator.buildRoutingQuery(anyList())).thenReturn("what time is it?");
        when(skillComponent.getAvailableSkills()).thenReturn(List.of(
                Skill.builder().name("greeting").description("Greet").available(true).build()));

        SkillMatchResult noMatch = SkillMatchResult.builder()
                .selectedSkill(null)
                .confidence(0.5)
                .modelTier("fast")
                .reason("No match")
                .build();
        when(skillMatcher.match(anyString(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(noMatch));

        system.process(ctx);

        assertNull(ctx.getActiveSkill());
        assertEquals("fast", ctx.getModelTier());
    }

    @Test
    void skipsWhenNoAvailableSkills() {
        AgentContext ctx = createContext("hello");
        when(messageAggregator.buildRoutingQuery(anyList())).thenReturn("hello");
        when(skillComponent.getAvailableSkills()).thenReturn(List.of());

        system.process(ctx);

        // skillMatcher.isEnabled() is called, but match() should not be
        verify(skillMatcher, never()).match(anyString(), anyList(), anyList());
        assertNull(ctx.getActiveSkill());
    }

    @Test
    void skipsWhenMatcherDisabled() {
        when(skillMatcher.isEnabled()).thenReturn(false);
        AgentContext ctx = createContext("hello");

        system.process(ctx);

        assertNull(ctx.getActiveSkill());
        verify(skillMatcher, never()).match(anyString(), anyList(), anyList());
    }

    @Test
    void skipsWhenBlankQuery() {
        AgentContext ctx = createContext("");
        when(messageAggregator.buildRoutingQuery(anyList())).thenReturn("");

        system.process(ctx);

        verify(skillComponent, never()).getAvailableSkills();
    }

    @Test
    void indexesSkillsWhenMatcherNotReady() {
        when(skillMatcher.isReady()).thenReturn(false);
        AgentContext ctx = createContext("hello");
        when(messageAggregator.buildRoutingQuery(anyList())).thenReturn("hello");
        List<Skill> skills = List.of(Skill.builder().name("s1").description("d1").available(true).build());
        when(skillComponent.getAvailableSkills()).thenReturn(skills);
        when(skillMatcher.match(anyString(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(SkillMatchResult.noMatch("test")));

        system.process(ctx);

        verify(skillMatcher).indexSkills(skills);
    }

    @Test
    void setsRoutingErrorOnException() {
        AgentContext ctx = createContext("hello");
        when(messageAggregator.buildRoutingQuery(anyList())).thenReturn("hello");
        when(skillComponent.getAvailableSkills()).thenReturn(List.of(
                Skill.builder().name("s1").description("d1").available(true).build()));

        when(skillMatcher.match(anyString(), anyList(), anyList()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("matcher error")));

        system.process(ctx);

        assertNotNull(ctx.getAttribute("routing.error"));
    }

    @Test
    void setsFragmentationAttributes() {
        AgentContext ctx = createContext("hello");
        when(messageAggregator.buildRoutingQuery(anyList())).thenReturn("hello");
        when(messageAggregator.analyze(anyList()))
                .thenReturn(new MessageAggregator.AggregationAnalysis(true,
                        List.of("too_short", "within_time_window"), "Fragmented input detected"));
        when(skillComponent.getAvailableSkills()).thenReturn(List.of(
                Skill.builder().name("s1").description("d1").available(true).build()));
        when(skillMatcher.match(anyString(), anyList(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(SkillMatchResult.noMatch("test")));

        system.process(ctx);

        assertTrue((boolean) ctx.getAttribute("routing.fragmented"));
        assertNotNull(ctx.getAttribute("routing.fragmentationSignals"));
    }
}
