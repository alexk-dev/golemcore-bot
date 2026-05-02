package me.golemcore.bot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;

class BotApplicationCliRunnerTest {

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @Test
    void shouldRouteHelpVersionAndCompletionThroughNoRuntimeCli() {
        RecordingNoRuntimeCli noRuntimeCli = new RecordingNoRuntimeCli();
        RecordingRuntimeCli runtimeCli = new RecordingRuntimeCli();
        BotApplicationCliRunner runner = new BotApplicationCliRunner(noRuntimeCli, runtimeCli);

        assertEquals(0, runner.runCli(new String[] { "--help" }, writer(out), writer(err)));
        assertEquals(0, runner.runCli(new String[] { "--version" }, writer(out), writer(err)));
        assertEquals(0, runner.runCli(new String[] { "completion", "bash" }, writer(out), writer(err)));

        assertEquals(3, noRuntimeCli.calls.size());
        assertArrayEquals(new String[] { "--help" }, noRuntimeCli.calls.get(0));
        assertArrayEquals(new String[] { "--version" }, noRuntimeCli.calls.get(1));
        assertArrayEquals(new String[] { "completion", "bash" }, noRuntimeCli.calls.get(2));
        assertEquals(0, runtimeCli.calls.size());
    }

    @Test
    void shouldRouteRuntimeCliCommandsThroughNonWebSpringBootstrap() {
        RecordingNoRuntimeCli noRuntimeCli = new RecordingNoRuntimeCli();
        RecordingRuntimeCli runtimeCli = new RecordingRuntimeCli();
        BotApplicationCliRunner runner = new BotApplicationCliRunner(noRuntimeCli, runtimeCli);

        assertEquals(0, runner.runCli(new String[0], writer(out), writer(err)));
        assertEquals(0, runner.runCli(new String[] { "run", "fix tests" }, writer(out), writer(err)));
        assertEquals(0, runner.runCli(new String[] { "serve" }, writer(out), writer(err)));
        assertEquals(0, runner.runCli(new String[] { "attach" }, writer(out), writer(err)));

        assertEquals(WebApplicationType.NONE, runtimeCli.webApplicationType());
        assertEquals(4, runtimeCli.calls.size());
        assertEquals(0, noRuntimeCli.calls.size());
    }

    @Test
    void shouldCreateAppOwnedCliRunnerAndDependencies() {
        assertNotNull(BotApplicationCliDependencies.create().doctorUseCase());

        BotApplicationCliRunner runner = BotApplication.createCliRunner();

        assertEquals(WebApplicationType.NONE, runner.runtimeWebApplicationType());
    }

    private static PrintWriter writer(StringWriter writer) {
        return new PrintWriter(writer, true);
    }

    private static final class RecordingNoRuntimeCli implements BotApplicationCliRunner.NoRuntimeCli {
        private final List<String[]> calls = new ArrayList<>();

        @Override
        public int run(String[] args, PrintWriter out, PrintWriter err) {
            calls.add(args);
            return 0;
        }
    }

    private static final class RecordingRuntimeCli implements BotApplicationCliRunner.RuntimeCli {
        private final List<String[]> calls = new ArrayList<>();

        @Override
        public int run(String[] args, PrintWriter out, PrintWriter err) {
            calls.add(args);
            return 0;
        }

        @Override
        public WebApplicationType webApplicationType() {
            return WebApplicationType.NONE;
        }
    }
}
