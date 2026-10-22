/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.apiexplorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.validation.ValidatedResult;

class ApiExplorerBuilderTest {

    private static ObjectNode render(Path source) {
        ValidatedResult<Model> result = TestModels.assemble(source);
        Model model = result.getResult().orElseThrow();
        return new ApiExplorerBuilder(Files.isDirectory(source) ? source : source.getParent())
                .build(result, ApiExplorerBuilder.selectService(ApiExplorerBuilder.services(model), null), null)
                .data();
    }

    private static ObjectNode orders() {
        return render(TestModels.ORDERS);
    }

    private static ObjectNode operation(ObjectNode data, String name) {
        return data.expectArrayMember("operations").getElementsAs(ObjectNode.class).stream()
                .filter(o -> o.expectStringMember("name").getValue().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static ObjectNode shape(ObjectNode data, String id) {
        return data.expectObjectMember("shapes").expectObjectMember(id);
    }

    private static ObjectNode member(ObjectNode shape, String name) {
        return shape.expectArrayMember("members").getElementsAs(ObjectNode.class).stream()
                .filter(m -> m.expectStringMember("name").getValue().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static String string(ObjectNode node, String member) {
        return node.expectStringMember(member).getValue();
    }

    private static boolean bool(ObjectNode node, String member) {
        return node.getBooleanMemberOrDefault(member, false);
    }

    private static List<String> strings(ObjectNode node, String member) {
        return node.expectArrayMember(member).getElements().stream()
                .map(n -> n.expectStringNode().getValue())
                .toList();
    }

    private static List<String> names(ObjectNode node, String member) {
        return node.expectArrayMember(member).getElementsAs(ObjectNode.class).stream()
                .map(n -> string(n, "name"))
                .toList();
    }

    @Test
    void rendersServiceAndProtocol() {
        ObjectNode service = orders().expectObjectMember("service");
        assertEquals("example.orders#OrderService", string(service, "id"));
        assertEquals("Example Orders", string(service, "title"));
        assertEquals("2026-10-15", string(service, "version"));
        assertEquals("aws.protocols#restJson1", string(service, "protocol"));
        assertEquals("main.smithy", string(service.expectObjectMember("source"), "file"));
    }

    @Test
    void rendersOperationsWithHttpBindingsSortedByName() {
        ObjectNode data = orders();
        assertEquals(List.of("CreateOrder", "CreateWarehouse", "GetShelf", "GetStatus", "GetWarehouse",
                "ListWarehouses", "PingWarehouse"), names(data, "operations"));

        ObjectNode getStatus = operation(data, "GetStatus");
        assertEquals("GET", string(getStatus.expectObjectMember("http"), "method"));
        assertEquals("/orders/{orderId}/status", string(getStatus.expectObjectMember("http"), "uri"));
        assertTrue(bool(getStatus, "readonly"));
        assertEquals("Returns the status of an order.", string(getStatus, "documentation"));
        // Errors include the service's common errors, as OperationIndex.getErrors(service, operation) does.
        assertEquals(List.of("example.orders#ThrottlingError", "example.orders#OrderNotFound"),
                strings(getStatus, "errors"));
    }

    @Test
    void rendersMemberBindingsAndDocs() {
        ObjectNode data = orders();
        ObjectNode input = shape(data, string(operation(data, "GetStatus"), "input"));

        ObjectNode orderId = member(input, "orderId");
        assertTrue(bool(orderId, "required"));
        assertEquals("label", string(orderId.expectObjectMember("binding"), "location"));
        assertEquals("The order to look up.", string(orderId, "documentation"));

        ObjectNode verbose = member(input, "verbose");
        assertEquals("query", string(verbose.expectObjectMember("binding"), "location"));
        assertEquals("verbose", string(verbose.expectObjectMember("binding"), "name"));
        assertEquals("boolean", string(verbose, "targetType"));

        ObjectNode token = member(shape(data, string(operation(data, "CreateOrder"), "input")), "clientToken");
        assertEquals("header", string(token.expectObjectMember("binding"), "location"));
        assertEquals("X-Client-Token", string(token.expectObjectMember("binding"), "name"));
        assertTrue(bool(token, "idempotencyToken"));
    }

    @Test
    void rendersErrorsAndEnums() {
        ObjectNode data = orders();
        ObjectNode notFound = shape(data, "example.orders#OrderNotFound");
        assertEquals("client", string(notFound, "error"));
        assertEquals(404, notFound.expectNumberMember("httpError").getValue().intValue());

        // Service errors are reachable from the service, so they are rendered too.
        assertNotNull(shape(data, "example.orders#ThrottlingError"));

        ObjectNode status = shape(data, "example.orders#OrderStatus");
        assertEquals("enum", string(status, "type"));
        assertEquals(List.of("PENDING", "SHIPPED"), names(status, "values"));
        assertEquals("The order is being prepared.", string(status.expectArrayMember("values")
                .get(0).orElseThrow().expectObjectNode(), "documentation"));
    }

    @Test
    void rendersResourcesAndOperationBindings() {
        ObjectNode data = orders();
        Map<String, ObjectNode> resources = data.expectArrayMember("resources").getElementsAs(ObjectNode.class)
                .stream()
                .collect(Collectors.toMap(r -> string(r, "id"), r -> r));
        ObjectNode warehouse = resources.get("example.orders#Warehouse");
        assertEquals("example.orders#WarehouseId",
                string(warehouse.expectObjectMember("identifiers"), "warehouseId"));
        assertEquals("example.orders#StorageKind", string(warehouse.expectObjectMember("properties"), "kind"));
        assertEquals("example.orders#GetWarehouse", string(warehouse.expectObjectMember("lifecycle"), "read"));
        assertFalse(warehouse.expectObjectMember("lifecycle").containsMember("delete"));
        assertEquals(List.of("example.orders#Shelf"), strings(warehouse, "resources"));
        assertEquals("example.orders#Warehouse", string(resources.get("example.orders#Shelf"), "parent"));
        assertEquals("main.smithy", string(warehouse.expectObjectMember("source"), "file"));

        ObjectNode binding = operation(data, "GetWarehouse").expectObjectMember("resource");
        assertEquals("example.orders#Warehouse", string(binding, "id"));
        assertEquals("instance", string(binding, "binding"));
        assertEquals("read", string(binding, "lifecycle"));
        assertEquals(List.of("warehouseId"), strings(binding, "inputIdentifiers"));
        assertEquals("collection",
                string(operation(data, "ListWarehouses").expectObjectMember("resource"), "binding"));
        assertFalse(operation(data, "PingWarehouse").expectObjectMember("resource").containsMember("lifecycle"));
    }

    @Test
    void rendersAuthPaginationExamplesAndServiceErrors() {
        ObjectNode data = orders();
        assertEquals(List.of("smithy.api#noAuth"), strings(operation(data, "PingWarehouse"), "auth"));
        assertEquals(List.of("smithy.api#httpBearerAuth"), strings(operation(data, "GetStatus"), "auth"));
        ObjectNode service = data.expectObjectMember("service");
        assertEquals(List.of("example.orders#ThrottlingError"), strings(service, "errors"));
        assertTrue(service.expectObjectMember("authSchemes").containsMember("smithy.api#httpBearerAuth"));
        assertEquals(List.of("example.orders#ThrottlingError"), strings(operation(data, "GetStatus"), "serviceErrors"));
        assertEquals("application/json",
                string(operation(data, "CreateOrder").expectObjectMember("http"), "requestContentType"));

        ObjectNode pagination = operation(data, "ListWarehouses").expectObjectMember("paginated");
        assertEquals("nextToken", string(pagination, "inputToken"));
        assertEquals("warehouses", string(pagination, "items"));
        assertEquals("maxResults", string(pagination, "pageSize"));

        assertEquals(2, operation(data, "GetWarehouse").expectArrayMember("examples").size());
    }

    @Test
    void rendersMemberNullabilityAndWireFormats() {
        ObjectNode data = orders();
        ObjectNode orderId = member(shape(data, string(operation(data, "GetStatus"), "input")), "orderId");
        // @required on an @input structure: not @clientOptional, but clients still treat it as nullable.
        assertFalse(bool(orderId, "clientOptional"));
        assertTrue(bool(orderId, "nullable"));
        // Body members carry no binding.
        assertFalse(member(shape(data, string(operation(data, "CreateOrder"), "input")), "items")
                .containsMember("binding"));

        ObjectNode shelfInfo = shape(data, "example.orders#ShelfInfo");
        ObjectNode createdAt = member(shelfInfo, "createdAt");
        assertEquals("epoch-seconds", string(createdAt, "wireTimestampFormat"));
        assertEquals("example.orders#Audited$createdAt", string(createdAt, "from"));
        ObjectNode required = createdAt.expectArrayMember("traits").getElementsAs(ObjectNode.class).stream()
                .filter(t -> string(t, "id").equals("smithy.api#required"))
                .findFirst()
                .orElseThrow();
        assertTrue(bool(required, "inherited"));
        assertEquals("example.orders#Audited$createdAt", string(required, "from"));
        assertEquals(List.of("example.orders#Audited"), strings(shelfInfo, "mixins"));
    }

    @Test
    void rendersLegacyEnumsConstraintsAndErrorCodes() {
        ObjectNode data = orders();
        ObjectNode kind = shape(data, "example.orders#StorageKind");
        assertTrue(bool(kind, "legacyEnum"));
        assertEquals(List.of("COLD", "DRY"), names(kind, "values"));
        // A Smithy 2 enum carries a synthetic @enum trait but is not a legacy enum.
        assertFalse(shape(data, "example.orders#OrderStatus").containsMember("legacyEnum"));
        assertEquals(404, shape(data, "example.orders#OrderNotFound").expectNumberMember("code").getValue().intValue());
        assertEquals(400,
                shape(data, "example.orders#InvalidWarehouseId").expectNumberMember("code").getValue().intValue());
        assertEquals(503, shape(data, "example.orders#ThrottlingError").expectNumberMember("code").getValue().intValue());

        ObjectNode constraints = shape(data, "example.orders#WarehouseId").expectObjectMember("constraints");
        assertEquals("^wh-[a-z0-9]{4,12}$", constraints.expectStringMember("pattern").getValue());
        assertEquals(7, constraints.expectObjectMember("length").expectNumberMember("min").getValue().intValue());
    }

    @Test
    void excludesPreludeShapes() {
        assertFalse(orders().expectObjectMember("shapes").containsMember("smithy.api#String"));
    }

    @Test
    void hasNoReviewData() {
        ObjectNode data = orders();
        assertFalse(data.containsMember("comments"));
        assertFalse(data.containsMember("review"));
        assertFalse(Node.printJson(data).contains("\"anchor\""));
    }

    @Test
    void bindingsOnMixinMembersAreNotMarkedIgnored(@TempDir Path tmp) throws IOException {
        Path model = Files.createDirectories(tmp.resolve("mixin-bindings"));
        Files.writeString(model.resolve("main.smithy"), String.join("\n",
                "$version: \"2\"",
                "namespace example.mixins",
                "",
                "service Svc { version: \"1\", operations: [List] }",
                "",
                "@readonly",
                "@http(method: \"GET\", uri: \"/items\")",
                "operation List { input: ListInput }",
                "",
                "@mixin",
                "structure Paged {",
                "    @httpQuery(\"maxResults\")",
                "    maxResults: Integer",
                "}",
                "",
                "@input",
                "structure ListInput with [Paged] {",
                "    filter: Nested",
                "}",
                "",
                "structure Nested {",
                "    @httpHeader(\"X-Nested\")",
                "    nested: String",
                "}",
                ""));
        ObjectNode data = render(model);
        ObjectNode mixinMember = member(shape(data, "example.mixins#Paged"), "maxResults").expectObjectMember("binding");
        assertEquals("query", string(mixinMember, "location"));
        assertTrue(bool(mixinMember, "mixin"));
        assertFalse(mixinMember.containsMember("ignored"));
        ObjectNode applied = member(shape(data, "example.mixins#ListInput"), "maxResults").expectObjectMember("binding");
        assertEquals("query", string(applied, "location"));
        assertFalse(applied.containsMember("mixin"));
        ObjectNode nested = member(shape(data, "example.mixins#Nested"), "nested").expectObjectMember("binding");
        assertTrue(bool(nested, "ignored"));
    }

    @Test
    void validationListsSuppressedEventsSeparatelyWithTheirReason(@TempDir Path tmp) throws IOException {
        Path model = Files.createDirectories(tmp.resolve("suppressed"));
        Files.writeString(model.resolve("main.smithy"), String.join("\n",
                "$version: \"2\"",
                "metadata validators = [{ name: \"EmitEachSelector\", id: \"Orphans\", severity: \"WARNING\",",
                "    configuration: { selector: \"string[id|namespace = 'example.suppressed']\" } }]",
                "metadata suppressions = [{ id: \"Orphans\", namespace: \"*\", reason: \"Kept for later\" }]",
                "namespace example.suppressed",
                "",
                "string Orphan",
                ""));
        ObjectNode validation = render(model).expectObjectMember("validation");
        assertTrue(validation.expectArrayMember("events").isEmpty(), "notes are not listed with warnings");
        ObjectNode note = validation.expectArrayMember("notes").getElementsAs(ObjectNode.class).stream()
                .filter(e -> string(e, "id").equals("Orphans"))
                .findFirst()
                .orElseThrow();
        assertEquals("SUPPRESSED", string(note, "severity"));
        assertEquals("Kept for later", string(note, "suppressionReason"));
        assertEquals("example.suppressed#Orphan", string(note, "shapeId"));
    }

    @Test
    void modelWithoutServiceRendersItsShapes(@TempDir Path tmp) throws IOException {
        Path model = Files.createDirectories(tmp.resolve("no-service"));
        Files.writeString(model.resolve("main.smithy"), "$version: \"2\"\nnamespace example.shapes\nstring Name\n");
        ObjectNode data = render(model);
        assertFalse(data.containsMember("service"));
        assertTrue(data.expectArrayMember("operations").isEmpty());
        assertTrue(data.expectObjectMember("shapes").containsMember("example.shapes#Name"));
    }

    @Test
    void selectServiceAcceptsIdOrNameAndRejectsUnknown() {
        Model model = TestModels.assemble(TestModels.ORDERS).unwrap();
        var services = ApiExplorerBuilder.services(model);
        assertEquals("example.orders#OrderService",
                ApiExplorerBuilder.selectService(services, "OrderService").getId().toString());
        assertEquals("example.orders#OrderService",
                ApiExplorerBuilder.selectService(services, "example.orders#OrderService").getId().toString());
        var e = assertThrows(IllegalArgumentException.class,
                () -> ApiExplorerBuilder.selectService(services, "example.orders#Missing"));
        assertTrue(e.getMessage().contains("example.orders#OrderService"));
    }
}
