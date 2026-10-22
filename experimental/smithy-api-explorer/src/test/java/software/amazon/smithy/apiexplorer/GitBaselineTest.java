/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.apiexplorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.smithy.build.SmithyBuildException;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;

/**
 * The git baseline: the build's model sources compared with a git revision, the way
 * {@code smithy diff --mode git} compares them. Each test makes its own repository, and the tests
 * are skipped where git is not installed.
 */
class GitBaselineTest {

    private static final String MAIN = """
            $version: "2"

            namespace example.notes

            service Notes {
                operations: [GetNote, DeleteNote]
            }

            operation GetNote {
                input := {
                    @required
                    id: String
                }
            }
            """;

    private static final String EXTRA = """
            $version: "2"

            namespace example.notes

            operation DeleteNote {
                input: DeleteNoteInput
            }

            structure DeleteNoteInput {
                @required
                id: String
            }
            """;

    @BeforeAll
    static void requireGit() {
        assumeTrue(gitWorks(), "git is not installed");
    }

    // ── The default: git HEAD when the sources are committed ──────────────────

    @Test
    void defaultComparesTheWorkingTreeWithHead(@TempDir Path tmp) throws IOException {
        Path model = committedModel(tmp);
        String head = git(tmp, "rev-parse", "HEAD").substring(0, 12);
        editWorkingTree(model);

        ObjectNode data = TestModels.data(TestModels.run(Node.objectNode(), model));
        assertFalse(data.containsMember("changesSkipped"));
        ObjectNode changes = data.expectObjectMember("changes");
        assertEquals("added", changes.expectObjectMember("entries")
                .expectStringMember("example.notes#ListNotes").getValue());
        List<String> removed = changes.expectArrayMember("removed").getElementsAs(ObjectNode.class).stream()
                .map(r -> r.expectStringMember("id").getValue())
                .toList();
        assertTrue(removed.contains("example.notes#DeleteNote"), removed.toString());
        assertTrue(removed.contains("example.notes#DeleteNoteInput"), removed.toString());

        ObjectNode baseline = changes.expectObjectMember("baseline");
        assertEquals("git", baseline.expectStringMember("kind").getValue());
        assertEquals("HEAD", baseline.expectStringMember("revision").getValue());
        assertEquals(head, baseline.expectStringMember("commit").getValue());
    }

    @Test
    void sourceDiffPairsFilesAndIncludesDeletedOnes(@TempDir Path tmp) throws IOException {
        Path model = committedModel(tmp);
        editWorkingTree(model);

        ObjectNode changes = TestModels.data(TestModels.run(Node.objectNode(), model)).expectObjectMember("changes");
        assertEquals("source", changes.expectStringMember("diffMode").getValue());
        List<String> files = changes.expectArrayMember("files").getElementsAs(ObjectNode.class).stream()
                .map(f -> f.expectStringMember("path").getValue() + ":" + f.expectStringMember("status").getValue())
                .toList();
        assertEquals(List.of("extra.smithy:removed", "main.smithy:modified"), files);
    }

    @Test
    void unchangedSourcesHaveNoChanges(@TempDir Path tmp) throws IOException {
        Path model = committedModel(tmp);
        ObjectNode changes = TestModels.data(TestModels.run(Node.objectNode(), model)).expectObjectMember("changes");
        assertTrue(changes.expectObjectMember("entries").isEmpty());
        assertTrue(changes.expectArrayMember("removed").isEmpty());
        assertTrue(changes.expectArrayMember("files").isEmpty());
    }

    @Test
    void singleFileSourceIsComparedByName(@TempDir Path tmp) throws IOException {
        Path model = committedModel(tmp);
        Files.writeString(model.resolve("main.smithy"), MAIN.replace("service Notes {", "/// Notes.\nservice Notes {"));

        ObjectNode changes = TestModels.data(TestModels.run(Node.objectNode(),
                List.of(model.resolve("main.smithy")), List.of(model.resolve("extra.smithy"))))
                .expectObjectMember("changes");
        List<String> files = changes.expectArrayMember("files").getElementsAs(ObjectNode.class).stream()
                .map(f -> f.expectStringMember("path").getValue() + ":" + f.expectStringMember("status").getValue())
                .toList();
        assertEquals(List.of("main.smithy:modified"), files);
        assertEquals("changed", changes.expectObjectMember("entries")
                .expectStringMember("example.notes#Notes").getValue());
    }

