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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.build.ProjectionTransformer;
import software.amazon.smithy.build.SmithyBuildException;
import software.amazon.smithy.build.SmithyBuildPlugin;
import software.amazon.smithy.build.TransformContext;
import software.amazon.smithy.build.model.ProjectionConfig;
import software.amazon.smithy.build.model.TransformConfig;
import software.amazon.smithy.diff.ModelDiff;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.transform.ModelTransformer;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidationEvent;

/**
 * Renders a service model as a single, self-contained HTML page: an interactive API reference
 * with the service's operations, HTTP bindings, errors, resources, shapes, validation events,
 * and, when a baseline is configured, the changes made since the baseline.
 *
 * <p>The plugin writes {@code index.html} to its output directory, for example
 * {@code build/smithy/source/api-explorer/index.html}. The page embeds all of its data, scripts,
 * and styles, makes no network requests, and works when opened from the file system.
 *
 * <p>This plugin is experimental. Its output and settings may change in a future release.
 *
 * @see ApiExplorerSettings
 */
public final class ApiExplorerPlugin implements SmithyBuildPlugin {

    /** The plugin name used in smithy-build.json. */
    public static final String NAME = "api-explorer";

    static final String TEMPLATE = "api-explorer.html";
    static final String PLACEHOLDER = "\"__API_EXPLORER_DATA__\"";
    static final String DATA_ELEMENT =
            "<script id=\"api-explorer-data\" type=\"application/json\">" + PLACEHOLDER + "</script>";
    static final String OUTPUT = "index.html";

    private static final Logger LOGGER = Logger.getLogger(ApiExplorerPlugin.class.getName());
    private static final Pattern TITLE_ELEMENT = Pattern.compile("<title>[^<]*</title>");

    private final Supplier<String> template;

    /** Creates the plugin, which reads its page template from the classpath. */
    public ApiExplorerPlugin() {
        this(ApiExplorerPlugin::readTemplate);
    }

