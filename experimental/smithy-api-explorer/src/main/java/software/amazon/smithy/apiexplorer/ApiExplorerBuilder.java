/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.apiexplorer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.knowledge.BottomUpIndex;
import software.amazon.smithy.model.knowledge.EventStreamIndex;
import software.amazon.smithy.model.knowledge.EventStreamInfo;
import software.amazon.smithy.model.knowledge.HttpBinding;
import software.amazon.smithy.model.knowledge.HttpBindingIndex;
import software.amazon.smithy.model.knowledge.IdentifierBindingIndex;
import software.amazon.smithy.model.knowledge.NullableIndex;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.knowledge.PaginatedIndex;
import software.amazon.smithy.model.knowledge.ServiceIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.neighbor.Walker;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.AbstractShapeBuilder;
import software.amazon.smithy.model.shapes.EnumShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ResourceShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.ClientOptionalTrait;
import software.amazon.smithy.model.traits.DefaultTrait;
import software.amazon.smithy.model.traits.DeprecatedTrait;
import software.amazon.smithy.model.traits.DocumentationTrait;
import software.amazon.smithy.model.traits.EndpointTrait;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.EnumValueTrait;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.model.traits.ExamplesTrait;
import software.amazon.smithy.model.traits.HttpErrorTrait;
import software.amazon.smithy.model.traits.HttpHeaderTrait;
import software.amazon.smithy.model.traits.HttpLabelTrait;
import software.amazon.smithy.model.traits.HttpPayloadTrait;
import software.amazon.smithy.model.traits.HttpPrefixHeadersTrait;
import software.amazon.smithy.model.traits.HttpQueryParamsTrait;
import software.amazon.smithy.model.traits.HttpQueryTrait;
import software.amazon.smithy.model.traits.HttpResponseCodeTrait;
import software.amazon.smithy.model.traits.HostLabelTrait;
import software.amazon.smithy.model.traits.HttpTrait;
import software.amazon.smithy.model.traits.IdempotencyTokenTrait;
import software.amazon.smithy.model.traits.IdempotentTrait;
import software.amazon.smithy.model.traits.InputTrait;
import software.amazon.smithy.model.traits.JsonNameTrait;
import software.amazon.smithy.model.traits.LengthTrait;
import software.amazon.smithy.model.traits.MixinTrait;
import software.amazon.smithy.model.traits.OptionalAuthTrait;
import software.amazon.smithy.model.traits.OutputTrait;
import software.amazon.smithy.model.traits.PaginatedTrait;
import software.amazon.smithy.model.traits.PatternTrait;
import software.amazon.smithy.model.traits.RangeTrait;
import software.amazon.smithy.model.traits.ReadonlyTrait;
import software.amazon.smithy.model.traits.RequiredTrait;
import software.amazon.smithy.model.traits.SensitiveTrait;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.model.traits.TimestampFormatTrait;
import software.amazon.smithy.model.traits.TitleTrait;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.traits.UniqueItemsTrait;
import software.amazon.smithy.model.traits.XmlNameTrait;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidationEvent;

/**
 * Renders an assembled Smithy model as the data the API explorer page shows: the service, its
 * operations and their HTTP bindings, every shape they reach with member bindings and
 * documentation, resources, validation events, and the changes against an optional baseline.
 *
 * <p>Traits are read by shape ID as nodes rather than through typed trait classes. The model may
 * be assembled with a different class loader than this plugin's, so reading nodes avoids
 * depending on which loader defined a trait class, and covers protocol traits this plugin does
 * not depend on. The prelude trait IDs come from smithy-model's trait classes.
 */
final class ApiExplorerBuilder {

    /** Protocol traits the explorer knows how to render a wire request for, in precedence order. */
    static final List<String> PROTOCOL_TRAITS = List.of(
            "aws.protocols#restJson1",
            "aws.protocols#restXml",
            "aws.protocols#awsJson1_0",
            "aws.protocols#awsJson1_1",
            "aws.protocols#awsQuery",
            "aws.protocols#ec2Query",
            "smithy.protocols#rpcv2Cbor");

    private static final int MAX_VALIDATION_EVENTS = 200;

    private static final ShapeId AWS_SERVICE = ShapeId.from("aws.api#service");
    private static final ShapeId SIGV4 = ShapeId.from("aws.auth#sigv4");
    /** Protocols whose body timestamps default to epoch-seconds; the others default to date-time. */
    private static final Set<String> EPOCH_SECONDS_PROTOCOLS = Set.of(
            "aws.protocols#restJson1",
            "aws.protocols#awsJson1_0",
            "aws.protocols#awsJson1_1",
            "smithy.protocols#rpcv2Cbor");
    private static final List<ShapeId> CONSTRAINTS = List.of(
            LengthTrait.ID, RangeTrait.ID, PatternTrait.ID, UniqueItemsTrait.ID);

    /**
     * The model before an edit, to mark what changed, plus the compatibility events from diffing it
     * against the rendered model, the source diff of its files ({@link SourceDiff#render}), and
     * what it was read from ({@code origin}, rendered as {@code changes.baseline}).
     */
    record Baseline(Model model, List<ValidationEvent> diffEvents, ArrayNode sourceFiles, String diffMode,
                    ObjectNode origin, String skipped) {
        Baseline(Model model, List<ValidationEvent> diffEvents, ArrayNode sourceFiles, String diffMode,
                 ObjectNode origin) {
            this(model, diffEvents, sourceFiles, diffMode, origin, null);
        }

        /** A baseline that was requested but not compared, and why. */
        static Baseline skipped(String reason) {
            return new Baseline(null, List.of(), ArrayNode.arrayNode(), "none", null, reason);
        }
    }

    /** The rendered explorer data plus a summary of it, for the plugin to log. */
    record Result(ObjectNode data, String serviceId, List<String> otherServices, int operationCount,
                  boolean valid, int eventCount, boolean compared, int changeCount, int breakingChangeCount,
                  int dangerChangeCount) {}

    private final Path sourceRoot;

    /**
     * The line that defines each element, by source file (as Smithy reports it), so source diff
     * lines can name the element they define.
     */
    private final Map<String, TreeMap<Integer, String>> definitions = new HashMap<>();
    /** HTTP bindings of every operation input, output, and error member, from HttpBindingIndex. */
    private final Map<ShapeId, HttpBinding> httpBindings = new HashMap<>();
    private NullableIndex nullable;
    /** The model being rendered, for mixin lookups when rendering traits. */
    private Model model;
    /** The selected service, which gives shapes their contextual (renamed) names; may be null. */
    private ServiceShape service;
    /** The selected service's protocol trait ID, or empty when it has none the explorer knows. */
    private String protocol = "";

