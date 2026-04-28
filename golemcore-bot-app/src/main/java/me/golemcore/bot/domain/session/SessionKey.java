package me.golemcore.bot.domain.session;

import java.util.Objects;

record SessionKey(String channelType,String chatId){SessionKey{Objects.requireNonNull(channelType,"channelType");Objects.requireNonNull(chatId,"chatId");}}
