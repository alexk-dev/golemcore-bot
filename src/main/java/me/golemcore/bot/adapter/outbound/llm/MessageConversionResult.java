package me.golemcore.bot.adapter.outbound.llm;

import dev.langchain4j.data.message.ChatMessage;
import java.util.List;

record MessageConversionResult(List<ChatMessage>messages,boolean hydratedToolImages){}
