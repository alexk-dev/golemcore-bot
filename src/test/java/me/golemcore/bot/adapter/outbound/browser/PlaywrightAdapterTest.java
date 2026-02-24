package me.golemcore.bot.adapter.outbound.browser;

import me.golemcore.bot.domain.service.RuntimeConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaywrightAdapterTest {

    @Mock
    private RuntimeConfigService runtimeConfigService;
    @Mock
    private PlaywrightDriverBundleService playwrightDriverBundleService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void shouldTryPreparingDriverOnNavigateWhenBrowserEnabled() {
        when(runtimeConfigService.isBrowserEnabled()).thenReturn(true);
        when(playwrightDriverBundleService.ensureDriverReady()).thenThrow(new IllegalStateException("driver missing"));

        PlaywrightAdapter adapter = new PlaywrightAdapter(runtimeConfigService, playwrightDriverBundleService);

        assertThrows(CompletionException.class, () -> adapter.navigate("https://example.com").join());
        verify(playwrightDriverBundleService).ensureDriverReady();
    }

    @Test
    void shouldNotPrepareDriverOnNavigateWhenBrowserDisabled() {
        when(runtimeConfigService.isBrowserEnabled()).thenReturn(false);

        PlaywrightAdapter adapter = new PlaywrightAdapter(runtimeConfigService, playwrightDriverBundleService);

        assertThrows(CompletionException.class, () -> adapter.navigate("https://example.com").join());
        verify(playwrightDriverBundleService, never()).ensureDriverReady();
    }
}
