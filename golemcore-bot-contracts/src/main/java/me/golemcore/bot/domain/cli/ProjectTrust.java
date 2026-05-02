package me.golemcore.bot.domain.cli;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Trust decision associated with a project identity.
 */
public record ProjectTrust(ProjectIdentity project,ProjectTrustState state,@JsonFormat(shape=JsonFormat.Shape.STRING)Instant grantedAt,String grantedBy,List<String>scopes){

private static final String FILESYSTEM_READ_SCOPE="filesystem.read";

public ProjectTrust{Objects.requireNonNull(project,"project");Objects.requireNonNull(state,"state");scopes=CliContractCollections.copyList(scopes);}

public static ProjectTrust defaultForUntrustedProject(ProjectIdentity project){return new ProjectTrust(project,ProjectTrustState.RESTRICTED,null,"system",List.of(FILESYSTEM_READ_SCOPE));}

public boolean trusted(){return state==ProjectTrustState.TRUSTED;}

public boolean readOnly(){return scopes.contains(FILESYSTEM_READ_SCOPE)&&scopes.stream().noneMatch(ProjectTrust::grantsMutation);}

private static boolean grantsMutation(String scope){return scope.contains("write")||scope.startsWith("shell.")||scope.startsWith("git.")||scope.startsWith("network.")||scope.startsWith("mcp.");}}