    @Test
    void sourceRootsWithFilesOfTheSameNameAreKeptApart(@TempDir Path tmp) throws IOException {
        init(tmp);
        Path model = Files.createDirectories(tmp.resolve("model"));
        Path shared = Files.createDirectories(tmp.resolve("shared"));
        Files.writeString(model.resolve("main.smithy"), MAIN.replace("operations: [GetNote, DeleteNote]",
                "operations: [GetNote, DeleteNote, Ping]"));
        Files.writeString(model.resolve("extra.smithy"), EXTRA);
        Files.writeString(shared.resolve("main.smithy"), "$version: \"2\"\n\nnamespace example.notes\n\n"
                + "operation Ping {}\n");
        commit(tmp, "Add two source directories");

        ObjectNode changes = TestModels.data(TestModels.run(Node.objectNode(), model, shared))
                .expectObjectMember("changes");
        assertTrue(changes.expectObjectMember("entries").isEmpty(), Node.printJson(changes));
        assertTrue(changes.expectArrayMember("removed").isEmpty(), Node.printJson(changes));
        assertTrue(changes.expectArrayMember("compatibility").isEmpty(), Node.printJson(changes));
    }

    @Test
    void defaultIsSkippedQuietlyOutsideARepository(@TempDir Path tmp) throws IOException {
        Path model = writeModel(tmp.resolve("model"));
        assumeTrue(!inRepository(model), "the temporary directory is inside a git repository");

        ObjectNode data = TestModels.data(TestModels.run(Node.objectNode(), model));
        assertFalse(data.containsMember("changes"));
        assertFalse(data.containsMember("changesSkipped"));
    }

    @Test
    void defaultIsSkippedQuietlyForUncommittedSources(@TempDir Path tmp) throws IOException {
        Path model = committedModel(tmp);
        Path untracked = writeModel(tmp.resolve("untracked"));

        ObjectNode data = TestModels.data(TestModels.run(Node.objectNode(), untracked));
        assertFalse(data.containsMember("changes"));
        assertFalse(data.containsMember("changesSkipped"));
        assertTrue(Files.isDirectory(model));
    }

    @Test
    void defaultIsSkippedQuietlyInARepositoryWithoutCommits(@TempDir Path tmp) throws IOException {
        init(tmp);
        Path model = writeModel(tmp.resolve("model"));
        ObjectNode data = TestModels.data(TestModels.run(Node.objectNode(), model));
        assertFalse(data.containsMember("changes"));
        assertFalse(data.containsMember("changesSkipped"));
    }

    // ── Configured git revisions ──────────────────────────────────────────────

    @Test
    void configuredRevisionIsCompared(@TempDir Path tmp) throws IOException {
        Path model = committedModel(tmp);
        String first = git(tmp, "rev-parse", "HEAD").substring(0, 12);
        editWorkingTree(model);
        commit(tmp, "Add ListNotes");

        ObjectNode changes = TestModels.data(TestModels.run(gitSetting("HEAD~1"), model))
                .expectObjectMember("changes");
        assertEquals("added", changes.expectObjectMember("entries")
                .expectStringMember("example.notes#ListNotes").getValue());
        ObjectNode baseline = changes.expectObjectMember("baseline");
        assertEquals("HEAD~1", baseline.expectStringMember("revision").getValue());
        assertEquals(first, baseline.expectStringMember("commit").getValue());
    }

    @Test
    void trueComparesWithHead(@TempDir Path tmp) throws IOException {
        Path model = committedModel(tmp);
        editWorkingTree(model);
        ObjectNode settings = Node.objectNode().withMember("baseline", Node.from(true));
        ObjectNode baseline = TestModels.data(TestModels.run(settings, model))
                .expectObjectMember("changes").expectObjectMember("baseline");
        assertEquals("HEAD", baseline.expectStringMember("revision").getValue());
    }

    @Test
    void invalidRevisionFailsTheBuild(@TempDir Path tmp) throws IOException {
        Path model = committedModel(tmp);
        var e = assertThrows(SmithyBuildException.class, () -> TestModels.run(gitSetting("nope"), model));
        assertTrue(e.getMessage().contains("'nope' does not name a commit")
                && e.getMessage().contains("\"baseline\""), e.getMessage());
    }

