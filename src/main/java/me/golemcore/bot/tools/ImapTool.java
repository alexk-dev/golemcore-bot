/*
 * Copyright 2026 Aleksei Kuleshov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contact: alex@kuleshov.tech
 */

package me.golemcore.bot.tools;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.tools.mail.HtmlSanitizer;
import me.golemcore.bot.tools.mail.MailSecurity;
import me.golemcore.bot.tools.mail.MailSessionFactory;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.BodyPart;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Tool for reading email via IMAP.
 *
 * <p>
 * Operations:
 * <ul>
 * <li>list_folders — list all IMAP folders with message counts
 * <li>list_messages — list messages newest-first with UID, from, subject, date,
 * flags
 * <li>read_message — full message: headers + body + attachment metadata
 * <li>search_messages — search via Jakarta Mail SearchTerm
 * </ul>
 *
 * <p>
 * Configuration: {@code bot.tools.imap.*}
 */
@Component
@Slf4j
@SuppressWarnings({ "PMD.ReplaceJavaUtilDate", "PMD.CloseResource",
        "PMD.UseTryWithResources" }) // Jakarta Mail: java.util.Date required; Folder.close(false) needs explicit
                                     // param
public class ImapTool implements ToolComponent {

    private static final String PARAM_TYPE = "type";
    private static final String TYPE_STRING = "string";
    private static final String TYPE_INTEGER = "integer";
    private static final String TYPE_BOOLEAN = "boolean";
    private static final String TYPE_OBJECT = "object";
    private static final String SCHEMA_DESC = "description";

    private static final String PARAM_OPERATION = "operation";
    private static final String PARAM_FOLDER = "folder";
    private static final String PARAM_UID = "uid";
    private static final String PARAM_OFFSET = "offset";
    private static final String PARAM_LIMIT = "limit";
    private static final String PARAM_FROM = "from";
    private static final String PARAM_SUBJECT = "subject";
    private static final String PARAM_SINCE = "since";
    private static final String PARAM_BEFORE = "before";
    private static final String PARAM_UNSEEN = "unseen";

    private static final String DEFAULT_FOLDER = "INBOX";
    private static final int MAX_MULTIPART_DEPTH = 10;
    private static final int SINGLE_TERM = 1;
    private static final String BODY_TRUNCATED_SUFFIX = "\n[Body truncated]";
    private static final String NO_SUBJECT = "(no subject)";
    private static final String UNKNOWN_DATE = "unknown";

    private final BotProperties.ImapToolProperties config;
    private final MailSecurity security;

