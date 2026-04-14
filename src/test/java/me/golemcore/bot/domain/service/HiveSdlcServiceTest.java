package me.golemcore.bot.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import me.golemcore.bot.domain.model.HiveSessionState;
import me.golemcore.bot.domain.model.hive.HiveCardSearchRequest;
import me.golemcore.bot.port.outbound.HiveGatewayPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HiveSdlcServiceTest {

    private RuntimeConfigService runtimeConfigService;
    private HiveSessionStateStore hiveSessionStateStore;
    private HiveGatewayPort hiveGatewayPort;
    private HiveSdlcService service;

    @BeforeEach
    void setUp() {
        runtimeConfigService = mock(RuntimeConfigService.class);
        hiveSessionStateStore = mock(HiveSessionStateStore.class);
        hiveGatewayPort = mock(HiveGatewayPort.class);
        service = new HiveSdlcService(runtimeConfigService, hiveSessionStateStore, hiveGatewayPort);
    }

    @Test
    void shouldDelegateCardSearchUsingStoredHiveSession() {
        HiveCardSearchRequest request = new HiveCardSearchRequest(null, "board-1", null, null, null, null, null,
                false);
        when(runtimeConfigService.isHiveEnabled()).thenReturn(true);
        when(hiveSessionStateStore.load()).thenReturn(Optional.of(HiveSessionState.builder()
                .serverUrl("https://hive.example.com")
                .golemId("golem-1")
                .accessToken("access")
                .build()));
        when(hiveGatewayPort.searchCards("https://hive.example.com", "golem-1", "access", request))
                .thenReturn(List.of());

        assertEquals(0, service.searchCards(request).size());

        verify(hiveGatewayPort).searchCards("https://hive.example.com", "golem-1", "access", request);
    }

    @Test
    void shouldRejectSdlcOperationsWhenHiveIsDisabled() {
        when(runtimeConfigService.isHiveEnabled()).thenReturn(false);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.getCard("card-1"));

        assertEquals("Hive integration is disabled", error.getMessage());
    }

    @Test
    void shouldRejectSdlcOperationsWithoutHiveSession() {
        when(runtimeConfigService.isHiveEnabled()).thenReturn(true);
        when(hiveSessionStateStore.load()).thenReturn(Optional.empty());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.getCard("card-1"));

        assertEquals("Hive session is not connected", error.getMessage());
    }
}