    @Test
    void optionLikeRevisionFailsTheBuild(@TempDir Path tmp) throws IOException {
        Path model = committedModel(tmp);
        assertThrows(SmithyBuildException.class, () -> TestModels.run(gitSetting("--output=x"), model));
    }

    @Test
    void configuredRevisionOutsideARepositoryIsSkippedWithAReason(@TempDir Path tmp) throws IOException {
        Path model = writeModel(tmp.resolve("model"));
        assumeTrue(!inRepository(model), "the temporary directory is inside a git repository");

        ObjectNode data = TestModels.data(TestModels.run(gitSetting("HEAD"), model));
        assertFalse(data.containsMember("changes"));
        String skipped = data.expectStringMember("changesSkipped").getValue();
        assertTrue(skipped.contains("not in a git repository"), skipped);
    }

    @Test
    void configuredRevisionWithoutTheSourcesIsSkippedWithAReason(@TempDir Path tmp) throws IOException {
        committedModel(tmp);
        Path untracked = writeModel(tmp.resolve("untracked"));
        String skipped = TestModels.data(TestModels.run(gitSetting("HEAD"), untracked))
                .expectStringMember("changesSkipped").getValue();
        assertTrue(skipped.contains("None of the model sources"), skipped);
    }

    @Test
    void falseDisablesTheComparison(@TempDir Path tmp) throws IOException {
        Path model = committedModel(tmp);
        editWorkingTree(model);
        ObjectNode settings = Node.objectNode().withMember("baseline", Node.from(false));
        ObjectNode data = TestModels.data(TestModels.run(settings, model));
        assertFalse(data.containsMember("changes"));
        assertFalse(data.containsMember("changesSkipped"));
    }

    @Test
    void checkoutWritesTheRevisionAndCloseDeletesIt(@TempDir Path tmp) throws Exception {
        Path model = committedModel(tmp);
        editWorkingTree(model);
        Path dir;
        try (GitBaseline checkout = GitBaseline.checkout(List.of(model), "HEAD")) {
            dir = checkout.dir();
            assertEquals(1, checkout.roots().size());
            Path root = checkout.roots().get(0);
            assertTrue(root.startsWith(dir), root.toString());
            assertEquals(MAIN, Files.readString(root.resolve("main.smithy")));
            assertEquals(EXTRA, Files.readString(root.resolve("extra.smithy")));
            assertEquals(12, checkout.commit().length());
            assertEquals("HEAD", checkout.revision());
        }
        assertFalse(Files.exists(dir));
        assertTrue(Files.exists(model.resolve("main.smithy")), "the working tree is left alone");
        assertFalse(Files.exists(model.resolve("extra.smithy")), "the working tree is left alone");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ObjectNode gitSetting(String revision) {
        return Node.objectNode().withMember("baseline", Node.objectNode().withMember("git", revision));
    }

    private static Path writeModel(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("main.smithy"), MAIN);
        Files.writeString(dir.resolve("extra.smithy"), EXTRA);
        return dir;
    }

    /** A repository at {@code repo} with the model committed under {@code model/}. */
    private static Path committedModel(Path repo) throws IOException {
        init(repo);
        Path model = writeModel(repo.resolve("model"));
        commit(repo, "Add the model");
        return model;
    }

    /** Adds ListNotes in place of DeleteNote and deletes the file that defined DeleteNote. */
    private static void editWorkingTree(Path model) throws IOException {
        Files.writeString(model.resolve("main.smithy"), MAIN
                .replace("operations: [GetNote, DeleteNote]", "operations: [GetNote, ListNotes]")
                + "\noperation ListNotes {}\n");
        Files.delete(model.resolve("extra.smithy"));
    }

    private static void init(Path repo) throws IOException {
        git(repo, "init", "--quiet");
        git(repo, "config", "user.name", "API Explorer Test");
        git(repo, "config", "user.email", "api-explorer-test@example.com");
        git(repo, "config", "commit.gpgsign", "false");
        git(repo, "config", "core.hooksPath", "/dev/null");
    }

    private static void commit(Path repo, String message) throws IOException {
        git(repo, "add", "--all");
        git(repo, "commit", "--quiet", "--no-verify", "-m", message);
    }

    private static boolean inRepository(Path dir) {
        try {
            git(dir, "rev-parse", "--show-toplevel");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean gitWorks() {
        try {
            git(Path.of("").toAbsolutePath(), "--version");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String git(Path dir, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("git " + String.join(" ", args) + " failed: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        return output.trim();
    }
}
