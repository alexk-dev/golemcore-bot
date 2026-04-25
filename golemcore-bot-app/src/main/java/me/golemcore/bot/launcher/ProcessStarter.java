package me.golemcore.bot.launcher;

import java.io.IOException;
import java.util.List;

/**
 * Starts the child runtime process.
 */
interface ProcessStarter {

    ChildProcess start(List<String> command) throws IOException;
}
