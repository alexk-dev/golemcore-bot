package me.golemcore.bot.application.update;

import me.golemcore.bot.runtime.RuntimeVersionSupport;

/**
 * Package-local compatibility wrapper that keeps the update module using the
 * shared runtime version helper without changing existing callers.
 */
final class UpdateVersionSupport extends RuntimeVersionSupport {
}
