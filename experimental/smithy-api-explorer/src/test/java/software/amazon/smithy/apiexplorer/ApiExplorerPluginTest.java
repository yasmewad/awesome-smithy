/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.apiexplorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.smithy.build.MockManifest;
import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.build.SmithyBuild;
import software.amazon.smithy.build.SmithyBuildResult;
import software.amazon.smithy.build.SmithyBuildPlugin;
import software.amazon.smithy.build.model.ProjectionConfig;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.build.model.TransformConfig;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.ExpectationNotMetException;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.ModelSerializer;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidationEvent;

class ApiExplorerPluginTest {

    private static final ObjectNode NO_SETTINGS = Node.objectNode();

    private static ObjectNode changes(String page) {
        return TestModels.data(page).expectObjectMember("changes");
    }

    private static List<ObjectNode> diffLines(ObjectNode changes) {
        return changes.expectArrayMember("files").getElementsAs(ObjectNode.class).stream()
                .flatMap(f -> f.expectArrayMember("hunks").getElementsAs(ObjectNode.class).stream())
                .flatMap(h -> h.expectArrayMember("lines").getElementsAs(ObjectNode.class).stream())
                .toList();
    }

    private static String diffText(ObjectNode changes) {
        StringBuilder text = new StringBuilder();
        diffLines(changes).forEach(l -> text.append(l.expectStringMember("kind").getValue())
                .append(l.expectStringMember("text").getValue())
                .append('\n'));
        return text.toString();
    }

    // ── Plugin and page ───────────────────────────────────────────────────────

