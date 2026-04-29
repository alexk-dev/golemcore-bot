package me.golemcore.bot.domain.cli;

import java.util.List;

/**
 * Token budget accounting for a CLI-visible run.
 */
public record ContextBudgetReport(int maxTokens,int usedTokens,int remainingTokens,List<Section>sections){

public ContextBudgetReport{sections=CliContractCollections.copyList(sections);}

public record Section(String name,int tokens){}}
