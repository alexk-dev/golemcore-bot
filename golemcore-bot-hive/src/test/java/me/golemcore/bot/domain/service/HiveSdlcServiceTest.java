package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.RuntimeConfig;
import me.golemcore.bot.domain.model.hive.HiveCardDetail;
import me.golemcore.bot.domain.model.hive.HiveCardSearchRequest;
import me.golemcore.bot.domain.model.hive.HiveCreateCardRequest;
import me.golemcore.bot.domain.model.hive.HiveRequestReviewRequest;
import me.golemcore.bot.domain.model.hive.HiveThreadMessage;
import me.golemcore.bot.port.outbound.HiveGatewayPort;
import me.golemcore.bot.port.outbound.RuntimeConfigQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiveSdlcServiceTest {

    private RuntimeConfigQueryPort runtimeConfigQueryPort;
    private HiveSessionStateStore hiveSessionStateStore;
    private HiveGatewayPort hiveGatewayPort;
    private HiveSdlcService service;

    @BeforeEach
    void setUp() {
        runtimeConfigQueryPort = mock(RuntimeConfigQueryPort.class);
        hiveSessionStateStore = mock(HiveSessionStateStore.class);
        hiveGatewayPort = mock(HiveGatewayPort.class);
        service = new HiveSdlcService(runtimeConfigQueryPort, hiveSessionStateStore, hiveGatewayPort);
    }

    @Test
    void shouldDelegateCardSearchUsingStoredHiveSession() {
        HiveCardSearchRequest request = new HiveCardSearchRequest(null, "board-1", null, null, null, null, null,
                false);
        arrangeConnectedHiveSession();
        when(hiveGatewayPort.searchCards("https://hive.example.com", "golem-1", "access", request))
                .thenReturn(List.of());

        assertEquals(0, service.searchCards(request).size());

        verify(hiveGatewayPort).searchCards("https://hive.example.com", "golem-1", "access", request);
    }

    @Test
    void shouldDelegateCardMutationsUsingStoredHiveSession() {
        arrangeConnectedHiveSession();
        HiveCardDetail card = cardDetail();
        HiveThreadMessage message = threadMessage();
        HiveCreateCardRequest createRequest = new HiveCreateCardRequest("svc-1", "board-1", "Title",
                "Description", null, "todo", "task", null, null, null, null, null, null, null, null, false);
        HiveRequestReviewRequest reviewRequest = new HiveRequestReviewRequest(List.of("reviewer-1"), null, 1);
        when(hiveGatewayPort.getCard("https://hive.example.com", "golem-1", "access", "card-1"))
                .thenReturn(card);
        when(hiveGatewayPort.createCard("https://hive.example.com", "golem-1", "access", createRequest))
                .thenReturn(card);
        when(hiveGatewayPort.postThreadMessage("https://hive.example.com", "golem-1", "access", "thread-1",
                "please review")).thenReturn(message);
        when(hiveGatewayPort.requestReview("https://hive.example.com", "golem-1", "access", "card-1",
                reviewRequest)).thenReturn(card);

        assertSame(card, service.getCard("card-1"));
        assertSame(card, service.createCard(createRequest));
        assertSame(message, service.postThreadMessage("thread-1", "please review"));
        assertSame(card, service.requestReview("card-1", reviewRequest));

        verify(hiveGatewayPort).getCard("https://hive.example.com", "golem-1", "access", "card-1");
        verify(hiveGatewayPort).createCard("https://hive.example.com", "golem-1", "access", createRequest);
        verify(hiveGatewayPort).postThreadMessage("https://hive.example.com", "golem-1", "access", "thread-1",
                "please review");
        verify(hiveGatewayPort).requestReview("https://hive.example.com", "golem-1", "access", "card-1",
                reviewRequest);
    }

    @Test
    void shouldRejectSdlcOperationsWhenHiveIsDisabled() {
        when(runtimeConfigQueryPort.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder().enabled(false).build())
                .build());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.getCard("card-1"));

        assertEquals("Hive integration is disabled", error.getMessage());
    }

    @Test
    void shouldRejectSdlcOperationsWithoutHiveSession() {
        when(runtimeConfigQueryPort.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder().enabled(true).build())
                .build());
        when(hiveSessionStateStore.load()).thenReturn(Optional.empty());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.getCard("card-1"));

        assertEquals("Hive session is not connected", error.getMessage());
    }

    @Test
    void shouldRejectSdlcOperationsWhenHiveSessionCredentialsAreIncomplete() {
        when(runtimeConfigQueryPort.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder().enabled(true).build())
                .build());
        when(hiveSessionStateStore.load()).thenReturn(Optional.of(HiveSessionState.builder()
                .serverUrl("https://hive.example.com")
                .golemId("golem-1")
                .accessToken(" ")
                .build()));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.getCard("card-1"));

        assertEquals("Hive access token is missing", error.getMessage());
    }

    private void arrangeConnectedHiveSession() {
        when(runtimeConfigQueryPort.getRuntimeConfig()).thenReturn(RuntimeConfig.builder()
                .hive(RuntimeConfig.HiveConfig.builder().enabled(true).build())
                .build());
        when(hiveSessionStateStore.load()).thenReturn(Optional.of(HiveSessionState.builder()
                .serverUrl("https://hive.example.com")
                .golemId("golem-1")
                .accessToken("access")
                .build()));
    }

    private HiveCardDetail cardDetail() {
        return new HiveCardDetail("card-1", "svc-1", "board-1", "task", null, null, List.of(), null, List.of(),
                null, null, null, null, null, "thread-1", "Title", "Description", null, "todo", null, null, 1,
                false, null, null, null, null);
    }

    private HiveThreadMessage threadMessage() {
        return new HiveThreadMessage("message-1", "thread-1", "card-1", null, null, null, "comment", "golem",
                "golem-1", "Golem", "please review", null);
    }
}
