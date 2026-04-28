package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.AgentSession;

public class SessionIdFactory {

    static final String LEGACY_PATH_SEPARATOR = "\\\\";
    static final String PATH_SEPARATOR = "/";
    static final String PROTO_EXTENSION = ".pb";
    static final String SESSION_ID_SEPARATOR = ":";
    static final String SESSIONS_DIR = "sessions";

    public String buildSessionId(String channelType, String chatId) {
        return channelType + SESSION_ID_SEPARATOR + chatId;
    }

    public String storageFileName(String sessionId) {
        return sessionId + PROTO_EXTENSION;
    }

    public boolean isStoredFileForChannel(String path, String channelType) {
        if (StringValueSupport.isBlank(path) || StringValueSupport.isBlank(channelType)) {
            return false;
        }

        String fileName = fileName(path);
        if (!fileName.endsWith(PROTO_EXTENSION)) {
            return false;
        }
        int extensionIndex = fileName.lastIndexOf('.');
        if (extensionIndex <= 0) {
            return false;
        }

        String sessionId = fileName.substring(0, extensionIndex);
        int separatorIndex = sessionId.indexOf(SESSION_ID_SEPARATOR);
        if (separatorIndex <= 0) {
            return false;
        }
        return channelType.equals(sessionId.substring(0, separatorIndex));
    }

    public void enrichSessionFields(AgentSession session, String filePath) {
        if (session.getId() == null || session.getId().isBlank()) {
            String normalized = filePath.replace(LEGACY_PATH_SEPARATOR, PATH_SEPARATOR);
            String withoutExtension = stripKnownExtension(normalized);
            String derivedId = withoutExtension.replace(PATH_SEPARATOR, SESSION_ID_SEPARATOR);
            session.setId(derivedId);
        }

        String sessionId = session.getId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        int separatorIndex = sessionId.indexOf(SESSION_ID_SEPARATOR);
        if ((session.getChannelType() == null || session.getChannelType().isBlank()) && separatorIndex > 0) {
            session.setChannelType(sessionId.substring(0, separatorIndex));
        }
        if ((session.getChatId() == null || session.getChatId().isBlank()) && separatorIndex >= 0
                && separatorIndex + 1 < sessionId.length()) {
            session.setChatId(sessionId.substring(separatorIndex + 1));
        }
    }

    private String fileName(String path) {
        int slashIndex = Math.max(path.lastIndexOf(PATH_SEPARATOR), path.lastIndexOf('\\'));
        return slashIndex >= 0 ? path.substring(slashIndex + 1) : path;
    }

    private String stripKnownExtension(String filePath) {
        if (filePath.endsWith(PROTO_EXTENSION)) {
            return filePath.substring(0, filePath.length() - PROTO_EXTENSION.length());
        }
        return filePath;
    }
}
