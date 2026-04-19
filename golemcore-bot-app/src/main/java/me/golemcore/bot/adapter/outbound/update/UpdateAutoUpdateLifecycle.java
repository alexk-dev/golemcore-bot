package me.golemcore.bot.adapter.outbound.update;

import me.golemcore.bot.application.update.UpdateService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UpdateAutoUpdateLifecycle {

    private static final int AUTO_UPDATE_TICK_INTERVAL_SECONDS = 60;

    private final UpdateService updateService;

    private ScheduledExecutorService autoUpdateScheduler;
    private ScheduledFuture<?> autoUpdateTask;

    @PostConstruct
    void start() {
        if (!updateService.isEnabled()) {
            return;
        }

        autoUpdateScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "update-auto-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        autoUpdateTask = autoUpdateScheduler.scheduleWithFixedDelay(
                updateService::runAutoUpdateCycleSafely,
                AUTO_UPDATE_TICK_INTERVAL_SECONDS,
                AUTO_UPDATE_TICK_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (autoUpdateTask != null) {
            autoUpdateTask.cancel(false);
        }
        if (autoUpdateScheduler != null) {
            autoUpdateScheduler.shutdownNow();
        }
    }
}
