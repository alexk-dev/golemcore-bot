package me.golemcore.bot.adapter.outbound.update;

import me.golemcore.bot.domain.service.JvmExitService;
import me.golemcore.bot.port.outbound.UpdateRestartPort;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class SpringApplicationUpdateRestartAdapter implements UpdateRestartPort {

    private final ApplicationContext applicationContext;
    private final JvmExitService jvmExitService;

    public SpringApplicationUpdateRestartAdapter(ApplicationContext applicationContext, JvmExitService jvmExitService) {
        this.applicationContext = applicationContext;
        this.jvmExitService = jvmExitService;
    }

    @Override
    public void restart(int exitCode) {
        int actualExitCode = SpringApplication.exit(applicationContext, () -> exitCode);
        jvmExitService.exit(actualExitCode);
    }
}