    public ImapTool(BotProperties properties) {
        this.config = properties.getTools().getImap();
        this.security = MailSecurity.fromString(config.getSecurity());
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled()
                && config.getHost() != null && !config.getHost().isBlank()
                && config.getUsername() != null && !config.getUsername().isBlank();
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("imap")
                .description(
                        """
                                Read email via IMAP. Operations: list_folders, list_messages, read_message, search_messages.
                                Messages are referenced by UID (persistent across sessions).
                                All folder names are case-sensitive. Default folder is INBOX.
                                """)
                .inputSchema(Map.of(
                        PARAM_TYPE, TYPE_OBJECT,
                        "properties", Map.of(
                                PARAM_OPERATION, Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        "enum", List.of("list_folders", "list_messages", "read_message",
                                                "search_messages"),
                                        SCHEMA_DESC, "Operation to perform"),
                                PARAM_FOLDER, Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        SCHEMA_DESC, "IMAP folder name (default: INBOX)"),
                                PARAM_UID, Map.of(
                                        PARAM_TYPE, TYPE_INTEGER,
                                        SCHEMA_DESC, "Message UID (for read_message)"),
                                PARAM_OFFSET, Map.of(
                                        PARAM_TYPE, TYPE_INTEGER,
                                        SCHEMA_DESC, "Offset for pagination (default: 0)"),
                                PARAM_LIMIT, Map.of(
                                        PARAM_TYPE, TYPE_INTEGER,
                                        SCHEMA_DESC, "Max messages to return (default: 20)"),
                                PARAM_FROM, Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        SCHEMA_DESC, "Search: filter by sender address"),
                                PARAM_SUBJECT, Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        SCHEMA_DESC, "Search: filter by subject"),
                                PARAM_SINCE, Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        SCHEMA_DESC, "Search: messages since date (yyyy-MM-dd)"),
                                PARAM_BEFORE, Map.of(
                                        PARAM_TYPE, TYPE_STRING,
                                        SCHEMA_DESC, "Search: messages before date (yyyy-MM-dd)"),
                                PARAM_UNSEEN, Map.of(
                                        PARAM_TYPE, TYPE_BOOLEAN,
                                        SCHEMA_DESC, "Search: only unread messages")),
                        "required", List.of(PARAM_OPERATION)))
                .build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String operation = (String) parameters.get(PARAM_OPERATION);
            if (operation == null || operation.isBlank()) {
                return ToolResult.failure("Missing required parameter: operation");
            }

            log.info("[IMAP] Operation: {}", operation);

            try {
                return switch (operation) {
                case "list_folders" -> listFolders();
                case "list_messages" -> listMessages(parameters);
                case "read_message" -> readMessage(parameters);
                case "search_messages" -> searchMessages(parameters);
                default -> ToolResult.failure("Unknown operation: " + operation);
                };
            } catch (AuthenticationFailedException e) {
                log.error("[IMAP] Authentication failed", e);
                return ToolResult.failure("IMAP authentication failed. Check username and password.");
            } catch (MessagingException e) {
                log.error("[IMAP] Messaging error: {}", e.getMessage(), e);
                return ToolResult.failure("IMAP error: " + sanitizeError(e.getMessage()));
            } catch (Exception e) { // NOSONAR - catch broadly for I/O layer
                log.error("[IMAP] Error: {}", e.getMessage(), e);
                return ToolResult.failure("IMAP error: " + sanitizeError(e.getMessage()));
            }
        });
    }

    Store connectStore() throws MessagingException {
        Session session = MailSessionFactory.createImapSession(
                config.getHost(), config.getPort(),
                config.getUsername(), config.getPassword(),
                security, config.getConnectTimeout(), config.getReadTimeout());

        String protocol = (security == MailSecurity.SSL) ? "imaps" : "imap";
        Store store = session.getStore(protocol);
        store.connect(config.getHost(), config.getPort(), config.getUsername(), config.getPassword());
        return store;
    }

    private ToolResult listFolders() throws MessagingException {
        try (Store store = connectStore()) {
            Folder[] folders = store.getDefaultFolder().list("*");
            StringBuilder sb = new StringBuilder();
            sb.append("IMAP Folders:\n\n");

            List<Map<String, Object>> folderList = new ArrayList<>();
            for (Folder folder : folders) {
                int type = folder.getType();
                if ((type & Folder.HOLDS_MESSAGES) != 0) {
                    folder.open(Folder.READ_ONLY);
                    int total = folder.getMessageCount();
                    int unread = folder.getUnreadMessageCount();
                    folder.close(false);

                    sb.append(String.format("  %s — %d messages (%d unread)%n",
                            folder.getFullName(), total, unread));

                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("name", folder.getFullName());
                    info.put("total", total);
                    info.put("unread", unread);
                    folderList.add(info);
                } else {
                    sb.append(String.format("  %s (folder only)%n", folder.getFullName()));

                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("name", folder.getFullName());
                    info.put("holdsMessages", false);
                    folderList.add(info);
                }
            }

            return ToolResult.success(sb.toString(), Map.of("folders", folderList));
        }
    }

    private ToolResult listMessages(Map<String, Object> params) throws MessagingException {
        String folderName = getStringParam(params, PARAM_FOLDER, DEFAULT_FOLDER);
        int offset = getIntParam(params, PARAM_OFFSET, 0);
        int limit = getIntParam(params, PARAM_LIMIT, config.getDefaultMessageLimit());

        try (Store store = connectStore()) {
            Folder folder = store.getFolder(folderName);
            if (!folder.exists()) {
                return ToolResult.failure("Folder not found: " + folderName);
            }
            folder.open(Folder.READ_ONLY);
            try {
                return buildMessageList(folder, offset, limit);
            } finally {
                folder.close(false);
            }
        }
    }

    private ToolResult buildMessageList(Folder folder, int offset, int limit) throws MessagingException {
        UIDFolder uidFolder = (UIDFolder) folder;
        int totalMessages = folder.getMessageCount();

        if (totalMessages == 0) {
            return ToolResult.success("No messages in " + folder.getFullName());
        }

        // Newest first: start from the end
        int endIndex = totalMessages - offset;
        int startIndex = Math.max(SINGLE_TERM, endIndex - limit + SINGLE_TERM);

        if (endIndex < SINGLE_TERM) {
            return ToolResult.success("No more messages (offset beyond total).");
        }

        Message[] messages = folder.getMessages(startIndex, endIndex);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Messages in %s (%d total, showing %d-%d):%n%n",
                folder.getFullName(), totalMessages, startIndex, endIndex));

        List<Map<String, Object>> messageList = new ArrayList<>();

        // Iterate in reverse for newest-first
        for (int i = messages.length - SINGLE_TERM; i >= 0; i--) {
            Message msg = messages[i];
            long msgUid = uidFolder.getUID(msg);
            String msgFrom = formatAddress(msg.getFrom());
            String msgSubject = msg.getSubject() != null ? msg.getSubject() : NO_SUBJECT;
            Date sentDate = msg.getSentDate();
            boolean seen = msg.isSet(Flags.Flag.SEEN);
            boolean flagged = msg.isSet(Flags.Flag.FLAGGED);

            String flags = (seen ? "" : "[UNREAD] ") + (flagged ? "[FLAGGED] " : "");
            sb.append(String.format("UID: %d %s%n  From: %s%n  Subject: %s%n  Date: %s%n%n",
                    msgUid, flags, msgFrom, msgSubject,
                    sentDate != null ? sentDate.toString() : UNKNOWN_DATE));

            Map<String, Object> info = new LinkedHashMap<>();
            info.put(PARAM_UID, msgUid);
            info.put(PARAM_FROM, msgFrom);
            info.put(PARAM_SUBJECT, msgSubject);
            info.put("date", sentDate != null ? sentDate.toString() : null);
            info.put("seen", seen);
            info.put("flagged", flagged);
            messageList.add(info);
        }

        return ToolResult.success(sb.toString(), Map.of(
                "messages", messageList,
                "total", totalMessages,
                PARAM_OFFSET, offset,
                PARAM_LIMIT, limit));
    }

    private ToolResult readMessage(Map<String, Object> params) throws MessagingException, IOException {
        String folderName = getStringParam(params, PARAM_FOLDER, DEFAULT_FOLDER);
        Object uidObj = params.get(PARAM_UID);
        if (uidObj == null) {
            return ToolResult.failure("Missing required parameter: uid");
        }
        long uid = ((Number) uidObj).longValue();

        try (Store store = connectStore()) {
            Folder folder = store.getFolder(folderName);
            if (!folder.exists()) {
                return ToolResult.failure("Folder not found: " + folderName);
            }
            folder.open(Folder.READ_ONLY);
            try {
                UIDFolder uidFolder = (UIDFolder) folder;
                Message msg = uidFolder.getMessageByUID(uid);
                if (msg == null) {
                    return ToolResult.failure("Message not found with UID: " + uid);
                }

                return buildFullMessage(msg, uid);
            } finally {
                folder.close(false);
            }
        }
    }

    private ToolResult buildFullMessage(Message msg, long uid) throws MessagingException, IOException {
        String msgFrom = formatAddress(msg.getFrom());
        String to = formatAddresses(msg.getRecipients(Message.RecipientType.TO));
        String cc = formatAddresses(msg.getRecipients(Message.RecipientType.CC));
        String msgSubject = msg.getSubject() != null ? msg.getSubject() : NO_SUBJECT;
        Date sentDate = msg.getSentDate();
        String messageId = getHeader(msg, "Message-ID");

        // Extract body
        BodyContent bodyContent = extractBody(msg, 0);
        String body = bodyContent.text;
        if (body.length() > config.getMaxBodyLength()) {
            body = body.substring(0, config.getMaxBodyLength()) + BODY_TRUNCATED_SUFFIX;
        }

        // Build output
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("UID: %d%n", uid));
        sb.append(String.format("From: %s%n", msgFrom));
        sb.append(String.format("To: %s%n", to));
        if (!cc.isEmpty()) {
            sb.append(String.format("Cc: %s%n", cc));
        }
        sb.append(String.format("Subject: %s%n", msgSubject));
        sb.append(String.format("Date: %s%n", sentDate != null ? sentDate.toString() : UNKNOWN_DATE));
        if (messageId != null) {
            sb.append(String.format("Message-ID: %s%n", messageId));
        }
        sb.append(String.format("%n--- Body ---%n%s%n", body));

        if (!bodyContent.attachments.isEmpty()) {
            sb.append(String.format("%n--- Attachments (%d) ---%n", bodyContent.attachments.size()));
            for (Map<String, String> att : bodyContent.attachments) {
                sb.append(String.format("  %s (%s, %s)%n",
                        att.get("filename"), att.get("contentType"), att.get("size")));
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put(PARAM_UID, uid);
        data.put(PARAM_FROM, msgFrom);
        data.put("to", to);
        data.put("cc", cc);
        data.put(PARAM_SUBJECT, msgSubject);
        data.put("date", sentDate != null ? sentDate.toString() : null);
        data.put("messageId", messageId);
        data.put("attachments", bodyContent.attachments);

        return ToolResult.success(sb.toString(), data);
    }

    private ToolResult searchMessages(Map<String, Object> params) throws MessagingException {
        String folderName = getStringParam(params, PARAM_FOLDER, DEFAULT_FOLDER);
        int limit = getIntParam(params, PARAM_LIMIT, config.getDefaultMessageLimit());

        List<SearchTerm> terms = new ArrayList<>();

        String from = getStringParam(params, PARAM_FROM, null);
        if (from != null) {
            terms.add(new FromStringTerm(from));
        }

        String subject = getStringParam(params, PARAM_SUBJECT, null);
        if (subject != null) {
            terms.add(new SubjectTerm(subject));
        }

        String since = getStringParam(params, PARAM_SINCE, null);
        if (since != null) {
            Date sinceDate = parseDate(since);
            if (sinceDate != null) {
                terms.add(new ReceivedDateTerm(ComparisonTerm.GE, sinceDate));
            }
        }

        String before = getStringParam(params, PARAM_BEFORE, null);
        if (before != null) {
            Date beforeDate = parseDate(before);
            if (beforeDate != null) {
                terms.add(new ReceivedDateTerm(ComparisonTerm.LE, beforeDate));
            }
        }

        Object unseenObj = params.get(PARAM_UNSEEN);
        if (Boolean.TRUE.equals(unseenObj)) {
            terms.add(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
        }

        if (terms.isEmpty()) {
            return ToolResult
                    .failure("At least one search criterion is required (from, subject, since, before, unseen)");
        }

        SearchTerm searchTerm = terms.size() == SINGLE_TERM
                ? terms.get(0)
                : new AndTerm(terms.toArray(new SearchTerm[0]));

        try (Store store = connectStore()) {
            Folder folder = store.getFolder(folderName);
            if (!folder.exists()) {
                return ToolResult.failure("Folder not found: " + folderName);
            }
            folder.open(Folder.READ_ONLY);
            try {
                UIDFolder uidFolder = (UIDFolder) folder;
                Message[] results = folder.search(searchTerm);

                if (results.length == 0) {
                    return ToolResult.success("No messages found matching search criteria.");
                }

                // Take only the last N (newest)
                int startIdx = Math.max(0, results.length - limit);
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("Search results in %s (%d found, showing %d):%n%n",
                        folderName, results.length, Math.min(results.length, limit)));

                List<Map<String, Object>> messageList = new ArrayList<>();

                for (int i = results.length - SINGLE_TERM; i >= startIdx; i--) {
                    Message msg = results[i];
                    long msgUid = uidFolder.getUID(msg);
                    String msgFrom = formatAddress(msg.getFrom());
                    String msgSubject = msg.getSubject() != null ? msg.getSubject() : NO_SUBJECT;
                    Date sentDate = msg.getSentDate();
                    boolean seen = msg.isSet(Flags.Flag.SEEN);

                    sb.append(String.format("UID: %d %s%n  From: %s%n  Subject: %s%n  Date: %s%n%n",
                            msgUid, seen ? "" : "[UNREAD]", msgFrom, msgSubject,
                            sentDate != null ? sentDate.toString() : UNKNOWN_DATE));

                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put(PARAM_UID, msgUid);
                    info.put(PARAM_FROM, msgFrom);
                    info.put(PARAM_SUBJECT, msgSubject);
                    info.put("date", sentDate != null ? sentDate.toString() : null);
                    info.put("seen", seen);
                    messageList.add(info);
                }

                return ToolResult.success(sb.toString(), Map.of(
                        "messages", messageList,
                        "totalFound", results.length));
            } finally {
                folder.close(false);
            }
        }
    }

    // ==================== Body extraction ====================

    private BodyContent extractBody(Part part, int depth) throws MessagingException, IOException {
        if (depth > MAX_MULTIPART_DEPTH) {
            return new BodyContent("[Content too deeply nested]", List.of());
        }

        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            return new BodyContent(content != null ? content.toString() : "", List.of());
        }

        if (part.isMimeType("text/html")) {
            Object content = part.getContent();
            String html = content != null ? content.toString() : "";
            return new BodyContent(HtmlSanitizer.stripHtml(html), List.of());
        }

        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            String plainText = null;
            String htmlText = null;
            List<Map<String, String>> attachments = new ArrayList<>();

            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                String disposition = bodyPart.getDisposition();

                if (Part.ATTACHMENT.equalsIgnoreCase(disposition)
                        || (disposition != null && bodyPart.getFileName() != null)) {
                    // Attachment — metadata only
                    Map<String, String> att = new LinkedHashMap<>();
                    att.put("filename", bodyPart.getFileName() != null ? bodyPart.getFileName() : UNKNOWN_DATE);
                    att.put("contentType", bodyPart.getContentType());
                    att.put("size", String.valueOf(bodyPart.getSize()));
                    attachments.add(att);
                } else {
                    BodyContent nested = extractBody(bodyPart, depth + SINGLE_TERM);
                    attachments.addAll(nested.attachments);

                    if (bodyPart.isMimeType("text/plain") && plainText == null) {
                        plainText = nested.text;
                    } else if (bodyPart.isMimeType("text/html") && htmlText == null) {
                        htmlText = nested.text;
                    } else if (bodyPart.isMimeType("multipart/*")) {
                        // Nested multipart — pick up text if we don't have any
                        if (plainText == null && !nested.text.isEmpty()) {
                            plainText = nested.text;
                        }
                    }
                }
            }

            // Prefer plain text over HTML
            String text = plainText != null ? plainText : (htmlText != null ? htmlText : "");
            return new BodyContent(text, attachments);
        }

        // Unknown content type — skip
        return new BodyContent("", List.of());
    }

    // ==================== Helpers ====================

    private String formatAddress(jakarta.mail.Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "";
        }
        return ((InternetAddress) addresses[0]).toUnicodeString();
    }

    private String formatAddresses(jakarta.mail.Address[] addresses) {
        if (addresses == null || addresses.length == 0) {
            return "";
        }
        return Arrays.stream(addresses)
                .map(a -> ((InternetAddress) a).toUnicodeString())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private String getHeader(Message msg, String name) throws MessagingException {
        String[] values = msg.getHeader(name);
        return (values != null && values.length > 0) ? values[0] : null;
    }

    private static String getStringParam(Map<String, Object> params, String key, String defaultValue) {
        Object value = params.get(key);
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return defaultValue;
    }

    private static int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }

    private static Date parseDate(String dateStr) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            return sdf.parse(dateStr);
        } catch (Exception e) { // NOSONAR
            return null;
        }
    }

    String sanitizeError(String message) {
        if (message == null) {
            return "Unknown error";
        }
        String sanitized = message;
        if (config.getUsername() != null && !config.getUsername().isBlank()) {
            sanitized = sanitized.replace(config.getUsername(), "***");
        }
        if (config.getPassword() != null && !config.getPassword().isBlank()) {
            sanitized = sanitized.replace(config.getPassword(), "***");
        }
        return sanitized;
    }

    private record BodyContent(String text, List<Map<String, String>> attachments) {
    }
}
