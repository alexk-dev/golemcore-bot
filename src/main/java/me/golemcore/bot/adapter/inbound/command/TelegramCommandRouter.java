package me.golemcore.bot.adapter.inbound.command;

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

import me.golemcore.bot.port.inbound.CommandPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Telegram channel command router facade.
 */
@Component
@RequiredArgsConstructor
public class TelegramCommandRouter implements TelegramCommandPort {

    private static final String CHANNEL_TYPE = "telegram";

    private final CommandRouter delegate;

    @Override
    public CompletableFuture<CommandPort.CommandResult> execute(String command, List<String> args,
            Map<String, Object> context) {
        Map<String, Object> contextWithChannel = new HashMap<>();
        if (context != null) {
            contextWithChannel.putAll(context);
        }
        contextWithChannel.put("channelType", CHANNEL_TYPE);
        return delegate.execute(command, args, contextWithChannel);
    }

    @Override
    public boolean hasCommand(String command) {
        return delegate.hasCommand(command, CHANNEL_TYPE);
    }

    @Override
    public boolean hasCommand(String command, String channelType) {
        return delegate.hasCommand(command, CHANNEL_TYPE);
    }

    @Override
    public List<CommandPort.CommandDefinition> listCommands() {
        return delegate.listCommands(CHANNEL_TYPE);
    }

    @Override
    public List<CommandPort.CommandDefinition> listCommands(String channelType) {
        return delegate.listCommands(CHANNEL_TYPE);
    }
}