    @Test
    void isRegisteredAsABuildPlugin() {
        Optional<SmithyBuildPlugin> found = ServiceLoader.load(SmithyBuildPlugin.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(p -> p.getName().equals("api-explorer"))
                .findFirst();
        assertTrue(found.isPresent());
        assertTrue(found.get() instanceof ApiExplorerPlugin);
    }

    @Test
    void writesASelfContainedPageWithTheData() {
        String page = TestModels.run(NO_SETTINGS, TestModels.ORDERS);
        assertFalse(page.contains(ApiExplorerPlugin.DATA_ELEMENT), "the data element still has its placeholder");

        ObjectNode data = TestModels.data(page);
        assertEquals("example.orders#OrderService",
                data.expectObjectMember("service").expectStringMember("id").getValue());
        assertEquals(7, data.expectArrayMember("operations").size());
        assertTrue(data.expectObjectMember("shapes").containsMember("example.orders#OrderNotFound"));
        assertTrue(data.expectObjectMember("validation").expectBooleanMember("valid").getValue());
        assertFalse(data.containsMember("changes"));
        assertFalse(data.containsMember("changesSkipped"));
        assertFalse(data.containsMember("title"));

        ObjectNode generator = data.expectObjectMember("generator");
        assertEquals("smithy-api-explorer", generator.expectStringMember("name").getValue());
        assertTrue(generator.expectBooleanMember("experimental").getValue());
        assertFalse(generator.expectStringMember("version").getValue().isBlank());
    }

    @Test
    void sourceFilesAreShownRelativeToTheWorkingDirectory() {
        ObjectNode service = TestModels.data(TestModels.run(NO_SETTINGS, TestModels.ORDERS)).expectObjectMember("service");
        String file = service.expectObjectMember("source").expectStringMember("file").getValue();
        // Gradle runs tests in the project directory, and the fixture is under it.
        assertFalse(Path.of(file).isAbsolute(), file);
        assertTrue(file.endsWith("orders/main.smithy"), file);
    }

    @Test
    void hasNoReviewOrCommentData() {
        ObjectNode data = TestModels.data(TestModels.run(NO_SETTINGS, TestModels.ORDERS));
        assertFalse(data.containsMember("comments"));
        assertFalse(data.containsMember("review"));
        assertEquals(List.of("generator", "operations", "resources", "service", "shapes", "validation"),
                data.getStringMap().keySet().stream().sorted().toList());
    }

    @Test
    void documentationCannotCloseTheDataElement(@TempDir Path tmp) throws IOException {
        Path model = Files.createDirectories(tmp.resolve("injection"));
        String docs = "Ends the script early </script><script>alert(1)</script> and opens <!-- a comment";
        Files.writeString(model.resolve("main.smithy"), String.join("\n",
                "$version: \"2\"",
                "namespace example.injection",
                "",
                "/// " + docs,
                "service Injected { version: \"1\" }",
                ""));
        String page = TestModels.run(NO_SETTINGS, model);
        String open = "<script id=\"api-explorer-data\" type=\"application/json\">";
        String embedded = page.substring(page.indexOf(open) + open.length());
        embedded = embedded.substring(0, embedded.indexOf("</script>"));
        assertTrue(embedded.contains("alert(1)"), "the element ended before the documentation");
        assertFalse(embedded.contains("</"), "documentation can close the data element");
        assertFalse(embedded.contains("<!--"), "documentation can open an HTML comment");

        // The text reads back unchanged.
        ObjectNode service = TestModels.data(page).expectObjectMember("service");
        assertEquals(docs, service.expectStringMember("documentation").getValue());
    }

    @Test
    void pageStartsAfterEveryTopLevelDeclaration() throws IOException {
        // The page renders synchronously when it starts, so a top-level const declared after the
        // boot() call would still be uninitialized (a ReferenceError) when the first view uses it.
        String template;
        try (var in = ApiExplorerPlugin.class.getResourceAsStream(ApiExplorerPlugin.TEMPLATE)) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> lines = template.lines().toList();
        int boot = lines.lastIndexOf("boot();");
        assertTrue(boot > 0, "the template calls boot() at the top level");
        assertEquals(1, lines.stream().filter("boot();"::equals).count());
        for (int i = boot + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            assertFalse(line.startsWith("const ") || line.startsWith("let ") || line.startsWith("class "),
                    "declared after boot(): " + line);
        }
        assertEquals("</script>", lines.stream().skip(boot + 1).filter(l -> !l.isBlank()).findFirst().orElse(""));
    }

    @Test
    void templateMustHaveExactlyOneDataElement() {
        ObjectNode data = Node.objectNode();
        assertThrows(IllegalStateException.class,
                () -> ApiExplorerPlugin.render("<html></html>", data, Optional.empty()));
        String twice = ApiExplorerPlugin.DATA_ELEMENT + ApiExplorerPlugin.DATA_ELEMENT;
        assertThrows(IllegalStateException.class, () -> ApiExplorerPlugin.render(twice, data, Optional.empty()));
    }

    @Test
    void placeholderOutsideTheDataElementIsLeftAlone() {
        String template = "<!-- " + ApiExplorerPlugin.PLACEHOLDER + " -->" + ApiExplorerPlugin.DATA_ELEMENT;
        String page = ApiExplorerPlugin.render(template, Node.objectNode(), Optional.empty());
        assertEquals("<!-- " + ApiExplorerPlugin.PLACEHOLDER + " -->"
                + "<script id=\"api-explorer-data\" type=\"application/json\">{}</script>", page);
    }

    @Test
    void titleSettingOverridesThePageTitle() {
        MockManifest manifest = new MockManifest();
        ObjectNode settings = TestModels.settings("title", "Orders <API> & more");
        new ApiExplorerPlugin(() -> "<html><head><title>API Explorer</title></head><body>"
                + ApiExplorerPlugin.DATA_ELEMENT + "</body></html>")
                .execute(TestModels.context(manifest, settings, List.of(TestModels.ORDERS), List.of()));
        String page = manifest.expectFileString("index.html");
        assertTrue(page.contains("<title>Orders &lt;API&gt; &amp; more</title>"), page);
        assertEquals("Orders <API> & more", TestModels.data(page).expectStringMember("title").getValue());
    }

    @Test
    void serviceSettingSelectsAService(@TempDir Path tmp) throws IOException {
        Path model = Files.createDirectories(tmp.resolve("two-services"));
        Files.writeString(model.resolve("main.smithy"), String.join("\n",
                "$version: \"2\"",
                "namespace example.two",
                "service Alpha { version: \"1\" }",
                "service Beta { version: \"1\" }",
                ""));
        String first = TestModels.data(TestModels.run(NO_SETTINGS, model))
                .expectObjectMember("service").expectStringMember("id").getValue();
        assertEquals("example.two#Alpha", first);
        String chosen = TestModels.data(TestModels.run(TestModels.settings("service", "example.two#Beta"), model))
                .expectObjectMember("service").expectStringMember("id").getValue();
        assertEquals("example.two#Beta", chosen);
        assertThrows(IllegalArgumentException.class,
                () -> TestModels.run(TestModels.settings("service", "example.two#Gamma"), model));
    }

    @Test
    void unknownSettingsAreRejected() {
        assertThrows(ExpectationNotMetException.class,
                () -> TestModels.run(TestModels.settings("format", "html"), TestModels.ORDERS));
    }

    // ── Changes against a baseline ────────────────────────────────────────────

    @Test
    void baselineMarksAddedChangedAndRemoved(@TempDir Path tmp) throws IOException {
        Path current = TestModels.editedCopy(tmp.resolve("current"), text -> text
                // Rename an operation: GetStatus is removed and GetOrderLifecycle is added.
                .replace("operations: [GetStatus, CreateOrder]", "operations: [GetOrderLifecycle, CreateOrder]")
                .replace("operation GetStatus {", "operation GetOrderLifecycle {")
                // Remove a member and change a doc comment.
                .replace("        @httpQuery(\"verbose\")\n        verbose: Boolean\n", "")
                .replace("/// The order does not exist.", "/// No order exists with the given ID."));
        ObjectNode changes = changes(TestModels.run(
                TestModels.settings("baseline", TestModels.ORDERS.toString()), current));
        ObjectNode entries = changes.expectObjectMember("entries");

        assertEquals("added", entries.expectStringMember("example.orders#GetOrderLifecycle").getValue());
        assertEquals("changed", entries.expectStringMember("example.orders#OrderNotFound").getValue());
        assertEquals("changed", entries.expectStringMember("example.orders#OrderService").getValue());
        assertFalse(entries.containsMember("example.orders#CreateOrder"));

        List<String> removed = changes.expectArrayMember("removed").getElementsAs(ObjectNode.class).stream()
                .map(r -> r.expectStringMember("id").getValue())
                .toList();
        assertTrue(removed.contains("example.orders#GetStatus"), removed.toString());

        // Removing an operation is a breaking change.
        assertTrue(changes.expectArrayMember("compatibility").getElementsAs(ObjectNode.class).stream()
                .anyMatch(e -> e.expectBooleanMember("breaking").getValue()));
    }

    @Test
    void baselineSourceDiffShowsEditedLinesAndTheElementsTheyDefine(@TempDir Path tmp) throws IOException {
        Path current = TestModels.editedCopy(tmp.resolve("current"), text -> text
                .replace("/// The order does not exist.", "/// No order exists with the given ID.")
                .replace("structure ThrottlingError {}", "structure ThrottlingError {}\n\nstring Note"));
        ObjectNode changes = changes(TestModels.run(
                TestModels.settings("baseline", TestModels.ORDERS.toString()), current));
        assertEquals("source", changes.expectStringMember("diffMode").getValue());
        List<ObjectNode> files = changes.expectArrayMember("files").getElementsAs(ObjectNode.class);
        assertEquals(1, files.size());
        assertEquals("main.smithy", files.get(0).expectStringMember("path").getValue());

        List<String> changed = diffLines(changes).stream()
                .filter(l -> !l.expectStringMember("kind").getValue().equals(" "))
                .map(l -> l.expectStringMember("kind").getValue() + l.expectStringMember("text").getValue())
                .toList();
        assertEquals(List.of("-/// The order does not exist.", "+/// No order exists with the given ID.",
                "+", "+string Note"), changed);
        // The line that defines an element names it.
        assertTrue(diffLines(changes).stream()
                .anyMatch(l -> l.getStringMemberOrDefault("element", "").equals("example.orders#OrderNotFound")
                        && l.expectStringMember("text").getValue().startsWith("structure OrderNotFound")));
        assertFalse(diffLines(changes).stream().anyMatch(l -> l.containsMember("anchor")));
        assertEquals(List.of("example.orders#OrderNotFound"),
                changes.expectArrayMember("docsChanged").getElements().stream()
                        .map(n -> n.expectStringNode().getValue())
                        .toList());
    }

    @Test
    void baselineListsTraitChanges(@TempDir Path tmp) throws IOException {
        Path current = TestModels.editedCopy(tmp.resolve("current"), text -> text
                .replace("@httpError(404)", "@httpError(410)")
                .replace("structure OrderNotFound {\n    message: String",
                        "structure OrderNotFound {\n    @required\n    message: String"));
        ObjectNode changes = changes(TestModels.run(
                TestModels.settings("baseline", TestModels.ORDERS.toString()), current));
        ObjectNode traits = changes.expectObjectMember("traits");
        ObjectNode changed = traits.expectObjectMember("example.orders#OrderNotFound")
                .expectArrayMember("changed").get(0).orElseThrow().expectObjectNode();
        assertEquals("smithy.api#httpError", changed.expectStringMember("id").getValue());
        assertEquals("@httpError(404)", changed.expectStringMember("before").getValue());
        assertEquals("smithy.api#required", traits.expectObjectMember("example.orders#OrderNotFound$message")
                .expectArrayMember("added").get(0).orElseThrow().expectStringNode().getValue());
    }

    @Test
    void baselineDangerEventsAreNotBreaking(@TempDir Path tmp) throws IOException {
        // Reordering members is DANGER (ChangedMemberOrder): it changes the struct layout, and so
        // the ABI, for languages such as C and C++, but is fine when the author accepts the risk.
        Path current = TestModels.editedCopy(tmp.resolve("current"), text -> text.replace(
                "structure Bin {\n    label: String\n\n    children: BinList\n}",
                "structure Bin {\n    children: BinList\n\n    label: String\n}"));
        ObjectNode changes = changes(TestModels.run(
                TestModels.settings("baseline", TestModels.ORDERS.toString()), current));
        List<ObjectNode> events = changes.expectArrayMember("compatibility").getElementsAs(ObjectNode.class);
        ObjectNode event = events.stream()
                .filter(e -> e.expectStringMember("id").getValue().startsWith("ChangedMemberOrder"))
                .findFirst()
                .orElseThrow();
        assertEquals("DANGER", event.expectStringMember("severity").getValue());
        assertTrue(event.expectBooleanMember("danger").getValue());
        assertFalse(event.expectBooleanMember("breaking").getValue());
        assertTrue(events.stream().noneMatch(e -> e.expectBooleanMember("breaking").getValue()), events.toString());
    }

    @Test
    void baselineJsonAstUsesNormalizedIdlDiff(@TempDir Path tmp) throws IOException {
        // A JSON AST baseline, as a build artifact would carry, has no IDL lines to compare.
        Model fixture = TestModels.assemble(TestModels.ORDERS).unwrap();
        Path json = tmp.resolve("baseline/model.json");
        Files.createDirectories(json.getParent());
        Files.writeString(json, Node.prettyPrintJson(ModelSerializer.builder().build().serialize(fixture)));
        Path current = TestModels.editedCopy(tmp.resolve("current"),
                text -> text.replace("/// The order does not exist.", "/// No order exists with the given ID."));

        ObjectNode changes = changes(TestModels.run(TestModels.settings("baseline", json.toString()), current));
        assertEquals("normalized", changes.expectStringMember("diffMode").getValue());
        String diff = diffText(changes);
        assertTrue(diff.contains("+/// No order exists with the given ID."), diff);
        assertTrue(diff.contains("-/// The order does not exist."), diff);
        // The prelude is not the model's own change.
        assertFalse(diff.contains("smithy.api#"), diff);
    }

    @Test
    void baselineResolvesTheModelsDependencies(@TempDir Path tmp) throws IOException {
        // The protocol trait is an import of the build, not one of its sources, and the baseline
        // directory does not have it: the baseline is assembled with the model's dependencies.
        Path current = Files.createDirectories(tmp.resolve("current"));
        Files.copy(TestModels.ORDERS.resolve("main.smithy"), current.resolve("main.smithy"));
        Path baseline = Files.createDirectories(tmp.resolve("baseline"));
        Files.copy(TestModels.ORDERS.resolve("main.smithy"), baseline.resolve("main.smithy"));

        String page = TestModels.run(TestModels.settings("baseline", baseline.toString()),
                List.of(current), List.of(TestModels.ORDERS.resolve("aws-protocols-stub.smithy")));
        ObjectNode changes = changes(page);
        assertTrue(changes.expectObjectMember("entries").isEmpty(), Node.printJson(changes));
        assertTrue(changes.expectArrayMember("removed").isEmpty(), Node.printJson(changes));
        assertTrue(changes.expectArrayMember("files").isEmpty(), Node.printJson(changes));
    }

    @Test
    void baselineRelativePathResolvesAgainstTheWorkingDirectory(@TempDir Path tmp) throws IOException {
        Path current = TestModels.editedCopy(tmp.resolve("current"),
                text -> text.replace("/// The order does not exist.", "/// No order exists with the given ID."));
        Path relative = Path.of("").toAbsolutePath().relativize(TestModels.ORDERS);
        assertFalse(relative.isAbsolute());
        ObjectNode changes = changes(TestModels.run(TestModels.settings("baseline", relative.toString()), current));
        assertEquals("changed", changes.expectObjectMember("entries")
                .expectStringMember("example.orders#OrderNotFound").getValue());
    }

    @Test
    void baselineMissingPathIsSkipped() {
        ObjectNode data = TestModels.data(TestModels.run(TestModels.settings("baseline", "does/not/exist"),
                TestModels.ORDERS));
        assertFalse(data.containsMember("changes"));
        String skipped = data.expectStringMember("changesSkipped").getValue();
        assertTrue(skipped.contains("does not exist: does/not/exist"), skipped);
        assertTrue(skipped.contains(Path.of("does/not/exist").toAbsolutePath().normalize().toString()), skipped);
    }

    @Test
    void baselineThatDoesNotLoadIsSkipped(@TempDir Path tmp) throws IOException {
        Path broken = Files.createDirectories(tmp.resolve("broken"));
        Files.writeString(broken.resolve("main.smithy"), "$version: \"2\"\nnamespace example.orders\nstructure {\n");
        ObjectNode data = TestModels.data(TestModels.run(TestModels.settings("baseline", broken.toString()),
                TestModels.ORDERS));
        assertFalse(data.containsMember("changes"));
        assertTrue(data.expectStringMember("changesSkipped").getValue().contains("baseline could not be loaded"),
                data.expectStringMember("changesSkipped").getValue());
    }

    @Test
    void baselineIsNotComparedWithAModelThatHasLoadErrors() {
        MockManifest manifest = new MockManifest();
        PluginContext loaded = TestModels.context(manifest, TestModels.settings("baseline", TestModels.ORDERS.toString()),
                List.of(TestModels.ORDERS), List.of());
        ValidationEvent syntaxError = ValidationEvent.builder()
                .id("Model")
                .severity(Severity.ERROR)
                .message("Syntax error")
                .build();
        new ApiExplorerPlugin().execute(loaded.toBuilder().events(List.of(syntaxError)).build());
        ObjectNode data = TestModels.data(manifest.expectFileString("index.html"));
        assertFalse(data.containsMember("changes"));
        assertTrue(data.expectStringMember("changesSkipped").getValue().contains("not compared"));
        assertFalse(data.expectObjectMember("validation").expectBooleanMember("valid").getValue());
    }

    @Test
    void runsFromSmithyBuildConfig(@TempDir Path tmp) {
        SmithyBuildConfig config = SmithyBuildConfig.builder()
                .version("1.0")
                .outputDirectory(tmp.toString())
                .plugins(Map.of("api-explorer", TestModels.settings("title", "Orders")))
                .build();
        SmithyBuildResult result = new SmithyBuild()
                .config(config)
                .registerSources(TestModels.ORDERS)
                .model(TestModels.assemble(TestModels.ORDERS).unwrap())
                .build();
        Path page = result.getProjectionResult("source").orElseThrow()
                .getPluginManifest("api-explorer").orElseThrow()
                .getBaseDir().resolve("index.html");
        assertTrue(Files.isRegularFile(page), page.toString());
        assertEquals(tmp.resolve("source/api-explorer/index.html"), page);
    }

    @Test
    void normalizedDiffKeepsNamespacesOnlyTheBaselineHas() {
        Model old = Model.assembler().addUnparsedModel("old.smithy",
                "$version: \"2\"\nnamespace com.example.v1\nstructure Thing {}\n").assemble().unwrap();
        Model current = Model.assembler().addUnparsedModel("new.smithy",
                "$version: \"2\"\nnamespace com.example.v2\nstructure Thing {}\n").assemble().unwrap();
        assertEquals(java.util.Set.of("com.example.v1", "com.example.v2"),
                ApiExplorerPlugin.diffNamespaces(old, current));
    }

    // ── Projections, project directories, JARs, and the class path ────────────

    private static String pageOf(SmithyBuildResult result, String projection) throws IOException {
        Path page = result.getProjectionResult(projection).orElseThrow()
                .getPluginManifest("api-explorer").orElseThrow()
                .getBaseDir().resolve("index.html");
        return Files.readString(page);
    }

    @Test
    void projectionTransformsAreAppliedToTheBaseline(@TempDir Path tmp) throws IOException {
        Path model = Files.createDirectories(tmp.resolve("model"));
        Files.writeString(model.resolve("main.smithy"), String.join("\n",
                "$version: \"2\"",
                "namespace ex",
                "service S { version: \"1\", operations: [A, B] }",
                "operation A {}",
                "@tags([\"internal\"])",
                "operation B {}",
                ""));
        ProjectionConfig external = ProjectionConfig.builder()
                .transforms(List.of(TransformConfig.builder()
                        .name("excludeShapesByTag")
                        .args(Node.objectNode().withMember("tags", Node.fromStrings("internal")))
                        .build()))
                .plugins(Map.of("api-explorer", TestModels.settings("baseline", model.toString())))
                .build();
        SmithyBuildConfig config = SmithyBuildConfig.builder()
                .version("1.0")
                .outputDirectory(tmp.resolve("build").toString())
                .projections(Map.of("external", external))
                .build();
        SmithyBuildResult result = new SmithyBuild()
                .config(config)
                .registerSources(model)
                .model(TestModels.assemble(model).unwrap())
                .build();

        ObjectNode data = TestModels.data(pageOf(result, "external"));
        assertEquals(List.of("ex#A"), data.expectArrayMember("operations").getElementsAs(ObjectNode.class).stream()
                .map(o -> o.expectStringMember("id").getValue())
                .toList());
        ObjectNode changes = data.expectObjectMember("changes");
        assertTrue(changes.expectObjectMember("entries").isEmpty(), Node.printJson(changes));
        assertTrue(changes.expectArrayMember("removed").isEmpty(), Node.printJson(changes));
        assertTrue(changes.expectArrayMember("compatibility").isEmpty(), Node.printJson(changes));
    }

    @Test
    void projectDirectoryBaselineLoadsOnlyItsDeclaredSources(@TempDir Path tmp) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(project.resolve("smithy-build.json"), "{\"version\": \"1.0\", \"sources\": [\"model\"]}");
        TestModels.editedCopy(project.resolve("model"), text -> text);
        // A stale build output and a stray model elsewhere in the project are not part of it.
        Path stale = Files.createDirectories(project.resolve("build/smithy/source/model"));
        Model edited = TestModels.assemble(TestModels.editedCopy(tmp.resolve("edited"),
                text -> text.replace("/// The order does not exist.", "/// stale"))).unwrap();
        Files.writeString(stale.resolve("model.json"),
                Node.prettyPrintJson(ModelSerializer.builder().build().serialize(edited)));
        Files.writeString(project.resolve("stray.smithy"),
                "$version: \"2\"\nnamespace example.orders\nstructure Stray {}\n");

        ObjectNode changes = changes(TestModels.run(TestModels.settings("baseline", project.toString()),
                TestModels.ORDERS));
        assertEquals("source", changes.expectStringMember("diffMode").getValue());
        assertTrue(changes.expectObjectMember("entries").isEmpty(), Node.printJson(changes));
        assertTrue(changes.expectArrayMember("removed").isEmpty(), Node.printJson(changes));
        assertTrue(changes.expectArrayMember("compatibility").isEmpty(), Node.printJson(changes));
        assertTrue(changes.expectArrayMember("files").isEmpty(), Node.printJson(changes));
    }

