package me.golemcore.bot.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

class JibContainerConfigurationTest {

    private static final String JIB_CONTAINER = "/*[local-name()='project']"
            + "/*[local-name()='build']"
            + "/*[local-name()='plugins']"
            + "/*[local-name()='plugin']"
            + "[*[local-name()='groupId']='com.google.cloud.tools'"
            + " and *[local-name()='artifactId']='jib-maven-plugin']"
            + "/*[local-name()='configuration']"
            + "/*[local-name()='container']";

    @Test
    void docker_image_should_use_strict_cli_entrypoint_with_default_web_command() throws Exception {
        Document pom = parsePom();
        XPath xpath = XPathFactory.newInstance().newXPath();

        assertEquals(List.of(
                "/usr/bin/tini",
                "--",
                "java",
                "-cp",
                "@/app/jib-classpath-file",
                "me.golemcore.bot.launcher.RuntimeCliLauncher"),
                textList(pom, xpath, JIB_CONTAINER + "/*[local-name()='entrypoint']/*[local-name()='arg']"));
        assertEquals(List.of("web"),
                textList(pom, xpath, JIB_CONTAINER + "/*[local-name()='args']/*[local-name()='arg']"));
    }

    private Document parsePom() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
    }

    private List<String> textList(Document document, XPath xpath, String expression) throws Exception {
        NodeList nodes = (NodeList) xpath.evaluate(expression, document, XPathConstants.NODESET);
        List<String> values = new ArrayList<>(nodes.getLength());
        for (int index = 0; index < nodes.getLength(); index++) {
            values.add(nodes.item(index).getTextContent().trim());
        }
        return List.copyOf(values);
    }
}
