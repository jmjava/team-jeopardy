package com.jmjava.teamjeopardy.ingest.maven;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Raw POM parser — no Maven invocation. Ported/simplified from jmjava/skgraph PomParser
 * so Team Jeopardy can ingest reactors without the private skgraph-core dependency.
 */
public class MavenPomParser {

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    public record Coordinate(String groupId, String artifactId, String version, String type, String scope) {
        public String ga() {
            return groupId + ":" + artifactId;
        }
    }

    public record Dependency(Coordinate coordinate, String versionRaw, boolean managed, boolean bomImport) {
    }

    public record Plugin(String groupId, String artifactId, String version) {
        public String ga() {
            return groupId + ":" + artifactId;
        }
    }

    public record ParsedModule(
            String filePath,
            String relativePath,
            Coordinate coordinate,
            String packaging,
            Coordinate parent,
            Map<String, String> properties,
            List<String> modules,
            List<Dependency> dependencies,
            List<Dependency> dependencyManagement,
            List<Plugin> plugins,
            Set<String> unresolvedProperties
    ) {
        public String moduleId() {
            return "module:" + coordinate.ga();
        }
    }

    public ParsedModule parseFile(Path pomPath, Path reactorRoot) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        Document doc;
        try (InputStream in = Files.newInputStream(pomPath)) {
            doc = factory.newDocumentBuilder().parse(in);
        }
        Element project = doc.getDocumentElement();

        Map<String, String> localProperties = parseProperties(project);
        Coordinate parent = parseParent(project);
        String groupId = text(project, "groupId");
        if (groupId == null && parent != null) {
            groupId = parent.groupId();
        }
        if (groupId == null) {
            groupId = "unknown.group";
        }
        String artifactId = text(project, "artifactId");
        if (artifactId == null) {
            artifactId = pomPath.getParent().getFileName().toString();
        }
        String version = text(project, "version");
        if (version == null && parent != null) {
            version = parent.version();
        }
        String packaging = text(project, "packaging");
        if (packaging == null) {
            packaging = "jar";
        }

        Set<String> unresolved = new LinkedHashSet<>();
        List<Dependency> deps = parseDependencies(child(project, "dependencies"), localProperties, unresolved, false);
        Element depMgmt = child(child(project, "dependencyManagement"), "dependencies");
        List<Dependency> managed = parseDependencies(depMgmt, localProperties, unresolved, true);
        List<Plugin> plugins = parsePlugins(child(child(project, "build"), "plugins"), localProperties, unresolved);
        List<String> modules = new ArrayList<>();
        Element modulesEl = child(project, "modules");
        if (modulesEl != null) {
            for (Element module : elementsNamed(modulesEl, "module")) {
                modules.add(module.getTextContent().trim());
            }
        }

        String relative = reactorRoot.relativize(pomPath).toString().replace('\\', '/');
        return new ParsedModule(
                pomPath.toAbsolutePath().toString(),
                relative,
                new Coordinate(groupId, artifactId, version, packaging, null),
                packaging,
                parent,
                localProperties,
                modules,
                deps,
                managed,
                plugins,
                unresolved
        );
    }

    private Coordinate parseParent(Element project) {
        Element parent = child(project, "parent");
        if (parent == null) {
            return null;
        }
        String groupId = text(parent, "groupId");
        String artifactId = text(parent, "artifactId");
        if (groupId == null || artifactId == null) {
            return null;
        }
        return new Coordinate(groupId, artifactId, text(parent, "version"), "pom", null);
    }

    private Map<String, String> parseProperties(Element project) {
        Map<String, String> props = new LinkedHashMap<>();
        Element properties = child(project, "properties");
        if (properties == null) {
            return props;
        }
        Node child = properties.getFirstChild();
        while (child != null) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) child;
                props.put(el.getTagName(), el.getTextContent().trim());
            }
            child = child.getNextSibling();
        }
        return props;
    }

    private List<Dependency> parseDependencies(
            Element depsEl,
            Map<String, String> localProperties,
            Set<String> unresolved,
            boolean managed
    ) {
        List<Dependency> deps = new ArrayList<>();
        if (depsEl == null) {
            return deps;
        }
        for (Element dep : elementsNamed(depsEl, "dependency")) {
            String groupId = resolveTokens(nullToEmpty(text(dep, "groupId")), localProperties, unresolved);
            String artifactId = resolveTokens(nullToEmpty(text(dep, "artifactId")), localProperties, unresolved);
            String versionRaw = text(dep, "version");
            String version = versionRaw == null ? null : resolveTokens(versionRaw, localProperties, unresolved);
            String type = text(dep, "type");
            if (type == null) {
                type = "jar";
            }
            String scope = text(dep, "scope");
            boolean bomImport = managed && "pom".equals(type) && "import".equals(scope);
            deps.add(new Dependency(
                    new Coordinate(groupId, artifactId, version, type, scope),
                    versionRaw,
                    managed,
                    bomImport
            ));
        }
        return deps;
    }

    private List<Plugin> parsePlugins(Element pluginsEl, Map<String, String> props, Set<String> unresolved) {
        List<Plugin> plugins = new ArrayList<>();
        if (pluginsEl == null) {
            return plugins;
        }
        for (Element plugin : elementsNamed(pluginsEl, "plugin")) {
            String groupId = text(plugin, "groupId");
            if (groupId == null) {
                groupId = "org.apache.maven.plugins";
            }
            groupId = resolveTokens(groupId, props, unresolved);
            String artifactId = resolveTokens(nullToEmpty(text(plugin, "artifactId")), props, unresolved);
            if (artifactId.isBlank()) {
                continue;
            }
            String version = text(plugin, "version");
            if (version != null) {
                version = resolveTokens(version, props, unresolved);
            }
            plugins.add(new Plugin(groupId, artifactId, version));
        }
        return plugins;
    }

    public static String resolveTokens(String raw, Map<String, String> props, Set<String> unresolved) {
        if (raw == null) {
            return null;
        }
        String resolved = raw;
        Matcher matcher = PROPERTY.matcher(raw);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = props.get(key);
            if (value == null) {
                unresolved.add(key);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
            }
        }
        matcher.appendTail(sb);
        resolved = sb.toString();
        return resolved;
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static List<Element> elementsNamed(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(node.getNodeName())) {
                out.add((Element) node);
            }
        }
        return out;
    }

    private static String text(Element parent, String name) {
        Element child = child(parent, name);
        if (child == null) {
            return null;
        }
        String value = child.getTextContent();
        return value == null ? null : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
