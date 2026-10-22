$version: "2"

namespace example.orders

use aws.protocols#restJson1

/// Order management for the API explorer tests.
@restJson1 @httpBearerAuth
@title("Example Orders")
service OrderService {
    version: "2026-10-15"
    operations: [GetStatus, CreateOrder]
    errors: [ThrottlingError] resources: [Warehouse]
}

/// Returns the status of an order.
@readonly
@http(method: "GET", uri: "/orders/{orderId}/status")
operation GetStatus {
    input := {
        /// The order to look up.
        @required
        @httpLabel
        orderId: String

        @httpQuery("verbose")
        verbose: Boolean
    }

    output := {
        status: OrderStatus
    }

    errors: [OrderNotFound]
}

@http(method: "POST", uri: "/orders")
operation CreateOrder {
    input := {
        @required
        items: ItemList

        @httpHeader("X-Client-Token")
        @idempotencyToken
        clientToken: String
    }
}

list ItemList {
    member: String
}

enum OrderStatus {
    /// The order is being prepared.
    PENDING
    SHIPPED
}

/// The order does not exist.
@error("client")
@httpError(404)
structure OrderNotFound {
    message: String
}

@error("server") @retryable(throttling: true)
@httpError(503)
structure ThrottlingError {}

// ── Resources, mixins, legacy enums, unions, recursion, and examples ──────────

/// A warehouse that stores order items.
resource Warehouse {
    identifiers: { warehouseId: WarehouseId }
    properties: { name: String, kind: StorageKind }
    create: CreateWarehouse
    read: GetWarehouse
    list: ListWarehouses
    operations: [PingWarehouse]
    resources: [Shelf]
}

/// A shelf inside a warehouse.
resource Shelf {
    identifiers: { warehouseId: WarehouseId, shelfId: String }
    read: GetShelf
}

@pattern("^wh-[a-z0-9]{4,12}$")
@length(min: 7, max: 15)
string WarehouseId

@enum([{ value: "COLD", name: "COLD" }, { value: "DRY", name: "DRY" }])
string StorageKind

@http(method: "POST", uri: "/warehouses")
operation CreateWarehouse {
    input := {
        @required
        name: String

        kind: StorageKind
    }

    output := {
        @required
        warehouseId: WarehouseId

        name: String

        kind: StorageKind
    }
}

/// Describes a warehouse.
@readonly
@http(method: "GET", uri: "/warehouses/{warehouseId}")
@examples([
    {
        title: "Get a warehouse"
        documentation: "Returns the warehouse and its storage kind."
        input: { warehouseId: "wh-abcd1234" }
        output: { warehouseId: "wh-abcd1234", name: "North", kind: "COLD" }
    }
    {
        title: "Invalid warehouse ID"
        input: { warehouseId: "nope" }
        error: {
            shapeId: InvalidWarehouseId
            content: { message: "The warehouse ID is not valid." }
        }
        allowConstraintErrors: true
    }
])
operation GetWarehouse {
    input := {
        @required
        @httpLabel
        warehouseId: WarehouseId
    }

    output := {
        @required
        warehouseId: WarehouseId

        name: String

        kind: StorageKind
    }

    errors: [InvalidWarehouseId]
}

@readonly
@paginated(inputToken: "nextToken", outputToken: "nextToken", items: "warehouses", pageSize: "maxResults")
@http(method: "GET", uri: "/warehouses")
operation ListWarehouses {
    input := {
        @httpQuery("nextToken")
        nextToken: String

        @httpQuery("maxResults")
        @range(min: 1, max: 100)
        maxResults: Integer
    }

    output := {
        nextToken: String

        @required
        warehouses: WarehouseIds
    }
}

list WarehouseIds {
    member: WarehouseId
}

/// Checks that a warehouse is reachable; needs no credentials.
@auth([])
@readonly
@http(method: "GET", uri: "/warehouses/{warehouseId}/ping")
operation PingWarehouse {
    input := {
        @required
        @httpLabel
        warehouseId: WarehouseId
    }

    output := {
        @notProperty
        status: String
    }
}

@readonly
@http(method: "GET", uri: "/warehouses/{warehouseId}/shelves/{shelfId}")
operation GetShelf {
    input := {
        @required
        @httpLabel
        warehouseId: WarehouseId

        @required
        @httpLabel
        shelfId: String
    }

    output := {
        info: ShelfInfo

        layout: Layout
    }
}

/// Audit fields shared by stored records.
@mixin
structure Audited {
    /// When the record was created.
    @required
    createdAt: Timestamp
}

structure ShelfInfo with [Audited] {
    label: String
}

/// How a shelf is laid out.
union Layout {
    /// No bins.
    open: Unit

    bins: Bin
}

/// A bin that may hold smaller bins.
structure Bin {
    label: String

    children: BinList
}

list BinList {
    member: Bin
}

/// The warehouse ID is not valid.
@error("client")
@httpError(400)
structure InvalidWarehouseId {
    message: String
}
