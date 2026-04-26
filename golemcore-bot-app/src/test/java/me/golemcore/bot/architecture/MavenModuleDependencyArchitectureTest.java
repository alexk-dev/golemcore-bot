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
            Map.entry("golemcore-bot-runtime-core",
                    Set.of("golemcore-bot-contracts", "golemcore-bot-runtime-config", "golemcore-bot-tracing")),
            Map.entry("golemcore-bot-client", Set.of("golemcore-bot-contracts")),
            Map.entry("golemcore-bot-self-evolving", Set.of("golemcore-bot-contracts", "golemcore-bot-client")),
            Map.entry("golemcore-bot-hive", Set.of("golemcore-bot-contracts", "golemcore-bot-client")),
            Map.entry("golemcore-bot-extensions", Set.of("golemcore-bot-contracts")),
            Map.entry("golemcore-bot-app",
                    Set.of("golemcore-bot-contracts", "golemcore-bot-runtime-config", "golemcore-bot-sessions",
                            "golemcore-bot-memory", "golemcore-bot-tools", "golemcore-bot-tracing",
                            "golemcore-bot-runtime-core", "golemcore-bot-client", "golemcore-bot-self-evolving",
                            "golemcore-bot-hive", "golemcore-bot-extensions")));
    private static final Set<String> PROJECT_ARTIFACT_IDS = ALLOWED_PROJECT_DEPENDENCIES.values().stream()
            .flatMap(Set::stream)
            .collect(Collectors.collectingAndThen(Collectors.toCollection(HashSet::new),
                    MavenModuleDependencyArchitectureTest::withModuleArtifactIds));

    @Test
    void production_maven_module_dependencies_should_follow_target_graph() {
        Map<String, String> moduleArtifactIds = new LinkedHashMap<>();
        Map<String, Set<String>> violations = new LinkedHashMap<>();
        for (String module : ALLOWED_PROJECT_DEPENDENCIES.keySet()) {
            ModulePom modulePom = readModulePom(module);
            moduleArtifactIds.put(module, modulePom.artifactId());
            Set<String> allowed = ALLOWED_PROJECT_DEPENDENCIES.get(module);
            Set<String> illegalDependencies = modulePom.productionProjectDependencies().stream()
                    .filter(PROJECT_ARTIFACT_IDS::contains)
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
        Set<String> offenders = ALLOWED_PROJECT_DEPENDENCIES.keySet().stream()
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
                annotationPrefix + "Autowired", annotationPrefix + "Resource");
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
    void ci_should_publish_dependency_report_for_review_visibility() throws IOException {
        String workflow = Files.readString(REPO_ROOT.resolve(".github/workflows/docker-publish.yml"));

        assertTrue(workflow.contains("dependency:tree") && workflow.toLowerCase(Locale.ROOT)
                .contains("dependency-report"), "CI must generate and upload a Maven dependency report artifact");
    }

    private ModulePom readModulePom(String module) {
        Path pom = REPO_ROOT.resolve(module).resolve("pom.xml");
        try (InputStream inputStream = Files.newInputStream(pom)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(inputStream);
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

    private static Set<String> withModuleArtifactIds(Set<String> artifactIds) {
        artifactIds.add(APP_ARTIFACT_ID);
        artifactIds.addAll(ALLOWED_PROJECT_DEPENDENCIES.keySet());
        artifactIds.remove("golemcore-bot-app");
        return Set.copyOf(artifactIds);
    }

    private record ModulePom(String artifactId, Set<String> productionProjectDependencies) {
    }
}
