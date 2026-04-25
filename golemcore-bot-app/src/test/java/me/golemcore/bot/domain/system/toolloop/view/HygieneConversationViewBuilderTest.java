package me.golemcore.bot.domain.system.toolloop.view;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.model.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HygieneConversationViewBuilderTest {

    @Test
    void shouldDecorateDelegateWithResolvedBudgetProjection() {
        ConversationViewBuilder delegate = mock(ConversationViewBuilder.class);
        ContextWindowProjector projector = mock(ContextWindowProjector.class);
        ContextBudgetResolver budgetResolver = mock(ContextBudgetResolver.class);
        HygieneConversationViewBuilder builder = new HygieneConversationViewBuilder(
                delegate, projector, budgetResolver);

        AgentContext context = AgentContext.builder().build();
        ConversationView rawView = ConversationView.ofMessages(List.of(Message.builder()
                .role("user")
                .content("hello")
                .build()));
        ContextBudget budget = new ContextBudget(1_000, 250, 500, 100);
        ConversationView projected = ConversationView.ofMessages(List.of(Message.builder()
                .role("user")
                .content("projected")
                .build()));
        when(delegate.buildView(context, "model-a")).thenReturn(rawView);
        when(budgetResolver.resolve(context, "model-a")).thenReturn(budget);
        when(projector.project(context, rawView, budget)).thenReturn(projected);

        ConversationView result = builder.buildView(context, "model-a");

        assertEquals(projected, result);
        verify(delegate).buildView(context, "model-a");
        verify(budgetResolver).resolve(context, "model-a");
        verify(projector).project(context, rawView, budget);
    }
}
