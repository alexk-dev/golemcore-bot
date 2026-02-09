package me.golemcore.bot.routing;

import me.golemcore.bot.domain.component.MessageAggregator;
import me.golemcore.bot.domain.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessageContextAggregatorTest {

    private MessageContextAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new MessageContextAggregator();
    }

    @Test
    void buildRoutingQuery_standaloneMessage_returnsAsIs() {
        List<Message> history = List.of(
                userMessage("Помоги написать unit тесты для класса UserService", now()));

        String result = aggregator.buildRoutingQuery(history);

        assertEquals("Помоги написать unit тесты для класса UserService", result);
    }

    @Test
    void buildRoutingQuery_fragmentedMessages_aggregates() {
        Instant base = now();
        List<Message> history = List.of(
                userMessage("посмотри код", base),
                userMessage("в файле main.py", base.plusSeconds(5)),
                userMessage("нужно найти баги", base.plusSeconds(10)));

        String result = aggregator.buildRoutingQuery(history);

        assertEquals("посмотри код в файле main.py нужно найти баги", result);
    }

    @Test
    void buildRoutingQuery_messageWithBackReference_aggregates() {
        Instant base = now();
        List<Message> history = List.of(
                userMessage("Вот мой код на Python", base),
                userMessage("проверь его на ошибки", base.plusSeconds(10)));

        String result = aggregator.buildRoutingQuery(history);

        // "его" is a back reference, should aggregate
        assertEquals("Вот мой код на Python проверь его на ошибки", result);
    }

    @Test
    void buildRoutingQuery_continuationMarker_aggregates() {
        Instant base = now();
        List<Message> history = List.of(
                userMessage("Сделай рефакторинг", base),
                userMessage("и добавь комментарии", base.plusSeconds(5)));

        String result = aggregator.buildRoutingQuery(history);

        assertEquals("Сделай рефакторинг и добавь комментарии", result);
    }

    @Test
    void buildRoutingQuery_lowercaseStart_aggregates() {
        Instant base = now();
        List<Message> history = List.of(
                userMessage("Проверь код", base),
                userMessage("особенно функцию main", base.plusSeconds(5)));

        String result = aggregator.buildRoutingQuery(history);

        assertEquals("Проверь код особенно функцию main", result);
    }

    @Test
    void buildRoutingQuery_largeTimeGap_doesNotAggregate() {
        Instant base = now();
        List<Message> history = List.of(
                userMessage("Старый запрос", base),
                userMessage("Новый самостоятельный запрос на рефакторинг кода", base.plusSeconds(120)));

        String result = aggregator.buildRoutingQuery(history);

        // Large time gap + new message is standalone
        assertEquals("Новый самостоятельный запрос на рефакторинг кода", result);
    }

    @Test
    void buildRoutingQuery_incompleteEnding_aggregates() {
        Instant base = now();
        List<Message> history = List.of(
                userMessage("Мне нужно:", base),
                userMessage("проверить код на баги", base.plusSeconds(3)));

        String result = aggregator.buildRoutingQuery(history);

        assertEquals("Мне нужно: проверить код на баги", result);
    }

    @Test
    void buildRoutingQuery_mixedRoles_onlyUserMessages() {
        Instant base = now();
        List<Message> history = List.of(
                userMessage("Привет", base),
                assistantMessage("Здравствуйте! Чем могу помочь?", base.plusSeconds(1)),
                userMessage("нужен code review", base.plusSeconds(5)));

        String result = aggregator.buildRoutingQuery(history);

        // Should aggregate user messages only
        assertTrue(result.contains("Привет"));
        assertTrue(result.contains("code review"));
        assertFalse(result.contains("Здравствуйте"));
    }

    @Test
    void buildRoutingQuery_emptyHistory_returnsEmpty() {
        String result = aggregator.buildRoutingQuery(List.of());

        assertEquals("", result);
    }

    @Test
    void buildRoutingQuery_englishContinuation_aggregates() {
        Instant base = now();
        List<Message> history = List.of(
                userMessage("Check the code", base),
                userMessage("also add tests", base.plusSeconds(5)));

        String result = aggregator.buildRoutingQuery(history);

        assertEquals("Check the code also add tests", result);
    }

    @Test
    void analyze_standaloneMessage_notFragmented() {
        List<Message> history = List.of(
                userMessage("Помоги написать документацию для API", now()));

        MessageAggregator.AggregationAnalysis analysis = aggregator.analyze(history);

        assertFalse(analysis.isFragmented());
        assertEquals("Standalone", analysis.summary());
    }

    @Test
    void analyze_fragmentedMessage_detectsSignals() {
        Instant base = now();
        List<Message> history = List.of(
                userMessage("код", base),
                userMessage("там баг", base.plusSeconds(2)));

        MessageAggregator.AggregationAnalysis analysis = aggregator.analyze(history);

        assertTrue(analysis.isFragmented());
        assertTrue(analysis.signals().contains("too_short"));
        assertTrue(analysis.signals().contains("has_back_reference")); // "там"
    }

    @Test
    void isFragmented_shortMessageWithReference_true() {
        List<Message> history = List.of(
                userMessage("проверь это", now()));

        boolean result = aggregator.isFragmented("проверь это", history);

        // Short + back reference ("это")
        assertTrue(result);
    }

    @Test
    void isFragmented_longStandaloneMessage_false() {
        List<Message> history = List.of(
                userMessage("Пожалуйста, проведи code review моего pull request и укажи на возможные проблемы", now()));

        boolean result = aggregator.isFragmented(
                "Пожалуйста, проведи code review моего pull request и укажи на возможные проблемы",
                history);

        assertFalse(result);
    }

    @Test
    void buildRoutingQuery_tripleFragmentation_aggregatesAll() {
        Instant base = now();
        List<Message> history = List.of(
                userMessage("код", base),
                userMessage("в main.py", base.plusSeconds(2)),
                userMessage("рефакторинг нужен", base.plusSeconds(4)));

        String result = aggregator.buildRoutingQuery(history);

        assertEquals("код в main.py рефакторинг нужен", result);
    }

    // Helper methods

    private Message userMessage(String content, Instant timestamp) {
        return Message.builder()
                .role("user")
                .content(content)
                .timestamp(timestamp)
                .build();
    }

    private Message assistantMessage(String content, Instant timestamp) {
        return Message.builder()
                .role("assistant")
                .content(content)
                .timestamp(timestamp)
                .build();
    }

    private Instant now() {
        return Instant.now();
    }
}
