/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.apiexplorer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.SmithyIdlModelSerializer;

/**
 * A unified diff of the {@code .smithy} sources of a baseline and a current model, for the
 * explorer's changes view. Files are matched by their path relative to the source root that
 * contains them (a source directory, or the file name for a source file), and only model sources
 * are compared: for a project directory, the sources its smithy-build.json declares, or else the
 * conventional source directories.
 *
 * <p>When a baseline is not IDL source, {@link #renderNormalized} diffs both models serialized
 * back to IDL instead.
 *
 * <p>The line diff trims the common prefix and suffix and runs an LCS over what is left, which is
 * exact for the small, local edits a change usually makes. A changed region too large for the LCS budget
 * is shown as a whole replacement instead.
 */
final class SourceDiff {

    static final int CONTEXT_LINES = 3;

    /** Changed regions above this many cells (old lines x new lines) are shown as a replacement. */
    private static final long LCS_CELL_BUDGET = 4_000_000L;

    /** Directories a project without declared sources keeps its models in. */
    private static final List<String> CONVENTIONAL_SOURCE_DIRS = List.of("model", "models", "src/main/smithy");

    /** Total diff lines returned across all files, to bound the page size. */
    private static final int MAX_DIFF_LINES = 4000;

    private SourceDiff() {}

    /** One line of a hunk: ' ' context, '-' removed, '+' added; line numbers are 1-based. */
    record Line(char kind, int oldLine, int newLine, String text) {}

    record Hunk(int oldStart, int oldCount, int newStart, int newCount, List<Line> lines) {}

    record FileDiff(String path, String status, int added, int removed, List<Hunk> hunks) {}

    /**
     * Diffs the sources under {@code baseline} against those under {@code current}.
     *
     * @param baseline Baseline file, model directory, or project directory.
     * @param current Current file, model directory, or project directory.
     * @return Returns the changed files as a node: {@code [{path, status, added, removed, hunks}]}.
     */
    static ArrayNode render(Path baseline, Path current) {
        return render(baseline, sources(current));
    }

    /**
     * Diffs the sources under {@code baseline} against the given current sources.
     *
     * @param baseline Baseline file, model directory, or project directory.
     * @param current Current sources keyed by relative path, as {@link #sources} returns them.
     * @return Returns the changed files as a node: {@code [{path, status, added, removed, hunks}]}.
     */
    static ArrayNode render(Path baseline, Map<String, Path> current) {
        return renderRoots(roots(baseline), current);
    }

    /**
     * Diffs the sources under the baseline's model roots against the given current sources.
     *
     * @param baselineRoots The baseline's model roots, as {@link #roots} returns them.
     * @param current Current sources keyed by relative path, as {@link #sources} returns them.
     * @return Returns the changed files as a node: {@code [{path, status, added, removed, hunks}]}.
     */
    static ArrayNode renderRoots(Collection<Path> baselineRoots, Map<String, Path> current) {
        return renderFiles(readAll(sources(baselineRoots)), readAll(current));
    }

    /**
     * Diffs two models serialized to IDL with {@link SmithyIdlModelSerializer}, one file per
     * namespace. Used when a baseline is not IDL source, such as a JSON AST model or a build jar,
     * so its source lines cannot be compared directly. Only shapes in {@code namespaces} are
     * serialized, which keeps dependency shapes out of the diff.
     */
    static ArrayNode renderNormalized(Model baseline, Model current, Set<String> namespaces) {
        SmithyIdlModelSerializer serializer = SmithyIdlModelSerializer.builder()
                .shapeFilter(shape -> namespaces.contains(shape.getId().getNamespace()))
                .metadataFilter(key -> false)
                .build();
        return renderFiles(serialized(serializer, baseline), serialized(serializer, current));
    }

    private static Map<String, List<String>> serialized(SmithyIdlModelSerializer serializer, Model model) {
        Map<String, List<String>> files = new TreeMap<>();
        serializer.serialize(model).forEach((path, text) -> {
            String name = path.toString().replace('\\', '/');
            // The serializer always emits a metadata file; it is empty with metadata filtered out.
            if (!text.lines().allMatch(l -> l.isBlank() || l.startsWith("$version"))) {
                files.put(name, text.lines().toList());
            }
        });
        return files;
    }

