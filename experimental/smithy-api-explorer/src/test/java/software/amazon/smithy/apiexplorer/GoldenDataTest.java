/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.apiexplorer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;

/**
 * Compares the data the plugin embeds for a small fixture, compared with a path baseline, with
 * the expected output in {@code golden/expected-data.json}. The generator version is ignored, and
 * the fixture's location, which depends on where the tests run, is written as {@code <golden>}.
 *
 * <p>When the output changes on purpose, the test writes what it got to
 * {@code build/golden/expected-data.json}; review it and copy it over the expected file.
 */
class GoldenDataTest {

    private static final Path GOLDEN = TestModels.resource("golden");
    private static final String GOLDEN_TOKEN = "<golden>";

    @Test
    void embeddedDataMatchesTheExpectedOutput() throws IOException {
        // A relative baseline path, as smithy-build.json usually has one.
        String goldenPath = Paths.get("").toAbsolutePath().relativize(GOLDEN).toString().replace('\\', '/');
        String baseline = goldenPath + "/baseline";
        ObjectNode data = TestModels.data(TestModels.run(
                TestModels.settings("title", "Notes API", "baseline", baseline),
                GOLDEN.resolve("model")));
        assertEquals(baseline, data.expectObjectMember("changes").expectObjectMember("baseline")
                .expectStringMember("path").getValue());

        ObjectNode actual = normalized(data, goldenPath);
        ObjectNode expected = normalized(
                Node.parse(Files.readString(GOLDEN.resolve("expected-data.json"))).expectObjectNode(), goldenPath);
        if (!expected.equals(actual)) {
            Path written = Paths.get("build", "golden", "expected-data.json");
            Files.createDirectories(written.getParent());
            Files.writeString(written, Node.prettyPrintJson(actual) + "\n");
            assertEquals(Node.prettyPrintJson(expected), Node.prettyPrintJson(actual),
                    "The embedded data changed; the new output is in " + written.toAbsolutePath());
        }
    }

    /** The data without the generator version, and with the fixture's path written as a token. */
    private static ObjectNode normalized(ObjectNode data, String goldenPath) {
        ObjectNode generator = data.expectObjectMember("generator").withoutMember("version");
        Node withoutVersion = data.withMember("generator", generator);
        return Node.parse(Node.printJson(withoutVersion).replace(goldenPath + "/", GOLDEN_TOKEN + "/"))
                .expectObjectNode();
    }
}
