package me.golemcore.bot.adapter.outbound.update;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.port.outbound.UpdateRuntimeConfigPort;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RuntimeConfigUpdateRuntimeAdapter implements UpdateRuntimeConfigPort {

    private final RuntimeConfigService runtimeConfigService;

    @Override
    public RuntimeConfig.UpdateConfig getUpdateConfig() {
        RuntimeConfig.UpdateConfig config = runtimeConfigService.getRuntimeConfigForApi().getUpdate();
        return config != null ? config : new RuntimeConfig.UpdateConfig();
    }

    @Override
    public RuntimeConfig.UpdateConfig updateUpdateConfig(RuntimeConfig.UpdateConfig updateConfig) {
        RuntimeConfig current = runtimeConfigService.getRuntimeConfig();
        RuntimeConfig copy = runtimeConfigService.copyRuntimeConfig(current);
        copy.setUpdate(updateConfig != null ? updateConfig : new RuntimeConfig.UpdateConfig());
        runtimeConfigService.updateRuntimeConfig(copy);
        RuntimeConfig.UpdateConfig updated = runtimeConfigService.getRuntimeConfigForApi().getUpdate();
        return updated != null ? updated : new RuntimeConfig.UpdateConfig();
    }

    @Override
    public boolean isAutoUpdateEnabled() {
        return runtimeConfigService.isAutoUpdateEnabled();
    }

    @Override
    public Integer getUpdateCheckIntervalMinutes() {
        return runtimeConfigService.getUpdateCheckIntervalMinutes();
    }

    @Override
    public boolean isUpdateMaintenanceWindowEnabled() {
        return runtimeConfigService.isUpdateMaintenanceWindowEnabled();
    }

    @Override
    public String getUpdateMaintenanceWindowStartUtc() {
        return runtimeConfigService.getUpdateMaintenanceWindowStartUtc();
    }

    @Override
    public String getUpdateMaintenanceWindowEndUtc() {
        return runtimeConfigService.getUpdateMaintenanceWindowEndUtc();
    }
}
