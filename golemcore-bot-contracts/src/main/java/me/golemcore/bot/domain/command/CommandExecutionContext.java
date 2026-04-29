package me.golemcore.bot.domain.command;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed execution context for user-facing commands. It replaces transport-owned
 * map contracts at the command boundary while preserving legacy map conversion
 * for existing adapters and plugins.
 */
public final class CommandExecutionContext {

    public static final String KEY_SESSION_ID = "sessionId";
    public static final String KEY_CHANNEL_TYPE = "channelType";
    public static final String KEY_CHAT_ID = "chatId";
    public static final String KEY_SESSION_CHAT_ID = "sessionChatId";
    public static final String KEY_TRANSPORT_CHAT_ID = "transportChatId";
    public static final String KEY_CONVERSATION_KEY = "conversationKey";
    public static final String KEY_ACTOR_ID = "actorId";
    public static final String KEY_LOCALE = "locale";

    private final String sessionIdValue;
    private final String channelTypeValue;
    private final String chatIdValue;
    private final String sessionChatIdValue;
    private final String transportChatIdValue;
    private final String conversationKeyValue;
    private final String actorIdValue;
    private final String localeValue;
    private final Map<String, Object> metadataValues;

    private CommandExecutionContext(Builder builder) {
        this.sessionIdValue = trimToNull(builder.sessionIdValue);
        this.channelTypeValue = trimToNull(builder.channelTypeValue);
        this.chatIdValue = trimToNull(builder.chatIdValue);
        this.sessionChatIdValue = trimToNull(builder.sessionChatIdValue);
        this.transportChatIdValue = trimToNull(builder.transportChatIdValue);
        this.conversationKeyValue = trimToNull(builder.conversationKeyValue);
        this.actorIdValue = trimToNull(builder.actorIdValue);
        this.localeValue = trimToNull(builder.localeValue);
        this.metadataValues = builder.metadataValues.isEmpty() ? Map.of() : Map.copyOf(builder.metadataValues);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static CommandExecutionContext fromLegacyMap(Map<String, Object> context) {
        Builder builder = builder();
        if (context == null || context.isEmpty()) {
            return builder.build();
        }
        builder.sessionId(asString(context.get(KEY_SESSION_ID)));
        builder.channelType(asString(context.get(KEY_CHANNEL_TYPE)));
        builder.chatId(asString(context.get(KEY_CHAT_ID)));
        builder.sessionChatId(asString(context.get(KEY_SESSION_CHAT_ID)));
        builder.transportChatId(asString(context.get(KEY_TRANSPORT_CHAT_ID)));
        builder.conversationKey(asString(context.get(KEY_CONVERSATION_KEY)));
        builder.actorId(asString(context.get(KEY_ACTOR_ID)));
        builder.locale(asString(context.get(KEY_LOCALE)));
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            String key = entry.getKey();
            if (!isReservedKey(key)) {
                builder.metadata(key, entry.getValue());
            }
        }
        return builder.build();
    }

    public Map<String, Object> toLegacyMap() {
        Map<String, Object> values = new LinkedHashMap<>(metadataValues);
        putIfPresent(values, KEY_SESSION_ID, sessionIdValue);
        putIfPresent(values, KEY_CHANNEL_TYPE, channelTypeValue);
        putIfPresent(values, KEY_CHAT_ID, chatIdValue);
        putIfPresent(values, KEY_SESSION_CHAT_ID, sessionChatIdValue);
        putIfPresent(values, KEY_TRANSPORT_CHAT_ID, transportChatIdValue);
        putIfPresent(values, KEY_CONVERSATION_KEY, conversationKeyValue);
        putIfPresent(values, KEY_ACTOR_ID, actorIdValue);
        putIfPresent(values, KEY_LOCALE, localeValue);
        return Map.copyOf(values);
    }

    public String sessionId() {
        return sessionIdValue;
    }

    public String channelType() {
        return channelTypeValue;
    }

    public String chatId() {
        return chatIdValue;
    }

    public String sessionChatId() {
        return sessionChatIdValue;
    }

    public String transportChatId() {
        return transportChatIdValue;
    }

    public String conversationKey() {
        return conversationKeyValue;
    }

    public String actorId() {
        return actorIdValue;
    }

    public String locale() {
        return localeValue;
    }

    public Map<String, Object> metadata() {
        return metadataValues;
    }

    public String effectiveSessionChatId() {
        return firstNonBlank(sessionChatIdValue, chatIdValue);
    }

    public String effectiveConversationKey() {
        return firstNonBlank(conversationKeyValue, effectiveSessionChatId());
    }

    public String effectiveTransportChatId() {
        return firstNonBlank(transportChatIdValue, chatIdValue);
    }

    public boolean hasExplicitSessionRouting() {
        return !isBlank(sessionChatIdValue) || !isBlank(conversationKeyValue);
    }

    private static boolean isReservedKey(String key) {
        return KEY_SESSION_ID.equals(key)
                || KEY_CHANNEL_TYPE.equals(key)
                || KEY_CHAT_ID.equals(key)
                || KEY_SESSION_CHAT_ID.equals(key)
                || KEY_TRANSPORT_CHAT_ID.equals(key)
                || KEY_CONVERSATION_KEY.equals(key)
                || KEY_ACTOR_ID.equals(key)
                || KEY_LOCALE.equals(key);
    }

    private static void putIfPresent(Map<String, Object> values, String key, String value) {
        if (!isBlank(value)) {
            values.put(key, value);
        }
    }

    private static String asString(Object value) {
        return value instanceof String stringValue ? stringValue : null;
    }

    private static String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first : second;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static final class Builder {

        private String sessionIdValue;
        private String channelTypeValue;
        private String chatIdValue;
        private String sessionChatIdValue;
        private String transportChatIdValue;
        private String conversationKeyValue;
        private String actorIdValue;
        private String localeValue;
        private final Map<String, Object> metadataValues = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder sessionId(String sessionId) {
            this.sessionIdValue = sessionId;
            return this;
        }

        public Builder channelType(String channelType) {
            this.channelTypeValue = channelType;
            return this;
        }

        public Builder chatId(String chatId) {
            this.chatIdValue = chatId;
            return this;
        }

        public Builder sessionChatId(String sessionChatId) {
            this.sessionChatIdValue = sessionChatId;
            return this;
        }

        public Builder transportChatId(String transportChatId) {
            this.transportChatIdValue = transportChatId;
            return this;
        }

        public Builder conversationKey(String conversationKey) {
            this.conversationKeyValue = conversationKey;
            return this;
        }

        public Builder actorId(String actorId) {
            this.actorIdValue = actorId;
            return this;
        }

        public Builder locale(String locale) {
            this.localeValue = locale;
            return this;
        }

        public Builder metadata(String key, Object value) {
            if (key != null && !key.isBlank() && value != null) {
                this.metadataValues.put(key, value);
            }
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            if (metadata == null || metadata.isEmpty()) {
                return this;
            }
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                metadata(entry.getKey(), entry.getValue());
            }
            return this;
        }

        public CommandExecutionContext build() {
            return new CommandExecutionContext(this);
        }
    }
}