    private static ArrayNode renderFiles(Map<String, List<String>> before, Map<String, List<String>> after) {
        TreeSet<String> paths = new TreeSet<>(before.keySet());
        paths.addAll(after.keySet());

        ArrayNode.Builder files = ArrayNode.builder();
        int budget = MAX_DIFF_LINES;
        for (String path : paths) {
            List<String> oldLines = before.getOrDefault(path, List.of());
            List<String> newLines = after.getOrDefault(path, List.of());
            if (oldLines.equals(newLines)) {
                continue;
            }
            String status = !before.containsKey(path) ? "added" : !after.containsKey(path) ? "removed" : "modified";
            FileDiff diff = diff(path, status, oldLines, newLines);
            ObjectNode.Builder node = ObjectNode.builder()
                    .withMember("path", path)
                    .withMember("status", status)
                    .withMember("added", diff.added())
                    .withMember("removed", diff.removed());
            ArrayNode.Builder hunks = ArrayNode.builder();
            boolean truncated = false;
            for (Hunk hunk : diff.hunks()) {
                if (budget - hunk.lines().size() < 0) {
                    truncated = true;
                    break;
                }
                budget -= hunk.lines().size();
                hunks.withValue(renderHunk(hunk));
            }
            node.withMember("hunks", hunks.build());
            if (truncated) {
                node.withMember("truncated", true);
            }
            files.withValue(node.build());
        }
        return files.build();
    }

    static FileDiff diff(String path, String status, List<String> oldLines, List<String> newLines) {
        List<Line> lines = align(oldLines, newLines);
        int added = (int) lines.stream().filter(l -> l.kind() == '+').count();
        int removed = (int) lines.stream().filter(l -> l.kind() == '-').count();
        return new FileDiff(path, status, added, removed, hunks(lines));
    }

