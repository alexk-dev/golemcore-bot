package me.golemcore.bot.domain.system;

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

import me.golemcore.bot.domain.context.ContextAssembler;
import me.golemcore.bot.domain.model.AgentContext;
import org.springframework.stereotype.Component;

/**
 * Pipeline system for assembling the complete LLM context (order=20).
 *
 * <p>
 * Delegates all context assembly work to {@link ContextAssembler}, which
 * orchestrates layered context construction via skill/tier resolution and
 * composable {@code ContextLayer} implementations.
 *
 * @see ContextAssembler
 */
@Component
public class ContextBuildingSystem implements AgentSystem {

    private final ContextAssembler contextAssembler;

    public ContextBuildingSystem(ContextAssembler contextAssembler) {
        this.contextAssembler = contextAssembler;
    }

    @Override
    public String getName() {
        return "ContextBuildingSystem";
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public AgentContext process(AgentContext context) {
        return contextAssembler.assemble(context);
    }
}
