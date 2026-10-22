/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.apiexplorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.SmithyBuild;
import software.amazon.smithy.build.SmithyBuildResult;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.ObjectNode;

/**
 * Builds examples/weather with its own smithy-build.json, the way the Smithy CLI would, and keeps
 * the rendered page under build/examples/weather so it can be opened in a browser.
 */
class WeatherExampleTest {

    private static final Path EXAMPLE = Paths.get("examples", "weather").toAbsolutePath().normalize();

    private static Path outputDirectory() {
        String configured = System.getProperty("apiExplorer.examplesOutput");
        Path root = configured != null ? Paths.get(configured) : Paths.get("build", "examples");
        return root.resolve("weather").toAbsolutePath().normalize();
    }

    private static Path build() {
        SmithyBuildConfig loaded = SmithyBuildConfig.load(EXAMPLE.resolve("smithy-build.json"));
        ObjectNode settings = loaded.getPlugins().get("api-explorer");
        // The example's baseline is relative to the example directory, where `smithy build` runs.
        // Tests run in the project directory, so resolve it here.
        String baseline = settings.expectStringMember("baseline").getValue();
        ObjectNode resolved = settings.withMember("baseline", EXAMPLE.resolve(baseline).toString());
        SmithyBuildConfig config = loaded.toBuilder()
                .plugins(Map.of("api-explorer", resolved))
                .outputDirectory(outputDirectory().toString())
                .build();

        List<Path> sources = config.getSources().stream().map(Paths::get).toList();
        var assembler = Model.assembler().discoverModels(WeatherExampleTest.class.getClassLoader());
        sources.forEach(assembler::addImport);
        Model model = assembler.assemble().unwrap();

        SmithyBuildResult result = new SmithyBuild()
                .config(config)
                .registerSources(sources.toArray(new Path[0]))
                .model(model)
                .build();
        return result.getProjectionResult("source").orElseThrow()
                .getPluginManifest("api-explorer").orElseThrow()
                .getBaseDir().resolve("index.html");
    }

    @Test
    void rendersTheWeatherExample() throws IOException {
        Path page = build();
        assertEquals(outputDirectory().resolve("source/api-explorer/index.html"), page);
        String html = Files.readString(page);
        assertTrue(html.contains("<title>Weather API</title>"), "the title setting is applied");

        ObjectNode data = TestModels.data(html);
        assertEquals("Weather API", data.expectStringMember("title").getValue());
        assertEquals("example.weather#Weather",
                data.expectObjectMember("service").expectStringMember("id").getValue());
        List<String> operations = data.expectArrayMember("operations").getElementsAs(ObjectNode.class).stream()
                .map(o -> o.expectStringMember("name").getValue())
                .sorted()
                .toList();
        assertEquals(List.of("GetCity", "GetCityImage", "GetCurrentTime", "GetForecast", "ListCities"), operations);
        assertTrue(data.expectObjectMember("shapes").containsMember("example.weather#CityCoordinates"));
        assertFalse(data.containsMember("changesSkipped"));
        // The service documentation has HTML, which the page sanitizes and renders on its first view.
        assertTrue(data.expectObjectMember("service").expectStringMember("documentation").getValue()
                .contains("<code>GetCity</code>"));
        assertTrue(html.contains("<code>GetCity<\\/code>"), "the HTML is escaped inside the data element");
    }

    @Test
    void comparesTheWeatherExampleWithItsBaseline() {
        ObjectNode changes = TestModels.data(readPage()).expectObjectMember("changes");
        List<ObjectNode> compatibility = changes.expectArrayMember("compatibility")
                .getElementsAs(ObjectNode.class);
        String eventIds = compatibility.stream()
                .map(e -> e.expectStringMember("id").getValue())
                .collect(Collectors.joining(","));
        // The reordered CityCoordinates members are dangerous, but not breaking.
        ObjectNode reordered = compatibility.stream()
                .filter(e -> e.expectStringMember("id").getValue().startsWith("ChangedMemberOrder"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(eventIds));
        assertTrue(reordered.expectBooleanMember("danger").getValue());
        assertFalse(reordered.expectBooleanMember("breaking").getValue());

        // Renaming the pageSize query parameter breaks existing clients.
        assertTrue(compatibility.stream()
                .filter(e -> e.expectStringMember("id").getValue().equals("TraitBreakingChange.Update.smithy.api#httpQuery"))
                .anyMatch(e -> e.expectBooleanMember("breaking").getValue()), eventIds);

        ObjectNode entries = changes.expectObjectMember("entries");
        assertEquals("added", entries.expectStringMember("example.weather#GetCityImage").getValue());
        assertEquals("changed", entries.expectStringMember("example.weather#CityCoordinates").getValue());
        assertEquals("source", changes.expectStringMember("diffMode").getValue());
        assertFalse(changes.expectArrayMember("files").isEmpty());
    }

    private static String readPage() {
        try {
            return Files.readString(build());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
