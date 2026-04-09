package me.golemcore.bot.adapter.outbound.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;

class HiveRuntimeMetadataAdapterTest {

    private ObjectProvider<BuildProperties> buildPropertiesProvider;
    private ObjectProvider<GitProperties> gitPropertiesProvider;
    private HiveRuntimeMetadataAdapter adapter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        buildPropertiesProvider = mock(ObjectProvider.class);
        gitPropertiesProvider = mock(ObjectProvider.class);
        adapter = new HiveRuntimeMetadataAdapter(buildPropertiesProvider, gitPropertiesProvider);
    }

    @Test
    void shouldResolveRuntimeAndBuildVersionsFromSpringMetadata() {
        BuildProperties buildProperties = mock(BuildProperties.class);
        GitProperties gitProperties = mock(GitProperties.class);
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(buildProperties);
        when(gitPropertiesProvider.getIfAvailable()).thenReturn(gitProperties);
        when(buildProperties.getVersion()).thenReturn("1.2.3");
        when(gitProperties.getShortCommitId()).thenReturn("abc123");

        assertEquals("1.2.3", adapter.runtimeVersion());
        assertEquals("abc123", adapter.buildVersion());
    }

    @Test
    void shouldFallBackToDevVersionWhenBuildMetadataMissing() {
        when(buildPropertiesProvider.getIfAvailable()).thenReturn(null);
        when(gitPropertiesProvider.getIfAvailable()).thenReturn(null);

        assertEquals("dev", adapter.runtimeVersion());
        assertEquals("dev", adapter.buildVersion());
    }

    @Test
    void shouldResolveHostLabelAndUptimeFromRuntime() {
        assertFalse(adapter.defaultHostLabel().isBlank());
        assertTrue(adapter.uptimeSeconds() >= 0L);
    }
}
