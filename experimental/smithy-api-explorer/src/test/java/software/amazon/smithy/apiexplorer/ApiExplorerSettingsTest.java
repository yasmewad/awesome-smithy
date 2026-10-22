/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.apiexplorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.apiexplorer.ApiExplorerSettings.Baseline;
import software.amazon.smithy.model.node.ExpectationNotMetException;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;

class ApiExplorerSettingsTest {

    private static ApiExplorerSettings parse(String json) {
        return ApiExplorerSettings.fromNode(Node.parse(json).expectObjectNode());
    }

    private static Baseline baseline(String json) {
        return parse("{\"baseline\": " + json + "}").getBaseline();
    }

    @Test
    void everySettingDefaults() {
        ApiExplorerSettings settings = parse("{}");
        assertEquals(Optional.empty(), settings.getService());
        assertEquals(Optional.empty(), settings.getTitle());
        assertEquals(Baseline.defaultBaseline(), settings.getBaseline());
        assertEquals(ApiExplorerSettings.builder().build(), settings);
    }

    @Test
    void absentBaselineIsUnconfiguredGitHead() {
        Baseline baseline = parse("{}").getBaseline();
        assertEquals(Baseline.Kind.GIT, baseline.getKind());
        assertEquals(Optional.of("HEAD"), baseline.getValue());
        assertFalse(baseline.isConfigured());
    }

    @Test
    void stringBaselineIsAPath() {
        Baseline baseline = baseline("\"baseline/model\"");
        assertEquals(Baseline.Kind.PATH, baseline.getKind());
        assertEquals(Optional.of("baseline/model"), baseline.getValue());
        assertTrue(baseline.isConfigured());
    }

    @Test
    void objectBaselineNamesAGitRevision() {
        Baseline baseline = baseline("{\"git\": \"origin/main\"}");
        assertEquals(Baseline.Kind.GIT, baseline.getKind());
        assertEquals(Optional.of("origin/main"), baseline.getValue());
        assertTrue(baseline.isConfigured());
        assertEquals(Baseline.git("origin/main"), baseline);
    }

    @Test
    void trueBaselineIsConfiguredGitHead() {
        Baseline baseline = baseline("true");
        assertEquals(Baseline.git("HEAD"), baseline);
        assertTrue(baseline.isConfigured());
    }

    @Test
    void falseBaselineDisablesTheComparison() {
        Baseline baseline = baseline("false");
        assertEquals(Baseline.Kind.NONE, baseline.getKind());
        assertEquals(Optional.empty(), baseline.getValue());
        assertTrue(baseline.isConfigured());
    }

    @Test
    void baselineOfAnotherTypeIsRejected() {
        var e = assertThrows(ExpectationNotMetException.class, () -> baseline("1"));
        assertTrue(e.getMessage().contains("\"baseline\"") && e.getMessage().contains("{\"git\": \"HEAD\"}"),
                e.getMessage());
        assertThrows(ExpectationNotMetException.class, () -> baseline("[\"a\"]"));
        assertThrows(ExpectationNotMetException.class, () -> baseline("null"));
    }

    @Test
    void emptyBaselinePathIsRejected() {
        var e = assertThrows(ExpectationNotMetException.class, () -> baseline("\"  \""));
        assertTrue(e.getMessage().contains("empty path") && e.getMessage().contains("false"), e.getMessage());
    }

    @Test
    void gitBaselineMustNameARevision() {
        var empty = assertThrows(ExpectationNotMetException.class, () -> baseline("{}"));
        assertTrue(empty.getMessage().contains("must name a git revision"), empty.getMessage());
        assertThrows(ExpectationNotMetException.class, () -> baseline("{\"git\": \"\"}"));
        assertThrows(ExpectationNotMetException.class, () -> baseline("{\"git\": 1}"));
    }

    @Test
    void gitBaselineRejectsUnknownMembers() {
        assertThrows(ExpectationNotMetException.class, () -> baseline("{\"git\": \"HEAD\", \"path\": \"x\"}"));
    }

    @Test
    void unknownSettingsAreRejected() {
        var e = assertThrows(ExpectationNotMetException.class, () -> parse("{\"basline\": \"x\"}"));
        assertTrue(e.getMessage().contains("basline"), e.getMessage());
    }

    @Test
    void serviceShapeIdIsValidated() {
        assertThrows(ExpectationNotMetException.class, () -> parse("{\"service\": \"example#\"}"));
        assertEquals(Optional.of("Weather"), parse("{\"service\": \"Weather\"}").getService());
        assertEquals(Optional.of("a.b#Weather"), parse("{\"service\": \"a.b#Weather\"}").getService());
    }

    @Test
    void settingsOfTheWrongTypeAreRejected() {
        assertThrows(ExpectationNotMetException.class, () -> parse("{\"title\": 1}"));
        assertThrows(ExpectationNotMetException.class, () -> parse("{\"service\": true}"));
    }

    @Test
    void builderRoundTrips() {
        ApiExplorerSettings settings = ApiExplorerSettings.builder()
                .service("a.b#Weather")
                .title("Weather API")
                .baseline(Baseline.git("main"))
                .build();
        assertEquals(settings, settings.toBuilder().build());
        assertEquals(settings, ApiExplorerSettings.fromNode(ObjectNode.builder()
                .withMember(ApiExplorerSettings.SERVICE_SETTING_KEY, "a.b#Weather")
                .withMember(ApiExplorerSettings.TITLE_SETTING_KEY, "Weather API")
                .withMember(ApiExplorerSettings.BASELINE_SETTING_KEY, ObjectNode.builder()
                        .withMember(ApiExplorerSettings.BASELINE_GIT_KEY, "main")
                        .build())
                .build()));
        assertEquals(Baseline.defaultBaseline(), settings.toBuilder().baseline(null).build().getBaseline());
    }
}