    /** Every line of both files in order, each marked as context, removed, or added. */
    static List<Line> align(List<String> a, List<String> b) {
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < prefix; i++) {
            out.add(new Line(' ', i + 1, i + 1, a.get(i)));
        }
        List<String> midA = a.subList(prefix, a.size() - suffix);
        List<String> midB = b.subList(prefix, b.size() - suffix);
        if ((long) midA.size() * midB.size() > LCS_CELL_BUDGET) {
            for (int i = 0; i < midA.size(); i++) {
                out.add(new Line('-', prefix + i + 1, 0, midA.get(i)));
            }
            for (int j = 0; j < midB.size(); j++) {
                out.add(new Line('+', 0, prefix + j + 1, midB.get(j)));
            }
        } else {
            lcs(midA, midB, prefix, out);
        }
        for (int k = suffix; k > 0; k--) {
            out.add(new Line(' ', a.size() - k + 1, b.size() - k + 1, a.get(a.size() - k)));
        }
        return out;
    }

    /** LCS alignment of the changed middle; removals come before additions within a change. */
    private static void lcs(List<String> a, List<String> b, int offset, List<Line> out) {
        int n = a.size();
        int m = b.size();
        int[][] table = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                table[i][j] = a.get(i).equals(b.get(j))
                        ? table[i + 1][j + 1] + 1
                        : Math.max(table[i + 1][j], table[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && a.get(i).equals(b.get(j))) {
                out.add(new Line(' ', offset + i + 1, offset + j + 1, a.get(i)));
                i++;
                j++;
            } else if (i < n && (j >= m || table[i + 1][j] >= table[i][j + 1])) {
                out.add(new Line('-', offset + i + 1, 0, a.get(i)));
                i++;
            } else {
                out.add(new Line('+', 0, offset + j + 1, b.get(j)));
                j++;
            }
        }
    }

    /** Groups changes with {@link #CONTEXT_LINES} lines of context into hunks, merging close ones. */
    static List<Hunk> hunks(List<Line> lines) {
        List<Hunk> hunks = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            if (lines.get(index).kind() == ' ') {
                index++;
                continue;
            }
            int start = Math.max(0, index - CONTEXT_LINES);
            int end = index;
            int quiet = 0;
            while (end < lines.size() && quiet <= 2 * CONTEXT_LINES) {
                quiet = lines.get(end).kind() == ' ' ? quiet + 1 : 0;
                end++;
            }
            // Keep CONTEXT_LINES of trailing context after the last change.
            end = Math.min(lines.size(), end - Math.max(0, quiet - CONTEXT_LINES));
            List<Line> slice = List.copyOf(lines.subList(start, end));
            hunks.add(new Hunk(
                    firstLine(slice, true), (int) slice.stream().filter(l -> l.kind() != '+').count(),
                    firstLine(slice, false), (int) slice.stream().filter(l -> l.kind() != '-').count(),
                    slice));
            index = end;
        }
        return hunks;
    }

    private static int firstLine(List<Line> slice, boolean old) {
        for (Line line : slice) {
            int n = old ? line.oldLine() : line.newLine();
            if (n > 0) {
                return n;
            }
        }
        return 0;
    }

    private static ObjectNode renderHunk(Hunk hunk) {
        ArrayNode.Builder lines = ArrayNode.builder();
        for (Line line : hunk.lines()) {
            ObjectNode.Builder node = ObjectNode.builder()
                    .withMember("kind", String.valueOf(line.kind()))
                    .withMember("text", line.text());
            if (line.oldLine() > 0) {
                node.withMember("old", line.oldLine());
            }
            if (line.newLine() > 0) {
                node.withMember("new", line.newLine());
            }
            lines.withValue(node.build());
        }
        return ObjectNode.builder()
                .withMember("oldStart", hunk.oldStart())
                .withMember("oldCount", hunk.oldCount())
                .withMember("newStart", hunk.newStart())
                .withMember("newCount", hunk.newCount())
                .withMember("lines", lines.build())
                .build();
    }

    // ── Source discovery ──────────────────────────────────────────────────────

    /**
     * The .smithy files a source contributes, keyed by path relative to the source root that
     * contains them. Empty when the source is not IDL, such as a JSON AST file or a JAR.
     *
     * @see #roots(Path)
     */
    static Map<String, Path> sources(Path source) {
        return sources(roots(source));
    }

    /**
     * The model roots of a source: the files and directories that hold its models. A directory
     * containing a smithy-build.json is a project directory, whose roots are the sources and
     * imports the file declares, or else the conventional source directories that exist. Any
     * other source is its own root.
     *
     * <p>The baseline is assembled from these roots, and its source diff reads them, so both see
     * the same files. Build outputs and other models elsewhere in a project directory are left
     * out.
     */
    static List<Path> roots(Path source) {
        if (!Files.isRegularFile(source.resolve("smithy-build.json"))) {
            return List.of(source);
        }
        List<Path> roots = new ArrayList<>();
        SmithyBuildConfig config = SmithyBuildConfig.load(source.resolve("smithy-build.json"));
        config.getSources().forEach(declared -> roots.add(source.resolve(declared).normalize()));
        config.getImports().forEach(declared -> roots.add(source.resolve(declared).normalize()));
        if (roots.isEmpty()) {
            for (String dir : CONVENTIONAL_SOURCE_DIRS) {
                if (Files.isDirectory(source.resolve(dir))) {
                    roots.add(source.resolve(dir));
                }
            }
        }
        return roots;
    }

    /**
     * The .smithy files under each of {@code roots}, keyed by path relative to the root that
     * contains them, or by file name for a root that is a file.
     */
    static Map<String, Path> sources(Collection<Path> roots) {
        Map<String, Path> files = new TreeMap<>();
        for (Path root : roots) {
            if (Files.isRegularFile(root)) {
                if (isIdl(root)) {
                    files.putIfAbsent(root.getFileName().toString(), root);
                }
                continue;
            }
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> Files.isRegularFile(p) && isIdl(p))
                        .sorted()
                        .forEach(p -> files.putIfAbsent(relative(root, p), p));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    private static boolean isIdl(Path file) {
        return file.getFileName().toString().endsWith(".smithy");
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static Map<String, List<String>> readAll(Map<String, Path> files) {
        Map<String, List<String>> out = new TreeMap<>();
        files.forEach((path, file) -> out.put(path, read(file)));
        return out;
    }

    private static List<String> read(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