    /**
     * @param sourceRoot Directory model source paths are shown relative to: the model directory or
     *     package root, or the parent directory when the model path is a single file.
     */
    ApiExplorerBuilder(Path sourceRoot) {
        this.sourceRoot = sourceRoot;
    }

    /**
     * Renders the model.
     *
     * @param result The model and its validation events.
     * @param service The service to render, or null to render the shapes the model defines.
     * @param baseline The baseline to compare with, or null to not compare.
     * @return Returns the rendered data and a summary of it.
     */
    Result build(ValidatedResult<Model> result, ServiceShape service, Baseline baseline) {
        Model model = result.getResult().orElse(Model.builder().build());
        List<ServiceShape> services = services(model);
        this.model = model;
        this.service = service;
        this.protocol = service != null ? protocolOf(service) : "";
        nullable = NullableIndex.of(model);
        List<OperationShape> contained = service != null
                ? new ArrayList<>(TopDownIndex.of(model).getContainedOperations(service))
                : List.of();
        indexHttpBindings(model, service, contained);

        Set<Shape> closure = closure(model, service);
        Map<String, ObjectNode> shapes = new TreeMap<>();
        for (Shape shape : closure) {
            if (shape.isServiceShape() || shape.isOperationShape() || shape.isResourceShape()) {
                continue;
            }
            shapes.put(shape.getId().toString(), renderShape(model, shape));
        }

        List<ObjectNode> resources = new ArrayList<>();
        if (service != null) {
            List<ResourceShape> containedResources = TopDownIndex.of(model).getContainedResources(service).stream()
                    .sorted(Comparator.comparing(r -> r.getId().toString()))
                    .toList();
            Map<ShapeId, ShapeId> parents = new HashMap<>();
            for (ResourceShape resource : containedResources) {
                resource.getResources().forEach(child -> parents.putIfAbsent(child, resource.getId()));
            }
            for (ResourceShape resource : containedResources) {
                addDefinition(resource, resource.getId().toString());
                resources.add(renderResource(resource, parents.get(resource.getId())));
            }
        }

        List<ObjectNode> operations = new ArrayList<>();
        if (service != null) {
            addDefinition(service, service.getId().toString());
            for (OperationShape operation : contained) {
                operations.add(renderOperation(model, service, operation));
                addDefinition(operation, operation.getId().toString());
            }
            operations.sort(Comparator.comparing(o -> o.expectStringMember("name").getValue()));
        }

        List<ValidationEvent> events = result.getValidationEvents();
        ObjectNode.Builder data = ObjectNode.builder()
                .withMember("operations", ArrayNode.fromNodes(new ArrayList<>(operations)))
                .withMember("shapes", objectOf(shapes))
                .withMember("validation", renderValidation(result, events));
        if (service != null) {
            data.withMember("service", renderService(model, service));
            data.withMember("resources", ArrayNode.fromNodes(new ArrayList<>(resources)));
        }
        int changeCount = 0;
        int breakingChangeCount = 0;
        int dangerChangeCount = 0;
        boolean compared = baseline != null && baseline.skipped() == null;
        if (baseline != null && !compared) {
            data.withMember("changesSkipped", baseline.skipped());
        } else if (compared) {
            ObjectNode changes = renderChanges(model, closure, service, baseline, annotateDiff(baseline));
            data.withMember("changes", changes);
            changeCount = changes.expectObjectMember("entries").size() + changes.expectArrayMember("removed").size();
            breakingChangeCount = (int) baseline.diffEvents().stream().filter(ApiExplorerBuilder::isBreaking).count();
            dangerChangeCount = (int) baseline.diffEvents().stream().filter(ApiExplorerBuilder::isDanger).count();
        }

        String serviceId = service != null ? service.getId().toString() : null;
        List<String> otherServices = services.stream()
                .map(s -> s.getId().toString())
                .filter(id -> !id.equals(serviceId))
                .toList();
        return new Result(data.build(), serviceId, otherServices, operations.size(),
                !result.isBroken(), events.size(), compared, changeCount, breakingChangeCount,
                dangerChangeCount);
    }

    /** The model's own (non-prelude) services, sorted by shape ID. */
    static List<ServiceShape> services(Model model) {
        return model.getServiceShapes().stream()
                .filter(s -> !Prelude.isPreludeShape(s))
                .sorted(Comparator.comparing(s -> s.getId().toString()))
                .toList();
    }

    /**
     * The non-prelude, non-member shapes to render: everything the service reaches, or, for a model
     * without a service, the shapes it defines itself (not those loaded from dependency jars).
     */
    private static Set<Shape> closure(Model model, ServiceShape service) {
        Stream<Shape> shapes = service != null
                ? new Walker(model).walkShapes(service).stream()
                : model.shapes().filter(s -> !s.getSourceLocation().getFilename().startsWith("jar:"));
        return shapes.filter(s -> !s.isMemberShape() && !Prelude.isPreludeShape(s)).collect(Collectors.toSet());
    }

    // ── Changes against a baseline ────────────────────────────────────────────

