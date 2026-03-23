package me.golemcore.bot.domain.context.layer;

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
import me.golemcore.bot.domain.context.ContextLayer;
import me.golemcore.bot.domain.context.ContextLayerResult;
import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.service.WorkspaceInstructionService;
import org.springframework.stereotype.Component;

/**
 * Injects workspace instruction files (AGENTS.md, CLAUDE.md) into context.
 *
 * <p>
 * Scans configured tool workspaces for instruction files, orders them by
 * directory depth (broader first, more local later), and renders them as a
 * single section with a precedence note.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkspaceInstructionsLayer implements ContextLayer {

    private final WorkspaceInstructionService workspaceInstructionService;

    @Override
    public String getName() {
        return "workspace_instructions";
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public boolean appliesTo(AgentContext context) {
        return true;
    }

    @Override
    public ContextLayerResult assemble(AgentContext context) {
        String instructions = workspaceInstructionService.getWorkspaceInstructionsContext();
        if (instructions == null || instructions.isBlank()) {
            return ContextLayerResult.empty(getName());
        }

        String content = "# Workspace Instructions\n"
                + "Follow these repository instruction files. "
                + "If instructions conflict, prefer more local files listed later.\n\n"
                + instructions;

        return ContextLayerResult.builder()
                .layerName(getName())
                .content(content)
                .estimatedTokens((int) Math.ceil(content.length() / 3.5))
                .build();
    }
}
