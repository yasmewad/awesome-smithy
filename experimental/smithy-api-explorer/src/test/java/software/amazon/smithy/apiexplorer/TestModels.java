/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.apiexplorer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import software.amazon.smithy.build.MockManifest;
import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.validation.ValidatedResult;

/** Fixtures and helpers shared by the tests. */
final class TestModels {

    /** The orders fixture: a service with resources, mixins, enums, unions, errors, and examples. */
    static final Path ORDERS = resource("orders");

    private TestModels() {}

    static Path resource(String name) {
        try {
            return Path.of(TestModels.class.getResource(name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static ValidatedResult<Model> assemble(Path... sources) {
        ModelAssembler assembler = Model.assembler();
        for (Path source : sources) {
            assembler.addImport(source);
        }
        return assembler.assemble();
    }

    /** A plugin context for a build whose sources are {@code sources}, as smithy-build creates one. */
    static PluginContext context(MockManifest manifest, ObjectNode settings, List<Path> sources, List<Path> imports) {
        ModelAssembler assembler = Model.assembler();
        sources.forEach(assembler::addImport);
        imports.forEach(assembler::addImport);
        ValidatedResult<Model> result = assembler.assemble();
        Model model = result.unwrap();
        return PluginContext.builder()
                .fileManifest(manifest)
                .model(model)
                .originalModel(model)
                .events(result.getValidationEvents())
                .sources(sources)
                .settings(settings)
                .build();
    }

    /** Runs the plugin with the real page template and returns the page it wrote. */
    static String run(ObjectNode settings, List<Path> sources, List<Path> imports) {
        MockManifest manifest = new MockManifest();
        new ApiExplorerPlugin().execute(context(manifest, settings, sources, imports));
        assertTrue(manifest.hasFile(ApiExplorerPlugin.OUTPUT), "index.html was not written");
        return manifest.expectFileString(ApiExplorerPlugin.OUTPUT);
    }

    static String run(ObjectNode settings, Path... sources) {
        return run(settings, List.of(sources), List.of());
    }

    /** The JSON embedded in a page's data element. */
    static ObjectNode data(String page) {
        String open = "<script id=\"api-explorer-data\" type=\"application/json\">";
        int start = page.indexOf(open);
        assertTrue(start >= 0, "data element not found");
        start += open.length();
        int end = page.indexOf("</script>", start);
        return Node.parse(page.substring(start, end)).expectObjectNode();
    }

    /** Copies the orders fixture to {@code dir} and applies {@code edit} to its main.smithy. */
    static Path editedCopy(Path dir, UnaryOperator<String> edit) throws IOException {
        Files.createDirectories(dir);
        for (String file : List.of("main.smithy", "aws-protocols-stub.smithy")) {
            String text = Files.readString(ORDERS.resolve(file));
            Files.writeString(dir.resolve(file), file.equals("main.smithy") ? edit.apply(text) : text);
        }
        return dir;
    }

    static ObjectNode settings(String... keysAndValues) {
        ObjectNode.Builder node = ObjectNode.builder();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            node.withMember(keysAndValues[i], keysAndValues[i + 1]);
        }
        return node.build();
    }
}
