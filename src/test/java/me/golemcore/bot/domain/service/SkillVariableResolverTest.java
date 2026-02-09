package me.golemcore.bot.domain.service;

import me.golemcore.bot.domain.model.SkillVariable;
import me.golemcore.bot.port.outbound.StoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillVariableResolverTest {

    private static final String TEST_SKILL = "test-skill";
    private static final String VAR_KEY = "KEY";
    private static final String VAR_API_KEY = "API_KEY";
    private static final String VAR_ENDPOINT = "ENDPOINT";
    private static final String VAR_TIMEOUT = "TIMEOUT";
    private static final String DEFAULT_TIMEOUT = "30";
    private static final String DEFAULT_ENDPOINT = "https://api.example.com";
    private static final String VARIABLES_FILE = "variables.json";
    private static final String YAML_KEY_DEFAULT = "default";

    private SkillVariableResolver resolver;
    private StoragePort mockStorage;

    @BeforeEach
    void setUp() {
        mockStorage = mock(StoragePort.class);
        resolver = new SkillVariableResolver(mockStorage);

        // Default: no files found
        when(mockStorage.getText(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    // --- parseVariableDefinitions tests ---

    @Test
    void parseVariableDefinitions_fullDefinition() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put(VAR_API_KEY, Map.of(
                "description", "API key",
                "required", true,
                "secret", true));

        List<SkillVariable> result = resolver.parseVariableDefinitions(vars);

        assertEquals(1, result.size());
        SkillVariable v = result.get(0);
        assertEquals(VAR_API_KEY, v.getName());
        assertEquals("API key", v.getDescription());
        assertTrue(v.isRequired());
        assertTrue(v.isSecret());
        assertNull(v.getDefaultValue());
    }

    @Test
    void parseVariableDefinitions_withDefault() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put(VAR_ENDPOINT, Map.of(
                "description", "API endpoint",
                YAML_KEY_DEFAULT, DEFAULT_ENDPOINT));

        List<SkillVariable> result = resolver.parseVariableDefinitions(vars);

        assertEquals(1, result.size());
        assertEquals(DEFAULT_ENDPOINT, result.get(0).getDefaultValue());
        assertFalse(result.get(0).isRequired());
        assertFalse(result.get(0).isSecret());
    }

    @Test
    void parseVariableDefinitions_shorthandString() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put(VAR_TIMEOUT, DEFAULT_TIMEOUT);

        List<SkillVariable> result = resolver.parseVariableDefinitions(vars);

        assertEquals(1, result.size());
        assertEquals(VAR_TIMEOUT, result.get(0).getName());
        assertEquals(DEFAULT_TIMEOUT, result.get(0).getDefaultValue());
        assertFalse(result.get(0).isRequired());
    }

    @Test
    void parseVariableDefinitions_nullInput() {
        List<SkillVariable> result = resolver.parseVariableDefinitions(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseVariableDefinitions_emptyInput() {
        List<SkillVariable> result = resolver.parseVariableDefinitions(Map.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void parseVariableDefinitions_multipleVars() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put(VAR_KEY, Map.of("required", true, "secret", true));
        vars.put(VAR_ENDPOINT, "https://default.api.com");
        vars.put(VAR_TIMEOUT, Map.of(YAML_KEY_DEFAULT, DEFAULT_TIMEOUT));

        List<SkillVariable> result = resolver.parseVariableDefinitions(vars);

        assertEquals(3, result.size());
    }

    // --- resolveVariables tests ---

    @Test
    void resolveVariables_fromPerSkillVarsJson() {
        when(mockStorage.getText("skills", TEST_SKILL + "/vars.json"))
                .thenReturn(CompletableFuture.completedFuture(
                        "{\"API_KEY\": \"per-skill-key\"}"));

        List<SkillVariable> defs = List.of(
                SkillVariable.builder().name(VAR_API_KEY).required(true).build());

        Map<String, String> result = resolver.resolveVariables(TEST_SKILL, defs);

        assertEquals("per-skill-key", result.get(VAR_API_KEY));
    }

    @Test
    void resolveVariables_fromGlobalSkillSection() {
        when(mockStorage.getText("", VARIABLES_FILE))
                .thenReturn(CompletableFuture.completedFuture(
                        "{\"test-skill\": {\"API_KEY\": \"global-skill-key\"}}"));

        List<SkillVariable> defs = List.of(
                SkillVariable.builder().name(VAR_API_KEY).required(true).build());

        Map<String, String> result = resolver.resolveVariables(TEST_SKILL, defs);

        assertEquals("global-skill-key", result.get(VAR_API_KEY));
    }

    @Test
    void resolveVariables_fromGlobalSection() {
        when(mockStorage.getText("", VARIABLES_FILE))
                .thenReturn(CompletableFuture.completedFuture(
                        "{\"_global\": {\"DEFAULT_LANG\": \"ru\"}}"));

        List<SkillVariable> defs = List.of(
                SkillVariable.builder().name("DEFAULT_LANG").build());

        Map<String, String> result = resolver.resolveVariables("some-skill", defs);

        assertEquals("ru", result.get("DEFAULT_LANG"));
    }

    @Test
    void resolveVariables_fromDefault() {
        List<SkillVariable> defs = List.of(
                SkillVariable.builder().name(VAR_TIMEOUT).defaultValue(DEFAULT_TIMEOUT).build());

        Map<String, String> result = resolver.resolveVariables(TEST_SKILL, defs);

        assertEquals(DEFAULT_TIMEOUT, result.get(VAR_TIMEOUT));
    }

    @Test
    void resolveVariables_priority_perSkillOverridesGlobal() {
        when(mockStorage.getText("skills", TEST_SKILL + "/vars.json"))
                .thenReturn(CompletableFuture.completedFuture(
                        "{\"KEY\": \"per-skill\"}"));
        when(mockStorage.getText("", VARIABLES_FILE))
                .thenReturn(CompletableFuture.completedFuture(
                        "{\"_global\": {\"KEY\": \"global\"}, \"test-skill\": {\"KEY\": \"global-skill\"}}"));

        List<SkillVariable> defs = List.of(
                SkillVariable.builder().name(VAR_KEY).defaultValue(YAML_KEY_DEFAULT).build());

        Map<String, String> result = resolver.resolveVariables(TEST_SKILL, defs);

        assertEquals("per-skill", result.get(VAR_KEY));
    }

    @Test
    void resolveVariables_priority_globalSkillSectionOverridesGlobal() {
        when(mockStorage.getText("", VARIABLES_FILE))
                .thenReturn(CompletableFuture.completedFuture(
                        "{\"_global\": {\"KEY\": \"global\"}, \"test-skill\": {\"KEY\": \"global-skill\"}}"));

        List<SkillVariable> defs = List.of(
                SkillVariable.builder().name(VAR_KEY).defaultValue(YAML_KEY_DEFAULT).build());

        Map<String, String> result = resolver.resolveVariables(TEST_SKILL, defs);

        assertEquals("global-skill", result.get(VAR_KEY));
    }

    @Test
    void resolveVariables_emptyDefinitions() {
        Map<String, String> result = resolver.resolveVariables(TEST_SKILL, List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void resolveVariables_nullDefinitions() {
        Map<String, String> result = resolver.resolveVariables(TEST_SKILL, null);
        assertTrue(result.isEmpty());
    }

    @Test
    void resolveVariables_unresolvedVar() {
        List<SkillVariable> defs = List.of(
                SkillVariable.builder().name("NONEXISTENT_VAR_XYZ_12345").build());

        Map<String, String> result = resolver.resolveVariables(TEST_SKILL, defs);

        assertFalse(result.containsKey("NONEXISTENT_VAR_XYZ_12345"));
    }

    // --- findMissingRequired tests ---

    @Test
    void findMissingRequired_detectsMissing() {
        List<SkillVariable> defs = List.of(
                SkillVariable.builder().name(VAR_KEY).required(true).build(),
                SkillVariable.builder().name("OPTIONAL").build());
        Map<String, String> resolved = Map.of("OPTIONAL", "value");

        List<String> missing = resolver.findMissingRequired(defs, resolved);

        assertEquals(List.of(VAR_KEY), missing);
    }

    @Test
    void findMissingRequired_noneWhenAllResolved() {
        List<SkillVariable> defs = List.of(
                SkillVariable.builder().name(VAR_KEY).required(true).build());
        Map<String, String> resolved = Map.of(VAR_KEY, "value");

        List<String> missing = resolver.findMissingRequired(defs, resolved);

        assertTrue(missing.isEmpty());
    }

    @Test
    void findMissingRequired_nullDefinitions() {
        List<String> missing = resolver.findMissingRequired(null, Map.of());
        assertTrue(missing.isEmpty());
    }

    // --- maskSecrets tests ---

    @Test
    void maskSecrets_masksSecretValues() {
        List<SkillVariable> defs = List.of(
                SkillVariable.builder().name(VAR_KEY).secret(true).build(),
                SkillVariable.builder().name(VAR_ENDPOINT).build());
        Map<String, String> resolved = Map.of(
                VAR_KEY, "sk-abc123",
                VAR_ENDPOINT, DEFAULT_ENDPOINT);

        Map<String, String> masked = resolver.maskSecrets(defs, resolved);

        assertEquals("***", masked.get(VAR_KEY));
        assertEquals(DEFAULT_ENDPOINT, masked.get(VAR_ENDPOINT));
    }

    @Test
    void maskSecrets_nullInputs() {
        Map<String, String> result = resolver.maskSecrets(null, null);
        assertTrue(result.isEmpty());
    }
}