    /** Creates the plugin with a page template, for tests. */
    ApiExplorerPlugin(Supplier<String> template) {
        this.template = template;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void execute(PluginContext context) {
        // smithy-build runs the plugin once per projection, so this is logged once for each.
        LOGGER.warning("The " + NAME + " plugin is experimental. Its output and settings may change in a "
                + "future release.");
        ApiExplorerSettings settings = ApiExplorerSettings.fromNode(context.getSettings());
        Model model = context.getModel();
        ValidatedResult<Model> result = new ValidatedResult<>(model, context.getEvents());

        List<ServiceShape> services = ApiExplorerBuilder.services(model);
        ServiceShape service = ApiExplorerBuilder.selectService(services, settings.getService().orElse(null));
        if (settings.getService().isEmpty() && services.size() > 1) {
            List<String> others = services.stream()
                    .skip(1)
                    .map(s -> s.getId().toString())
                    .toList();
            LOGGER.warning("The model has more than one service, so the " + NAME + " plugin rendered "
                    + service.getId() + ". Set the \"service\" setting to render another one: " + others);
        }

        LOGGER.fine(() -> "The " + NAME + " plugin compares the model with " + settings.getBaseline());
        ApiExplorerBuilder.Baseline baseline = baseline(context, result, settings.getBaseline());
        ApiExplorerBuilder.Result rendered = new ApiExplorerBuilder(sourceRoot(context.getSources()))
                .build(result, service, baseline);

        ObjectNode.Builder data = rendered.data().toBuilder()
                .withMember("generator", ObjectNode.builder()
                        .withMember("name", "smithy-api-explorer")
                        .withMember("version", version())
                        .withMember("experimental", true)
                        .build());
        settings.getTitle().ifPresent(title -> data.withMember("title", title));

        context.getFileManifest().writeFile(OUTPUT, render(template.get(), data.build(), settings.getTitle()));
        LOGGER.info(() -> "Rendered " + (rendered.serviceId() != null ? rendered.serviceId() : "the model")
                + " with " + rendered.operationCount() + " operations"
                + (rendered.compared()
                        ? ", " + rendered.changeCount() + " changes (" + rendered.breakingChangeCount()
                                + " breaking, " + rendered.dangerChangeCount() + " dangerous) since "
                                + describe(baseline.origin())
                        : "")
                + " to " + context.getFileManifest().getBaseDir().resolve(OUTPUT));
    }

    /**
     * Embeds the data in the page template. The JSON is made safe to embed in a script element,
     * so text in the model cannot close the element or open an HTML comment: every {@code </}
     * becomes {@code <\/}, and the {@code <} of every {@code <!--} is written as a JSON unicode
     * escape. JSON.parse reads both back unchanged.
     */
    static String render(String template, ObjectNode data, Optional<String> title) {
        // Match the whole element, so a mention of the placeholder elsewhere (such as in a
        // comment) is left alone.
        int element = template.indexOf(DATA_ELEMENT);
        if (element < 0 || template.indexOf(DATA_ELEMENT, element + 1) >= 0) {
            throw new IllegalStateException("The " + TEMPLATE + " template must contain exactly one "
                    + DATA_ELEMENT + " element");
        }
        int at = template.indexOf(PLACEHOLDER, element);
        String json = Node.printJson(data)
                .replace("</", "<\\/")
                .replace("<!--", "\\u003C!--");
        String page = template.substring(0, at) + json + template.substring(at + PLACEHOLDER.length());
        if (title.isPresent()) {
            Matcher titleElement = TITLE_ELEMENT.matcher(page);
            if (titleElement.find()) {
                page = page.substring(0, titleElement.start())
                        + "<title>" + escapeHtml(title.get()) + "</title>"
                        + page.substring(titleElement.end());
            }
        }
        return page;
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * The directory source file paths are shown relative to: the working directory when every
     * source is under it, as when a build runs in its project directory, or else the deepest
     * directory that contains every source. Null when there is no such directory.
     */
    static Path sourceRoot(Set<Path> sources) {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        List<Path> dirs = new ArrayList<>();
        for (Path source : sources) {
            Path path = source.toAbsolutePath().normalize();
            dirs.add(Files.isDirectory(path) || path.getParent() == null ? path : path.getParent());
        }
        if (dirs.stream().allMatch(dir -> dir.startsWith(cwd))) {
            return cwd;
        }
        Path common = dirs.get(0);
        for (Path dir : dirs) {
            while (common != null && !dir.startsWith(common)) {
                common = common.getParent();
            }
        }
        return common;
    }

    // ── Baseline ──────────────────────────────────────────────────────────────

    /**
     * Reads the configured baseline and compares the model with it.
     *
     * @return Returns the comparison, a skipped comparison that says why it was not made, or null
     *     when there is nothing to compare with: the baseline is disabled, or it is the default git
     *     baseline and the model sources are not committed in a git repository.
     * @throws SmithyBuildException when a configured git revision does not name a commit.
     */
    static ApiExplorerBuilder.Baseline baseline(
            PluginContext context, ValidatedResult<Model> result, ApiExplorerSettings.Baseline setting) {
        return switch (setting.getKind()) {
            case NONE -> null;
            case PATH -> pathBaseline(context, result, setting.getValue().orElseThrow());
            case GIT -> gitBaseline(context, result, setting);
        };
    }

    /** Compares the model with a model file, model directory, project directory, or JAR. */
    private static ApiExplorerBuilder.Baseline pathBaseline(
            PluginContext context, ValidatedResult<Model> result, String path) {
        Path baselinePath = Paths.get(path).toAbsolutePath().normalize();
        if (!Files.exists(baselinePath)) {
            return skip("The baseline does not exist: " + path + " (resolved to " + baselinePath
                    + "; a relative path resolves against the working directory of the build)");
        }
        List<Path> roots = SourceDiff.roots(baselinePath);
        if (roots.isEmpty()) {
            return skip("The baseline project " + baselinePath + " declares no sources and has none of the "
                    + "conventional source directories");
        }
        ObjectNode origin = ObjectNode.builder()
                .withMember("kind", "path")
                .withMember("path", path)
                .build();
        return compare(context, result, roots, origin, "the baseline " + baselinePath);
    }

    /**
     * Compares the model with its sources as a git revision has them, like
     * {@code smithy diff --mode git}: the working tree, including uncommitted edits, against the
     * revision. The revision's files are written to a temporary directory, deleted afterwards.
     */
    private static ApiExplorerBuilder.Baseline gitBaseline(
            PluginContext context, ValidatedResult<Model> result, ApiExplorerSettings.Baseline setting) {
        String revision = setting.getValue().orElseThrow();
        try (GitBaseline checkout = GitBaseline.checkout(context.getSources(), revision)) {
            LOGGER.fine(() -> "Read the model sources of git " + revision + " (" + checkout.commit() + ") into "
                    + checkout.dir());
            ObjectNode origin = ObjectNode.builder()
                    .withMember("kind", "git")
                    .withMember("revision", revision)
                    .withMember("commit", checkout.commit())
                    .build();
            return compare(context, result, checkout.roots(), origin,
                    "git " + revision + " (" + checkout.commit() + ")");
        } catch (GitBaseline.Unavailable e) {
            if (!setting.isConfigured()) {
                LOGGER.info(() -> "The " + NAME + " plugin did not compare the model with git " + revision
                        + ". " + e.getMessage() + ". Set the \"" + ApiExplorerSettings.BASELINE_SETTING_KEY
                        + "\" setting to compare it with a path or another revision, or to false to not compare it.");
                return null;
            }
            if (e.invalidRevision()) {
                throw new SmithyBuildException("The \"" + ApiExplorerSettings.BASELINE_SETTING_KEY
                        + "\" setting of the " + NAME + " plugin is invalid. " + e.getMessage()
                        + ". Use a branch, tag, or commit, such as {\"" + ApiExplorerSettings.BASELINE_GIT_KEY
                        + "\": \"" + ApiExplorerSettings.DEFAULT_GIT_REVISION + "\"}.");
            }
            return skip(e.getMessage());
        }
    }

    /** What a baseline was read from, as the page says it: "git HEAD (abc123def456)" or the configured path. */
    static String describe(ObjectNode origin) {
        if (origin.getStringMember("kind").map(k -> k.getValue().equals("git")).orElse(false)) {
            return "git " + origin.expectStringMember("revision").getValue()
                    + " (" + origin.expectStringMember("commit").getValue() + ")";
        }
        return origin.getStringMember("path").map(p -> p.getValue()).orElse("the baseline");
    }

    /** A comparison that was requested but not made: logged as a warning, and the page says why. */
    private static ApiExplorerBuilder.Baseline skip(String reason) {
        LOGGER.warning("The " + NAME + " plugin did not compare the model with a baseline. " + reason);
        return ApiExplorerBuilder.Baseline.skipped(reason);
    }

    /**
     * Assembles the baseline from its model roots with the model's dependencies, applies the
     * projection's transforms to it, and diffs it with the model, both as models and as source
     * files. A baseline that cannot be loaded is skipped, and the page says why.
     *
     * @param roots The baseline's model files and directories.
     * @param origin What the baseline was read from, rendered as {@code changes.baseline}.
     * @param label Names the baseline in log messages.
     */
    private static ApiExplorerBuilder.Baseline compare(
            PluginContext context, ValidatedResult<Model> result, List<Path> roots, ObjectNode origin, String label) {
        // A model that does not load would diff as if everything were removed. Show its errors
        // instead of a misleading comparison.
        Optional<ValidationEvent> loadError = result.getValidationEvents(Severity.ERROR).stream()
                .filter(e -> e.getId().equals("Model"))
                .findFirst();
        if (result.getResult().isEmpty() || loadError.isPresent()) {
            return ApiExplorerBuilder.Baseline.skipped("The model has errors, so it was not compared with the baseline"
                    + loadError.map(e -> ": " + e.getMessage()).orElse("."));
        }
        Model current = result.getResult().get();
        ClassLoader classLoader = context.getPluginClassLoader().orElse(ApiExplorerPlugin.class.getClassLoader());

        ValidatedResult<Model> assembled = assembleBaseline(context, classLoader, roots, true);
        if (!loaded(assembled)) {
            // The baseline may define a different version of a dependency; load it on its own.
            assembled = assembleBaseline(context, classLoader, roots, false);
        }
        if (!loaded(assembled)) {
            String reason = assembled.getValidationEvents(Severity.ERROR).stream()
                    .sorted(Comparator.comparing((ValidationEvent e) -> !e.getId().equals("Model")))
                    .findFirst()
                    .map(e -> ": " + e.getMessage())
                    .orElse(".");
            LOGGER.warning("The " + NAME + " " + label + " could not be loaded" + reason);
            return ApiExplorerBuilder.Baseline.skipped("The baseline could not be loaded" + reason);
        }
        Model old;
        try {
            old = project(context, classLoader, assembled);
        } catch (RuntimeException e) {
            return skip("The projection's transforms could not be applied to the baseline: " + e.getMessage());
        }
        List<ValidationEvent> diff = ModelDiff.compare(classLoader, old, current);

        // Diff source lines when both sides are IDL source; otherwise (a JSON AST or JAR baseline)
        // diff the two models serialized back to IDL, limited to the model's own namespaces.
        Map<String, Path> currentSources = SourceDiff.sources(context.getSources());
        boolean bothIdl = !SourceDiff.sources(roots).isEmpty() && !currentSources.isEmpty();
        ArrayNode files = bothIdl
                ? SourceDiff.renderRoots(roots, currentSources)
                : SourceDiff.renderNormalized(old, current, diffNamespaces(old, current));
        LOGGER.info(() -> "The " + NAME + " plugin compared the model with " + label + ": " + diff.size()
                + " compatibility events");
        return new ApiExplorerBuilder.Baseline(old, diff, files, bothIdl ? "source" : "normalized", origin);
    }

    /**
     * Whether a model loaded: it has a result and no load errors, such as a syntax error or a
     * shape defined twice. Other validation errors are fine for a baseline.
     */
    private static boolean loaded(ValidatedResult<Model> result) {
        return result.getResult().isPresent()
                && result.getValidationEvents(Severity.ERROR).stream().noneMatch(e -> e.getId().equals("Model"));
    }

    /**
     * Assembles the baseline from its model roots. With {@code withDependencies}, the model's
     * dependency shapes are added, so the baseline resolves the same dependencies: the shapes of
     * the build's model (before the projection's transforms) that do not come from its sources,
     * such as those from JARs and top-level imports, unless the baseline defines them itself.
     * Without them, models on the plugin class path are discovered instead.
     */
    private static ValidatedResult<Model> assembleBaseline(
            PluginContext context, ClassLoader classLoader, List<Path> roots, boolean withDependencies) {
        ModelAssembler assembler = Model.assembler(classLoader);
        boolean knowsSources = context.getOriginalModel().isPresent() && !context.getSources().isEmpty();
        if (withDependencies && knowsSources) {
            Set<ShapeId> ownShapes = definedShapes(classLoader, roots);
            Model.Builder dependencies = Model.builder();
            context.getOriginalModel().get().shapes()
                    .filter(s -> !Prelude.isPreludeShape(s) && !context.isSourceShape(s))
                    .filter(s -> !ownShapes.contains(s.getId().withoutMember()))
                    .forEach(dependencies::addShape);
            assembler.addModel(dependencies.build());
        } else if (context.getPluginClassLoader().isPresent()) {
            assembler.discoverModels(classLoader);
        }
        roots.forEach(assembler::addImport);
        return assembler.assemble();
    }

    /** The shapes the baseline's own files define, read without validation. */
    private static Set<ShapeId> definedShapes(ClassLoader classLoader, List<Path> roots) {
        ModelAssembler assembler = Model.assembler(classLoader)
                .disableValidation()
                .putProperty(ModelAssembler.ALLOW_UNKNOWN_TRAITS, true);
        roots.forEach(assembler::addImport);
        Set<ShapeId> ids = new HashSet<>();
        assembler.assemble().getResult().ifPresent(model -> model.shapes()
                .filter(s -> !Prelude.isPreludeShape(s))
                .forEach(s -> ids.add(s.getId())));
        return ids;
    }

    /**
     * Applies the projection's transforms to the baseline, the way smithy-build applies them to the
     * model, so shapes the projection filters out are not reported as removed.
     *
     * @throws IllegalStateException when a transform is unknown or applies other projections.
     */
    private static Model project(PluginContext context, ClassLoader classLoader, ValidatedResult<Model> baseline) {
        Model model = baseline.unwrap();
        List<TransformConfig> transforms = context.getProjection()
                .map(ProjectionConfig::getTransforms)
                .orElse(List.of());
        if (transforms.isEmpty()) {
            return model;
        }
        Function<String, Optional<ProjectionTransformer>> factory =
                ProjectionTransformer.createServiceFactory(classLoader);
        ModelTransformer transformer = ModelTransformer.createWithServiceProviders(classLoader);
        Model original = model;
        for (TransformConfig config : transforms) {
            ProjectionTransformer projectionTransformer = factory.apply(config.getName())
                    .orElseThrow(() -> new IllegalStateException("Unknown transform: " + config.getName()));
            TransformContext transformContext = TransformContext.builder()
                    .model(model)
                    .originalModel(original)
                    .originalModelValidationEvents(baseline.getValidationEvents())
                    .transformer(transformer)
                    .projectionName(context.getProjectionName())
                    .sources(context.getSources())
                    .settings(config.getArgs())
                    .build();
            model = projectionTransformer.transform(transformContext);
            if (!projectionTransformer.getAdditionalProjections(transformContext).isEmpty()) {
                throw new IllegalStateException("the " + config.getName() + " transform applies other "
                        + "projections, which the baseline comparison does not support");
            }
        }
        return model;
    }

    /**
     * Namespaces to include in a normalized diff: those the model defines itself, plus those only
     * the baseline has (a removed or renamed namespace), but not the dependencies both load.
     */
    static Set<String> diffNamespaces(Model old, Model current) {
        Set<String> dependencies = new TreeSet<>();
        Set<String> namespaces = new TreeSet<>();
        current.shapes().filter(s -> !Prelude.isPreludeShape(s)).forEach(s -> {
            boolean fromJar = s.getSourceLocation().getFilename().startsWith("jar:");
            (fromJar ? dependencies : namespaces).add(s.getId().getNamespace());
        });
        dependencies.removeAll(namespaces);
        old.shapes()
                .filter(s -> !Prelude.isPreludeShape(s))
                .map(Shape::getId)
                .map(id -> id.getNamespace())
                .filter(ns -> !dependencies.contains(ns))
                .forEach(namespaces::add);
        return namespaces;
    }

    // ── Resources ─────────────────────────────────────────────────────────────

    private static String readTemplate() {
        try (InputStream in = ApiExplorerPlugin.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + TEMPLATE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The version of this plugin, recorded in a resource at build time. */
    static String version() {
        try (InputStream in = ApiExplorerPlugin.class.getResourceAsStream("api-explorer.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                String version = properties.getProperty("version", "");
                if (!version.isBlank() && !version.contains("${")) {
                    return version;
                }
            }
        } catch (IOException e) {
            // Fall through to the unknown version.
        }
        return "unknown";
    }
}
