package com.jmjava.teamjeopardy.ingest.osgi;

import com.jmjava.teamjeopardy.graph.CodeEdge;
import com.jmjava.teamjeopardy.graph.CodeGraph;
import com.jmjava.teamjeopardy.graph.CodeNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Optional OSGi bundle metadata extraction (from skgraph ideas).
 * Non-OSGi projects are unaffected: this parser no-ops unless markers exist,
 * and never fails ingest when manifests are absent or incomplete.
 */
@Component
public class OsgiManifestParser {

    /**
     * Cheap marker check so Vue/Gradle/Python trees skip the OSGi walk entirely.
     */
    public boolean hasOsgiMarkers(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root, 8)) {
            return walk
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> {
                        String name = p.getFileName().toString();
                        if ("bnd.bnd".equals(name)) {
                            return true;
                        }
                        String relative = root.relativize(p).toString().replace('\\', '/');
                        return relative.endsWith("META-INF/MANIFEST.MF");
                    });
        }
    }

    public int ingestInto(CodeGraph graph, Path root) throws IOException {
        if (!hasOsgiMarkers(root)) {
            return 0;
        }
        int count = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String relative = root.relativize(p).toString().replace('\\', '/');
                        return relative.endsWith("META-INF/MANIFEST.MF")
                                || "MANIFEST.MF".equals(p.getFileName().toString());
                    })
                    .toList()) {
                if (parseManifest(graph, root, path)) {
                    count++;
                }
            }
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile)
                    .filter(p -> "bnd.bnd".equals(p.getFileName().toString()))
                    .toList()) {
                if (parseBnd(graph, root, path)) {
                    count++;
                }
            }
        }
        return count;
    }

    private boolean parseManifest(CodeGraph graph, Path root, Path path) throws IOException {
        try (var in = Files.newInputStream(path)) {
            Manifest manifest = new Manifest(in);
            String bsn = manifest.getMainAttributes().getValue("Bundle-SymbolicName");
            if (bsn == null || bsn.isBlank()) {
                return false;
            }
            if (bsn.contains(";")) {
                bsn = bsn.substring(0, bsn.indexOf(';')).trim();
            }
            String relative = root.relativize(path).toString().replace('\\', '/');
            String id = "osgi:" + bsn;
            graph.addNode(new CodeNode(
                    id,
                    CodeNode.NodeKind.COMPONENT,
                    bsn,
                    bsn,
                    "osgi",
                    relative,
                    "Bundle-SymbolicName: " + bsn,
                    "Bundle-Version: " + manifest.getMainAttributes().getValue("Bundle-Version")
            ));
            graph.addEdge(new CodeEdge("file:" + relative, id, CodeEdge.Relation.DEFINES));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean parseBnd(CodeGraph graph, Path root, Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        String bsn = null;
        for (String line : content.split("\\R")) {
            if (line.startsWith("Bundle-SymbolicName:")) {
                bsn = line.substring("Bundle-SymbolicName:".length()).trim();
                break;
            }
        }
        if (bsn == null || bsn.isBlank()) {
            return false;
        }
        String relative = root.relativize(path).toString().replace('\\', '/');
        String id = "osgi:" + bsn;
        graph.addNode(new CodeNode(
                id,
                CodeNode.NodeKind.COMPONENT,
                bsn,
                bsn,
                "osgi",
                relative,
                "bnd Bundle-SymbolicName: " + bsn,
                null
        ));
        return true;
    }
}
