package me.golemcore.bot.port.outbound;

/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

import me.golemcore.bot.domain.model.AgentSession;
import me.golemcore.bot.domain.model.Message;

import java.util.List;
import java.util.Optional;

/**
 * Port for managing agent conversation sessions. Abstracts session CRUD and
 * message compaction from domain consumers.
 */
public interface SessionPort {

    AgentSession getOrCreate(String channelType, String chatId);

    Optional<AgentSession> get(String sessionId);

    void save(AgentSession session);

    void delete(String sessionId);

    void clearMessages(String sessionId);

    int compactMessages(String sessionId, int keepLast);

    int compactWithSummary(String sessionId, int keepLast, Message summaryMessage);

    List<Message> getMessagesToCompact(String sessionId, int keepLast);

    int getMessageCount(String sessionId);

    List<AgentSession> listAll();

    List<AgentSession> listByChannelType(String channelType);

    List<AgentSession> listByChannelTypeAndTransportChatId(String channelType, String transportChatId);
}
