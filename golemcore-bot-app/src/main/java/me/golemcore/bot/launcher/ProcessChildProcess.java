package me.golemcore.bot.launcher;

final class ProcessChildProcess implements ChildProcess {

    private final Process process;

    ProcessChildProcess(Process process) {
        this.process = process;
    }

    @Override
    public int waitFor() throws InterruptedException {
        return process.waitFor();
    }

    @Override
    public void destroy() {
        if (process.isAlive()) {
            process.destroy();
        }
    }
}
