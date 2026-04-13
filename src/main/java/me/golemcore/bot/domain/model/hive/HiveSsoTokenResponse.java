package me.golemcore.bot.domain.model.hive;

import java.util.List;

public record HiveSsoTokenResponse(String accessToken,String username,String displayName,List<String>roles){}
