package me.golemcore.bot.domain.model;

/**
 * Stable channel type identifiers shared by use cases and channel adapters.
 */
public final class ChannelTypes {

    public static final String WEB = "web";
    public static final String TELEGRAM = "telegram";
    public static final String WEBHOOK = "webhook";
    public static final String HIVE = "hive";
    public static final String JUDGE = "judge";
    public static final String JUDGE_PREFIX = JUDGE + "_";

    private ChannelTypes() {
    }
}
