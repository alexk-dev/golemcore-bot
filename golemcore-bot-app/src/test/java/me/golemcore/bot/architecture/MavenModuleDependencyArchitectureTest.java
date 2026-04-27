package me.golemcore.bot.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

class MavenModuleDependencyArchitectureTest {

    private static final Path REPO_ROOT = Paths.get("..").normalize();
    private static final String PROJECT_GROUP_ID = "me.golemcore";
    private static final String APP_ARTIFACT_ID = "bot";
    private static final Map<String, Set<String>> ALLOWED_PROJECT_DEPENDENCIES = Map.ofEntries(
            Map.entry("golemcore-bot-contracts", Set.of()),
            Map.entry("golemcore-bot-runtime-config", Set.of("golemcore-bot-contracts")),
            Map.entry("golemcore-bot-tracing", Set.of("golemcore-bot-contracts", "golemcore-bot-runtime-config")),
            Map.entry("golemcore-bot-sessions",
                    Set.of("golemcore-bot-contracts", "golemcore-bot-runtime-config", "golemcore-bot-tracing")),
            Map.entry("golemcore-bot-memory", Set.of("golemcore-bot-contracts", "golemcore-bot-runtime-config")),
            Map.entry("golemcore-bot-tools", Set.of("golemcore-bot-contracts", "golemcore-bot-runtime-config")),
            Map.entry("golemcore-bot-scheduling",
                    Set.of("golemcore-bot-contracts", "golemcore-bot-runtime-config", "golemcore-bot-tracing")),
            Map.entry("golemcore-bot-runtime-core",
                    Set.of("golemcore-bot-contracts", "golemcore-bot-runtime-config", "golemcore-bot-tracing")),
            Map.entry("golemcore-bot-client", Set.of("golemcore-bot-contracts")),
            Map.entry("golemcore-bot-self-evolving", Set.of("golemcore-bot-contracts", "golemcore-bot-client")),
            Map.entry("golemcore-bot-hive", Set.of("golemcore-bot-contracts", "golemcore-bot-client")),
            Map.entry("golemcore-bot-extensions", Set.of("golemcore-bot-contracts")),
            Map.entry("golemcore-bot-app",
                    Set.of("golemcore-bot-contracts", "golemcore-bot-runtime-config", "golemcore-bot-sessions",
                            "golemcore-bot-memory", "golemcore-bot-tools", "golemcore-bot-tracing",
                            "golemcore-bot-scheduling", "golemcore-bot-runtime-core", "golemcore-bot-client",
                            "golemcore-bot-self-evolving", "golemcore-bot-hive", "golemcore-bot-extensions")));

    @Test
    void allowed_dependency_graph_should_cover_every_parent_maven_module() {
        Set<String> parentModules = new TreeSet<>(readParentModules());
        Set<String> documentedModules = new TreeSet<>(ALLOWED_PROJECT_DEPENDENCIES.keySet());

        assertTrue(parentModules.equals(documentedModules),
                () -> "Every parent Maven module must be represented in the dependency graph. parent=" + parentModules
                        + ", documented=" + documentedModules);
    }

    @Test
    void production_maven_module_dependencies_should_follow_target_graph() {
        Set<String> projectArtifactIds = projectArtifactIds();
        Map<String, String> moduleArtifactIds = new LinkedHashMap<>();
        Map<String, Set<String>> violations = new LinkedHashMap<>();
        for (String module : readParentModules()) {
            ModulePom modulePom = readModulePom(module);
            moduleArtifactIds.put(module, modulePom.artifactId());
            Set<String> allowed = ALLOWED_PROJECT_DEPENDENCIES.get(module);
            Set<String> illegalDependencies = modulePom.productionProjectDependencies().stream()
                    .filter(projectArtifactIds::contains)
                    .filter(dependency -> !allowed.contains(dependency))
                    .collect(Collectors.toCollection(TreeSet::new));
            if (!illegalDependencies.isEmpty()) {
                violations.put(module, illegalDependencies);
            }
        }

        assertTrue(violations.isEmpty(),
                () -> "Maven module dependency graph drifted from docs/ARCHITECTURE.md:\n"
                        + violations.entrySet().stream()
                                .map(entry -> entry.getKey() + " -> " + entry.getValue())
                                .collect(Collectors.joining("\n"))
                        + "\nModule artifact ids: " + moduleArtifactIds);
    }

    @Test
    void feature_modules_should_not_depend_on_app_module() {
        Set<String> offenders = readParentModules().stream()
                .filter(module -> !"golemcore-bot-app".equals(module))
                .filter(module -> readModulePom(module).productionProjectDependencies().contains(APP_ARTIFACT_ID))
                .collect(Collectors.toCollection(TreeSet::new));

        assertTrue(offenders.isEmpty(),
                () -> "Feature/runtime modules must not depend on the app composition root: " + offenders);
    }

