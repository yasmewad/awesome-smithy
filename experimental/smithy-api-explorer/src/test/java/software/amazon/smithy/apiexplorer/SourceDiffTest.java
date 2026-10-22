/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.apiexplorer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.ObjectNode;

class SourceDiffTest {

    private static List<String> lines(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> "line " + i).toList();
    }

    @Test
    void alignMarksInsertionWithLineNumbers() {
        List<SourceDiff.Line> lines = SourceDiff.align(List.of("a", "b", "c"), List.of("a", "x", "b", "c"));
        assertEquals(List.of(' ', '+', ' ', ' '), lines.stream().map(SourceDiff.Line::kind).toList());
        assertEquals(2, lines.get(1).newLine());
        assertEquals(0, lines.get(1).oldLine());
        assertEquals(3, lines.get(3).oldLine());
        assertEquals(4, lines.get(3).newLine());
    }

    @Test
    void alignReplacementPutsRemovalBeforeAddition() {
        List<SourceDiff.Line> lines = SourceDiff.align(List.of("a", "old", "c"), List.of("a", "new", "c"));
        assertEquals(List.of(' ', '-', '+', ' '), lines.stream().map(SourceDiff.Line::kind).toList());
    }

    @Test
    void hunksKeepThreeLinesOfContextAndSplitDistantChanges() {
        List<String> before = lines(40);
        List<String> after = new ArrayList<>(before);
        after.set(4, "changed 5");
        after.set(34, "changed 35");
        var diff = SourceDiff.diff("f.smithy", "modified", before, after);
        assertEquals(2, diff.hunks().size());
        SourceDiff.Hunk first = diff.hunks().get(0);
        assertEquals(2, first.oldStart());
        assertEquals(7, first.oldCount()); // 3 context + 1 removed + 3 context
        assertEquals(8, first.lines().size());
        assertEquals(2, diff.added());
        assertEquals(2, diff.removed());
    }

    @Test
    void hunksMergeNearbyChanges() {
        List<String> before = lines(20);
        List<String> after = new ArrayList<>(before);
        after.set(4, "changed 5");
        after.set(8, "changed 9");
        assertEquals(1, SourceDiff.diff("f.smithy", "modified", before, after).hunks().size());
    }

    @Test
    void renderComparesModelSourcesByPathRelativeToTheirSourceRoot(@TempDir Path tmp) throws IOException {
        Path old = tmp.resolve("old");
        Path now = tmp.resolve("now");
        for (Path root : List.of(old, now)) {
            Files.createDirectories(root.resolve("model"));
            Files.writeString(root.resolve("smithy-build.json"), "{\"version\": \"1.0\"}");
            Files.writeString(root.resolve("model/same.smithy"), "namespace a\n");
            // Outside the conventional source directories, so never compared.
            Files.createDirectories(root.resolve("scratch"));
            Files.writeString(root.resolve("scratch/ignored.smithy"), root.equals(old) ? "x\n" : "y\n");
        }
        Files.writeString(old.resolve("model/main.smithy"), "namespace a\nstring A\n");
        Files.writeString(now.resolve("model/main.smithy"), "namespace a\nstring A\nstring B\n");
        Files.writeString(now.resolve("model/new.smithy"), "namespace a\n");

        ArrayNode files = SourceDiff.render(old, now);
        List<String> paths = files.getElementsAs(ObjectNode.class).stream()
                .map(f -> f.expectStringMember("path").getValue())
                .toList();
        assertEquals(List.of("main.smithy", "new.smithy"), paths);

        ObjectNode main = files.get(0).orElseThrow().expectObjectNode();
        assertEquals("modified", main.expectStringMember("status").getValue());
        assertEquals(1, main.expectNumberMember("added").getValue().intValue());
        assertEquals(0, main.expectNumberMember("removed").getValue().intValue());
        assertEquals(1, main.expectArrayMember("hunks").size());
        assertEquals("added", files.get(1).orElseThrow().expectObjectNode().expectStringMember("status").getValue());
    }

    @Test
    void renderUsesDeclaredSources(@TempDir Path tmp) throws IOException {
        Path old = Files.createDirectories(tmp.resolve("old/specs"));
        Files.writeString(tmp.resolve("old/smithy-build.json"), "{\"version\": \"1.0\", \"sources\": [\"specs\"]}");
        Files.writeString(old.resolve("main.smithy"), "namespace a\n");
        Path now = Files.createDirectories(tmp.resolve("now"));
        Files.writeString(now.resolve("main.smithy"), "namespace a\nstring A\n");

        ArrayNode files = SourceDiff.render(tmp.resolve("old"), SourceDiff.sources(List.of(now)));
        assertEquals(1, files.size());
        assertEquals("main.smithy", files.get(0).orElseThrow().expectObjectNode().expectStringMember("path").getValue());
    }

    @Test
    void nonIdlSourcesHaveNoLines(@TempDir Path tmp) throws IOException {
        Path json = Files.writeString(tmp.resolve("model.json"), "{\"smithy\": \"2.0\"}");
        assertEquals(0, SourceDiff.sources(json).size());
    }
}
