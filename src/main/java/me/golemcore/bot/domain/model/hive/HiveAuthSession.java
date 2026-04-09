package me.golemcore.bot.domain.model.hive;

import java.time.Instant;
import java.util.List;

public record HiveAuthSession(String golemId,String accessToken,String refreshToken,Instant accessTokenExpiresAt,Instant refreshTokenExpiresAt,String issuer,String audience,String controlChannelUrl,int heartbeatIntervalSeconds,List<String>scopes){}
