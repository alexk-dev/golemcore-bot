package me.golemcore.bot.tools;

import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.domain.service.RuntimeConfigService;
import me.golemcore.bot.infrastructure.config.BotProperties;
import jakarta.mail.BodyPart;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({ "PMD.ReplaceJavaUtilDate", "PMD.CloseResource",
        "PMD.AvoidDuplicateLiterals" }) // Jakarta Mail mock setup; mock Folders don't need closing
class ImapToolTest {

    private static final String IMAP_HOST = "imap.example.com";
    private static final String USERNAME = "user@example.com";
    private static final String PASSWORD = "secret";
    private static final String INBOX = "INBOX";
    private static final String PARAM_OPERATION = "operation";

    private RuntimeConfigService runtimeConfigService;
    private BotProperties.ImapToolProperties imapConfig;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        imapConfig = new BotProperties.ImapToolProperties();
        when(runtimeConfigService.getResolvedImapConfig()).thenReturn(imapConfig);
    }

    // ==================== isEnabled ====================

    @Test
    void shouldBeDisabledByDefault() {
        ImapTool tool = new ImapTool(runtimeConfigService);

        assertFalse(tool.isEnabled());
    }

    @Test
    void shouldBeDisabledWhenEnabledButNoHost() {
        imapConfig.setEnabled(true);
        imapConfig.setHost("");
        imapConfig.setUsername(USERNAME);

        ImapTool tool = new ImapTool(runtimeConfigService);

        assertFalse(tool.isEnabled());
    }

    @Test
    void shouldBeDisabledWhenEnabledButNoUsername() {
        imapConfig.setEnabled(true);
        imapConfig.setHost(IMAP_HOST);
        imapConfig.setUsername("");

        ImapTool tool = new ImapTool(runtimeConfigService);

        assertFalse(tool.isEnabled());
    }

    @Test
    void shouldBeEnabledWhenFullyConfigured() {
        configureProperties();

        ImapTool tool = new ImapTool(runtimeConfigService);

        assertTrue(tool.isEnabled());
    }

    @Test
    void shouldUseLatestRuntimeConfigForEnabledCheck() {
        BotProperties.ImapToolProperties initialConfig = new BotProperties.ImapToolProperties();
        BotProperties.ImapToolProperties updatedConfig = new BotProperties.ImapToolProperties();
        updatedConfig.setEnabled(true);
        updatedConfig.setHost(IMAP_HOST);
        updatedConfig.setUsername(USERNAME);
        updatedConfig.setPassword(PASSWORD);
        when(runtimeConfigService.getResolvedImapConfig()).thenReturn(initialConfig, updatedConfig);

        ImapTool tool = new ImapTool(runtimeConfigService);

        assertFalse(tool.isEnabled());
        assertTrue(tool.isEnabled());
    }

    // ==================== Parameter validation ====================

    @Test
    void shouldFailWithMissingOperation() throws ExecutionException, InterruptedException {
        configureProperties();
        ImapTool tool = new ImapTool(runtimeConfigService);

        ToolResult result = tool.execute(Map.of()).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(PARAM_OPERATION));
    }

    @Test
    void shouldFailWithUnknownOperation() throws ExecutionException, InterruptedException {
        configureProperties();
        ImapTool tool = new ImapTool(runtimeConfigService);

        ToolResult result = tool.execute(Map.of(PARAM_OPERATION, "unknown_op")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Unknown operation"));
    }

    @Test
    void shouldFailWithMissingUidForReadMessage() throws ExecutionException, InterruptedException {
        configureProperties();

        ImapTool tool = new ImapTool(runtimeConfigService) {
            @Override
            Store connectStore() throws MessagingException {
                Store store = mock(Store.class);
                Folder folder = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
                when(folder.exists()).thenReturn(true);
                when(store.getFolder(INBOX)).thenReturn(folder);
                return store;
            }
        };

        ToolResult result = tool.execute(Map.of(PARAM_OPERATION, "read_message")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("uid"));
    }

    // ==================== list_folders ====================

    @Test
    void shouldListFolders() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            Folder defaultFolder = mock(Folder.class);
            Folder inbox = mock(Folder.class);
            when(inbox.getFullName()).thenReturn(INBOX);
            when(inbox.getType()).thenReturn(Folder.HOLDS_MESSAGES);
            when(inbox.getMessageCount()).thenReturn(42);
            when(inbox.getUnreadMessageCount()).thenReturn(5);

            Folder sent = mock(Folder.class);
            when(sent.getFullName()).thenReturn("Sent");
            when(sent.getType()).thenReturn(Folder.HOLDS_MESSAGES);
            when(sent.getMessageCount()).thenReturn(100);
            when(sent.getUnreadMessageCount()).thenReturn(0);

            when(defaultFolder.list("*")).thenReturn(new Folder[] { inbox, sent });
            when(store.getDefaultFolder()).thenReturn(defaultFolder);
        });

        ToolResult result = tool.execute(Map.of(PARAM_OPERATION, "list_folders")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains(INBOX));
        assertTrue(result.getOutput().contains("42"));
        assertTrue(result.getOutput().contains("5 unread"));
        assertTrue(result.getOutput().contains("Sent"));
    }

    @Test
    void shouldListFoldersIncludingNonMessageFolders() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            Folder defaultFolder = mock(Folder.class);

            Folder containerOnly = mock(Folder.class);
            when(containerOnly.getFullName()).thenReturn("Archive");
            when(containerOnly.getType()).thenReturn(Folder.HOLDS_FOLDERS);

            when(defaultFolder.list("*")).thenReturn(new Folder[] { containerOnly });
            when(store.getDefaultFolder()).thenReturn(defaultFolder);
        });

        ToolResult result = tool.execute(Map.of(PARAM_OPERATION, "list_folders")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Archive"));
        assertTrue(result.getOutput().contains("folder only"));
    }

    // ==================== list_messages ====================

    @Test
    void shouldListMessagesNewestFirst() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);
            when(folder.getFullName()).thenReturn(INBOX);
            when(folder.getMessageCount()).thenReturn(2);

            Message msg1 = createMockMessage("alice@example.com", "Hello", new Date(), true, false);
            Message msg2 = createMockMessage("bob@example.com", "World", new Date(), false, true);

            when(folder.getMessages(1, 2)).thenReturn(new Message[] { msg1, msg2 });
            when(folder.getUID(msg1)).thenReturn(101L);
            when(folder.getUID(msg2)).thenReturn(102L);

            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(PARAM_OPERATION, "list_messages")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("102"));
        assertTrue(result.getOutput().contains("101"));
        assertTrue(result.getOutput().contains("[UNREAD]"));
    }

    @Test
    void shouldReturnEmptyWhenNoMessages() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);
            when(folder.getFullName()).thenReturn(INBOX);
            when(folder.getMessageCount()).thenReturn(0);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(PARAM_OPERATION, "list_messages")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("No messages"));
    }

    @Test
    void shouldFailWhenFolderNotFound() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            Folder folder = mock(Folder.class);
            when(folder.exists()).thenReturn(false);
            when(store.getFolder("NonExistent")).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "list_messages",
                "folder", "NonExistent")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Folder not found"));
    }

    @Test
    void shouldHandleOffsetBeyondTotal() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);
            when(folder.getFullName()).thenReturn(INBOX);
            when(folder.getMessageCount()).thenReturn(5);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "list_messages",
                "offset", 10)).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("No more messages"));
    }

    @Test
    void shouldListMessagesWithCustomFolderAndLimit() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);
            when(folder.getFullName()).thenReturn("Sent");
            when(folder.getMessageCount()).thenReturn(3);

            Message msg1 = createMockMessage("me@example.com", "Sent 1", new Date(), true, false);
            Message msg2 = createMockMessage("me@example.com", "Sent 2", new Date(), true, false);

            when(folder.getMessages(2, 3)).thenReturn(new Message[] { msg1, msg2 });
            when(folder.getUID(msg1)).thenReturn(50L);
            when(folder.getUID(msg2)).thenReturn(51L);

            when(store.getFolder("Sent")).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "list_messages",
                "folder", "Sent",
                "limit", 2)).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Sent"));
    }

    @Test
    void shouldShowFlaggedMessages() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);
            when(folder.getFullName()).thenReturn(INBOX);
            when(folder.getMessageCount()).thenReturn(1);

            Message msg = createMockMessage("alice@example.com", "Important", new Date(), false, true);
            when(folder.getMessages(1, 1)).thenReturn(new Message[] { msg });
            when(folder.getUID(msg)).thenReturn(300L);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(PARAM_OPERATION, "list_messages")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("[FLAGGED]"));
        assertTrue(result.getOutput().contains("[UNREAD]"));
    }

    @Test
    void shouldHandleMessageWithNullSubject() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);
            when(folder.getFullName()).thenReturn(INBOX);
            when(folder.getMessageCount()).thenReturn(1);

            Message msg = createMockMessage("alice@example.com", null, new Date(), true, false);
            when(folder.getMessages(1, 1)).thenReturn(new Message[] { msg });
            when(folder.getUID(msg)).thenReturn(400L);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(PARAM_OPERATION, "list_messages")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("(no subject)"));
    }

    @Test
    void shouldHandleMessageWithNullDate() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);
            when(folder.getFullName()).thenReturn(INBOX);
            when(folder.getMessageCount()).thenReturn(1);

            Message msg = createMockMessage("alice@example.com", "Test", null, true, false);
            when(folder.getMessages(1, 1)).thenReturn(new Message[] { msg });
            when(folder.getUID(msg)).thenReturn(500L);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(PARAM_OPERATION, "list_messages")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("unknown"));
    }

    // ==================== read_message ====================

    @Test
    void shouldReadPlainTextMessage() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);

            Message msg = mock(Message.class);
            when(msg.getFrom()).thenReturn(new jakarta.mail.Address[] { new InternetAddress("sender@example.com") });
            when(msg.getRecipients(Message.RecipientType.TO))
                    .thenReturn(new jakarta.mail.Address[] { new InternetAddress("to@example.com") });
            when(msg.getRecipients(Message.RecipientType.CC)).thenReturn(null);
            when(msg.getSubject()).thenReturn("Test Subject");
            when(msg.getSentDate()).thenReturn(new Date());
            when(msg.getHeader("Message-ID")).thenReturn(new String[] { "<msg123@example.com>" });
            when(msg.isMimeType("text/plain")).thenReturn(true);
            when(msg.getContent()).thenReturn("Hello, this is the body.");

            when(folder.getMessageByUID(100L)).thenReturn(msg);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "read_message",
                "uid", 100)).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("sender@example.com"));
        assertTrue(result.getOutput().contains("to@example.com"));
        assertTrue(result.getOutput().contains("Test Subject"));
        assertTrue(result.getOutput().contains("Hello, this is the body."));
        assertTrue(result.getOutput().contains("Message-ID:"));
    }

    @Test
    void shouldReadHtmlMessageAndStripTags() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);

            Message msg = mock(Message.class);
            when(msg.getFrom()).thenReturn(new jakarta.mail.Address[] { new InternetAddress("sender@example.com") });
            when(msg.getRecipients(Message.RecipientType.TO))
                    .thenReturn(new jakarta.mail.Address[] { new InternetAddress("to@example.com") });
            when(msg.getRecipients(Message.RecipientType.CC)).thenReturn(null);
            when(msg.getSubject()).thenReturn("HTML Email");
            when(msg.getSentDate()).thenReturn(new Date());
            when(msg.getHeader("Message-ID")).thenReturn(null);
            when(msg.isMimeType("text/plain")).thenReturn(false);
            when(msg.isMimeType("text/html")).thenReturn(true);
            when(msg.getContent()).thenReturn("<p>Hello &amp; <b>World</b></p>");

            when(folder.getMessageByUID(200L)).thenReturn(msg);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "read_message",
                "uid", 200)).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Hello & World"));
        assertFalse(result.getOutput().contains("Message-ID:"));
    }

    @Test
    void shouldReadMultipartMessageWithPlainAndHtml() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);

            BodyPart plainPart = mock(BodyPart.class);
            when(plainPart.getDisposition()).thenReturn(null);
            when(plainPart.isMimeType("text/plain")).thenReturn(true);
            when(plainPart.isMimeType("text/html")).thenReturn(false);
            when(plainPart.isMimeType("multipart/*")).thenReturn(false);
            when(plainPart.getContent()).thenReturn("Plain text body");

            BodyPart htmlPart = mock(BodyPart.class);
            when(htmlPart.getDisposition()).thenReturn(null);
            when(htmlPart.isMimeType("text/plain")).thenReturn(false);
            when(htmlPart.isMimeType("text/html")).thenReturn(true);
            when(htmlPart.isMimeType("multipart/*")).thenReturn(false);
            when(htmlPart.getContent()).thenReturn("<p>HTML body</p>");

            Multipart multipart = mock(Multipart.class);
            when(multipart.getCount()).thenReturn(2);
            when(multipart.getBodyPart(0)).thenReturn(plainPart);
            when(multipart.getBodyPart(1)).thenReturn(htmlPart);

            Message msg = mock(Message.class);
            when(msg.getFrom()).thenReturn(new jakarta.mail.Address[] { new InternetAddress("sender@example.com") });
            when(msg.getRecipients(Message.RecipientType.TO))
                    .thenReturn(new jakarta.mail.Address[] { new InternetAddress("to@example.com") });
            when(msg.getRecipients(Message.RecipientType.CC)).thenReturn(null);
            when(msg.getSubject()).thenReturn("Multipart");
            when(msg.getSentDate()).thenReturn(new Date());
            when(msg.getHeader("Message-ID")).thenReturn(null);
            when(msg.isMimeType("text/plain")).thenReturn(false);
            when(msg.isMimeType("text/html")).thenReturn(false);
            when(msg.isMimeType("multipart/*")).thenReturn(true);
            when(msg.getContent()).thenReturn(multipart);

            when(folder.getMessageByUID(300L)).thenReturn(msg);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "read_message",
                "uid", 300)).get();

        assertTrue(result.isSuccess());
        // Should prefer plain text over HTML
        assertTrue(result.getOutput().contains("Plain text body"));
    }

    @Test
    void shouldReadMessageWithAttachments() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);

            BodyPart textPart = mock(BodyPart.class);
            when(textPart.getDisposition()).thenReturn(null);
            when(textPart.isMimeType("text/plain")).thenReturn(true);
            when(textPart.isMimeType("text/html")).thenReturn(false);
            when(textPart.isMimeType("multipart/*")).thenReturn(false);
            when(textPart.getContent()).thenReturn("See attached.");

            BodyPart attachment = mock(BodyPart.class);
            when(attachment.getDisposition()).thenReturn(Part.ATTACHMENT);
            when(attachment.getFileName()).thenReturn("document.pdf");
            when(attachment.getContentType()).thenReturn("application/pdf");
            when(attachment.getSize()).thenReturn(12345);

            Multipart multipart = mock(Multipart.class);
            when(multipart.getCount()).thenReturn(2);
            when(multipart.getBodyPart(0)).thenReturn(textPart);
            when(multipart.getBodyPart(1)).thenReturn(attachment);

            Message msg = mock(Message.class);
            when(msg.getFrom()).thenReturn(new jakarta.mail.Address[] { new InternetAddress("sender@example.com") });
            when(msg.getRecipients(Message.RecipientType.TO))
                    .thenReturn(new jakarta.mail.Address[] { new InternetAddress("to@example.com") });
            when(msg.getRecipients(Message.RecipientType.CC)).thenReturn(null);
            when(msg.getSubject()).thenReturn("With attachment");
            when(msg.getSentDate()).thenReturn(new Date());
            when(msg.getHeader("Message-ID")).thenReturn(null);
            when(msg.isMimeType("text/plain")).thenReturn(false);
            when(msg.isMimeType("text/html")).thenReturn(false);
            when(msg.isMimeType("multipart/*")).thenReturn(true);
            when(msg.getContent()).thenReturn(multipart);

            when(folder.getMessageByUID(400L)).thenReturn(msg);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "read_message",
                "uid", 400)).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("See attached."));
        assertTrue(result.getOutput().contains("Attachments (1)"));
        assertTrue(result.getOutput().contains("document.pdf"));
    }

    @Test
    void shouldReadMessageWithCcRecipients() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);

            Message msg = mock(Message.class);
            when(msg.getFrom()).thenReturn(new jakarta.mail.Address[] { new InternetAddress("sender@example.com") });
            when(msg.getRecipients(Message.RecipientType.TO))
                    .thenReturn(new jakarta.mail.Address[] { new InternetAddress("to@example.com") });
            when(msg.getRecipients(Message.RecipientType.CC))
                    .thenReturn(new jakarta.mail.Address[] {
                            new InternetAddress("cc1@example.com"),
                            new InternetAddress("cc2@example.com")
                    });
            when(msg.getSubject()).thenReturn("CC test");
            when(msg.getSentDate()).thenReturn(new Date());
            when(msg.getHeader("Message-ID")).thenReturn(null);
            when(msg.isMimeType("text/plain")).thenReturn(true);
            when(msg.getContent()).thenReturn("Body");

            when(folder.getMessageByUID(500L)).thenReturn(msg);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "read_message",
                "uid", 500)).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Cc:"));
        assertTrue(result.getOutput().contains("cc1@example.com"));
        assertTrue(result.getOutput().contains("cc2@example.com"));
    }

    @Test
    void shouldFailWhenMessageNotFound() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);
            when(folder.getMessageByUID(999L)).thenReturn(null);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "read_message",
                "uid", 999)).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Message not found"));
    }

    @Test
    void shouldTruncateLongMessageBody() throws Exception {
        imapConfig.setMaxBodyLength(20);
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);

            Message msg = mock(Message.class);
            when(msg.getFrom()).thenReturn(new jakarta.mail.Address[] { new InternetAddress("s@x.com") });
            when(msg.getRecipients(Message.RecipientType.TO))
                    .thenReturn(new jakarta.mail.Address[] { new InternetAddress("t@x.com") });
            when(msg.getRecipients(Message.RecipientType.CC)).thenReturn(null);
            when(msg.getSubject()).thenReturn("Long body");
            when(msg.getSentDate()).thenReturn(new Date());
            when(msg.getHeader("Message-ID")).thenReturn(null);
            when(msg.isMimeType("text/plain")).thenReturn(true);
            when(msg.getContent()).thenReturn("A".repeat(100));

            when(folder.getMessageByUID(600L)).thenReturn(msg);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "read_message",
                "uid", 600)).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("[Body truncated]"));
    }

    @Test
    void shouldReadMessageWithUnknownContentType() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);

            Message msg = mock(Message.class);
            when(msg.getFrom()).thenReturn(new jakarta.mail.Address[] { new InternetAddress("s@x.com") });
            when(msg.getRecipients(Message.RecipientType.TO))
                    .thenReturn(new jakarta.mail.Address[] { new InternetAddress("t@x.com") });
            when(msg.getRecipients(Message.RecipientType.CC)).thenReturn(null);
            when(msg.getSubject()).thenReturn("Binary");
            when(msg.getSentDate()).thenReturn(null);
            when(msg.getHeader("Message-ID")).thenReturn(null);
            when(msg.isMimeType("text/plain")).thenReturn(false);
            when(msg.isMimeType("text/html")).thenReturn(false);
            when(msg.isMimeType("multipart/*")).thenReturn(false);

            when(folder.getMessageByUID(700L)).thenReturn(msg);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "read_message",
                "uid", 700)).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("unknown"));
    }

    @Test
    void shouldReadFolderNotFoundForReadMessage() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            Folder folder = mock(Folder.class);
            when(folder.exists()).thenReturn(false);
            when(store.getFolder("Trash")).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "read_message",
                "folder", "Trash",
                "uid", 1)).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Folder not found"));
    }

    // ==================== search_messages ====================

    @Test
    void shouldFailSearchWithNoCriteria() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(PARAM_OPERATION, "search_messages")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("search criterion"));
    }

    @Test
    void shouldSearchBySubject() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);
            when(folder.getFullName()).thenReturn(INBOX);

            Message msg = createMockMessage("alice@example.com", "Invoice #123", new Date(), false, false);
            when(folder.search(any())).thenReturn(new Message[] { msg });
            when(folder.getUID(msg)).thenReturn(200L);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "search_messages",
                "subject", "Invoice")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Invoice #123"));
    }

    @Test
    void shouldSearchByFrom() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);
            when(folder.getFullName()).thenReturn(INBOX);

            Message msg = createMockMessage("boss@company.com", "Meeting", new Date(), true, false);
            when(folder.search(any())).thenReturn(new Message[] { msg });
            when(folder.getUID(msg)).thenReturn(201L);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "search_messages",
                "from", "boss@company.com")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Meeting"));
    }

    @Test
    void shouldSearchByDateRange() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);
            when(folder.getFullName()).thenReturn(INBOX);

            Message msg = createMockMessage("alice@example.com", "Recent", new Date(), false, false);
            when(folder.search(any())).thenReturn(new Message[] { msg });
            when(folder.getUID(msg)).thenReturn(202L);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "search_messages",
                "since", "2026-01-01",
                "before", "2026-12-31")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Recent"));
    }

    @Test
    void shouldSearchByUnseenFlag() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);
            when(folder.getFullName()).thenReturn(INBOX);

            Message msg = createMockMessage("alice@example.com", "Unread", new Date(), false, false);
            when(folder.search(any())).thenReturn(new Message[] { msg });
            when(folder.getUID(msg)).thenReturn(203L);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "search_messages",
                "unseen", true)).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Unread"));
    }

    @Test
    void shouldReturnNoResultsWhenSearchFindsNothing() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);
            when(folder.getFullName()).thenReturn(INBOX);
            when(folder.search(any())).thenReturn(new Message[0]);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "search_messages",
                "subject", "nonexistent")).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("No messages found"));
    }

    @Test
    void shouldSearchFolderNotFound() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            Folder folder = mock(Folder.class);
            when(folder.exists()).thenReturn(false);
            when(store.getFolder("Missing")).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "search_messages",
                "folder", "Missing",
                "from", "test@test.com")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Folder not found"));
    }

    @Test
    void shouldSearchWithMultipleCriteriaCombined() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);
            when(folder.getFullName()).thenReturn(INBOX);

            Message msg = createMockMessage("alice@example.com", "Report", new Date(), false, false);
            when(folder.search(any())).thenReturn(new Message[] { msg });
            when(folder.getUID(msg)).thenReturn(204L);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "search_messages",
                "from", "alice@example.com",
                "subject", "Report",
                "unseen", true)).get();

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().contains("Report"));
    }

    @Test
    void shouldIgnoreInvalidDateInSearch() throws Exception {
        ImapTool tool = createToolWithMockStore((store) -> {
            TestUIDFolder folder = mock(TestUIDFolder.class);
            when(folder.exists()).thenReturn(true);
            when(store.getFolder(INBOX)).thenReturn(folder);
        });

        // Invalid date should be skipped; with no other criteria, should fail
        ToolResult result = tool.execute(Map.of(
                PARAM_OPERATION, "search_messages",
                "since", "not-a-date")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("search criterion"));
    }

    // ==================== Error handling ====================

    @Test
    void shouldSanitizeCredentialsInError() {
        imapConfig.setEnabled(true);
        imapConfig.setHost(IMAP_HOST);
        imapConfig.setUsername(USERNAME);
        imapConfig.setPassword("secret123");

        ImapTool tool = new ImapTool(runtimeConfigService);

        String sanitized = tool.sanitizeError("Login failed for " + USERNAME + " with password secret123");

        assertTrue(sanitized.contains("***"));
        assertFalse(sanitized.contains(USERNAME));
        assertFalse(sanitized.contains("secret123"));
    }

    @Test
    void shouldHandleNullErrorMessage() {
        ImapTool tool = new ImapTool(runtimeConfigService);
        assertEquals("Unknown error", tool.sanitizeError(null));
    }

    @Test
    void shouldSanitizeErrorWithNoCredentials() {
        ImapTool tool = new ImapTool(runtimeConfigService);

        String sanitized = tool.sanitizeError("Connection timed out");

        assertEquals("Connection timed out", sanitized);
    }

    @Test
    void shouldHandleMessagingExceptionInExecute() throws ExecutionException, InterruptedException {
        configureProperties();

        ImapTool tool = new ImapTool(runtimeConfigService) {
            @Override
            Store connectStore() throws MessagingException {
                throw new MessagingException("Connection refused");
            }
        };

        ToolResult result = tool.execute(Map.of(PARAM_OPERATION, "list_folders")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("IMAP error"));
    }

    @Test
    void shouldHandleRuntimeExceptionInExecute() throws ExecutionException, InterruptedException {
        configureProperties();

        ImapTool tool = new ImapTool(runtimeConfigService) {
            @Override
            Store connectStore() throws MessagingException {
                throw new RuntimeException("Unexpected error");
            }
        };

        ToolResult result = tool.execute(Map.of(PARAM_OPERATION, "list_messages")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("IMAP error"));
    }

    // ==================== Helpers ====================

    private void configureProperties() {
        imapConfig.setEnabled(true);
        imapConfig.setHost(IMAP_HOST);
        imapConfig.setUsername(USERNAME);
        imapConfig.setPassword(PASSWORD);
    }

    private ImapTool createToolWithMockStore(StoreConfigurer configurer) throws MessagingException {
        configureProperties();

        return new ImapTool(runtimeConfigService) {
            @Override
            Store connectStore() throws MessagingException {
                try {
                    Store store = mock(Store.class);
                    configurer.configure(store);
                    return store;
                } catch (IOException e) {
                    throw new MessagingException("IO error in test setup", e);
                }
            }
        };
    }

    private static Message createMockMessage(String from, String subject, Date date,
            boolean seen, boolean flagged) throws MessagingException {
        Message msg = mock(Message.class);
        when(msg.getFrom()).thenReturn(new jakarta.mail.Address[] { new InternetAddress(from) });
        when(msg.getSubject()).thenReturn(subject);
        when(msg.getSentDate()).thenReturn(date);
        when(msg.isSet(Flags.Flag.SEEN)).thenReturn(seen);
        when(msg.isSet(Flags.Flag.FLAGGED)).thenReturn(flagged);
        return msg;
    }

    @FunctionalInterface
    interface StoreConfigurer {
        void configure(Store store) throws MessagingException, IOException;
    }

    /** Abstract class combining Folder and UIDFolder for mocking. */
    abstract static class TestUIDFolder extends Folder implements UIDFolder {
        protected TestUIDFolder(Store store) {
            super(store);
        }
    }
}