    @Test
    void contracts_main_sources_should_not_use_spring_stereotypes_or_injection_annotations() throws IOException {
        Path contractsMain = REPO_ROOT.resolve("golemcore-bot-contracts/src/main/java");
        String annotationPrefix = "@";
        List<String> forbiddenPatterns = List.of("org.springframework.stereotype.", annotationPrefix + "Service",
                annotationPrefix + "Component", annotationPrefix + "Configuration", annotationPrefix + "Bean",
                annotationPrefix + "Autowired", annotationPrefix + "Resource",
                annotationPrefix + "RequiredArgsConstructor");
        Set<String> violations = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(contractsMain)) {
            for (Path path : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                for (String forbiddenPattern : forbiddenPatterns) {
                    if (source.contains(forbiddenPattern)) {
                        violations.add(contractsMain.relativize(path) + " contains " + forbiddenPattern);
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                () -> "Contracts module must stay free of Spring stereotypes and injection annotations:\n"
                        + String.join("\n", violations));
    }

    @Test
    void production_sources_should_use_explicit_injection_constructors() throws IOException {
        List<String> forbiddenPatterns = List.of("import lombok.RequiredArgsConstructor;",
                "@RequiredArgsConstructor", "import org.springframework.beans.factory.annotation.Autowired;",
                "import jakarta.annotation.Resource;", "@Autowired", "@Resource");
        Set<String> violations = new TreeSet<>();
        for (String module : readParentModules()) {
            Path mainSources = REPO_ROOT.resolve(module).resolve("src/main/java");
            if (!Files.exists(mainSources)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(mainSources)) {
                for (Path path : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(path);
                    for (String forbiddenPattern : forbiddenPatterns) {
                        if (source.contains(forbiddenPattern)) {
                            violations.add(module + "/" + mainSources.relativize(path) + " contains "
                                    + forbiddenPattern);
                        }
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                () -> "Production beans must use private final fields and explicit constructors:\n"
                        + String.join("\n", violations));
    }

    @Test
    void ci_should_publish_dependency_report_for_review_visibility() throws IOException {
        String workflow = Files.readString(REPO_ROOT.resolve(".github/workflows/docker-publish.yml"));

        assertTrue(workflow.contains("dependency:tree") && workflow.toLowerCase(Locale.ROOT)
                .contains("dependency-report"), "CI must generate and upload a Maven dependency report artifact");
    }

    private List<String> readParentModules() {
        Path pom = REPO_ROOT.resolve("pom.xml");
        try (InputStream inputStream = Files.newInputStream(pom)) {
            Document document = newDocumentBuilderFactory().newDocumentBuilder().parse(inputStream);
            Element project = document.getDocumentElement();
            Element modules = directChild(project, "modules");
            if (modules == null) {
                return List.of();
            }
            NodeList moduleNodes = modules.getChildNodes();
            List<String> result = new java.util.ArrayList<>();
            for (int index = 0; index < moduleNodes.getLength(); index++) {
                Node moduleNode = moduleNodes.item(index);
                if (moduleNode instanceof Element module && "module".equals(module.getTagName())) {
                    result.add(module.getTextContent().trim());
                }
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse parent Maven POM: " + pom, exception);
        }
    }

    private Set<String> projectArtifactIds() {
        return readParentModules().stream()
                .map(this::readModulePom)
                .map(ModulePom::artifactId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private ModulePom readModulePom(String module) {
        Path pom = REPO_ROOT.resolve(module).resolve("pom.xml");
        try (InputStream inputStream = Files.newInputStream(pom)) {
            Document document = newDocumentBuilderFactory().newDocumentBuilder().parse(inputStream);
            Element project = document.getDocumentElement();
            String artifactId = directChildText(project, "artifactId");
            Set<String> productionProjectDependencies = new TreeSet<>();
            Element dependencies = directChild(project, "dependencies");
            if (dependencies != null) {
                NodeList dependencyNodes = dependencies.getChildNodes();
                for (int index = 0; index < dependencyNodes.getLength(); index++) {
                    Node dependencyNode = dependencyNodes.item(index);
                    if (dependencyNode instanceof Element dependency
                            && "dependency".equals(dependency.getTagName())
                            && PROJECT_GROUP_ID.equals(directChildText(dependency, "groupId"))
                            && !"test".equals(directChildText(dependency, "scope"))) {
                        productionProjectDependencies.add(directChildText(dependency, "artifactId"));
                    }
                }
            }
            return new ModulePom(artifactId, productionProjectDependencies);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse Maven module POM: " + pom, exception);
        }
    }

    private static DocumentBuilderFactory newDocumentBuilderFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static Element directChild(Element parent, String tagName) {
        NodeList childNodes = parent.getChildNodes();
        for (int index = 0; index < childNodes.getLength(); index++) {
            Node node = childNodes.item(index);
            if (node instanceof Element element && tagName.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private static String directChildText(Element parent, String tagName) {
        Element child = directChild(parent, tagName);
        return child != null ? child.getTextContent().trim() : "";
    }

    private record ModulePom(String artifactId, Set<String> productionProjectDependencies) {
    }
}