    /**
     * Marks every rendered element as added or changed relative to the baseline, lists elements the
     * baseline had that are gone, and carries the compatibility events. A shape counts as changed
     * when anything about it differs, including its traits or any member.
     */
    private static ObjectNode renderChanges(Model model, Set<Shape> closure, ServiceShape service, Baseline baseline,
                                            ArrayNode files) {
        Model old = baseline.model();
        Map<String, String> entries = new TreeMap<>();
        Map<String, ObjectNode> traitChanges = new TreeMap<>();
        Map<String, String> targetChanged = new TreeMap<>();
        Map<String, ObjectNode> resourceChanges = new TreeMap<>();
        Set<String> docsChanged = new java.util.TreeSet<>();
        Set<ShapeId> current = new HashSet<>();
        for (Shape shape : closure) {
            current.add(shape.getId());
            List<Shape> elements = new ArrayList<>(List.of(shape));
            elements.addAll(shape.members());
            for (Shape element : elements) {
                Shape before = old.getShape(element.getId()).orElse(null);
                mark(entries, element, before);
                if (before == null || before.equals(element)) {
                    continue;
                }
                String id = element.getId().toString();
                diffTraits(before, element).ifPresent(node -> traitChanges.put(id, node));
                if (element.isMemberShape() && before.isMemberShape()
                        && !before.asMemberShape().orElseThrow().getTarget()
                                .equals(element.asMemberShape().orElseThrow().getTarget())) {
                    targetChanged.put(id, before.asMemberShape().orElseThrow().getTarget().toString());
                }
                if (withoutDocs(before).equals(withoutDocs(element))) {
                    docsChanged.add(id);
                }
            }
            if (shape.isResourceShape()) {
                old.getShape(shape.getId()).flatMap(Shape::asResourceShape)
                        .flatMap(was -> resourceChanges(was, shape.asResourceShape().orElseThrow()))
                        .ifPresent(node -> resourceChanges.put(shape.getId().toString(), node));
            }
        }

        ServiceShape oldService = service == null ? null
                : old.getShape(service.getId()).flatMap(Shape::asServiceShape).orElse(null);
        ArrayNode.Builder removed = ArrayNode.builder();
        closure(old, oldService).stream()
                .sorted(Comparator.comparing(s -> s.getId().toString()))
                .forEach(s -> {
                    if (!current.contains(s.getId())) {
                        removed.withValue(removedNode(s, null, oldService));
                        return;
                    }
                    Shape now = model.expectShape(s.getId());
                    for (MemberShape member : s.members()) {
                        if (now.getMember(member.getMemberName()).isEmpty()) {
                            removed.withValue(removedNode(member, s.getId().toString(), oldService));
                        }
                    }
                });

        ArrayNode.Builder compatibility = ArrayNode.builder();
        mostSevere(baseline.diffEvents().stream())
                .forEach(e -> compatibility.withValue(eventNode(e)
                        .withMember("breaking", isBreaking(e))
                        .withMember("danger", isDanger(e))
                        .build()));

        ObjectNode.Builder entryNodes = ObjectNode.builder();
        entries.forEach(entryNodes::withMember);
        ObjectNode.Builder targets = ObjectNode.builder();
        targetChanged.forEach(targets::withMember);
        return ObjectNode.builder()
                .withMember("entries", entryNodes.build())
                .withMember("removed", removed.build())
                .withMember("compatibility", compatibility.build())
                .withMember("traits", objectOf(traitChanges))
                .withMember("targetChanged", targets.build())
                .withMember("resources", objectOf(resourceChanges))
                .withMember("docsChanged", docsChanged.stream().map(Node::from).collect(ArrayNode.collect()))
                .withMember("files", files)
                .withMember("diffMode", baseline.diffMode())
                .withOptionalMember("baseline", Optional.ofNullable(baseline.origin()))
                .build();
    }

    /**
     * The identifiers and properties a resource gained or lost, and those whose target changed,
     * since the baseline; its own row in the reference marks each one. Empty when none differ.
     */
    private static Optional<ObjectNode> resourceChanges(ResourceShape before, ResourceShape after) {
        ObjectNode.Builder node = ObjectNode.builder();
        boolean any = false;
        for (String kind : List.of("identifiers", "properties")) {
            Map<String, ShapeId> was = kind.equals("identifiers") ? before.getIdentifiers() : before.getProperties();
            Map<String, ShapeId> now = kind.equals("identifiers") ? after.getIdentifiers() : after.getProperties();
            ObjectNode.Builder names = ObjectNode.builder();
            boolean changed = false;
            for (Map.Entry<String, ShapeId> e : new TreeMap<>(now).entrySet()) {
                ShapeId old = was.get(e.getKey());
                if (old == null || !old.equals(e.getValue())) {
                    names.withMember(e.getKey(), old == null ? "added" : "changed");
                    changed = true;
                }
            }
            for (String name : new TreeMap<>(was).keySet()) {
                if (!now.containsKey(name)) {
                    names.withMember(name, "removed");
                    changed = true;
                }
            }
            if (changed) {
                node.withMember(kind, names.build());
                any = true;
            }
        }
        return any ? Optional.of(node.build()) : Optional.empty();
    }

    /**
     * The traits added, removed, and changed between two versions of an element, ignoring synthetic
     * traits and documentation (which the reference shows as prose); empty when they match.
     */
    private static Optional<ObjectNode> diffTraits(Shape before, Shape after) {
        Map<ShapeId, Trait> was = comparableTraits(before);
        Map<ShapeId, Trait> now = comparableTraits(after);
        ArrayNode.Builder added = ArrayNode.builder();
        ArrayNode.Builder removed = ArrayNode.builder();
        ArrayNode.Builder changed = ArrayNode.builder();
        boolean any = false;
        for (Map.Entry<ShapeId, Trait> e : now.entrySet()) {
            Trait old = was.get(e.getKey());
            if (old == null) {
                added.withValue(e.getKey().toString());
                any = true;
            } else if (!old.toNode().equals(e.getValue().toNode())) {
                changed.withValue(ObjectNode.builder()
                        .withMember("id", e.getKey().toString())
                        .withMember("before", idlText(old))
                        .build());
                any = true;
            }
        }
        for (Map.Entry<ShapeId, Trait> e : was.entrySet()) {
            if (!now.containsKey(e.getKey())) {
                removed.withValue(ObjectNode.builder()
                        .withMember("id", e.getKey().toString())
                        .withMember("text", idlText(e.getValue()))
                        .build());
                any = true;
            }
        }
        if (!any) {
            return Optional.empty();
        }
        return Optional.of(ObjectNode.builder()
                .withMember("added", added.build())
                .withMember("removed", removed.build())
                .withMember("changed", changed.build())
                .build());
    }

    private static Map<ShapeId, Trait> comparableTraits(Shape shape) {
        Map<ShapeId, Trait> traits = new TreeMap<>();
        shape.getAllTraits().forEach((id, t) -> {
            if (!t.isSynthetic() && !id.equals(DocumentationTrait.ID)) {
                traits.put(id, t);
            }
        });
        return traits;
    }

    /** The shape with its documentation removed, to tell a docs-only edit from any other. */
    private static Shape withoutDocs(Shape shape) {
        AbstractShapeBuilder<?, ?> builder = Shape.shapeToBuilder(shape);
        return builder.removeTrait(DocumentationTrait.ID).build();
    }

    private static void mark(Map<String, String> entries, Shape shape, Shape before) {
        if (before == null) {
            entries.put(shape.getId().toString(), "added");
        } else if (!before.equals(shape)) {
            entries.put(shape.getId().toString(), "changed");
        }
    }

    private static ObjectNode removedNode(Shape shape, String parent, ServiceShape service) {
        String name = shape.isMemberShape()
                ? shape.asMemberShape().orElseThrow().getMemberName()
                : contextualName(service, shape);
        ObjectNode.Builder node = ObjectNode.builder()
                .withMember("id", shape.getId().toString())
                .withMember("name", name)
                .withMember("type", shape.getType().toString());
        if (!shape.isMemberShape() && !name.equals(shape.getId().getName())) {
            node.withMember("renamedFrom", shape.getId().getName());
        }
        if (parent != null) {
            node.withMember("parent", parent);
        }
        return node.build();
    }

    /** The name a service gives a shape, applying its {@code rename} map. */
    private static String contextualName(ServiceShape service, Shape shape) {
        return service != null ? service.getContextualName(shape) : shape.getId().getName();
    }

