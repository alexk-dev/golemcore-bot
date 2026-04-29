package me.golemcore.bot.domain.cli;

import java.time.Instant;
import java.util.List;

/**
 * Trust decision associated with a project identity.
 */
public record ProjectTrust(ProjectIdentity project,ProjectTrustState state,Instant grantedAt,String grantedBy,List<String>scopes){

public ProjectTrust{scopes=CliContractCollections.copyList(scopes);}

public boolean trusted(){return state==ProjectTrustState.TRUSTED;}}
