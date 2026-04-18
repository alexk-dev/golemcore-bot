package me.golemcore.bot.launcher;

import java.util.List;

/**
 * Immutable child-process launch descriptor.
 *
 * @param command
 *            exact process command line
 * @param description
 *            human-readable command source description for logs
 */
record LaunchCommand(List<String>command,String description){}
