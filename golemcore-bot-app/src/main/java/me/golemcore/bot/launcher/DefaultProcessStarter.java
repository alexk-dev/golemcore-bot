package me.golemcore.bot.launcher;

import java.io.IOException;
import java.util.List;

final class DefaultProcessStarter implements ProcessStarter {

    @Override
    public ChildProcess start(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command)
                .inheritIO()
                .start();
        return new ProcessChildProcess(process);
    }
}
