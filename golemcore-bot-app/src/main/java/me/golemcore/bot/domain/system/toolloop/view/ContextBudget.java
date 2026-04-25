package me.golemcore.bot.domain.system.toolloop.view;

/**
 * Token budgets resolved for one provider request.
 *
 * @param inputTokens
 *            total request input budget after output reserve
 * @param systemPromptTokens
 *            maximum system-prompt budget
 * @param conversationTokens
 *            maximum projected conversation-history budget
 * @param toolResultTokens
 *            target budget for raw or summarized tool results
 */
public record ContextBudget(int inputTokens,int systemPromptTokens,int conversationTokens,int toolResultTokens){

public static ContextBudget unlimited(){return new ContextBudget(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE);}

public boolean isUnlimited(){return inputTokens==Integer.MAX_VALUE||conversationTokens==Integer.MAX_VALUE;}}
