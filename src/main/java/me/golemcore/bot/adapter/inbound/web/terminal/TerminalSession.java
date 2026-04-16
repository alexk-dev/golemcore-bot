package me.golemcore.bot.adapter.inbound.web.terminal;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Wraps a pty4j {@link PtyProcess} with a pumped reader thread and a simple API
 * for writing input, resizing, and closing. Output bytes are delivered to the
 * consumer provided at start-time.
 */
@Slf4j
public final class TerminalSession implements TerminalConnection.SessionHandle {

    private static final int READ_BUFFER_BYTES = 4096;

    private final PtyProcess process;
    private final Thread readerThread;
    private volatile boolean closed;

    private TerminalSession(PtyProcess process, Thread readerThread) {
        this.process = process;
        this.readerThread = readerThread;
    }

    public static TerminalSession start(
            String[] command,
            int cols,
            int rows,
            Consumer<byte[]> outputConsumer) throws IOException {
        return start(command, cols, rows, null, outputConsumer);
    }

    /**
     * Starts a PTY process with the requested dimensions and optional working
     * directory.
     *
     * @param workingDirectory
     *            directory passed to pty4j as the process cwd; {@code null} leaves
     *            the platform default unchanged
     */
    public static TerminalSession start(
            String[] command,
            int cols,
            int rows,
            Path workingDirectory,
            Consumer<byte[]> outputConsumer) throws IOException {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.putIfAbsent("TERM", "xterm-256color");
        env.putIfAbsent("LANG", StandardCharsets.UTF_8.name());

        PtyProcessBuilder builder = new PtyProcessBuilder(command)
                .setEnvironment(env)
                .setInitialColumns(cols)
                .setInitialRows(rows);
        if (workingDirectory != null) {
            builder.setDirectory(workingDirectory.toString());
        }
        PtyProcess process = builder.start();

        Thread reader = new Thread(() -> pumpOutput(process, outputConsumer), "pty4j-reader");
        reader.setDaemon(true);
        reader.start();

        log.info(
                "[Terminal] Session started pid={} cols={} rows={} cwd={}",
                process.pid(),
                cols,
                rows,
                workingDirectory);
        return new TerminalSession(process, reader);
    }

    public void writeInput(byte[] data) throws IOException {
        if (closed) {
            return;
        }
        // The PTY owns this stream; closing it here would terminate the terminal
        // session instead of just flushing the current input frame.
        @SuppressWarnings("PMD.CloseResource")
        OutputStream out = process.getOutputStream();
        out.write(data);
        out.flush();
    }

    public void resize(int cols, int rows) {
        if (closed) {
            return;
        }
        try {
            process.setWinSize(new WinSize(cols, rows));
        } catch (RuntimeException e) {
            log.warn("[Terminal] Failed to resize pty: {}", e.getMessage());
        }
    }

    public boolean isAlive() {
        return !closed && process.isAlive();
    }

    public Integer exitCode() {
        if (process.isAlive()) {
            return null;
        }
        return process.exitValue();
    }

    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        return process.waitFor(timeout, unit);
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (process.isAlive()) {
            process.destroy();
        }
        readerThread.interrupt();
        log.info("[Terminal] Session closed pid={}", process.pid());
    }

    private static void pumpOutput(PtyProcess process, Consumer<byte[]> outputConsumer) {
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        try (InputStream in = process.getInputStream()) {
            while (!Thread.currentThread().isInterrupted()) {
                int read = in.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                byte[] chunk = new byte[read];
                System.arraycopy(buffer, 0, chunk, 0, read);
                try {
                    outputConsumer.accept(chunk);
                } catch (RuntimeException e) {
                    log.warn("[Terminal] Output consumer threw: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.debug("[Terminal] Reader thread ended: {}", e.getMessage());
        }
    }
}
