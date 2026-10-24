package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.*;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Explicit, offline profile: Java 21 and Jupiter 6.0.3, with no project-controlled build hooks. */
public final class BuildProfile {
    private BuildProfile() {}

    public static String name(Map<String, String> files) {
        return files.containsKey("pom.xml") ? "java21-junit6-v1" : "java21-main-v1";
    }

    public static void validatePom(String pom) {
        try {
            if (pom.length() > 32768) throw new IllegalArgumentException();
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var document =
                    factory.newDocumentBuilder().parse(new InputSource(new StringReader(pom)));
            if (!document.getDocumentElement().getTagName().equals("project"))
                throw new IllegalArgumentException();
            for (String forbidden :
                    List.of(
                            "parent",
                            "build",
                            "profiles",
                            "modules",
                            "repositories",
                            "pluginRepositories",
                            "reporting",
                            "extensions"))
                if (document.getElementsByTagName(forbidden).getLength() > 0)
                    throw new IllegalArgumentException();
            var dependencies = document.getElementsByTagName("dependency");
            if (dependencies.getLength() != 1) throw new IllegalArgumentException();
            var d = (org.w3c.dom.Element) dependencies.item(0);
            if (!text(d, "groupId").equals("org.junit.jupiter")
                    || !text(d, "artifactId").equals("junit-jupiter")
                    || !text(d, "version").equals("6.0.3")
                    || !text(d, "scope").equals("test")) throw new IllegalArgumentException();
            var releases = document.getElementsByTagName("maven.compiler.release");
            if (releases.getLength() != 1 || !releases.item(0).getTextContent().trim().equals("21"))
                throw new IllegalArgumentException();
        } catch (Exception unsupported) {
            throw new PlatformException(
                    422,
                    "UNSUPPORTED_BUILD_PROFILE",
                    "Supported Maven profile: Java 21, junit-jupiter 6.0.3 test dependency, no"
                        + " parent, plugins, profiles or other dependencies. The immutable project"
                        + " POM is retained; execution uses the pinned offline runner POM.");
        }
    }

    private static String text(org.w3c.dom.Element root, String tag) {
        var elements = root.getElementsByTagName(tag);
        return elements.getLength() == 1 ? elements.item(0).getTextContent().trim() : "";
    }
}
