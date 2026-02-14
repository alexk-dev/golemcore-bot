package me.golemcore.bot.domain.system;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.ContextAttributes;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.model.OutgoingResponse;
import me.golemcore.bot.domain.service.UserPreferencesService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeedbackGuaranteeSystemTest {

    @Test
    void shouldNotRunInAutoMode() {
        UserPreferencesService prefs = mock(UserPreferencesService.class);
        FeedbackGuaranteeSystem system = new FeedbackGuaranteeSystem(prefs);

        Message userAuto = Message.builder()
                .role("user")
                .content("hi")
                .timestamp(Instant.now())
                .metadata(Map.of("auto.mode", true))
                .build();

        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().channelType("test").chatId("1").build())
                .messages(List.of(userAuto))
                .build();

        assertThat(system.shouldProcess(ctx)).isFalse();
    }

    @Test
    void shouldNotOverrideExistingOutgoingResponse() {
        UserPreferencesService prefs = mock(UserPreferencesService.class);
        FeedbackGuaranteeSystem system = new FeedbackGuaranteeSystem(prefs);

        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().channelType("test").chatId("1").build())
                .messages(List.of(Message.builder().role("user").content("hi").timestamp(Instant.now()).build()))
                .build();
        ctx.setAttribute(ContextAttributes.OUTGOING_RESPONSE, OutgoingResponse.textOnly("ok"));

        assertThat(system.shouldProcess(ctx)).isFalse();
        system.process(ctx);
        OutgoingResponse out = ctx.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertThat(out.getText()).isEqualTo("ok");
    }

    @Test
    void shouldProduceFallbackWhenNoOutgoingResponse() {
        UserPreferencesService prefs = mock(UserPreferencesService.class);
        when(prefs.getMessage("system.error.generic.feedback")).thenReturn("fallback");
        FeedbackGuaranteeSystem system = new FeedbackGuaranteeSystem(prefs);

        AgentContext ctx = AgentContext.builder()
                .session(AgentSession.builder().channelType("test").chatId("1").build())
                .messages(List.of(Message.builder().role("user").content("hi").timestamp(Instant.now()).build()))
                .build();

        assertThat(system.shouldProcess(ctx)).isTrue();
        system.process(ctx);

        OutgoingResponse out = ctx.getAttribute(ContextAttributes.OUTGOING_RESPONSE);
        assertThat(out).isNotNull();
        assertThat(out.getText()).isEqualTo("fallback");
    }
}