    /** Events sorted most severe first, capped at {@link #MAX_VALIDATION_EVENTS}. */
    private static Stream<ValidationEvent> mostSevere(Stream<ValidationEvent> events) {
        return events.sorted(Comparator.comparing(ValidationEvent::getSeverity).reversed()).limit(MAX_VALIDATION_EVENTS);
    }

    private static ObjectNode.Builder eventNode(ValidationEvent e) {
        ObjectNode.Builder node = ObjectNode.builder()
                .withMember("severity", e.getSeverity().toString())
                .withMember("id", e.getId())
                .withMember("message", e.getMessage());
        e.getShapeId().ifPresent(id -> node.withMember("shapeId", id.toString()));
        return node;
    }

    /** Diff events of ERROR severity break existing clients. */
    static boolean isBreaking(ValidationEvent event) {
        return event.getSeverity() == Severity.ERROR;
    }

    /**
     * Diff events of DANGER severity can break some clients, and are acceptable only when the author
     * knows and accepts the risk. For example, ChangedMemberOrder changes the memory layout, and so
     * the ABI, of structs generated for languages such as C and C++.
     */
    static boolean isDanger(ValidationEvent event) {
        return event.getSeverity() == Severity.DANGER;
    }

    /**
     * The service named by {@code requested}, as a shape ID or a bare name, or the first service
     * when none is requested.
     *
     * @throws IllegalArgumentException when no service matches {@code requested}, or when it is a
     *     bare name that more than one service has.
     */
    static ServiceShape selectService(List<ServiceShape> services, String requested) {
        if (requested == null || requested.isBlank()) {
            return services.isEmpty() ? null : services.get(0);
        }
        List<String> all = services.stream().map(s -> s.getId().toString()).toList();
        Optional<ServiceShape> exact = services.stream()
                .filter(s -> s.getId().toString().equals(requested))
                .findFirst();
        if (exact.isPresent()) {
            return exact.get();
        }
        List<ServiceShape> named = services.stream()
                .filter(s -> s.getId().getName().equals(requested))
                .toList();
        if (named.size() > 1) {
            throw new IllegalArgumentException("More than one service is named " + requested
                    + ", so set the service setting to the shape ID of one of them: "
                    + named.stream().map(s -> s.getId().toString()).toList());
        }
        if (named.isEmpty()) {
            throw new IllegalArgumentException("Service not found in model: " + requested + ". Services: " + all);
        }
        return named.get(0);
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private ObjectNode renderService(Model model, ServiceShape service) {
        ObjectNode.Builder node = ObjectNode.builder()
                .withMember("id", service.getId().toString())
                .withMember("name", service.getId().getName())
                .withMember("version", service.getVersion());
        traitString(service, TitleTrait.ID).ifPresent(v -> node.withMember("title", v));
        traitString(service, DocumentationTrait.ID).ifPresent(v -> node.withMember("documentation", v));
        node.withMember("protocol", protocolOf(service));
        // Errors every operation can return, and the auth schemes the service supports.
        node.withMember("errors", OperationIndex.of(model).getErrors(service).stream()
                .map(e -> Node.from(e.getId().toString()))
                .collect(ArrayNode.collect()));
        ObjectNode.Builder schemes = ObjectNode.builder();
        ServiceIndex.of(model).getAuthSchemes(service).forEach((id, t) -> schemes.withMember(id.toString(), t.toNode()));
        node.withMember("authSchemes", schemes.build());
        service.findTrait(AWS_SERVICE).map(t -> t.toNode().expectObjectNode()).ifPresent(aws -> {
            aws.getStringMember("sdkId").ifPresent(v -> node.withMember("sdkId", v.getValue()));
            aws.getStringMember("endpointPrefix").ifPresent(v -> node.withMember("endpointPrefix", v.getValue()));
        });
        service.findTrait(SIGV4).map(t -> t.toNode().expectObjectNode())
                .flatMap(sigv4 -> sigv4.getStringMember("name"))
                .ifPresent(v -> node.withMember("sigv4Name", v.getValue()));
        withTraits(node, service);
        return withSource(node, service.getSourceLocation()).build();
    }

    /** The first protocol trait the explorer knows, in {@link #PROTOCOL_TRAITS} order, or empty. */
    private static String protocolOf(ServiceShape service) {
        return PROTOCOL_TRAITS.stream()
                .filter(id -> service.findTrait(ShapeId.from(id)).isPresent())
                .findFirst()
                .orElse("");
    }

    /**
     * A resource with its identifiers, properties, lifecycle operations, and children, so the
     * reference can show it as a page of its own.
     */
    private ObjectNode renderResource(ResourceShape resource, ShapeId parent) {
        ObjectNode.Builder node = ObjectNode.builder()
                .withMember("id", resource.getId().toString())
                .withMember("name", contextualName(service, resource))
                .withMember("identifiers", targets(resource.getIdentifiers()))
                .withMember("properties", targets(resource.getProperties()));
        traitString(resource, DocumentationTrait.ID).ifPresent(v -> node.withMember("documentation", v));
        ObjectNode.Builder lifecycle = ObjectNode.builder();
        resource.getCreate().ifPresent(id -> lifecycle.withMember("create", id.toString()));
        resource.getPut().ifPresent(id -> lifecycle.withMember("put", id.toString()));
        resource.getRead().ifPresent(id -> lifecycle.withMember("read", id.toString()));
        resource.getUpdate().ifPresent(id -> lifecycle.withMember("update", id.toString()));
        resource.getDelete().ifPresent(id -> lifecycle.withMember("delete", id.toString()));
        resource.getList().ifPresent(id -> lifecycle.withMember("list", id.toString()));
        node.withMember("lifecycle", lifecycle.build())
                .withMember("operations", ids(resource.getOperations()))
                .withMember("collectionOperations", ids(resource.getCollectionOperations()))
                .withMember("resources", ids(resource.getResources()));
        if (parent != null) {
            node.withMember("parent", parent.toString());
        }
        withTraits(node, resource);
        return withSource(node, resource.getSourceLocation()).build();
    }

    /** The lifecycle role an operation plays in a resource, or null when it is not a lifecycle operation. */
    private static String lifecycleRole(ResourceShape resource, ShapeId operation) {
        Map<String, Optional<ShapeId>> roles = new java.util.LinkedHashMap<>();
        roles.put("create", resource.getCreate());
        roles.put("put", resource.getPut());
        roles.put("read", resource.getRead());
        roles.put("update", resource.getUpdate());
        roles.put("delete", resource.getDelete());
        roles.put("list", resource.getList());
        return roles.entrySet().stream()
                .filter(e -> e.getValue().filter(operation::equals).isPresent())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private static ObjectNode targets(Map<String, ShapeId> members) {
        ObjectNode.Builder node = ObjectNode.builder();
        members.forEach((name, target) -> node.withMember(name, target.toString()));
        return node.build();
    }

    private static ArrayNode ids(Set<ShapeId> ids) {
        return ids.stream().map(ShapeId::toString).sorted().map(Node::from).collect(ArrayNode.collect());
    }

    /**
     * Resolves member bindings with HttpBindingIndex, as protocol code generators do, so unbound
     * members of an HTTP operation read as body members and error bindings are included.
     */
    private void indexHttpBindings(Model model, ServiceShape service, List<OperationShape> operations) {
        HttpBindingIndex index = HttpBindingIndex.of(model);
        OperationIndex operationIndex = OperationIndex.of(model);
        for (OperationShape operation : operations) {
            if (!operation.hasTrait(HttpTrait.ID)) {
                continue;
            }
            index.getRequestBindings(operation).values().forEach(b -> httpBindings.put(b.getMember().getId(), b));
            index.getResponseBindings(operation).values().forEach(b -> httpBindings.put(b.getMember().getId(), b));
            for (StructureShape error : operationIndex.getErrors(service, operation)) {
                index.getResponseBindings(error).values().forEach(b -> httpBindings.putIfAbsent(b.getMember().getId(), b));
            }
        }
    }

    private ObjectNode renderOperation(Model model, ServiceShape service, OperationShape operation) {
        List<StructureShape> errors = OperationIndex.of(model).getErrors(service, operation);
        ObjectNode.Builder node = ObjectNode.builder()
                .withMember("id", operation.getId().toString())
                .withMember("name", contextualName(service, operation))
                .withMember("readonly", operation.hasTrait(ReadonlyTrait.ID))
                .withMember("idempotent", operation.hasTrait(IdempotentTrait.ID))
                .withMember("deprecated", operation.hasTrait(DeprecatedTrait.ID))
                .withMember("input", operation.getInput().<Node>map(id -> Node.from(id.toString())).orElse(Node.nullNode()))
                .withMember("output", operation.getOutput().<Node>map(id -> Node.from(id.toString())).orElse(Node.nullNode()))
                // OperationIndex includes the errors the service declares for every operation;
                // serviceErrors names the ones that come from the service.
                .withMember("errors", errors.stream()
                        .map(e -> Node.from(e.getId().toString()))
                        .collect(ArrayNode.collect()))
                .withMember("serviceErrors", errors.stream()
                        .filter(e -> service.getErrors().contains(e.getId()))
                        .map(e -> Node.from(e.getId().toString()))
                        .collect(ArrayNode.collect()));
        traitString(operation, DocumentationTrait.ID).ifPresent(v -> node.withMember("documentation", v));
        operation.findTrait(HttpTrait.ID).ifPresent(t -> {
            HttpBindingIndex http = HttpBindingIndex.of(model);
            String documentType = "aws.protocols#restXml".equals(protocol) ? "application/xml" : "application/json";
            ObjectNode.Builder httpNode = t.toNode().expectObjectNode().toBuilder()
                    .withMember("code", http.getResponseCode(operation));
            http.determineRequestContentType(operation, documentType)
                    .ifPresent(v -> httpNode.withMember("requestContentType", v));
            http.determineResponseContentType(operation, documentType)
                    .ifPresent(v -> httpNode.withMember("responseContentType", v));
            node.withMember("http", httpNode.build());
        });
        operation.findTrait(EndpointTrait.ID).map(e -> e.toNode().expectObjectNode())
                .flatMap(e -> e.getStringMember("hostPrefix"))
                .ifPresent(prefix -> node.withMember("endpoint", ObjectNode.builder()
                        .withMember("hostPrefix", prefix.getValue()).build()));
        BottomUpIndex.of(model).getResourceBinding(service, operation)
                .ifPresent(resource -> node.withMember("resource", renderResourceBinding(model, resource, operation)));
        // PaginatedIndex merges the service's @paginated defaults and resolves the member paths.
        PaginatedIndex.of(model).getPaginationInfo(service, operation).ifPresent(info -> {
            ObjectNode.Builder pagination = ObjectNode.builder()
                    .withMember("inputToken", info.getInputTokenMember().getMemberName())
                    .withMember("outputToken", memberPath(info.getOutputTokenMemberPath()));
            if (!info.getItemsMemberPath().isEmpty()) {
                pagination.withMember("items", memberPath(info.getItemsMemberPath()));
            }
            info.getPageSizeMember().ifPresent(m -> pagination.withMember("pageSize", m.getMemberName()));
            node.withMember("paginated", pagination.build());
        });
        // NO_AUTH_AWARE reports smithy.api#noAuth for an operation that needs no credentials.
        node.withMember("auth", ServiceIndex.of(model)
                .getEffectiveAuthSchemes(service, operation, ServiceIndex.AuthSchemeMode.NO_AUTH_AWARE).keySet().stream()
                .map(id -> Node.from(id.toString()))
                .collect(ArrayNode.collect()));
        if (operation.hasTrait(OptionalAuthTrait.ID)) {
            node.withMember("optionalAuth", true);
        }
        EventStreamIndex streams = EventStreamIndex.of(model);
        streams.getInputInfo(operation).ifPresent(i -> node.withMember("inputEventStream", eventStream(i)));
        streams.getOutputInfo(operation).ifPresent(i -> node.withMember("outputEventStream", eventStream(i)));
        // Modeled examples, as smithy-docgen and SDK docs render them: title, docs, input, and
        // the output or error the service returns.
        operation.findTrait(ExamplesTrait.ID).ifPresent(t -> node.withMember("examples", t.toNode()));
        withTraits(node, operation);
        return withSource(node, operation.getSourceLocation()).build();
    }

    /** The event stream member, its union, the events by name, and the initial-message members. */
    private static ObjectNode eventStream(EventStreamInfo info) {
        ObjectNode.Builder events = ObjectNode.builder();
        info.getEvents().forEach((name, struct) -> events.withMember(name, struct.getId().toString()));
        return ObjectNode.builder()
                .withMember("member", info.getEventStreamMember().getMemberName())
                .withMember("target", info.getEventStreamTarget().getId().toString())
                .withMember("events", events.build())
                .withMember("initialMembers", info.getInitialMessageMembers().keySet().stream()
                        .map(Node::from)
                        .collect(ArrayNode.collect()))
                .build();
    }

    /**
     * How an operation binds to its resource: as an instance or collection operation, its lifecycle
     * role, and the input and output members that carry the resource's identifiers.
     */
    private static ObjectNode renderResourceBinding(Model model, ResourceShape resource, OperationShape operation) {
        IdentifierBindingIndex identifiers = IdentifierBindingIndex.of(model);
        ObjectNode.Builder node = ObjectNode.builder()
                .withMember("id", resource.getId().toString())
                .withMember("binding", identifiers.getOperationBindingType(resource, operation).toString()
                        .toLowerCase(Locale.ROOT));
        String role = lifecycleRole(resource, operation.getId());
        if (role != null) {
            node.withMember("lifecycle", role);
        }
        return node
                .withMember("inputIdentifiers", identifiers.getOperationInputBindings(resource, operation).values().stream()
                        .map(Node::from).collect(ArrayNode.collect()))
                .withMember("outputIdentifiers", identifiers.getOperationOutputBindings(resource, operation).values().stream()
                        .map(Node::from).collect(ArrayNode.collect()))
                .build();
    }

    private ObjectNode renderShape(Model model, Shape shape) {
        String id = shape.getId().toString();
        addDefinition(shape, id);
        String name = contextualName(service, shape);
        ObjectNode.Builder node = ObjectNode.builder()
                .withMember("id", id)
                .withMember("name", name)
                .withMember("type", shape.getType().toString())
                .withMember("deprecated", shape.hasTrait(DeprecatedTrait.ID))
                .withMember("sensitive", shape.hasTrait(SensitiveTrait.ID));
        if (!name.equals(shape.getId().getName())) {
            node.withMember("renamedFrom", shape.getId().getName());
        }
        flag(node, "input", shape.hasTrait(InputTrait.ID));
        flag(node, "output", shape.hasTrait(OutputTrait.ID));
        flag(node, "mixin", shape.hasTrait(MixinTrait.ID));
        if (!shape.getMixins().isEmpty()) {
            node.withMember("mixins", shape.getMixins().stream().map(m -> Node.from(m.toString())).collect(ArrayNode.collect()));
        }
        traitString(shape, DocumentationTrait.ID).ifPresent(v -> node.withMember("documentation", v));
        shape.findTrait(ErrorTrait.ID).ifPresent(t -> node.withMember("error", t.toNode())
                // The status the protocol sends: @httpError, or 400 / 500 by the error's side.
                .withMember("code", HttpBindingIndex.of(model).getResponseCode(shape)));
        shape.findTrait(HttpErrorTrait.ID).ifPresent(t -> node.withMember("httpError", t.toNode()));
        shape.findTrait(TimestampFormatTrait.ID).ifPresent(t -> node.withMember("timestampFormat", t.toNode()));
        ObjectNode constraints = renderConstraints(shape);
        if (!constraints.isEmpty()) {
            node.withMember("constraints", constraints);
        }

        ArrayNode.Builder members = ArrayNode.builder();
        ArrayNode.Builder values = ArrayNode.builder();
        boolean enumLike = shape.getType() == ShapeType.ENUM || shape.getType() == ShapeType.INT_ENUM;
        for (MemberShape member : shape.members()) {
            if (!isInheritedFromMixin(model, member)) {
                addDefinition(member, member.getId().toString());
            }
            if (enumLike) {
                values.withValue(renderEnumValue(member));
            } else {
                members.withValue(renderMember(model, member));
            }
        }
        if (enumLike) {
            node.withMember("values", values.build());
        } else if (!shape.members().isEmpty()) {
            node.withMember("members", members.build());
        }
        // A Smithy 1.0 @enum string reads as an enum, with the values its trait defines.
        // Enum shapes extend StringShape and carry a synthetic @enum trait, so match the exact type.
        if (shape.getType() == ShapeType.STRING && shape.hasTrait(EnumTrait.ID)) {
            EnumShape.fromStringShape((StringShape) shape, true).ifPresent(converted -> {
                ArrayNode.Builder legacy = ArrayNode.builder();
                converted.members().forEach(m -> legacy.withValue(renderEnumValue(m)));
                node.withMember("values", legacy.build()).withMember("legacyEnum", true);
            });
        }
        withTraits(node, shape);
        return withSource(node, shape.getSourceLocation()).build();
    }

    private ObjectNode renderMember(Model model, MemberShape member) {
        Shape target = model.getShape(member.getTarget()).orElse(null);
        ObjectNode.Builder node = ObjectNode.builder()
                .withMember("id", member.getId().toString())
                .withMember("name", member.getMemberName())
                .withMember("target", member.getTarget().toString())
                .withMember("targetType", target != null ? target.getType().toString() : "unknown")
                .withMember("required", member.hasTrait(RequiredTrait.ID))
                .withMember("deprecated", member.hasTrait(DeprecatedTrait.ID))
                .withMember("sensitive", member.hasTrait(SensitiveTrait.ID) || (target != null && target.hasTrait(SensitiveTrait.ID)))
                .withMember("idempotencyToken", member.hasTrait(IdempotencyTokenTrait.ID))
                .withMember("clientOptional", member.hasTrait(ClientOptionalTrait.ID))
                // Whether generated clients may leave the member null: @clientOptional, an @input
                // structure, or a member without @required or a non-null default.
                .withMember("nullable", nullable.isMemberNullable(member, NullableIndex.CheckMode.CLIENT));
        Optional<String> ownDocs = traitString(member, DocumentationTrait.ID);
        if (ownDocs.isPresent()) {
            node.withMember("documentation", ownDocs.get());
        } else if (target != null) {
            traitString(target, DocumentationTrait.ID).ifPresent(v -> node
                    .withMember("documentation", v)
                    .withMember("documentationFrom", "target"));
        }
        if (target != null && target.hasTrait(StreamingTrait.ID)) {
            if (target.isUnionShape()) {
                node.withMember("streaming", "events");
            } else if (target.isBlobShape()) {
                node.withMember("streaming", "blob");
            }
        }
        if (!member.getMixins().isEmpty()) {
            node.withMember("from", member.getMixins().iterator().next().toString());
        }
        if (target != null && target.isTimestampShape()) {
            HttpBinding bound = httpBindings.get(member.getId());
            HttpBinding.Location location = bound != null ? bound.getLocation() : HttpBinding.Location.DOCUMENT;
            TimestampFormatTrait.Format fallback = EPOCH_SECONDS_PROTOCOLS.contains(protocol)
                    ? TimestampFormatTrait.Format.EPOCH_SECONDS
                    : TimestampFormatTrait.Format.DATE_TIME;
            node.withMember("wireTimestampFormat",
                    HttpBindingIndex.of(model).determineTimestampFormat(member, location, fallback).toString());
        }
        member.findTrait(DefaultTrait.ID).ifPresent(t -> node.withMember("default", t.toNode()));
        traitString(member, JsonNameTrait.ID).ifPresent(v -> node.withMember("jsonName", v));
        traitString(member, XmlNameTrait.ID).ifPresent(v -> node.withMember("xmlName", v));
        member.findTrait(TimestampFormatTrait.ID).ifPresent(t -> node.withMember("timestampFormat", t.toNode()));
        renderBinding(member).ifPresent(binding -> node.withMember("binding", binding));
        ObjectNode constraints = renderConstraints(member);
        if (!constraints.isEmpty()) {
            node.withMember("constraints", constraints);
        }
        withTraits(node, member);
        return withSource(node, member.getSourceLocation()).build();
    }

    /**
     * Where a member goes on the wire. Body members (DOCUMENT) and unbound members carry no
     * binding. An HTTP binding trait the index did not resolve (on a nested member, or in an
     * operation without {@code @http}) is still shown, marked {@code ignored}. A mixin's member is
     * marked {@code mixin} instead: the index only resolves the copies in the structures that
     * apply the mixin, so its binding takes effect there.
     */
    private Optional<ObjectNode> renderBinding(MemberShape member) {
        HttpBinding resolved = httpBindings.get(member.getId());
        if (resolved != null) {
            String name = resolved.getLocationName();
            ObjectNode bound = switch (resolved.getLocation()) {
                case LABEL -> binding("label", name);
                case QUERY -> binding("query", name);
                case QUERY_PARAMS -> binding("queryParams", null);
                case HEADER -> binding("header", name);
                case PREFIX_HEADERS -> binding("prefixHeaders", name);
                case PAYLOAD -> binding("payload", null);
                case RESPONSE_CODE -> binding("responseCode", null);
                default -> null;
            };
            if (bound != null) {
                return Optional.of(bound);
            }
        } else {
            Optional<ObjectNode> unresolved = traitBinding(member);
            if (unresolved.isPresent()) {
                boolean inMixin = model.getShape(member.getContainer())
                        .filter(container -> container.hasTrait(MixinTrait.ID))
                        .isPresent();
                return Optional.of(unresolved.get().withMember(inMixin ? "mixin" : "ignored", true));
            }
        }
        if (member.hasTrait(HostLabelTrait.ID)) {
            return Optional.of(binding("hostLabel", member.getMemberName()));
        }
        return Optional.empty();
    }

    /** A binding read from the member's HTTP binding traits alone. */
    private static Optional<ObjectNode> traitBinding(MemberShape member) {
        if (member.hasTrait(HttpLabelTrait.ID)) {
            return Optional.of(binding("label", member.getMemberName()));
        }
        if (member.hasTrait(HttpQueryTrait.ID)) {
            return Optional.of(binding("query", traitString(member, HttpQueryTrait.ID).orElse(member.getMemberName())));
        }
        if (member.hasTrait(HttpQueryParamsTrait.ID)) {
            return Optional.of(binding("queryParams", null));
        }
        if (member.hasTrait(HttpHeaderTrait.ID)) {
            return Optional.of(binding("header", traitString(member, HttpHeaderTrait.ID).orElse(member.getMemberName())));
        }
        if (member.hasTrait(HttpPrefixHeadersTrait.ID)) {
            return Optional.of(binding("prefixHeaders", traitString(member, HttpPrefixHeadersTrait.ID).orElse("")));
        }
        if (member.hasTrait(HttpPayloadTrait.ID)) {
            return Optional.of(binding("payload", null));
        }
        if (member.hasTrait(HttpResponseCodeTrait.ID)) {
            return Optional.of(binding("responseCode", null));
        }
        return Optional.empty();
    }

    private static void flag(ObjectNode.Builder node, String name, boolean value) {
        if (value) {
            node.withMember(name, true);
        }
    }

    private static ObjectNode binding(String location, String name) {
        ObjectNode.Builder node = ObjectNode.builder().withMember("location", location);
        if (name != null) {
            node.withMember("name", name);
        }
        return node.build();
    }

    private ObjectNode renderEnumValue(MemberShape member) {
        ObjectNode.Builder node = ObjectNode.builder()
                .withMember("id", member.getId().toString())
                .withMember("name", member.getMemberName())
                .withMember("deprecated", member.hasTrait(DeprecatedTrait.ID));
        member.findTrait(EnumValueTrait.ID).ifPresent(t -> node.withMember("value", t.toNode()));
        traitString(member, DocumentationTrait.ID).ifPresent(v -> node.withMember("documentation", v));
        withTraits(node, member);
        return withSource(node, member.getSourceLocation()).build();
    }

    /**
     * Adds every applied trait except documentation (shown as prose), in the order and form the
     * IDL uses ({@code @length(min: 1, max: 63)}), marking those a mixin supplied and which mixin.
     * A trait too long to show inline also carries its {@code full} text, up to a cap; rule sets
     * and examples are rendered elsewhere and are too large to repeat.
     */
    private void withTraits(ObjectNode.Builder node, Shape shape) {
        Map<ShapeId, Trait> introduced = shape.getIntroducedTraits();
        ArrayNode traits = shape.getAllTraits().values().stream()
                .filter(t -> !t.isSynthetic() && !t.toShapeId().equals(DocumentationTrait.ID))
                .sorted(Comparator.comparing((Trait t) -> !t.toShapeId().getNamespace().equals(Prelude.NAMESPACE))
                        .thenComparing(t -> t.toShapeId().toString()))
                .map(t -> {
                    String text = idlText(t);
                    ObjectNode.Builder trait = ObjectNode.builder()
                            .withMember("id", t.toShapeId().toString())
                            .withMember("text", text);
                    String full = idlText(t, Integer.MAX_VALUE);
                    if (!full.equals(text) && !t.toShapeId().getNamespace().equals("smithy.rules")
                            && !t.toShapeId().equals(ExamplesTrait.ID)) {
                        trait.withMember("full", full.length() > MAX_FULL_TRAIT_TEXT
                                ? full.substring(0, MAX_FULL_TRAIT_TEXT - 1) + "…"
                                : full);
                    }
                    if (!introduced.containsKey(t.toShapeId())) {
                        trait.withMember("inherited", true);
                        mixinSupplying(shape, t.toShapeId()).ifPresent(m -> trait.withMember("from", m.toString()));
                    }
                    return trait.build();
                })
                .collect(ArrayNode.collect());
        if (!traits.isEmpty()) {
            node.withMember("traits", traits);
        }
    }

    /** The first mixin (a shape, or a member for members) that applies the trait. */
    private Optional<ShapeId> mixinSupplying(Shape shape, ShapeId trait) {
        return shape.getMixins().stream()
                .filter(id -> model.getShape(id).map(m -> m.hasTrait(trait)).orElse(false))
                .findFirst();
    }

    private static String memberPath(List<MemberShape> path) {
        return path.stream().map(MemberShape::getMemberName).collect(Collectors.joining("."));
    }

    private static final int MAX_TRAIT_TEXT = 240;
    private static final int MAX_FULL_TRAIT_TEXT = 8192;

    /** A trait as written in IDL: {@code @name}, {@code @name(value)}, or {@code @name(key: value, …)}. */
    static String idlText(Trait trait) {
        return idlText(trait, MAX_TRAIT_TEXT);
    }

    /** {@link #idlText(Trait)}, with the value cut to {@code max} characters. */
    static String idlText(Trait trait, int max) {
        ShapeId id = trait.toShapeId();
        String name = id.getNamespace().equals(Prelude.NAMESPACE) ? id.getName() : id.toString();
        Node value = trait.toNode();
        if (value.isObjectNode() && value.expectObjectNode().isEmpty()) {
            return "@" + name;
        }
        String body = value.isObjectNode()
                ? value.expectObjectNode().getMembers().entrySet().stream()
                        .map(e -> e.getKey().getValue() + ": " + Node.printJson(e.getValue()))
                        .collect(Collectors.joining(", "))
                : Node.printJson(value);
        if (body.length() > max) {
            body = body.substring(0, max - 1) + "…";
        }
        return "@" + name + "(" + body + ")";
    }

    private static ObjectNode renderConstraints(Shape shape) {
        ObjectNode.Builder node = ObjectNode.builder();
        for (ShapeId constraint : CONSTRAINTS) {
            shape.findTrait(constraint).ifPresent(t -> node.withMember(constraint.getName(), t.toNode()));
        }
        return node.build();
    }

    private ObjectNode renderValidation(ValidatedResult<Model> result, List<ValidationEvent> events) {
        ArrayNode.Builder eventNodes = ArrayNode.builder();
        mostSevere(events.stream().filter(e -> e.getSeverity().ordinal() >= Severity.WARNING.ordinal()))
                .forEach(e -> eventNodes.withValue(withSource(eventNode(e), e.getSourceLocation()).build()));
        // Notes and suppressed events, which the page keeps behind a "Show" toggle; a suppressed
        // event carries the reason its suppression gives.
        ArrayNode.Builder noteNodes = ArrayNode.builder();
        mostSevere(events.stream().filter(e -> e.getSeverity().ordinal() < Severity.WARNING.ordinal()))
                .forEach(e -> {
                    ObjectNode.Builder node = eventNode(e);
                    e.getSuppressionReason().ifPresent(reason -> node.withMember("suppressionReason", reason));
                    noteNodes.withValue(withSource(node, e.getSourceLocation()).build());
                });
        // Events per severity, so the page can count what it lists (WARNING and above) and say how
        // many notes and suppressed events it leaves out.
        Map<String, Integer> bySeverity = new TreeMap<>();
        events.forEach(e -> bySeverity.merge(e.getSeverity().toString(), 1, Integer::sum));
        ObjectNode.Builder counts = ObjectNode.builder();
        bySeverity.forEach(counts::withMember);
        return ObjectNode.builder()
                .withMember("valid", !result.isBroken())
                .withMember("eventCount", events.size())
                .withMember("counts", counts.build())
                .withMember("events", eventNodes.build())
                .withMember("notes", noteNodes.build())
                .build();
    }

    // ── Element definitions ───────────────────────────────────────────────────

    /** Records the line that defines an element, for {@link #annotateDiff}. */
    private void addDefinition(Shape shape, String id) {
        String file = shape.getSourceLocation().getFilename();
        if (file.isEmpty() || file.startsWith("jar:")) {
            return;
        }
        definitions.computeIfAbsent(file, f -> new TreeMap<>()).putIfAbsent(shape.getSourceLocation().getLine(), id);
    }

    /**
     * Whether a member only repeats a mixin's member: same source location, nothing redeclared.
     * Recording it would credit the mixin member's line to the structure that applies the mixin.
     */
    private static boolean isInheritedFromMixin(Model model, MemberShape member) {
        return member.getMixins().stream()
                .map(model::getShape)
                .flatMap(Optional::stream)
                .anyMatch(mixin -> mixin.getSourceLocation().equals(member.getSourceLocation()));
    }

    private static boolean fileMatches(String absoluteFile, String diffPath) {
        String file = normalize(absoluteFile);
        return file.equals(diffPath) || file.endsWith("/" + diffPath);
    }

    /**
     * Adds to each source diff line that defines an element the {@code element} it defines, so the
     * page can link diff lines to the reference. Only applies to source diffs: a normalized diff's
     * line numbers are not source lines.
     */
    private ArrayNode annotateDiff(Baseline baseline) {
        if (!"source".equals(baseline.diffMode())) {
            return baseline.sourceFiles();
        }
        ArrayNode.Builder files = ArrayNode.builder();
        for (Node fileNode : baseline.sourceFiles()) {
            ObjectNode file = fileNode.expectObjectNode();
            String path = file.expectStringMember("path").getValue();
            Map<Integer, String> lines = definitions.entrySet().stream()
                    .filter(e -> fileMatches(e.getKey(), path))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(new TreeMap<>());
            ArrayNode.Builder hunks = ArrayNode.builder();
            for (Node hunkNode : file.expectArrayMember("hunks")) {
                ObjectNode hunk = hunkNode.expectObjectNode();
                ArrayNode.Builder hunkLines = ArrayNode.builder();
                for (Node lineNode : hunk.expectArrayMember("lines")) {
                    ObjectNode line = lineNode.expectObjectNode();
                    String element = line.getNumberMember("new")
                            .map(n -> lines.get(n.getValue().intValue()))
                            .orElse(null);
                    hunkLines.withValue(element != null ? line.withMember("element", element) : line);
                }
                hunks.withValue(hunk.withMember("lines", hunkLines.build()));
            }
            files.withValue(file.withMember("hunks", hunks.build()));
        }
        return files.build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ObjectNode.Builder withSource(ObjectNode.Builder node, SourceLocation location) {
        if (location == null || location.getFilename().isEmpty() || location == SourceLocation.NONE) {
            return node;
        }
        return node.withMember("source", ObjectNode.builder()
                .withMember("file", displayPath(location.getFilename()))
                .withMember("line", location.getLine())
                .build());
    }

    private String displayPath(String filename) {
        try {
            Path path = Path.of(filename).toAbsolutePath().normalize();
            if (sourceRoot != null && path.startsWith(sourceRoot)) {
                return normalize(sourceRoot.relativize(path).toString());
            }
        } catch (RuntimeException e) {
            // Not a filesystem path (e.g. a jar: URL); show it as is.
        }
        return filename;
    }

    private static String normalize(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static Optional<String> traitString(Shape shape, ShapeId trait) {
        return shape.findTrait(trait)
                .map(t -> t.toNode())
                .filter(Node::isStringNode)
                .map(n -> n.expectStringNode().getValue());
    }

    private static ObjectNode objectOf(Map<String, ObjectNode> members) {
        ObjectNode.Builder node = ObjectNode.builder();
        members.forEach(node::withMember);
        return node.build();
    }

}
