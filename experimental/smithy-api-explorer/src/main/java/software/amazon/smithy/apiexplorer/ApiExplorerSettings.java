/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.apiexplorer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import software.amazon.smithy.model.SourceException;
import software.amazon.smithy.model.node.ExpectationNotMetException;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;
import software.amazon.smithy.utils.SmithyBuilder;
import software.amazon.smithy.utils.ToSmithyBuilder;

/**
 * Settings of the {@code api-explorer} plugin, read from its entry in smithy-build.json. Every
 * setting is optional.
 *
 * <p>This plugin is experimental: its settings may change in a future release.
 */
public final class ApiExplorerSettings implements ToSmithyBuilder<ApiExplorerSettings> {

    /** The service to render, as a shape ID or a bare service name. */
    public static final String SERVICE_SETTING_KEY = "service";

    /** The page title, overriding the one derived from the service. */
    public static final String TITLE_SETTING_KEY = "title";

    /** What to compare the model with: a path, a git revision, or {@code false}. */
    public static final String BASELINE_SETTING_KEY = "baseline";

    /** The member of an object {@code baseline} setting that names a git revision. */
    public static final String BASELINE_GIT_KEY = "git";

    /** The git revision compared with when {@code baseline} is not set or is {@code true}. */
    public static final String DEFAULT_GIT_REVISION = "HEAD";

    private static final List<String> SETTING_KEYS = List.of(
            SERVICE_SETTING_KEY, TITLE_SETTING_KEY, BASELINE_SETTING_KEY);

    private final String service;
    private final String title;
    private final Baseline baseline;

    private ApiExplorerSettings(Builder builder) {
        this.service = builder.service;
        this.title = builder.title;
        this.baseline = builder.baseline;
    }

    /** @return Returns a builder for the settings, with the default of every setting. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Builder toBuilder() {
        return builder().service(service).title(title).baseline(baseline);
    }

    /**
     * Reads the settings from the plugin's configuration node.
     *
     * @param settings The plugin's settings in smithy-build.json.
     * @return Returns the settings.
     * @throws ExpectationNotMetException when the node has a setting this plugin does not know,
     *     or a setting of the wrong type or with an invalid value.
     */
    public static ApiExplorerSettings fromNode(ObjectNode settings) {
        settings.expectNoAdditionalProperties(SETTING_KEYS);
        Builder builder = builder();
        settings.getStringMember(SERVICE_SETTING_KEY).ifPresent(node -> {
            // Validate the shape ID syntax early, but keep the text: a bare name is accepted too.
            String value = node.getValue();
            if (value.contains("#")) {
                try {
                    node.expectShapeId();
                } catch (SourceException e) {
                    throw new ExpectationNotMetException("The \"" + SERVICE_SETTING_KEY + "\" setting of the "
                            + ApiExplorerPlugin.NAME + " plugin is not a valid shape ID: " + value
                            + ". Use a shape ID such as example.weather#Weather, or a bare service name.", node);
                }
            }
            builder.service(value);
        });
        settings.getStringMember(TITLE_SETTING_KEY).map(StringNode::getValue).ifPresent(builder::title);
        settings.getMember(BASELINE_SETTING_KEY).map(Baseline::fromNode).ifPresent(builder::baseline);
        return builder.build();
    }

    /**
     * The service to render, as a shape ID or a bare service name.
     *
     * <p>When not set, the plugin renders the model's only service, or the first one by shape ID
     * when it has several.
     *
     * @return Returns the configured service, if set.
     */
    public Optional<String> getService() {
        return Optional.ofNullable(service).filter(s -> !s.isBlank());
    }

    /**
     * The page title, overriding the one derived from the service.
     *
     * @return Returns the configured title, if set.
     */
    public Optional<String> getTitle() {
        return Optional.ofNullable(title).filter(s -> !s.isBlank());
    }

