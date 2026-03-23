package me.golemcore.bot.domain.context.resolution;

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.SkillTransitionReason;
import me.golemcore.bot.domain.model.SkillTransitionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillResolverTest {

    private SkillComponent skillComponent;
    private SkillResolver resolver;

    @BeforeEach
    void setUp() {
        skillComponent = mock(SkillComponent.class);
        resolver = new SkillResolver(skillComponent);
    }

    @Test
    void shouldApplySkillFromTransitionRequest() {
        Skill coding = Skill.builder().name("coding").description("Code").available(true).build();
        when(skillComponent.findByName("coding")).thenReturn(Optional.of(coding));

        AgentContext context = contextWithSession();
        context.setSkillTransitionRequest(
                SkillTransitionRequest.explicit("coding"));

        resolver.resolve(context);

        assertEquals(coding, context.getActiveSkill());
        assertEquals("coding", context.getAttribute(ContextAttributes.ACTIVE_SKILL_NAME));
        assertNull(context.getSkillTransitionRequest(), "Transition request should be cleared");
    }

    @Test
    void shouldResolveStickySkillFromSession() {
        Skill coding = Skill.builder().name("coding").description("Code").available(true).build();
        when(skillComponent.findByName("coding")).thenReturn(Optional.of(coding));

        AgentContext context = contextWithSession();
        context.getSession().getMetadata().put(ContextAttributes.ACTIVE_SKILL_NAME, "coding");

        resolver.resolve(context);

        assertEquals(coding, context.getActiveSkill());
    }

    @Test
    void shouldResolveSkillFromContextAttribute() {
        Skill research = Skill.builder().name("research").description("Research").available(true).build();
        when(skillComponent.findByName("research")).thenReturn(Optional.of(research));

        AgentContext context = contextWithSession();
        context.setAttribute(ContextAttributes.ACTIVE_SKILL_NAME, "research");

        resolver.resolve(context);

        assertEquals(research, context.getActiveSkill());
    }

    @Test
    void shouldClearStickySkillWhenNotFound() {
        when(skillComponent.findByName("deleted")).thenReturn(Optional.empty());

        AgentContext context = contextWithSession();
        context.getSession().getMetadata().put(ContextAttributes.ACTIVE_SKILL_NAME, "deleted");

        resolver.resolve(context);

        assertNull(context.getActiveSkill());
        assertNull(context.getSession().getMetadata().get(ContextAttributes.ACTIVE_SKILL_NAME));
    }

    @Test
    void shouldClearStickySkillWhenUnavailable() {
        Skill unavailable = Skill.builder().name("locked").description("Locked").available(false).build();
        when(skillComponent.findByName("locked")).thenReturn(Optional.of(unavailable));

        AgentContext context = contextWithSession();
        context.getSession().getMetadata().put(ContextAttributes.ACTIVE_SKILL_NAME, "locked");

        resolver.resolve(context);

        assertNull(context.getActiveSkill());
    }

    @Test
    void shouldPersistActiveSkillToSessionMetadata() {
        Skill coding = Skill.builder().name("coding").description("Code").available(true).build();
        when(skillComponent.findByName("coding")).thenReturn(Optional.of(coding));

        AgentContext context = contextWithSession();
        context.setSkillTransitionRequest(
                SkillTransitionRequest.explicit("coding"));

        resolver.resolve(context);

        assertEquals("coding", context.getSession().getMetadata().get(ContextAttributes.ACTIVE_SKILL_NAME));
    }

    @Test
    void shouldHandleNullContext() {
        resolver.resolve(null); // should not throw
    }

    @Test
    void shouldKeepExistingActiveSkillIfAlreadySet() {
        Skill existing = Skill.builder().name("existing").description("Exists").available(true).build();

        AgentContext context = contextWithSession();
        context.setActiveSkill(existing);

        resolver.resolve(context);

        assertEquals(existing, context.getActiveSkill());
    }

    private AgentContext contextWithSession() {
        AgentSession session = AgentSession.builder()
                .channelType("web")
                .chatId("chat-1")
                .build();
        return AgentContext.builder()
                .session(session)
                .build();
    }
}