    private static Path jarOf(Path jar, Path dir, String... files) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/smithy/manifest"));
            out.write((String.join("\n", files) + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (String file : files) {
                out.putNextEntry(new JarEntry("META-INF/smithy/" + file));
                out.write(Files.readAllBytes(dir.resolve(file)));
                out.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void jarBaselineUsesNormalizedIdlDiff(@TempDir Path tmp) throws IOException {
        Path jar = jarOf(tmp.resolve("baseline.jar"), TestModels.ORDERS, "main.smithy", "aws-protocols-stub.smithy");
        Path current = TestModels.editedCopy(tmp.resolve("current"),
                text -> text.replace("/// The order does not exist.", "/// No order exists with the given ID."));
        ObjectNode changes = changes(TestModels.run(TestModels.settings("baseline", jar.toString()), current));
        assertEquals("normalized", changes.expectStringMember("diffMode").getValue());
        assertEquals("changed", changes.expectObjectMember("entries")
                .expectStringMember("example.orders#OrderNotFound").getValue());
        assertEquals(1, changes.expectObjectMember("entries").size(), Node.printJson(changes));
    }

    @Test
    void baselineDiscoversDependenciesOnThePluginClassPath(@TempDir Path tmp) throws IOException {
        // Without the build's sources, the plugin cannot tell its dependencies apart, so the
        // baseline finds them on the plugin class path instead.
        Path stubs = Files.createDirectories(tmp.resolve("stubs"));
        Files.writeString(stubs.resolve("marker.smithy"),
                "$version: \"2\"\nnamespace test.stub\n@trait\nstructure marker {}\n");
        Path jar = jarOf(tmp.resolve("stubs.jar"), stubs, "marker.smithy");
        Path baseline = Files.createDirectories(tmp.resolve("baseline"));
        Files.writeString(baseline.resolve("main.smithy"), "$version: \"2\"\nnamespace ex\nuse test.stub#marker\n"
                + "@marker\nservice S { version: \"1\", operations: [A] }\noperation A {}\n");

        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {jar.toUri().toURL()},
                ApiExplorerPluginTest.class.getClassLoader())) {
            Model model = Model.assembler(classLoader).discoverModels(classLoader)
                    .addImport(baseline.resolve("main.smithy")).assemble().unwrap();
            MockManifest manifest = new MockManifest();
            new ApiExplorerPlugin().execute(PluginContext.builder()
                    .fileManifest(manifest)
                    .model(model)
                    .settings(TestModels.settings("baseline", baseline.toString()))
                    .pluginClassLoader(classLoader)
                    .build());
            ObjectNode data = TestModels.data(manifest.expectFileString(ApiExplorerPlugin.OUTPUT));
            assertFalse(data.containsMember("changesSkipped"), Node.printJson(data));
            ObjectNode changes = data.expectObjectMember("changes");
            assertTrue(changes.expectObjectMember("entries").isEmpty(), Node.printJson(changes));
            assertTrue(changes.expectArrayMember("removed").isEmpty(), Node.printJson(changes));
        }
    }

    @Test
    void importsTheBaselineDefinesAreComparedWithIt(@TempDir Path tmp) throws IOException {
        // The stub is an import of the build, not a source, but the baseline defines it too: the
        // baseline's own definition is compared, rather than the current one being copied in.
        Path current = Files.createDirectories(tmp.resolve("current"));
        Files.copy(TestModels.ORDERS.resolve("main.smithy"), current.resolve("main.smithy"));
        Path stub = tmp.resolve("imports/aws-protocols-stub.smithy");
        Files.createDirectories(stub.getParent());
        Files.writeString(stub, Files.readString(TestModels.ORDERS.resolve("aws-protocols-stub.smithy"))
                .replace("structure restJson1 {}", "structure restJson1 {\n    http: String\n}"));

        ObjectNode changes = changes(TestModels.run(TestModels.settings("baseline", TestModels.ORDERS.toString()),
                List.of(current), List.of(stub)));
        assertTrue(Node.printJson(changes.expectArrayMember("compatibility")).contains("aws.protocols#restJson1$http"),
                Node.printJson(changes));
    }

    // ── Settings and logging ──────────────────────────────────────────────────

    @Test
    void settingsOfTheWrongTypeAreRejected() {
        ObjectNode settings = Node.objectNode().withMember("service", Node.from(1));
        assertThrows(ExpectationNotMetException.class, () -> TestModels.run(settings, TestModels.ORDERS));
    }

    @Test
    void ambiguousServiceNameIsRejected(@TempDir Path tmp) throws IOException {
        Path model = Files.createDirectories(tmp.resolve("weather"));
        Files.writeString(model.resolve("v1.smithy"), "$version: \"2\"\nnamespace a.v1\nservice Weather {}\n");
        Files.writeString(model.resolve("v2.smithy"), "$version: \"2\"\nnamespace a.v2\nservice Weather {}\n");
        var e = assertThrows(IllegalArgumentException.class,
                () -> TestModels.run(TestModels.settings("service", "Weather"), model));
        assertTrue(e.getMessage().contains("a.v1#Weather") && e.getMessage().contains("a.v2#Weather"),
                e.getMessage());
        String chosen = TestModels.data(TestModels.run(TestModels.settings("service", "a.v2#Weather"), model))
                .expectObjectMember("service").expectStringMember("id").getValue();
        assertEquals("a.v2#Weather", chosen);
    }

    @Test
    void logsTheExperimentalAndMultipleServiceWarningsOnEveryRun(@TempDir Path tmp) throws IOException {
        Path model = Files.createDirectories(tmp.resolve("two-services"));
        Files.writeString(model.resolve("main.smithy"),
                "$version: \"2\"\nnamespace example.two\nservice Alpha {}\nservice Beta {}\n");
        Logger logger = Logger.getLogger(ApiExplorerPlugin.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            TestModels.run(NO_SETTINGS, model);
            TestModels.run(NO_SETTINGS, model);
        } finally {
            logger.removeHandler(handler);
        }
        List<String> warnings = records.stream()
                .filter(r -> r.getLevel() == Level.WARNING)
                .map(LogRecord::getMessage)
                .toList();
        assertEquals(2, warnings.stream().filter(m -> m.contains("experimental")).count(), warnings.toString());
        assertEquals(2, warnings.stream().filter(m -> m.contains("more than one service")
                && m.contains("example.two#Beta")).count(), warnings.toString());
    }

    @Test
    void outputIsDeterministic(@TempDir Path tmp) throws IOException {
        Path current = TestModels.editedCopy(tmp.resolve("current"),
                text -> text.replace("/// The order does not exist.", "/// No order exists with the given ID."));
        ObjectNode settings = TestModels.settings("baseline", TestModels.ORDERS.toString());
        assertEquals(TestModels.run(settings, current), TestModels.run(settings, current));
    }
}
