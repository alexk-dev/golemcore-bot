package me.golemcore.bot.tools;

import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import me.golemcore.bot.infrastructure.config.BotProperties;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({ "PMD.ReplaceJavaUtilDate",
        "PMD.CloseResource" }) // Jakarta Mail mock setup requires java.util.Date; mock Folders don't need
                               // closing
class ImapToolTest {

    private static final String IMAP_HOST = "imap.example.com";
    private static final String USERNAME = "user@example.com";
    private static final String PASSWORD = "secret";
    private static final String INBOX = "INBOX";
    private static final String PARAM_OPERATION = "operation";

    private BotProperties properties;

    @BeforeEach
    void setUp() {
        properties = new BotProperties();
    }

    // ==================== isEnabled ====================

    @Test
    void shouldBeDisabledByDefault() {
        ImapTool tool = new ImapTool(properties);

        assertFalse(tool.isEnabled());
    }

    @Test
    void shouldBeDisabledWhenEnabledButNoHost() {
        BotProperties.ImapToolProperties config = properties.getTools().getImap();
        config.setEnabled(true);
        config.setHost("");
        config.setUsername(USERNAME);

        ImapTool tool = new ImapTool(properties);

        assertFalse(tool.isEnabled());
    }

    @Test
    void shouldBeDisabledWhenEnabledButNoUsername() {
        BotProperties.ImapToolProperties config = properties.getTools().getImap();
        config.setEnabled(true);
        config.setHost(IMAP_HOST);
        config.setUsername("");

        ImapTool tool = new ImapTool(properties);

        assertFalse(tool.isEnabled());
    }

    @Test
    void shouldBeEnabledWhenFullyConfigured() {
        configureProperties();

        ImapTool tool = new ImapTool(properties);

        assertTrue(tool.isEnabled());
    }

    // ==================== Definition ====================

    @Test
    void shouldReturnCorrectDefinition() {
        ImapTool tool = new ImapTool(properties);

        ToolDefinition definition = tool.getDefinition();

        assertEquals("imap", definition.getName());
        assertNotNull(definition.getDescription());
        assertNotNull(definition.getInputSchema());
    }

    // ==================== Parameter validation ====================

    @Test
    void shouldFailWithMissingOperation() throws ExecutionException, InterruptedException {
        configureProperties();
        ImapTool tool = new ImapTool(properties);

        ToolResult result = tool.execute(Map.of()).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains(PARAM_OPERATION));
    }

    @Test
    void shouldFailWithUnknownOperation() throws ExecutionException, InterruptedException {
        configureProperties();
        ImapTool tool = new ImapTool(properties);

        ToolResult result = tool.execute(Map.of(PARAM_OPERATION, "unknown_op")).get();

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Unknown operation"));
    }

    @Test
    void shouldFailWithMissingUidForReadMessage() throws ExecutionException, InterruptedException {
        configureProperties();

        // Create tool with mock store that returns a valid folder
        ImapTool tool = new ImapTool(properties) {
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

    // ==================== Error handling ====================

    @Test
    void shouldSanitizeCredentialsInError() {
        BotProperties.ImapToolProperties config = properties.getTools().getImap();
        config.setEnabled(true);
        config.setHost(IMAP_HOST);
        config.setUsername(USERNAME);
        config.setPassword("secret123");

        ImapTool tool = new ImapTool(properties);

        String sanitized = tool.sanitizeError("Login failed for " + USERNAME + " with password secret123");

        assertTrue(sanitized.contains("***"));
        assertFalse(sanitized.contains(USERNAME));
        assertFalse(sanitized.contains("secret123"));
    }

    @Test
    void shouldHandleNullErrorMessage() {
        ImapTool tool = new ImapTool(properties);
        assertEquals("Unknown error", tool.sanitizeError(null));
    }

    // ==================== Helpers ====================

    private void configureProperties() {
        BotProperties.ImapToolProperties config = properties.getTools().getImap();
        config.setEnabled(true);
        config.setHost(IMAP_HOST);
        config.setUsername(USERNAME);
        config.setPassword(PASSWORD);
    }

    @SuppressWarnings("PMD.CloseResource") // Mock Store does not need closing
    private ImapTool createToolWithMockStore(StoreConfigurer configurer) throws MessagingException {
        configureProperties();

        return new ImapTool(properties) {
            @Override
            Store connectStore() throws MessagingException {
                Store store = mock(Store.class);
                configurer.configure(store);
                return store;
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
        void configure(Store store) throws MessagingException;
    }

    /** Abstract class combining Folder and UIDFolder for mocking. */
    abstract static class TestUIDFolder extends Folder implements UIDFolder {
        protected TestUIDFolder(Store store) {
            super(store);
        }
    }
}
