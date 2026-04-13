package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.domain.model.Skill;
import me.golemcore.bot.domain.model.selfevolving.tactic.TacticSearchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillPipelineSystemTacticSelectionTest {

    @Test
    void shouldCarrySelectedTacticAsSeparateGuidanceWithoutSettingActiveSkillMetadata() {
        SkillComponent skillComponent = mock(SkillComponent.class);
        SkillPipelineSystem system = new SkillPipelineSystem(skillComponent);
        Skill activeSkill = Skill.builder().name("analyzer").nextSkill("executor").build();
        Skill nextSkill = Skill.builder().name("executor").available(true).build();
        AgentContext context = AgentContext.builder()
                .session(AgentSession.builder().chatId("chat-1").messages(new ArrayList<>()).build())
                .messages(new ArrayList<>())
                .activeSkill(activeSkill)
                .build();
        context.setAttribute(ContextAttributes.LLM_RESPONSE,
                LlmResponse.builder().content("Analysis complete").build());
        context.setAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_SELECTION, TacticSearchResult.builder()
                .tacticId("planner-tactic")
                .artifactKey("skill:planner")
                .title("Planner tactic")
                .promotionState("approved")
                .build());
        when(skillComponent.findByName("executor")).thenReturn(java.util.Optional.of(nextSkill));

        system.process(context);

        assertEquals("planner-tactic", ((TacticSearchResult) context
                .getAttribute(ContextAttributes.SELF_EVOLVING_TACTIC_GUIDANCE)).getTacticId());
        assertFalse(context.getAttributes().containsKey(ContextAttributes.ACTIVE_SKILL_NAME));
    }
}
