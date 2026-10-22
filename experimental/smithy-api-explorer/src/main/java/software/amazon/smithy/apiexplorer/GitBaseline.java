/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.apiexplorer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The build's model sources as a git revision has them, written to a temporary directory: the
 * baseline {@code smithy diff --mode git} compares the working tree with.
 *
 * <p>Each source root of the build (a file or a directory from smithy-build.json) is read from
 * the revision into its own subdirectory, so files deleted since then are included and roots
 * that hold files of the same name do not overwrite each other. Within a root, files keep their
 * paths relative to it, as {@link SourceDiff#sources} names the current files. Nothing in the
 * repository is changed: no worktree or branch is created.
 */
final class GitBaseline implements AutoCloseable {

    private static final long TIMEOUT_SECONDS = 60;

    private final Path dir;
    private final String revision;
    private final String commit;
    private final List<Path> roots = new ArrayList<>();

    private GitBaseline(Path dir, String revision, String commit) {
        this.dir = dir;
        this.revision = revision;
        this.commit = commit;
    }

    /** Why the sources could not be read from git. */
    static final class Unavailable extends Exception {
        private final boolean invalidRevision;

        Unavailable(String message, boolean invalidRevision) {
            super(message);
            this.invalidRevision = invalidRevision;
        }

        /** Whether the configured revision does not name a commit, as opposed to git being unusable here. */
        boolean invalidRevision() {
            return invalidRevision;
        }
    }

    /** The temporary directory holding the revision's model files, deleted by {@link #close}. */
    Path dir() {
        return dir;
    }

    /**
     * The revision's model roots, one for each build source root the revision has files for, in
     * the order of the build's sources: a directory laid out like the source directory, or the
     * file of a source file.
     */
    List<Path> roots() {
        return List.copyOf(roots);
    }

    /** The revision as configured, for example {@code HEAD} or {@code main}. */
    String revision() {
        return revision;
    }

    /** The abbreviated commit the revision resolved to. */
    String commit() {
        return commit.length() > 12 ? commit.substring(0, 12) : commit;
    }

    /**
     * Writes the model files of {@code sourceRoots} as {@code revision} has them to a temporary
     * directory.
     *
     * @param sourceRoots The build's source roots: model files and directories.
     * @param revision A git revision, such as {@code HEAD}, a branch, a tag, or a commit.
     * @return Returns the checked out baseline, which must be closed to delete the directory.
     * @throws Unavailable when the sources are not in a git repository, the revision does not
     *     name a commit, or the revision has none of the sources.
     */
    static GitBaseline checkout(Collection<Path> sourceRoots, String revision) throws Unavailable {
        List<Path> roots = new ArrayList<>();
        for (Path root : sourceRoots) {
            try {
                roots.add(root.toRealPath());
            } catch (IOException e) {
                // A declared source that does not exist has nothing to compare.
            }
        }
        if (roots.isEmpty()) {
            throw new Unavailable("The build has no model sources to compare with git " + revision, false);
        }
        Path start = Files.isDirectory(roots.get(0)) ? roots.get(0) : roots.get(0).getParent();
        Path top;
        try {
            top = Path.of(git(start, "rev-parse", "--show-toplevel").trim()).toRealPath();
        } catch (GitNotFound e) {
            throw new Unavailable(e.getMessage(), false);
        } catch (IOException e) {
            throw new Unavailable("The model sources are not in a git repository (" + e.getMessage() + ")", false);
        }
        if (revision.startsWith("-")) {
            throw new Unavailable("The git revision '" + revision + "' does not name a commit", true);
        }
        String commit;
        try {
            commit = git(top, "rev-parse", "--verify", "--quiet", revision + "^{commit}").trim();
        } catch (IOException e) {
            if (!hasCommits(top)) {
                throw new Unavailable("The git repository " + top + " has no commits yet", false);
            }
            throw new Unavailable("The git revision '" + revision + "' does not name a commit in " + top, true);
        }

        Path dir;
        try {
            dir = Files.createTempDirectory("api-explorer-baseline-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        GitBaseline baseline = new GitBaseline(dir, revision, commit);
        try {
            for (Path root : roots) {
                if (!root.startsWith(top)) {
                    continue;
                }
                Path rootDir = dir.resolve(String.valueOf(baseline.roots.size()));
                boolean isDirectory = Files.isDirectory(root);
                String rel = top.relativize(root).toString().replace('\\', '/');
                String listing = git(top, "ls-tree", "-r", "-z", "--name-only", commit, "--", rel.isEmpty() ? "." : rel);
                Path written = null;
                for (String file : listing.split("\0")) {
                    if (file.isEmpty() || !(file.endsWith(".smithy") || file.endsWith(".json"))) {
                        continue;
                    }
                    String name = isDirectory
                            ? (rel.isEmpty() ? file : Path.of(rel).relativize(Path.of(file)).toString())
                            : Path.of(file).getFileName().toString();
                    Path target = rootDir.resolve(name).normalize();
                    if (!target.startsWith(rootDir)) {
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    Files.write(target, gitBytes(top, "show", commit + ":" + file));
                    written = target;
                }
                if (written != null) {
                    baseline.roots.add(isDirectory ? rootDir : written);
                }
            }
        } catch (IOException e) {
            baseline.close();
            throw new Unavailable("The model sources could not be read from git " + revision + ": " + e.getMessage(),
                    false);
        }
        if (baseline.roots.isEmpty()) {
            baseline.close();
            throw new Unavailable("None of the model sources exist in git " + revision, false);
        }
        return baseline;
    }

    private static boolean hasCommits(Path top) {
        try {
            git(top, "rev-parse", "--verify", "--quiet", "HEAD^{commit}");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void close() {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // A temporary directory left behind is not worth failing the build over.
        }
    }

    private static String git(Path dir, String... args) throws IOException {
        return new String(gitBytes(dir, args), StandardCharsets.UTF_8);
    }

    /** Runs git in {@code dir} and returns its standard output; a non-zero exit is an IOException. */
    private static byte[] gitBytes(Path dir, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(dir.toString());
        command.addAll(List.of(args));
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new GitNotFound("git could not be run, so the model was not compared with a git revision ("
                    + e.getMessage() + ")");
        }
        process.getOutputStream().close();
        // Read both streams on their own threads, so a full pipe cannot block git and a git that
        // hangs is stopped by the timeout.
        CompletableFuture<byte[]> out = readAsync(process.getInputStream());
        CompletableFuture<byte[]> err = readAsync(process.getErrorStream());
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("git " + String.join(" ", args) + " timed out after " + TIMEOUT_SECONDS
                        + " seconds");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running git", e);
        }
        if (process.exitValue() != 0) {
            String message = new String(join(err), StandardCharsets.UTF_8).trim();
            throw new IOException(message.isEmpty() ? "git exited with " + process.exitValue() : message);
        }
        return join(out);
    }

    private static byte[] join(CompletableFuture<byte[]> output) throws IOException {
        try {
            return output.join();
        } catch (CompletionException e) {
            throw new IOException("The output of git could not be read", e.getCause());
        }
    }

    /** Reads a stream to its end on a new daemon thread, so a busy common pool cannot delay it. */
    private static CompletableFuture<byte[]> readAsync(InputStream stream) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        Thread reader = new Thread(() -> {
            try (stream) {
                result.complete(stream.readAllBytes());
            } catch (IOException e) {
                result.completeExceptionally(e);
            }
        }, "api-explorer-git-reader");
        reader.setDaemon(true);
        reader.start();
        return result;
    }

    /** The git executable could not be started. */
    private static final class GitNotFound extends IOException {
        GitNotFound(String message) {
            super(message);
        }
    }
}
