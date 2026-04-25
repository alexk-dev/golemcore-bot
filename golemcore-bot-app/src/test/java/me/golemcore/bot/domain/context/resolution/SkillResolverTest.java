package me.golemcore.bot.domain.context.resolution;

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Skill;
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

    @Test
    void shouldRecordExplicitToolSourceAfterTransitionRequest() {
        // Guards formatActiveSkillSource — both the "return null" mutant and the
        // negated null-check: if the method returned null or the empty string,
        // ACTIVE_SKILL_SOURCE would not be set to "explicit_tool".
        Skill coding = Skill.builder().name("coding").description("Code").available(true).build();
        when(skillComponent.findByName("coding")).thenReturn(Optional.of(coding));

        AgentContext context = contextWithSession();
        context.setSkillTransitionRequest(SkillTransitionRequest.explicit("coding"));

        resolver.resolve(context);

        assertEquals("explicit_tool", context.getAttribute(ContextAttributes.ACTIVE_SKILL_SOURCE));
    }

    @Test
    void shouldRecordSkillPipelineSourceAfterPipelineTransitionRequest() {
        // Uses the pipeline() factory so the lowercased reason name differs
        // from the explicit path — kills return-value mutations that collapse
        // both branches to the same constant.
        Skill research = Skill.builder().name("research").description("Research").available(true).build();
        when(skillComponent.findByName("research")).thenReturn(Optional.of(research));

        AgentContext context = contextWithSession();
        context.setSkillTransitionRequest(SkillTransitionRequest.pipeline("research"));

        resolver.resolve(context);

        assertEquals("skill_pipeline", context.getAttribute(ContextAttributes.ACTIVE_SKILL_SOURCE));
    }

    @Test
    void shouldDefaultToMessageMetadataSourceWhenResolvingViaContextAttribute() {
        // Kills resolveExplicitSkillSource "replaced return value with empty
        // string" mutant — asserts the explicit default "message_metadata".
        Skill research = Skill.builder().name("research").description("Research").available(true).build();
        when(skillComponent.findByName("research")).thenReturn(Optional.of(research));

        AgentContext context = contextWithSession();
        context.setAttribute(ContextAttributes.ACTIVE_SKILL_NAME, "research");

        resolver.resolve(context);

        assertEquals("message_metadata", context.getAttribute(ContextAttributes.ACTIVE_SKILL_SOURCE));
    }

    @Test
    void shouldPreserveExistingSkillSourceAttributeWhenResolvingViaContextAttribute() {
        // Guards the `return existing;` branch of resolveExplicitSkillSource:
        // if the field were replaced with null/empty, the pre-set source would
        // be overwritten by "message_metadata".
        Skill research = Skill.builder().name("research").description("Research").available(true).build();
        when(skillComponent.findByName("research")).thenReturn(Optional.of(research));

        AgentContext context = contextWithSession();
        context.setAttribute(ContextAttributes.ACTIVE_SKILL_NAME, "research");
        context.setAttribute(ContextAttributes.ACTIVE_SKILL_SOURCE, "preexisting_source");

        resolver.resolve(context);

        assertEquals("preexisting_source", context.getAttribute(ContextAttributes.ACTIVE_SKILL_SOURCE));
    }

    @Test
    void shouldRecordSessionStateSourceAfterStickyResolution() {
        // Exercises the session-state arm of applyActiveSkillByName and kills
        // return-value mutants in that branch.
        Skill coding = Skill.builder().name("coding").description("Code").available(true).build();
        when(skillComponent.findByName("coding")).thenReturn(Optional.of(coding));

        AgentContext context = contextWithSession();
        context.getSession().getMetadata().put(ContextAttributes.ACTIVE_SKILL_NAME, "coding");

        resolver.resolve(context);

        assertEquals("session_state", context.getAttribute(ContextAttributes.ACTIVE_SKILL_SOURCE));
    }

    @Test
    void shouldIgnoreSessionActiveSkillWhenMetadataValueIsNotString() {
        // Guards readSessionActiveSkillName "return value replaced with empty
        // string" mutant on the non-String branch — if it returned "" instead
        // of null, applyActiveSkillByName would short-circuit on the blank
        // check but the test intent (no sticky skill resolved) still holds.
        // Combined with the blank-value guard below, this pins the branch.
        AgentContext context = contextWithSession();
        context.getSession().getMetadata().put(ContextAttributes.ACTIVE_SKILL_NAME, 42);

        resolver.resolve(context);

        assertNull(context.getActiveSkill());
    }

    @Test
    void shouldIgnoreSessionActiveSkillWhenMetadataIsMissing() {
        // Session has no metadata at all — kills the early-null branch of
        // readSessionActiveSkillName.
        AgentSession session = AgentSession.builder()
                .channelType("web")
                .chatId("chat-1")
                .build();
        session.setMetadata(null);
        AgentContext context = AgentContext.builder().session(session).build();

        resolver.resolve(context);

        assertNull(context.getActiveSkill());
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