    /**
     * What to compare the model with. Defaults to {@link Baseline#defaultBaseline()}, the git
     * {@code HEAD} of the repository that holds the model sources, when there is one.
     *
     * @return Returns the baseline.
     */
    public Baseline getBaseline() {
        return baseline;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ApiExplorerSettings other)) {
            return false;
        }
        return Objects.equals(service, other.service)
                && Objects.equals(title, other.title)
                && baseline.equals(other.baseline);
    }

    @Override
    public int hashCode() {
        return Objects.hash(service, title, baseline);
    }

    /** Builds {@link ApiExplorerSettings}. */
    public static final class Builder implements SmithyBuilder<ApiExplorerSettings> {
        private String service;
        private String title;
        private Baseline baseline = Baseline.defaultBaseline();

        private Builder() {}

        /**
         * @param service The shape ID or bare name of the service to render, or null for the default.
         * @return Returns the builder.
         */
        public Builder service(String service) {
            this.service = service;
            return this;
        }

        /**
         * @param title The page title, or null to derive it from the service.
         * @return Returns the builder.
         */
        public Builder title(String title) {
            this.title = title;
            return this;
        }

        /**
         * @param baseline What to compare the model with, or null for the default.
         * @return Returns the builder.
         */
        public Builder baseline(Baseline baseline) {
            this.baseline = baseline != null ? baseline : Baseline.defaultBaseline();
            return this;
        }

        @Override
        public ApiExplorerSettings build() {
            return new ApiExplorerSettings(this);
        }
    }

    /**
     * What the model is compared with: a git revision of the model sources, a path, or nothing.
     */
    public static final class Baseline {

        /** The kind of baseline. */
        public enum Kind {
            /** The model sources as a git revision has them. */
            GIT,
            /** A model file, model directory, project directory, or JAR. */
            PATH,
            /** No comparison. */
            NONE
        }

        private static final Baseline DEFAULT = new Baseline(Kind.GIT, DEFAULT_GIT_REVISION, false);
        private static final Baseline NONE = new Baseline(Kind.NONE, null, true);

        private final Kind kind;
        private final String value;
        private final boolean configured;

        private Baseline(Kind kind, String value, boolean configured) {
            this.kind = kind;
            this.value = value;
            this.configured = configured;
        }

        /**
         * The baseline used when the setting is absent: git {@code HEAD}, compared only when the
         * model sources are committed in a git repository.
         *
         * @return Returns the default baseline.
         */
        public static Baseline defaultBaseline() {
            return DEFAULT;
        }

        /**
         * @param revision A git revision, such as {@code HEAD}, a branch, a tag, or a commit.
         * @return Returns a configured git baseline.
         */
        public static Baseline git(String revision) {
            return new Baseline(Kind.GIT, requireText(revision, "git revision"), true);
        }

        /**
         * @param path A model file, model directory, project directory, or JAR.
         * @return Returns a configured path baseline.
         */
        public static Baseline path(String path) {
            return new Baseline(Kind.PATH, requireText(path, "baseline path"), true);
        }

        /** @return Returns a baseline that disables the comparison. */
        public static Baseline none() {
            return NONE;
        }

        /**
         * Reads the {@code baseline} setting: a path string, {@code {"git": "<revision>"}},
         * {@code true} for git {@code HEAD}, or {@code false} for no comparison.
         *
         * @param node The value of the setting.
         * @return Returns the baseline.
         * @throws ExpectationNotMetException when the value is none of those forms.
         */
        public static Baseline fromNode(Node node) {
            if (node.isStringNode()) {
                String path = node.expectStringNode().getValue();
                if (path.isBlank()) {
                    throw new ExpectationNotMetException(
                            "The \"" + BASELINE_SETTING_KEY + "\" setting of the " + ApiExplorerPlugin.NAME
                                    + " plugin is an empty path. Set it to a path, to {\"" + BASELINE_GIT_KEY
                                    + "\": \"<revision>\"}, or to false to not compare the model.", node);
                }
                return path(path);
            }
            if (node.isBooleanNode()) {
                return node.expectBooleanNode().getValue() ? git(DEFAULT_GIT_REVISION) : none();
            }
            if (node.isObjectNode()) {
                ObjectNode object = node.expectObjectNode();
                object.expectNoAdditionalProperties(List.of(BASELINE_GIT_KEY));
                Optional<StringNode> revision = object.getStringMember(BASELINE_GIT_KEY);
                if (revision.isEmpty() || revision.get().getValue().isBlank()) {
                    throw new ExpectationNotMetException(
                            "The \"" + BASELINE_SETTING_KEY + "\" setting of the " + ApiExplorerPlugin.NAME
                                    + " plugin must name a git revision, as in {\"" + BASELINE_GIT_KEY
                                    + "\": \"HEAD\"}.", node);
                }
                return git(revision.get().getValue());
            }
            throw new ExpectationNotMetException(
                    "The \"" + BASELINE_SETTING_KEY + "\" setting of the " + ApiExplorerPlugin.NAME
                            + " plugin must be a path string, an object such as {\"" + BASELINE_GIT_KEY
                            + "\": \"HEAD\"}, true, or false, but found " + node.getType(), node);
        }

        /** @return Returns the kind of baseline. */
        public Kind getKind() {
            return kind;
        }

        /**
         * The git revision of a {@link Kind#GIT} baseline, or the path of a {@link Kind#PATH} one.
         *
         * @return Returns the revision or path, or empty for {@link Kind#NONE}.
         */
        public Optional<String> getValue() {
            return Optional.ofNullable(value);
        }

        /**
         * Whether smithy-build.json sets the baseline. The default git baseline is not configured:
         * when git cannot provide it, the comparison is skipped quietly rather than reported.
         *
         * @return Returns true when the setting is present.
         */
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Baseline other)) {
                return false;
            }
            return kind == other.kind && Objects.equals(value, other.value) && configured == other.configured;
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, value, configured);
        }

        @Override
        public String toString() {
            return switch (kind) {
                case GIT -> "git " + value + (configured ? "" : " (default)");
                case PATH -> value;
                case NONE -> "none";
            };
        }

        private static String requireText(String text, String what) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("The " + what + " must not be empty");
            }
            return text;
        }
    }
}
