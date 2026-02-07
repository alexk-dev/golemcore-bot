package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillPipelineSystemTest {

    private SkillComponent skillComponent;
    private SkillPipelineSystem system;

    @BeforeEach
    void setUp() {
        skillComponent = mock(SkillComponent.class);
        system = new SkillPipelineSystem(skillComponent);
    }

    private AgentContext createContext(Skill activeSkill, LlmResponse response) {
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder()
                        .chatId("ch1")
                        .messages(new ArrayList<>())
                        .build())
                .messages(new ArrayList<>())
                .activeSkill(activeSkill)
                .build();
        if (response != null) {
            context.setAttribute("llm.response", response);
        }
        return context;
    }

    @Test
    void name() {
        assertEquals("SkillPipelineSystem", system.getName());
    }

    @Test
    void order() {
        assertEquals(55, system.getOrder());
    }

    @Test
    void shouldProcess_noResponse() {
        Skill skill = Skill.builder().name("a").nextSkill("b").build();
        AgentContext ctx = createContext(skill, null);

        assertFalse(system.shouldProcess(ctx));
    }

    @Test
    void shouldProcess_responseWithToolCalls() {
        Skill skill = Skill.builder().name("a").nextSkill("b").build();
        LlmResponse response = LlmResponse.builder()
                .content("result")
                .toolCalls(List.of(Message.ToolCall.builder()
                        .id("tc1").name("shell").build()))
                .build();
        AgentContext ctx = createContext(skill, response);

        assertFalse(system.shouldProcess(ctx));
    }

    @Test
    void shouldProcess_noActiveSkill() {
        LlmResponse response = LlmResponse.builder().content("result").build();
        AgentContext ctx = createContext(null, response);

        assertFalse(system.shouldProcess(ctx));
    }

    @Test
    void shouldProcess_noNextSkill() {
        Skill skill = Skill.builder().name("a").build();
        LlmResponse response = LlmResponse.builder().content("result").build();
        AgentContext ctx = createContext(skill, response);

        assertFalse(system.shouldProcess(ctx));
    }

    @Test
    void shouldProcess_explicitTransitionAlreadySet() {
        Skill skill = Skill.builder().name("a").nextSkill("b").build();
        LlmResponse response = LlmResponse.builder().content("result").build();
        AgentContext ctx = createContext(skill, response);
        ctx.setAttribute("skill.transition.target", "c");

        assertFalse(system.shouldProcess(ctx));
    }

    @Test
    void shouldProcess_validAutoTransition() {
        Skill skill = Skill.builder().name("a").nextSkill("b").build();
        LlmResponse response = LlmResponse.builder().content("result").build();
        AgentContext ctx = createContext(skill, response);

        assertTrue(system.shouldProcess(ctx));
    }

    @Test
    void process_autoTransition() {
        Skill activeSkill = Skill.builder().name("analyzer").nextSkill("executor").build();
        Skill nextSkill = Skill.builder().name("executor").available(true).build();
        LlmResponse response = LlmResponse.builder().content("Analysis complete").build();

        when(skillComponent.findByName("executor")).thenReturn(Optional.of(nextSkill));

        AgentContext ctx = createContext(activeSkill, response);
        system.process(ctx);

        // Transition target should be set
        assertEquals("executor", ctx.getAttribute("skill.transition.target"));
        // Pipeline depth incremented
        assertEquals(1, (int) ctx.getAttribute("skill.pipeline.depth"));
        // Loop continuation forced
        assertEquals(true, ctx.getAttribute("tools.executed"));
        // Response cleared
        assertNull(ctx.getAttribute("llm.response"));
        // Intermediate response stored in session
        assertEquals(1, ctx.getSession().getMessages().size());
        assertEquals("assistant", ctx.getSession().getMessages().get(0).getRole());
        assertEquals("Analysis complete", ctx.getSession().getMessages().get(0).getContent());
    }

    @Test
    void process_maxDepthReached() {
        Skill activeSkill = Skill.builder().name("a").nextSkill("b").build();
        LlmResponse response = LlmResponse.builder().content("result").build();

        AgentContext ctx = createContext(activeSkill, response);
        ctx.setAttribute("skill.pipeline.depth", 5);

        system.process(ctx);

        // Should NOT set transition
        assertNull(ctx.getAttribute("skill.transition.target"));
        // Response should still be present
        assertNotNull(ctx.getAttribute("llm.response"));
    }

    @Test
    void process_nextSkillNotFound() {
        Skill activeSkill = Skill.builder().name("a").nextSkill("nonexistent").build();
        LlmResponse response = LlmResponse.builder().content("result").build();

        when(skillComponent.findByName("nonexistent")).thenReturn(Optional.empty());

        AgentContext ctx = createContext(activeSkill, response);
        system.process(ctx);

        // Should NOT set transition
        assertNull(ctx.getAttribute("skill.transition.target"));
    }

    @Test
    void process_nextSkillUnavailable() {
        Skill activeSkill = Skill.builder().name("a").nextSkill("broken").build();
        Skill brokenSkill = Skill.builder().name("broken").available(false).build();
        LlmResponse response = LlmResponse.builder().content("result").build();

        when(skillComponent.findByName("broken")).thenReturn(Optional.of(brokenSkill));

        AgentContext ctx = createContext(activeSkill, response);
        system.process(ctx);

        assertNull(ctx.getAttribute("skill.transition.target"));
    }

    @Test
    void process_incrementsDepth() {
        Skill activeSkill = Skill.builder().name("a").nextSkill("b").build();
        Skill nextSkill = Skill.builder().name("b").available(true).build();
        LlmResponse response = LlmResponse.builder().content("result").build();

        when(skillComponent.findByName("b")).thenReturn(Optional.of(nextSkill));

        AgentContext ctx = createContext(activeSkill, response);
        ctx.setAttribute("skill.pipeline.depth", 3);

        system.process(ctx);

        assertEquals(4, (int) ctx.getAttribute("skill.pipeline.depth"));
    }

    @Test
    void process_blankResponseNotStoredInSession() {
        Skill activeSkill = Skill.builder().name("a").nextSkill("b").build();
        Skill nextSkill = Skill.builder().name("b").available(true).build();
        LlmResponse response = LlmResponse.builder().content("  ").build();

        when(skillComponent.findByName("b")).thenReturn(Optional.of(nextSkill));

        AgentContext ctx = createContext(activeSkill, response);
        system.process(ctx);

        // Blank content should not be stored
        assertEquals(0, ctx.getSession().getMessages().size());
        // But transition should still happen
        assertEquals("b", ctx.getAttribute("skill.transition.target"));
    }
}
