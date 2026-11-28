# Order module — Design (Phase 1)

The core of SellerDesk: a moderator takes an order from Facebook / Messenger / WhatsApp / phone,
the order moves through the pipeline `PENDING → CONFIRMED → PACKED → SHIPPED → DELIVERED`
(or `CANCELLED` / `RETURNED`), and every step moves stock and customer counters together with
the order in one transaction.

Status: **backend built** except `PATCH .../details` and `GET /customers/{id}/orders`: create,
get, list, `PENDING` edit, the six status actions with history, bulk status change, timeline, stock
and customer counters. Frontend built: `/orders` (list, tabs, filters, bulk), `/orders/new`, `/orders/:id`
(no edit screen for PENDING orders yet). Backend package `com.sellerdesk.order`, frontend
`features/orders`.

Related: `SCHEMA.md` §9 (`orders`), §11 (transactions); `API.md` §7; `SECURITY.md` §3
(`ORDER_*` permissions); `ARCHITECTURE.md` §1 (`order → inventory / customer / product`);
`modules/INVENTORY.md` §4 (order operations); `modules/CUSTOMER.md` §5 (Java API for Order);
`modules/PRODUCT.md` §5 (`ProductUsageChecker`).

---

## 1. Decisions (and why)

| Decision | Why |
|----------|-----|
| **Items and customer are snapshots.** Each item copies `productName`, `variantName`, `sku`, `unitPrice` at creation; the order copies customer `name`, `phone`, delivery `address`. Later product or customer edits never change an existing order. | The invoice, the courier label and the COD amount must show what was agreed with the customer, even if the product is renamed, repriced or archived tomorrow. |
| **All money is `long` poisha**, and **every total is computed by the server** (`subtotal`, `total`, `codAmount`, `lineTotal`). The client never sends totals; if it does, they are ignored. | Same rule as Product (`PRODUCT.md` §1). A client that sends `total` could be wrong or tampered with; the server is the only source. |
| **Order number = shop prefix + per-shop sequence** (`SD-1042`). The number comes from an atomic `$inc` on `shops.orderSeq` **before** and **outside** the order transaction. The prefix is copied onto the order. | `findAndModify` with `$inc` is atomic, so two parallel creates can never get the same number. Outside the transaction, because the shop document would otherwise be a hot spot: every parallel create would `WriteConflict` on it. Cost: a create that fails after taking its number leaves a gap (#1041, #1043). Acceptable (`SCHEMA.md` §2). |
| **Stock is not checked at create; it is reserved at `CONFIRMED`.** The create response shows the current `available` per item (with `STOCK_VIEW`) so the moderator sees a shortage early. | F-commerce sellers take many orders that are never confirmed (customer stops answering). Reserving at create would lock stock for orders that never happen. |
| **One action endpoint per transition** (`/confirm`, `/pack`, `/ship`, ...), not `PATCH {status}`. | Each transition has its own permission, body and side effects (`API.md` "Why action endpoints"). Easy to secure, document and test one by one. |
| **Every transition is a conditional update** `{_id, shopId, status: <from>}` inside one transaction with its stock and customer writes. | If two people press "Confirm" at the same moment, only one update matches; the other changes nothing and reserves nothing. |
| **Customer counters are updated inside the order transaction** through `CustomerService.record*` (`MANDATORY`), **not** with after-commit events. | Decided 2026-11-28. Already built that way (`CUSTOMER.md` §5). Order status and counters commit or roll back together, so the counters are always exact. With after-commit events, a crash between the commit and the listener would lose a counter update forever. |
| **Full edit only while `PENDING`**; after that only the delivery address and note (`CONFIRMED` / `PACKED`) or the note (`SHIPPED`). Items and money are frozen from `CONFIRMED` on. | Decided 2026-11-28. Customers often send a corrected address after confirming. Freezing items after confirm means an edit never has to touch reserved stock. |
| **Returns only from `SHIPPED`.** `DELIVERED` is terminal. | Decided 2026-11-28. Matches `SCOPE.md`. A return after delivery (exchange, refund) belongs to the Phase 2 Returns module; it would also need reversing `totalSpent`. |
| **Bulk status change is per order**: each order runs in its own transaction, the response lists one result per order. | Decided 2026-11-28. One order that is short of stock must not block the other 29 in a packing batch. Small transactions also mean fewer `WriteConflict` retries on popular products. |
| **Archived products and inactive variants cannot be added** to an order (create, or new items in a PENDING edit). Items already in an order stay valid and can still be confirmed. | Retiring a product should stop new sales, not break orders already accepted. Inventory allows reserving them (`INVENTORY.md` §1). |
| **`requestId` (client UUID) on create**, unique per shop. A repeat returns the existing order. | On a slow mobile connection, a double tap or a retry after a timeout would otherwise create two orders and use up two numbers. Same pattern as restock (`INVENTORY.md` §1). |
| **The same `variantId` twice in one order → `400`.** | One line per variant keeps the snapshot and the stock lines simple. The UI increases the quantity instead. |

---

## 2. Data: `orders` (tenant-owned)

Replaces the draft in `SCHEMA.md` §9 (that section will point here).

| Field | Type | Req | Notes |
|-------|------|-----|-------|
| `_id` | ObjectId | Y | |
| `shopId` | ObjectId | Y | from the JWT |
| `orderNumber` | Int64 | Y | per-shop sequence, from `shops.orderSeq`; unique per shop |
| `orderNumberPrefix` | String | Y | copy of `settings.orderNumberPrefix` at creation (missing → `SD`). Display: `SD-1042`. A later prefix change never renames old orders. |
| `requestId` | String | N | client UUID from create (idempotency) |
| `customerId` | ObjectId | Y | → `customers._id`; fixed after create |
| `customer.name` | String | Y | snapshot, 1–100; editable while `PENDING` |
| `customer.phone` | String | Y | snapshot, `01XXXXXXXXX`; always the customer's main phone at creation |
| `customer.address` | Address | Y | delivery address snapshot (`SCHEMA.md` §5: `line`, `area?`, `district`, `zone`, `note?`) |
| `items` | Array\<OrderItem\> | Y | 1–50 |
| `subtotal` | Int64 | Y | Σ `lineTotal` |
| `discount` | Int64 | Y | `0`–`subtotal` |
| `deliveryCharge` | Int64 | Y | `0`–`500000`; default from shop settings by `customer.address.zone` (§5) |
| `total` | Int64 | Y | `subtotal − discount + deliveryCharge` |
| `advancePaid` | Int64 | Y | `0`–`total` (bKash / Nagad advance) |
| `codAmount` | Int64 | Y | `total − advancePaid`: what the courier collects |
| `status` | String | Y | `PENDING`, `CONFIRMED`, `PACKED`, `SHIPPED`, `DELIVERED`, `CANCELLED`, `RETURNED` |
| `statusHistory` | Array\<StatusChange\> | Y | first entry `{from: null, to: PENDING}` at create; ≤ 8 entries (the longest path has 6) |
| `edits` | Array\<OrderEdit\> | Y | edit log for the timeline, newest last, capped at 50 (`$push` with `$slice: -50`) |
| `source` | String | Y | `FACEBOOK`, `MESSENGER`, `WHATSAPP`, `PHONE`, `OTHER` |
| `note` | String | N | ≤ 1000, internal note ("Call before delivery") |
| `shipping.courierName` | String | N | ≤ 50, free text (courier integration is Phase 2) |
| `shipping.trackingId` | String | N | ≤ 100, free text |
| `cancelReason` | String | N | 1–500, required on cancel |
| `returnReason` | String | N | 1–500, required on return |
| `createdBy` | ObjectId | Y | → `users._id` |
| `createdByName` | String | Y | snapshot, so lists need no join |
| `version` | Int64 | Y | `@Version`; bumped by every write (edit or status change) |
| `createdAt`, `updatedAt` | Date | Y | |

**`OrderItem`** (snapshot, never re-read from the product):

| Field | Type | Req | Notes |
|-------|------|-----|-------|
| `productId` | ObjectId | Y | from the product lookup, never from the client |
| `variantId` | ObjectId | Y | |
| `sku` | String | Y | snapshot (SKUs are immutable anyway) |
| `productName` | String | Y | snapshot |
| `variantName` | String | Y | snapshot |
| `unitPrice` | Int64 | Y | `0`–`100,000,000`; default = variant `price` at the time it is added |
| `quantity` | Int32 | Y | `1`–`999` |
| `lineTotal` | Int64 | Y | `unitPrice × quantity` |
| `returnedRestocked` | Boolean | N | set on return: was this item put back on the shelf |

**`StatusChange`**: `from` (String / null), `to`, `at` (Date), `by` (ObjectId), `byName`
(snapshot), `note` (≤ 500, optional; the cancel / return reason is copied here too).

**`OrderEdit`**: `at`, `by`, `byName`, `fields` (names of changed fields, e.g.
`["items", "discount"]`), `summary` (short text, e.g. `"TSHIRT-M qty 1 → 2"`, ≤ 300).

**Max values.** 50 items × 999 × ৳10 lakh fits easily in `long` (about 5 × 10^12 poisha). No
overflow check is needed beyond the field limits.

**Indexes** (all start with `shopId`, `SCHEMA.md` §10):

1. `{shopId: 1, orderNumber: 1}` **unique**: number per shop; search by number.
2. `{shopId: 1, status: 1, createdAt: -1, _id: -1}`: pipeline tabs ("all PACKED orders"), one or
   more statuses (`$in`: MongoDB merges the sorted ranges, no in-memory sort), status + date.
3. `{shopId: 1, createdAt: -1, _id: -1}`: default list, date range.
4. `{shopId: 1, source: 1, createdAt: -1, _id: -1}`: source filter.
5. `{shopId: 1, "customer.phone": 1, createdAt: -1, _id: -1}`: search by phone.
6. `{shopId: 1, customerId: 1, createdAt: -1}`: `GET /customers/{id}/orders`.
7. `{shopId: 1, requestId: 1}` **unique**, partial (`requestId` exists): idempotent create.
8. `{shopId: 1, "items.productId": 1}`: `OrderUsageChecker` ("is this product used?").

The list sorts by `createdAt` then `_id`, so rows with the same time still page in a fixed
order. Indexes 2–5 end with these two fields, so the sort comes from the index. Combined
filters (status + source) use one of them and filter the rest. `OrderListIntegrationTest`
runs `explain` on every filter combination: always an index starting with `shopId`, never a
collection scan, never an in-memory sort.

---

## 3. Order number

```
shops.findAndModify(
  filter: { _id: shopId },
  update: { $inc: { orderSeq: 1 } },
  returnNew: true,
  fields: { orderSeq: 1, "settings.orderNumberPrefix": 1 })
→ number = orderSeq, prefix = settings.orderNumberPrefix ?? "SD"
```

- **One round trip** gives both the number and the current prefix.
- **Concurrency**: MongoDB applies the two `$inc`s one after the other on the shop document, so
  20 parallel creates get 20 different numbers. The unique index #1 is the last line of defence.
- **When**: after all validation (variants found, money valid), right **before** the
  transaction starts, and only once: `TransactionRunner` retries re-use the same number.
  Validation first keeps gaps rare (only a DB failure during the transaction leaves one).
- **Owner**: the `shop` module owns `shops`, so this is a new method
  `ShopService.nextOrderNumber()` (shop from `CurrentUser`) returning `OrderNumber(prefix, number)`.
  It uses a targeted `$inc` (never a `save`), like all shop writes (`SCHEMA.md` §2).
- First order of a shop gets number `1` (`orderSeq` starts at `0`). (resolved question 8).

---

## 4. Status machine

```
            confirm          pack           ship            deliver
PENDING ───────────► CONFIRMED ───────► PACKED ───────► SHIPPED ───────► DELIVERED
   │                    │                 │                │
   │ cancel             │ cancel          │ cancel         │ return
   ▼                    ▼                 ▼                ▼
CANCELLED ◄─────────────┴─────────────────┘             RETURNED
```

`DELIVERED`, `CANCELLED` and `RETURNED` are terminal. No backward moves (resolved question 7).

### Allowed transitions, permission and side effects

| From → To | Endpoint | Permission | Stock (via `InventoryService`) | Customer (via `CustomerService`) | Extra body |
|-----------|----------|------------|--------------------------------|----------------------------------|------------|
| (create) → `PENDING` | `POST /orders` | `ORDER_CREATE` | none | `findOrCreateByPhone` / `getForOrder`, `recordOrderCreated` | full order |
| `PENDING` → `CONFIRMED` | `POST /orders/{id}/confirm` | `ORDER_CONFIRM` | `reserve` all items | none | `note?` |
| `CONFIRMED` → `PACKED` | `POST /orders/{id}/pack` | `ORDER_FULFILL` | none | none | `note?` |
| `PACKED` → `SHIPPED` | `POST /orders/{id}/ship` | `ORDER_FULFILL` | `deduct` all items | none | `courierName?`, `trackingId?`, `note?` |
| `SHIPPED` → `DELIVERED` | `POST /orders/{id}/deliver` | `ORDER_CLOSE` | none | `recordDelivered(total)` | `note?` |
| `SHIPPED` → `RETURNED` | `POST /orders/{id}/return` | `ORDER_CLOSE` | `restockReturn` for items with `restock = true` (none if all false) | `recordReturned` | `reason`, `items?[{variantId, restock}]` |
| `PENDING` → `CANCELLED` | `POST /orders/{id}/cancel` | `ORDER_CANCEL` | none (nothing reserved) | `recordCancelled` | `reason` |
| `CONFIRMED` / `PACKED` → `CANCELLED` | `POST /orders/{id}/cancel` | `ORDER_CANCEL` | `release` all items | `recordCancelled` | `reason` |

Any other pair → `409 INVALID_STATUS_TRANSITION` (message names the current status). Examples:
`SHIPPED → CANCELLED` (the parcel is with the courier: use return), `DELIVERED → RETURNED`,
`PENDING → PACKED`.

Default roles (`SECURITY.md` §3): owner can do everything; `MODERATOR` creates, edits,
confirms, closes, cancels; `PACKER` packs and ships; `ACCOUNTANT` closes (delivered /
returned, when the courier's COD report arrives). Permissions, not roles, are checked.

**Return body.** `items` lists only exceptions: an item left out is restocked (`restock`
defaults to `true`). A `variantId` that is not in the order → `400 VALIDATION_FAILED`. Each
item stores `returnedRestocked`. Damaged items (`restock: false`) get no movement; they are
simply gone from `onHand` since the `DEDUCT` at shipping.

**Already in the target status.** If the order is already in the requested status (for example
the same "Confirm" click retried after a timeout, or `TransactionRunner` re-running after
`UnknownTransactionCommitResult`), the endpoint returns `200` with the current order and does
nothing. No second reservation, no second history entry. This makes every transition idempotent.

### Status history

Every transition `$push`es one `StatusChange {from, to, at, by, byName, note}` in the same
update that changes `status`. The create writes `{from: null, to: PENDING}`. For cancel and
return, `note` holds the reason. The history is never edited.

---

## 5. Delivery charge

- **Default** from `ShopSettingsLookup.forShop(shopId)` (cached 10 min, evicted on `PUT /shop`):
  `customer.address.zone = INSIDE_DHAKA` → `settings.deliveryChargeInsideDhaka`, else
  `settings.deliveryChargeOutsideDhaka`.
- **Override**: the request may send `deliveryCharge` (`0`–`500000`), e.g. free delivery for a
  big order. Omitted → default by zone.
- On a `PENDING` edit (`PUT`, full replace): omitted → default for the (possibly new) zone again.
- From `CONFIRMED` on, money is frozen: an address change in `CONFIRMED` / `PACKED` does **not**
  change `deliveryCharge` or `codAmount` (resolved question 13).
- The order stores the charge it used; a later settings change never changes existing orders.

---

## 6. Inventory interaction

Only through `InventoryService` (`INVENTORY.md` §4). Order never reads or writes `stock.*` or
`stock_movements` itself.

```java
// all @Transactional(propagation = MANDATORY); shop and user from CurrentUser
inventoryService.reserve(lines, orderRef);        // CONFIRMED
inventoryService.release(lines, orderRef);        // CONFIRMED / PACKED → CANCELLED
inventoryService.deduct(lines, orderRef);         // SHIPPED
inventoryService.restockReturn(lines, orderRef);  // RETURNED, only restocked items
// lines = items.map(i -> new StockLine(i.variantId, i.quantity)); orderRef = (orderId, "SD-1042")
```

| Status change | Movement per item | onHand / reserved / available |
|---------------|-------------------|-------------------------------|
| → `CONFIRMED` | `RESERVE` | 0 / +q / −q |
| `CONFIRMED`/`PACKED` → `CANCELLED` | `RELEASE` | 0 / −q / +q |
| → `SHIPPED` | `DEDUCT` | −q / −q / 0 |
| → `RETURNED` (restock) | `RETURN` | +q / 0 / +q |

### Transaction boundaries

One status change = **one transaction** through `TransactionRunner.run(...)` (retries
`WriteConflict` and unknown commit results, max 10 s):

```
TransactionRunner.run(() -> {                       // @Transactional boundary
  1. load order {_id, shopId}                       → 404 if missing / other shop
  2. status == target?                              → return as is (idempotent, §4)
     transition allowed?                            → else 409 INVALID_STATUS_TRANSITION
  3. updateFirst {_id, shopId, status: from}
       $set status (+ shipping / reasons), $push statusHistory, $inc version
     matched 0                                      → someone moved it meanwhile: re-read, go to 2
  4. InventoryService.reserve / release / deduct / restockReturn
  5. CustomerService.record*
})                                                  // commit: order + stock + movements + counters
```

The order status update (3) comes **before** the stock calls (4): a parallel second request
then conflicts on the order document immediately, and after its retry it sees the new status
and stops in step 2. Nothing slow (mail, HTTP) happens inside the transaction.

**Create** is also one transaction (after the number is taken, §3): find-or-create customer,
insert the order, `recordOrderCreated`. If the insert fails, a newly created customer is rolled
back too.

**Edits** (`PUT`, `PATCH .../details`) are single-document updates (`{_id, shopId, version,
status}` filter); no transaction needed. A `PENDING` edit of items never touches stock, because
nothing is reserved yet.

### Insufficient stock

- Confirm of an order with 3 items where item 3 is short: `InventoryService.reserve` throws
  `INSUFFICIENT_STOCK` → **the whole transaction rolls back**: the reservations of items 1
  and 2, the status change and the history entry. The order stays `PENDING`, stock and movement
  log are unchanged.
- After the rollback, `OrderService` reads the current `available` of **all** items (new
  read-only method `InventoryService.availableFor(variantIds)`) and answers
  `409 INSUFFICIENT_STOCK` with one `fieldErrors` entry per short item
  (`field: items[2].quantity`, message `"TSHIRT-M: requested 3, available 1"`). The moderator
  sees every short item at once, not only the first.
- `release` / `deduct` cannot normally fail (the invariant `reserved ≤ onHand` guarantees the
  units are there, `INVENTORY.md` §1). If they do, it is data corruption: same rollback, `409`,
  error logged.
- Nothing is reserved partially, ever.

---

## 7. Customer interaction

Only through `CustomerService` (`CUSTOMER.md` §5). Order never touches `customers`.

- **Create with `customer` {name, phone, address?}** → `findOrCreateByPhone`: phone normalized
  by `BdPhone`; existing customer is reused (name never overwritten, archived one reactivated, a
  new address added if there is room); a new phone creates the customer. Race-safe (unique index
  + retry). `ORDER_CREATE` alone is enough; `CUSTOMER_EDIT` is not required.
- **Create with `customerId`** → `getForOrder`: `404` if unknown or of another shop.
- Both together, or neither → `400 VALIDATION_FAILED`.
- **Delivery address**: `deliveryAddress` from the request; else the inline customer's
  `address`; else the customer's default address; none at all → `400` on `deliveryAddress`.
- **Snapshot**: the order copies `name` (from the request if given inline, else the saved
  customer name), the **normalized** phone, and the delivery address.
- **Counters**, all inside the order's transaction (decision §1):

| Event | Call | Effect |
|-------|------|--------|
| create | `recordOrderCreated(customerId, createdAt)` | `totalOrders +1`, `lastOrderAt = max` |
| → `DELIVERED` | `recordDelivered(customerId, total)` | `deliveredOrders +1`, `totalSpent += total` |
| → `RETURNED` | `recordReturned(customerId)` | `returnedOrders +1` |
| → `CANCELLED` | `recordCancelled(customerId)` | `cancelledOrders +1` |

`totalSpent` uses `total` (what the customer paid, including delivery, minus discount).

---

## 8. Endpoints

Base `/api/v1`. The shop is always the logged-in user's (JWT). No `shopId` in any path, query
or body; one sent in a body is ignored.

| Method | Path | Permission | Request → Response |
|--------|------|------------|--------------------|
| POST | `/orders` | `ORDER_CREATE` | `CreateOrderRequest` → `201 OrderResponse` (`PENDING`). Repeat `requestId` → `200` with the existing order. |
| GET | `/orders` | `ORDER_VIEW` | `?status=&source=&from=&to=&q=&sort=createdAt,desc\|createdAt,asc&page=&size=` → page of `OrderSummary` |
| GET | `/orders/{id}` | `ORDER_VIEW` | → `OrderResponse` |
| PUT | `/orders/{id}` | `ORDER_EDIT` | `UpdateOrderRequest` → `OrderResponse`. **`PENDING` only**, full replace. Else `409 ORDER_NOT_EDITABLE`. |
| PATCH | `/orders/{id}/details` | `ORDER_EDIT` | `{deliveryAddress?, note?, version}` → `OrderResponse`. `CONFIRMED` / `PACKED`: address and note. `SHIPPED`: note only (address → `409 ORDER_NOT_EDITABLE`). `PENDING` also allowed. Terminal → `409 ORDER_NOT_EDITABLE`. |
| POST | `/orders/{id}/confirm` | `ORDER_CONFIRM` | `{note?}` → `OrderResponse` |
| POST | `/orders/{id}/pack` | `ORDER_FULFILL` | `{note?}` → `OrderResponse` |
| POST | `/orders/{id}/ship` | `ORDER_FULFILL` | `{courierName?, trackingId?, note?}` → `OrderResponse` |
| POST | `/orders/{id}/deliver` | `ORDER_CLOSE` | `{note?}` → `OrderResponse` |
| POST | `/orders/{id}/return` | `ORDER_CLOSE` | `{reason, items?: [{variantId, restock}]}` → `OrderResponse` |
| POST | `/orders/{id}/cancel` | `ORDER_CANCEL` | `{reason}` → `OrderResponse` |
| POST | `/orders/bulk-status` | per action (below) | `{action, orderIds (1–50, unique), note?, reason?, courierName?}` → `200 BulkStatusResponse` |
| GET | `/orders/{id}/timeline` | `ORDER_VIEW` | → `[TimelineEntry]`, oldest first |
| GET | `/customers/{id}/orders` | `CUSTOMER_VIEW` + `ORDER_VIEW` | `?page=&size=` → page of `OrderSummary`, newest first. Unknown / other shop's customer → `404`. |

### Create / update request

```
CreateOrderRequest {
  requestId,                                  // UUID, required
  customerId?            | customer? {name, phone, address? (AddressRequest)},   // exactly one
  deliveryAddress?,                           // Address: line, area?, district, zone, note?
  items: [{variantId, quantity (1–999), unitPrice? (0–100000000)}] (1–50, variantIds unique),
  deliveryCharge? (0–500000), discount? (default 0), advancePaid? (default 0),
  source (FACEBOOK|MESSENGER|WHATSAPP|PHONE|OTHER), note? (≤1000)
}
UpdateOrderRequest {
  customerName?, deliveryAddress, items (as above), deliveryCharge?, discount?, advancePaid?,
  source, note?, version
}
```

- **Update is a full replace** of the editable fields (like `PUT /customers/{id}`). The
  customer (`customerId`, phone) cannot change (resolved question 10).
- **Item prices on update**: an item whose `variantId` was already in the order and has no
  `unitPrice` keeps its **stored snapshot** (the product is not re-read). A new item takes the
  variant's current price and must be orderable (active variant of an active product).
- `discount > subtotal` → `400` on `discount`; `advancePaid > total` → `400` on `advancePaid`.
- Unknown fields (`total`, `subtotal`, `codAmount`, `status`, `orderNumber`, `shopId`,
  `productId`, `sku`) are ignored.

### Responses

```
OrderResponse {id, orderNumber ("SD-1042"), status, source,
               customer{id, name, phone, address{line, area, district, zone, note}},
               items[{productId, variantId, sku, productName, variantName, unitPrice, quantity,
                      lineTotal, returnedRestocked, available*}],
               subtotal, discount, deliveryCharge, total, advancePaid, codAmount,
               note, shipping{courierName, trackingId}, cancelReason, returnReason,
               statusHistory[{from, to, at, by{id, name}, note}],
               allowedActions [CONFIRM, CANCEL, ...],     // for the current user's permissions
               createdBy{id, name}, version, createdAt, updatedAt}
OrderSummary  {id, orderNumber, status, source, customerId, customerName, customerPhone, district,
               zone, itemCount (units: sum of quantities), total, codAmount, createdAt}
BulkStatusResponse {action, succeeded, failed,
                    results[{orderId, orderNumber?, ok, status?, errorCode?, message?}]}
TimelineEntry {type: STATUS | EDIT, at, by{id, name}, from?, to?, note?, fields?, summary?}
```

`*` `available` = live stock, only with `STOCK_VIEW` and only while `PENDING` (afterwards the
units are reserved or shipped, so the number would mislead). `allowedActions` lets the UI show
only buttons that would work; the backend still checks everything.

### List filters

- `status`: one or more (`?status=PENDING&status=CONFIRMED`). Unknown value → `400`.
- `source`: one value. `from` / `to`: ISO dates `YYYY-MM-DD`, read as **Asia/Dhaka** days on
  `createdAt` (`from` inclusive, `to` inclusive; the server turns them into UTC instants).
  `from > to` → `400`.
- `q` (trimmed):
  - looks like an order number (`SD-1042`, `sd1042`, `#1042`, `1042`: optional letters, `-` or
    `#`, then 1–12 digits) → exact `orderNumber` (index #1). The prefix is ignored, because the
    number alone is unique per shop and old orders may carry an old prefix.
  - a valid Bangladeshi mobile in any accepted format → `BdPhone.normalize` → exact
    `customer.phone` (index #5).
  - anything else → `400 VALIDATION_FAILED` on `q` (name search is not supported in Phase 1).
- `page` 0-based, `size` default 20, max 100. Default sort `createdAt,desc` (`_id` breaks ties).

### Bulk status change

- `action`: `CONFIRM`, `PACK`, `SHIP`, `DELIVER`, `CANCEL`. `RETURN` is not offered in bulk
  (needs a per-item restock choice). `CANCEL` requires `reason`.
- **Permission**: the controller checks the permission of the chosen `action` (same table as §4)
  for the whole request → `403` if missing. One endpoint, so this is a check in code, not one
  `@PreAuthorize` per action.
- Each order runs the **same service method** as the single endpoint, in **its own
  transaction**, one after the other (not in parallel: keeps load predictable and the order of
  stock reservation stable).
- Per-order failures do not fail the request: `{orderId, ok: false, errorCode:
  INVALID_STATUS_TRANSITION | INSUFFICIENT_STOCK | NOT_FOUND | CONCURRENT_MODIFICATION}`. Another
  shop's id (or a malformed one) is reported as `NOT_FOUND`, exactly like an unknown id. An order
  already in the target status is `ok: true` (nothing to do).
- A short order in a bulk `CONFIRM` rolls back only its own transaction; its `message` names the
  short SKU (or the number of short items). The per-item `fieldErrors` list is only in the
  single `confirm` response.
- Max 50 ids, no duplicates → else `400`.

### Timeline

Merges `statusHistory` (type `STATUS`) and `edits` (type `EDIT`) sorted by `at`. Stock
movements are not repeated here; they are in the inventory log (`/inventory/movements?orderId=`).

### Errors

| Situation | Status | `code` |
|-----------|--------|--------|
| Bean Validation (sizes, ranges, missing `reason`, bad UUID, duplicate `variantId`, both / neither customer fields, `discount > subtotal`, `advancePaid > total`, unknown query value, bad `q`) | 400 | `VALIDATION_FAILED` (`fieldErrors`) |
| Decimal number for a quantity or money, unknown enum in the body, broken JSON | 400 | `MALFORMED_REQUEST` |
| Order / customer / variant id unknown **or of another shop** | 404 | `NOT_FOUND` |
| Variant inactive or product archived (create / new item in edit) | 409 | `VARIANT_NOT_ORDERABLE` (`fieldErrors[items[i].variantId]`) |
| Transition not allowed from the current status | 409 | `INVALID_STATUS_TRANSITION` |
| Edit not allowed in the current status | 409 | `ORDER_NOT_EDITABLE` |
| Not enough stock on confirm | 409 | `INSUFFICIENT_STOCK` (`fieldErrors` per short item) |
| Stale `version` on edit | 409 | `CONCURRENT_MODIFICATION` |
| Same `requestId` with a different order body | 409 | `REQUEST_ID_REUSED` |
| Missing permission | 403 | `FORBIDDEN` |

`requestId` repeat check: same `requestId` → compare `customer.phone`, item lines and money
inputs; equal → `200` with the stored order; different → `409 REQUEST_ID_REUSED`.

---

## 9. Rules

- **Snapshots never change by themselves.** A product rename / reprice / archive or a customer
  edit never touches existing orders. Only an explicit `PENDING` edit changes items or prices.
- **Totals are always recomputed by the server** on create and every `PENDING` edit:
  `lineTotal = unitPrice × quantity`, `subtotal = Σ lineTotal`,
  `total = subtotal − discount + deliveryCharge`, `codAmount = total − advancePaid`.
- **Multi-tenancy**: `shopId` only from `CurrentUser.shopId()`. Every query starts with
  `TenantCriteria.of(shopId)` or a repository method taking `shopId`. Another shop's order,
  customer or variant id → `404` (never `403`). Variants are resolved by
  `{shopId, "variants.variantId"}`; `productId` always comes from that lookup.
- **Orderable** = variant `active` and product `ACTIVE`, checked at create and for new items in
  a `PENDING` edit only.
- **Items** 1–50, unique `variantId`, quantity 1–999. **Money** in integer poisha; JSON `2.5` →
  `400 MALFORMED_REQUEST` (`accept-float-as-int: false`, `INVENTORY.md` §2).
- **No delete.** Orders are never deleted; a wrong order is cancelled.
- **Lost-update rule**: after create, the order is never saved as a whole document. Edits and
  transitions are targeted `$set` / `$push` with a `{_id, shopId, ...}` filter, like Product and
  Customer.
- **`ProductUsageChecker`**: `OrderUsageChecker` says a product is used if any order item has its
  `productId`, so `DELETE /products/{id}` → `409 PRODUCT_IN_USE`.

---

## 10. Backend structure

```
com.sellerdesk.order
├── controller  OrderController, CustomerOrdersController (GET /customers/{id}/orders)
├── service     OrderService (create, update, details, transitions, bulk, timeline, lists),
│               OrderTransitions (allowed pairs + required permission, one table),
│               OrderPricing (totals, delivery default), OrderSearchParser (q → number / phone),
│               OrderMapper, OrderUsageChecker, OrderErrors
├── repository  OrderRepository (Repository<Order, String>, shopId methods + insert),
│               OrderQueryRepository (MongoTemplate: filters, conditional status update, edits)
├── dto         requests / responses above
└── model       Order, OrderItem, OrderCustomerSnapshot, StatusChange, OrderEdit, OrderStatus,
                OrderSource, Shipping
```

Dependencies: `order → inventory`, `order → customer`, `order → product`, `order → shop`.
Nothing points back to `order` (`ProductUsageChecker` is implemented by `order`, defined in `product`).

### Changes in existing code (done)

1. **`ShopService.nextOrderNumber()`** → `OrderNumber(prefix, number)`: targeted `findAndModify`
   `$inc orderSeq` (§3), outside any transaction.
2. **`ProductService.resolveForOrder(Collection<String> variantIds)`** →
   `List<OrderableVariant {productId, productName, productStatus, variantId, sku, variantName,
   price, active}>`, one query `{shopId, "variants.variantId": {$in: ids}}` (product index #2).
   Missing id → `404`. Order decides "orderable".
3. **`InventoryService.availableFor(Collection<String> variantIds)`** → `Map<variantId, Integer>`,
   read-only, for the create response and the shortage report.
4. **`OrderUsageChecker`** registered as a `ProductUsageChecker` bean.
5. **Docs**: `SCHEMA.md` §9 (source values `FACEBOOK`/`WHATSAPP`, `requestId`, `edits`,
   `createdByName`, `returnedRestocked`, indexes #6–7) points here; `API.md` §7 (PUT PENDING-only,
   new `PATCH .../details`, bulk, timeline, `REQUEST_ID_REUSED`); `CUSTOMER.md` §3
   (`/customers/{id}/orders` built).

---

## 11. Tests

**Unit** (`OrderServiceTest`, `OrderPricingTest`, `OrderTransitionsTest`, `OrderSearchParserTest`):
- pricing: line totals, subtotal, discount bounds, delivery default by zone (inside / outside /
  override / `0`), `advancePaid` bounds, `codAmount`; client totals ignored;
- create: inline customer → `findOrCreateByPhone`; `customerId` → `getForOrder`; both / neither
  → 400; delivery address fallback order; inactive variant / archived product → 409; duplicate
  `variantId` → 400; number taken once, after validation, before the transaction;
- `requestId`: repeat → same order, no second number; different body → `REQUEST_ID_REUSED`;
- PENDING edit: existing item keeps snapshot price, new item takes current price; customer cannot
  change; edit log entry written; `details` rules per status;
- search parsing: `SD-1042`, `sd1042`, `#1042`, `1042` → number; `+8801712345678`,
  `01712-345678` → phone; `abc` → 400;
- repositories always called with the `TenantContext` shop.

**Status transition matrix** (`OrderTransitionsTest` + integration, parameterized over all 7 × 7
pairs): the 8 allowed transitions succeed with exactly their side effects (stock movement types
and counts, customer counter, history entry); every other pair → `409 INVALID_STATUS_TRANSITION`
with order, stock, movements and counters unchanged; target == current → `200`, no side effect.

**Integration** (`OrderIntegrationTest extends MongoIntegrationTestBase`):
- full happy path create → confirm → pack → ship → deliver: stock `{5,0,5}` → `{5,2,3}` →
  `{3,0,3}`; movements `RESERVE`, `DEDUCT`; `totalOrders 1`, `deliveredOrders 1`, `totalSpent = total`;
- cancel from `PENDING` (no movement), from `CONFIRMED` and `PACKED` (`RELEASE`); `SHIPPED` →
  cancel → 409;
- return with mixed `restock` → `RETURN` only for restocked items; `returnedRestocked` stored;
- **multi-item rollback**: item 3 short on confirm → order `PENDING`, items 1–2 not reserved, no
  movements, `409` lists every short item;
- insert failure after find-or-create (forced with a `collMod` validator) → new customer rolled back;
- product rename / reprice after create → order unchanged;
- delete a product used in an order → `409 PRODUCT_IN_USE`;
- list filters (status multi, source, Dhaka-day date range, `q` number / phone), paging, sort;
  `/customers/{id}/orders`;
- bulk: 5 orders, one short and one in the wrong status → 3 ok, 2 failed with codes.

**Concurrency** (real MongoDB, threads released together by a `CountDownLatch`):
- **Last unit**: stock `{1,0,1}`, two `PENDING` orders for 1 unit each, both confirmed at the
  same moment → exactly **one** `CONFIRMED`, the other `409 INSUFFICIENT_STOCK` and still
  `PENDING`; stock `{1,1,0}`; exactly one `RESERVE` movement. Repeated with 10 orders.
- **Same order, double confirm** (10 threads) → one transition, one history entry, one
  reservation per item; all callers get `200`.
- **Confirm vs cancel** on one `PENDING` order → final state consistent: either `CONFIRMED` with
  reservation, or `CANCELLED` without (or `CANCELLED` after a `RELEASE`); `reserved` matches.
- **Order numbers**: 20 parallel creates → 20 distinct numbers, no duplicate-key error.
- **Same `requestId`** 5 times in parallel → one order, `orderSeq` used once or with gaps but
  only one document.
- **Same new phone**, 2 parallel creates → one customer, `totalOrders = 2`.

**Controller** (`OrderControllerTest`): each endpoint `403` without its permission (`PACKER`
cannot create / confirm / cancel; `ACCOUNTANT` can deliver / return but not pack); bulk checks
the action's permission; `PLATFORM_ADMIN` → `403`; no token → `401`; validation `400`s
(quantity, money, decimals, enums, `requestId`, `q`, dates, bulk size); `allowedActions` follows
the user's permissions.

**`TenantIsolationTest` → `@Nested class Orders`** (`assertHiddenFromOtherShop`):
- A with B's order id → `404` on `GET /orders/{id}`, `PUT`, `PATCH .../details`, `.../timeline`,
  `confirm`, `pack`, `ship`, `deliver`, `return`, `cancel`; B's order, stock, movements and
  customer counters unchanged.
- Bulk with B's id → that entry `NOT_FOUND`, B unchanged.
- `GET /orders` shows only A's orders, also with `q` = B's order number or B's customer phone;
  `GET /customers/{B's id}/orders` → `404`.
- Create with B's `customerId` or B's `variantId` → `404`; nothing created, no number taken.
  PENDING edit adding B's `variantId` → `404`.
- `"shopId": "<B>"` in create / update / action bodies → ignored; saved with A's shop.
- A `requestId` already used by B does not block A; A and B both have an order `#1`.

A module is done only when these pass (CLAUDE.md).

---

## 12. Resolved questions

Decided on 2026-11-28 (all recommended defaults):

1. **Customer counters**: inside the order transaction (`CustomerService.record*`, `MANDATORY`), not after-commit events (§1, §7).
2. **Edit window**: full edit while `PENDING`; address and note in `CONFIRMED` / `PACKED`; note in `SHIPPED` (§8).
3. **Bulk status change**: one transaction per order, partial success, max 50 ids (§8).
4. **Returns**: only `SHIPPED → RETURNED`; `DELIVERED` is terminal (§4).
5. **Unit price override**: allowed with `ORDER_CREATE` / `ORDER_EDIT`; the edit log shows who changed it.
6. **Skip packing**: no; `CONFIRMED → SHIPPED` is not allowed.
7. **Undo a step**: no backward transitions; cancel or return instead.
8. **First order number**: `1` (`orderSeq` starts at `0`).
9. **Phone search in orders**: full number only (exact `customer.phone`).
10. **Customer of a `PENDING` order**: fixed; a wrong customer means cancel and re-create.
11. **`requestId` on create**: required; the frontend generates one per form.
12. **Date filter**: Asia/Dhaka days, `from` and `to` inclusive.
13. **Address change after confirm**: `deliveryCharge` and `codAmount` stay frozen.
14. **Notes**: one internal `note`.
15. **Stock at create**: warning only (`available` in the response), never blocks.
16. **Frontend**: separate task after the backend is built and tested.
