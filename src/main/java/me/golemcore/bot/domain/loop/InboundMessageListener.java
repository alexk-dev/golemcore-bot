package me.golemcore.bot.domain.loop;

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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.domain.service.SessionRunCoordinator;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for inbound messages and delegates processing to
 * {@link SessionRunCoordinator}.
 *
 * <p>
 * This listener exists so that {@link AgentLoop} remains a single-turn
 * orchestrator and does not directly manage concurrency/queueing.
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InboundMessageListener {

    private final SessionRunCoordinator coordinator;

    @EventListener
    public void onInboundMessage(AgentLoop.InboundMessageEvent event) {
        Message message = event.message();
        log.debug("[Inbound] enqueue message (channel={}, chatId={})", message.getChannelType(), message.getChatId());
        coordinator.enqueue(message);
    }
}
