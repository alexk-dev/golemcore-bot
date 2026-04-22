package me.golemcore.bot.infrastructure.config;

import java.util.List;
import me.golemcore.bot.domain.component.MemoryComponent;
import me.golemcore.bot.domain.component.SkillComponent;
import me.golemcore.bot.domain.context.ContextAssembler;
import me.golemcore.bot.domain.context.ContextLayer;
import me.golemcore.bot.domain.context.PromptComposer;
import me.golemcore.bot.domain.context.layer.AutoModeLayer;
import me.golemcore.bot.domain.context.layer.IdentityLayer;
import me.golemcore.bot.domain.context.layer.MemoryLayer;
import me.golemcore.bot.domain.context.layer.PlanModeLayer;
import me.golemcore.bot.domain.context.layer.RagLayer;
import me.golemcore.bot.domain.context.layer.SkillLayer;
import me.golemcore.bot.domain.context.layer.TierAwarenessLayer;
import me.golemcore.bot.domain.context.layer.ToolLayer;
import me.golemcore.bot.domain.context.layer.WebhookResponseSchemaLayer;
import me.golemcore.bot.domain.context.layer.WorkspaceInstructionsLayer;
import me.golemcore.bot.domain.context.resolution.SkillResolver;
import me.golemcore.bot.domain.context.resolution.TierResolver;
import me.golemcore.bot.domain.service.AutoModeService;
import me.golemcore.bot.domain.service.ContextCompactionPolicy;
import me.golemcore.bot.domain.service.DelayedActionPolicyService;
import me.golemcore.bot.domain.service.MemoryPresetService;
import me.golemcore.bot.domain.service.ModelSelectionService;
import me.golemcore.bot.domain.service.PlanModeToolRestrictionService;
import me.golemcore.bot.domain.service.PlanService;
import me.golemcore.bot.domain.service.PromptSectionService;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.domain.service.SkillTemplateEngine;
import me.golemcore.bot.domain.service.ToolCallExecutionService;
import me.golemcore.bot.domain.service.UserPreferencesService;
import me.golemcore.bot.domain.service.WorkspaceInstructionService;
import me.golemcore.bot.port.outbound.McpPort;
import me.golemcore.bot.port.outbound.RagPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ContextLayerConfiguration {

    @Bean
    PromptComposer promptComposer() {
        return new PromptComposer();
    }

    @Bean
    SkillResolver skillResolver(SkillComponent skillComponent) {
        return new SkillResolver(skillComponent);
    }

    @Bean
    TierResolver tierResolver(
            UserPreferencesService userPreferencesService,
            ModelSelectionService modelSelectionService,
            RuntimeConfigService runtimeConfigService,
            SkillComponent skillComponent) {
        return new TierResolver(userPreferencesService, modelSelectionService, runtimeConfigService, skillComponent);
    }

    @Bean
    IdentityLayer identityLayer(PromptSectionService promptSectionService,
            UserPreferencesService userPreferencesService) {
        return new IdentityLayer(promptSectionService, userPreferencesService);
    }

    @Bean
    WorkspaceInstructionsLayer workspaceInstructionsLayer(WorkspaceInstructionService workspaceInstructionService) {
        return new WorkspaceInstructionsLayer(workspaceInstructionService);
    }

    @Bean
    MemoryLayer memoryLayer(
            MemoryComponent memoryComponent,
            RuntimeConfigService runtimeConfigService,
            MemoryPresetService memoryPresetService) {
        return new MemoryLayer(memoryComponent, runtimeConfigService, memoryPresetService);
    }

    @Bean
    RagLayer ragLayer(RagPort ragPort) {
        return new RagLayer(ragPort);
    }

    @Bean
    SkillLayer skillLayer(SkillComponent skillComponent, SkillTemplateEngine skillTemplateEngine) {
        return new SkillLayer(skillComponent, skillTemplateEngine);
    }

    @Bean
    ToolLayer toolLayer(ToolCallExecutionService toolCallExecutionService,
            McpPort mcpPort,
            DelayedActionPolicyService delayedActionPolicyService,
            PlanModeToolRestrictionService planModeToolRestrictionService) {
        return new ToolLayer(toolCallExecutionService, mcpPort, delayedActionPolicyService,
                planModeToolRestrictionService);
    }

    @Bean
    TierAwarenessLayer tierAwarenessLayer(UserPreferencesService userPreferencesService) {
        return new TierAwarenessLayer(userPreferencesService);
    }

    @Bean
    AutoModeLayer autoModeLayer(AutoModeService autoModeService) {
        return new AutoModeLayer(autoModeService);
    }

    @Bean
    PlanModeLayer planModeLayer(PlanService planService) {
        return new PlanModeLayer(planService);
    }

    @Bean
    WebhookResponseSchemaLayer webhookResponseSchemaLayer() {
        return new WebhookResponseSchemaLayer();
    }

    @Bean
    ContextAssembler contextAssembler(SkillResolver skillResolver,
            TierResolver tierResolver,
            List<ContextLayer> layers,
            PromptComposer promptComposer,
            ContextCompactionPolicy contextCompactionPolicy) {
        return new ContextAssembler(skillResolver, tierResolver, layers, promptComposer, contextCompactionPolicy);
    }
}
