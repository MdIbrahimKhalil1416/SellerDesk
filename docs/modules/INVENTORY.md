# Inventory module — Design (Phase 1)

Stock levels per variant and the stock movement log. Inventory is the **only** module that
changes stock numbers; every change writes exactly one movement entry in the same transaction.

Status: **designed, not built**. Backend package `com.sellerdesk.inventory`, frontend
`features/inventory` (separate task, after the backend).

Related: `SCHEMA.md` §6 (`products.variants[].stock`), §7 (`stock_movements`), §11
(transactions); `API.md` §5; `SECURITY.md` §3 (`STOCK_VIEW`, `STOCK_ADJUST`);
`ARCHITECTURE.md` §1 (data ownership); `modules/PRODUCT.md` (variants, lost-update rule).

---

## 1. Decisions (and why)

| Decision | Why |
|----------|-----|
| **Stock stays inside the variant** (`variants[].stock {onHand, reserved, available}`), no new stock collection. | Already built that way (`VariantStock`). The stock list reads products, no join. A reservation is one atomic update on one document. |
| **`available` is stored**, always `= onHand − reserved`. | The "enough stock?" check must be a filter on the update itself (`stock.available ≥ q`). Without the stored field it would need `$expr` inside `$elemMatch`, which MongoDB does not support well. Cost: three numbers must stay consistent, so every write changes them together with `$inc`. |
| **Invariant `0 ≤ reserved ≤ onHand`** (so `available ≥ 0`). A manual decrease can only remove **unreserved** units. | Every confirmed order then always has real units behind it and can always ship. Example: onHand 5, reserved 3 → at most 2 can be removed; removing 4 → `409 INSUFFICIENT_STOCK`. Since `available ≥ 0` and `reserved ≥ 0`, `onHand` can never go negative either. |
| **Movement types** `INITIAL, RESTOCK, ADJUSTMENT, RESERVE, RELEASE, DEDUCT, RETURN`. | Purchases (`RESTOCK`) are kept apart from corrections (`ADJUSTMENT`), so reports can tell "we bought 50" from "5 were damaged". |
| **Two signed deltas per movement**: `onHandDelta`, `reservedDelta` (+ balances after). | `RESERVE` changes `reserved` but not `onHand`; one "quantity" field would be ambiguous. With signed deltas, the sum of all deltas of a variant **is** its current stock, so the log can be checked against the document without a direction table. |
| **`INITIAL` is automatic**: the first stock-in of a variant that has no movements yet is recorded as `INITIAL`; every later one is `RESTOCK`. No separate endpoint. | Products are created with stock 0 (`PRODUCT.md`). The user just "adds stock"; they cannot pick the wrong type. |
| **Manual adjustment = signed change** (`quantityChange: -3`) with a required `reasonCode` (`DAMAGED`, `LOST`, `FOUND`, `COUNT_CORRECTION`, `OTHER`) and an optional `note` (required for `OTHER`). | The server applies `-3` atomically, so it stays correct while orders reserve or ship in parallel. A "set to counted value" input would be wrong if an order moved stock between counting and saving. Preset codes let reports group losses by cause; the note keeps the details. |
| **`requestId` (UUID from the client) on restock and adjustment**, unique per shop. | A double tap or a retry after a timeout on a slow mobile connection would otherwise record `+10` twice. A repeat with the same `requestId` returns the first result instead of applying it again. |
| **Inventory writes never change the product's `version` or `updatedAt`**, and publish no `ProductChangedEvent`. | Otherwise every order confirm would make an open product edit form fail with `409 CONCURRENT_MODIFICATION`. The product lookup cache holds no stock (`PRODUCT.md` §7), so it does not need evicting. Product edits never touch `stock.*` (targeted `$set`, `PRODUCT.md` §1), so the two modules never overwrite each other. |
| **Low-stock threshold** = the variant's own `lowStockThreshold`, or if `null` the shop's `settings.defaultLowStockThreshold`. | Most sellers set one shop-wide number (Shop settings) and override only a few variants. Read through `ShopSettingsLookup.forShop(shopId)` (cached 10 min, evicted on `PUT /shop`), so a settings change is visible in the next list request. |
| **Stock status** of a variant: `OUT` if `available ≤ 0`; `LOW` if `0 < available ≤ threshold`; otherwise `IN_STOCK`. | Based on `available`, not `onHand`: reserved units cannot be sold again. Threshold `0` means "warn only when sold out" (this is what the Settings page hint says). |
| **Limits**: one movement moves 1–100,000 units; `onHand` ≤ 1,000,000. | Stock fields are `Int32`; the caps keep every sum far from overflow and catch typos like `100000000`. |
| **Inactive variants and archived products can still be restocked, adjusted and reserved.** | Leftover stock of a retired product must still be corrected (damaged, sold elsewhere). An order accepted before the product was retired must still be confirmable: the Order module blocks inactive variants when an order is **created**, not when it is confirmed. They are hidden from lists by default (`includeInactive=true` shows them). |
| **Returns restock per item**: the order's return action carries a `restock` choice per item; only items with `restock = true` get a `RETURN` movement. | A damaged return must not go back on the shelf (`SCHEMA.md` §9). |
| **Movement log visible to every `STOCK_VIEW` role** (also `PACKER`), including reasons and staff names. | Packers need to see why stock changed. Revisit if a shop asks to restrict it. |
| **One variant per request** for restock and adjustment. | Bulk restock (a supplier delivery for many variants) is deferred; it can be added later as a new endpoint without schema change. |
| **Order-driven operations** (`RESERVE`, `RELEASE`, `DEDUCT`, `RETURN`) have **no HTTP endpoint**. They are Java methods called by `OrderService` inside its transaction. | Stock must only move together with an order status change (`SCHEMA.md` §9). An endpoint would allow reserving stock without an order. |

---

## 2. Where stock lives

Inside each variant of `products` (`SCHEMA.md` §6). No schema change.

| Field | Type | Meaning |
|-------|------|---------|
| `variants[].stock.onHand` | Int32 | physical units on the shelf, `0`–`1,000,000` |
| `variants[].stock.reserved` | Int32 | units held by `CONFIRMED` / `PACKED` orders, `0`–`onHand` |
| `variants[].stock.available` | Int32 | `onHand − reserved`; what can still be sold |
| `variants[].lowStockThreshold` | Int32 / null | own threshold, `null` = shop default |

**Who writes what** (`ARCHITECTURE.md` §1): the product module writes `{0, 0, 0}` when it
creates a variant and never touches `stock.*` again. Inventory writes only `stock.*`, always
with `$inc` on all affected fields in one update, never with `$set` of a value read earlier.

### Changes needed in existing code

Built in the implementation step, listed here so nothing is forgotten:

1. **Reset a variant to the shop default threshold.** Today `PATCH /products/{id}/variants/{variantId}`
   treats `lowStockThreshold: null` as "keep current", so once set it can never go back to the
   shop default. Add `useShopDefaultThreshold: Boolean` to `product.dto.UpdateVariantRequest`;
   `true` → `$unset variants.$.lowStockThreshold` in `ProductService.updateVariant`. Sending it
   together with `lowStockThreshold` → `400 VALIDATION_FAILED`. Frontend variant editor
   (`features/products/components/VariantTable.tsx`, `variantRows.ts`): "Use shop default (N)"
   option. Update `PRODUCT.md` §5 and `API.md` §4.
2. **`InventoryUsageChecker implements ProductUsageChecker`** (in `inventory.service`): a product
   is "used" if any movement exists for `{shopId, productId}`. `ProductService` already injects
   all checkers, so delete then returns `409 PRODUCT_IN_USE`.
3. **`common.transaction.TransactionRunner`** (new): `run(Supplier<T>)` uses the existing
   `TransactionTemplate` bean (`common/config/MongoConfig.java`) and retries the whole block up
   to 3 times (10–50 ms random pause) when the cause chain contains a `MongoException` with the
   error label `TransientTransactionError` or `UnknownTransactionCommitResult`. Any other error
   is not retried. Spring's `@Transactional` never retries by itself (`SCHEMA.md` §11).
   Why a full retry is safe: after "commit result unknown" the first attempt may have committed.
   Every operation that runs in it is idempotent: restock/adjust through `requestId`, order
   operations through the order's status condition (a second run finds the order already moved
   and stops).
4. **Integers only in JSON**: Jackson's default `ACCEPT_FLOAT_AS_INT = true` silently turns
   `2.5` into `2`. Set `spring.jackson.deserialization.accept-float-as-int: false` in
   `application.yml`, so `2.5` → `400 VALIDATION_FAILED` everywhere (no other DTO expects
   decimals: money is integer poisha).
5. `ProductQueryRepository.deleteIfUnstocked` already refuses products with stock > 0. No change.
6. Docs: `SCHEMA.md` §7, §9 and §11 and `API.md` §5 now point here and use these type names.

---

## 3. `stock_movements` (append-only)

Never updated, never deleted. A mistake is fixed by a new `ADJUSTMENT`.

| Field | Type | Req | Notes |
|-------|------|-----|-------|
| `_id` | ObjectId | Y | |
| `shopId` | ObjectId | Y | from the JWT |
| `productId` | ObjectId | Y | |
| `variantId` | ObjectId | Y | |
| `sku` | String | Y | snapshot (SKUs are immutable, kept for exports) |
| `variantName` | String | Y | snapshot at the time of the movement |
| `type` | String | Y | `INITIAL`, `RESTOCK`, `ADJUSTMENT`, `RESERVE`, `RELEASE`, `DEDUCT`, `RETURN` |
| `onHandDelta` | Int32 | Y | signed change of `onHand` |
| `reservedDelta` | Int32 | Y | signed change of `reserved` |
| `onHandAfter` | Int32 | Y | balances right after this movement, from the updated document |
| `reservedAfter` | Int32 | Y | |
| `availableAfter` | Int32 | Y | |
| `reasonCode` | String | N | **required for `ADJUSTMENT`**: `DAMAGED`, `LOST`, `FOUND`, `COUNT_CORRECTION`, `OTHER`; `null` otherwise |
| `note` | String | N | ≤ 200 chars; required when `reasonCode = OTHER`; optional for `ADJUSTMENT`, `RESTOCK`, `INITIAL`; not used for order movements |
| `orderId` | ObjectId | N | set for `RESERVE`, `RELEASE`, `DEDUCT`, `RETURN`; otherwise `null` |
| `orderNumber` | String | N | display snapshot, e.g. `SD-1042` |
| `requestId` | String | N | client UUID for restock / adjustment (idempotency) |
| `createdBy` | ObjectId | Y | → `users._id` |
| `createdByName` | String | Y | snapshot, so the log needs no join |
| `createdAt` | Date | Y | |

**Type → effect** (`q` > 0):

| type | `onHandDelta` | `reservedDelta` | available | Trigger |
|------|---------------|-----------------|-----------|---------|
| `INITIAL` | +q | 0 | +q | first stock-in of a variant |
| `RESTOCK` | +q | 0 | +q | stock-in (purchase, supplier delivery) |
| `ADJUSTMENT` | ±q | 0 | ±q | manual correction, `reasonCode` required |
| `RESERVE` | 0 | +q | −q | order → `CONFIRMED` |
| `RELEASE` | 0 | −q | +q | `CONFIRMED` / `PACKED` order → `CANCELLED` |
| `DEDUCT` | −q | −q | 0 | order → `SHIPPED` |
| `RETURN` | +q | 0 | +q | order → `RETURNED`, item restocked |

**Indexes** (every index starts with `shopId`, `SCHEMA.md` §10):

1. `{shopId: 1, variantId: 1, createdAt: -1}`: history of one variant.
2. `{shopId: 1, createdAt: -1}`: shop-wide log, date range.
3. `{shopId: 1, productId: 1, createdAt: -1}`: history of one product; `InventoryUsageChecker`.
4. `{shopId: 1, orderId: 1}`, partial (`orderId` exists): movements of one order.
5. `{shopId: 1, requestId: 1}` **unique**, partial (`requestId` exists): idempotency.

A `type` or `reasonCode` filter is applied on top of index 1, 2 or 3; one shop's movements are a small set.

---

## 4. Atomic operations

### One update per variant, conditional

Every stock change is one `findAndModify` on `products` with `returnNew = true` (the new
document gives the `*After` values for the movement):

```
filter: { _id: P, shopId: S, variants: { $elemMatch: { variantId: V, <condition> } } }
update: { $inc: { "variants.$.stock.onHand": a, "variants.$.stock.reserved": b, "variants.$.stock.available": a - b } }
```

| Operation | `<condition>` | `$inc` onHand / reserved / available |
|-----------|---------------|--------------------------------------|
| `INITIAL`, `RESTOCK`, `ADJUSTMENT +q`, `RETURN` | `stock.onHand ≤ 1,000,000 − q` | +q / 0 / +q |
| `ADJUSTMENT −q` | `stock.available ≥ q` | −q / 0 / −q |
| `RESERVE` | `stock.available ≥ q` | 0 / +q / −q |
| `RELEASE` | `stock.reserved ≥ q` | 0 / −q / +q |
| `DEDUCT` | `stock.reserved ≥ q` (then also `onHand ≥ q`) | −q / −q / 0 |

**Why this never goes negative under concurrency:** the check and the change are **one**
operation in the database. Two requests for the last unit: MongoDB applies them one after the
other on the document; the second no longer matches `available ≥ 1` and changes nothing. No
read-then-write gap exists that both could slip through.

**No match** (`null` result): re-read `{shopId, "variants.variantId": V}` to tell why: no such
variant in this shop → `404 NOT_FOUND`; otherwise `409 INSUFFICIENT_STOCK` (decrease, reserve,
release, deduct) or `409 STOCK_LIMIT_EXCEEDED` (increase). `productId` is always taken from this
lookup, never from the client.

### Which operations need a transaction, and why

| Operation | Transaction? | Why |
|-----------|--------------|-----|
| Restock (`INITIAL` / `RESTOCK`), adjustment | **Yes**, own transaction through `TransactionRunner` | Two documents change: the product (stock) and a new movement. If the server crashed between them, stock would change without a log entry, and "every change is logged" would be broken. |
| `RESERVE`, `RELEASE`, `DEDUCT`, `RETURN` | **Yes**, the **caller's** transaction (`@Transactional(propagation = MANDATORY)`) | They run inside the order status change: order status + every item's stock + one movement per item must commit or roll back together. An order with 3 items where item 3 is short: `INSUFFICIENT_STOCK` is thrown, and the reservations of items 1 and 2 are rolled back too. `MANDATORY` makes it an error to call them without a transaction. |
| Stock list, variant detail, movement history | No | Reads only. |

**Why keep the conditional filter inside a transaction?** A MongoDB transaction does not lock
what it reads. The filter is what really guarantees "never negative"; the transaction only
makes stock and log commit together. Two transactions writing the same product document → one
gets `WriteConflict` → `TransactionRunner` runs it again, now against the new numbers.

### Restock / adjustment step by step (inside one transaction)

1. `requestId` already in `stock_movements` for this shop? → same variant, type and quantity:
   return the stored movement and the current stock (no change); different → `409 REQUEST_ID_REUSED`.
2. Restock only: any movement for this variant? No → type `INITIAL`, else `RESTOCK`.
3. Conditional `findAndModify` (table above).
4. Insert the movement with the `*After` values from step 3.

Race-free: two parallel "first" restocks both write the same product document in step 3, so one
gets `WriteConflict`, is retried, and then sees the other's movement in steps 1–2. The unique
index on `requestId` is the last line of defence: `DuplicateKeyException` → re-read and return
the stored movement.

### Order operations (Java API, used by the Order module)

```java
// all: @Transactional(propagation = MANDATORY), shopId from CurrentUser via the caller
List<StockMovement> reserve(String shopId, List<StockLine> lines, OrderRef order)
List<StockMovement> release(String shopId, List<StockLine> lines, OrderRef order)
List<StockMovement> deduct (String shopId, List<StockLine> lines, OrderRef order)
List<StockMovement> restockReturn(String shopId, List<StockLine> lines, OrderRef order)

record StockLine(String variantId, int quantity)
record OrderRef(String orderId, String orderNumber)
```

Lines with the same `variantId` are merged first (one update per variant).

---

## 5. Endpoints

Base `/api/v1`. The shop is always the logged-in user's (JWT). No `shopId` in any path, query
or body; one sent in a body is ignored.

| Method | Path | Permission | Request → Response |
|--------|------|------------|--------------------|
| GET | `/inventory` | `STOCK_VIEW` | `?q=&stockStatus=ALL\|LOW\|OUT\|LOW_OR_OUT&includeInactive=false&sort=name,asc\|available,asc&page=&size=` → page of `StockRow` (one row per variant) |
| GET | `/inventory/low-stock` | `STOCK_VIEW` | `?page=&size=` → page of `StockRow`: `LOW_OR_OUT`, active variants of active products only, lowest `available` first (dashboard card) |
| GET | `/inventory/variants/{variantId}` | `STOCK_VIEW` | → `VariantStockDetail {row: StockRow, recentMovements: [MovementResponse] (last 10)}` |
| POST | `/inventory/variants/{variantId}/restock` | `STOCK_ADJUST` | `{quantity (1–100000), note? (≤200), requestId (UUID)}` → `201 StockChangeResponse` (type `INITIAL` or `RESTOCK`) |
| POST | `/inventory/variants/{variantId}/adjustments` | `STOCK_ADJUST` | `{quantityChange (≠ 0, −100000…100000), reasonCode (required), note? (≤200, required for OTHER), requestId (UUID)}` → `201 StockChangeResponse` |
| GET | `/inventory/movements` | `STOCK_VIEW` | `?variantId=&productId=&type=&reasonCode=&orderId=&from=&to=&page=&size=` → page of `MovementResponse`, newest first |

```
StockRow            {productId, productName, coverImageUrl, productStatus, variantId, sku, variantName,
                     active, onHand, reserved, available,
                     lowStockThreshold (effective), thresholdSource: VARIANT | SHOP,
                     stockStatus: IN_STOCK | LOW | OUT}
StockChangeResponse {movement: MovementResponse, stock: {onHand, reserved, available}}
MovementResponse    {id, productId, variantId, sku, variantName, type, onHandDelta, reservedDelta,
                     onHandAfter, reservedAfter, availableAfter, reasonCode, note, orderId, orderNumber,
                     createdBy: {id, name}, createdAt}
```

- `q`: exact SKU (uppercased) or product name prefix, same as `GET /products`.
- `page` 0-based, `size` default 20, max 100. Unknown `stockStatus` / `sort` / `type` / `reasonCode` → `400`.
- `from` / `to`: ISO dates, `from` inclusive, `to` exclusive.
- An id filter of another shop (`variantId`, `productId`, `orderId` on `/movements`) gives an
  **empty page**, not an error (lists filter; they do not look up a single record).

### Stock list query

Aggregation on `products` (one shop's documents only):

1. `$match {shopId, status: ACTIVE (unless includeInactive), q}`: uses product indexes #1/#3.
2. `$unwind: "$variants"`, then `$match {"variants.active": true}` (unless `includeInactive`).
3. `$addFields threshold: {$ifNull: ["$variants.lowStockThreshold", <shop default>]}`. The shop
   default is put in as a literal number from `ShopSettingsLookup`.
4. `$match` on `stockStatus` (`$expr` comparing `available` with `threshold`).
5. `$sort`, then `$facet {rows: [$skip, $limit], total: [$count]}`.

Comparing two fields cannot use an index (`SCHEMA.md` §6), but step 1 already limits the scan to
one shop's products. Fine for a few thousand variants per shop.

### Errors

| Situation | Status | `code` |
|-----------|--------|--------|
| Bean Validation (quantity range, `quantityChange = 0`, missing `reasonCode`, `OTHER` without note, decimal number, bad UUID) | 400 | `VALIDATION_FAILED` (`fieldErrors`) |
| Variant id unknown **or from another shop** | 404 | `NOT_FOUND` |
| Decrease / reserve / release / deduct more than allowed | 409 | `INSUFFICIENT_STOCK` (message names SKU, requested, available) |
| Increase above 1,000,000 on hand | 409 | `STOCK_LIMIT_EXCEEDED` |
| Same `requestId` with a different change | 409 | `REQUEST_ID_REUSED` |
| Missing permission | 403 | `FORBIDDEN` |

`INSUFFICIENT_STOCK`, `STOCK_LIMIT_EXCEEDED` and `REQUEST_ID_REUSED` are `BusinessRuleException`s
(`common.error`); not found is `ResourceNotFoundException`.

---

## 6. Rules

- **Quantities are whole numbers.** `quantity` ≥ 1, `quantityChange` ≠ 0, both ≤ 100,000 in
  absolute value. JSON `2.5` → `400` (§2 change 4).
- **No change may make stock negative**: decreases need `available ≥ q`, so `onHand` and
  `reserved` stay ≥ 0 and `reserved ≤ onHand`.
- **`reasonCode` is required for `ADJUSTMENT`**; `note` (trimmed, ≤ 200) is required when it is `OTHER`.
- **Multi-tenancy:** `shopId` only from the JWT (`CurrentUser.shopId()`). Every query starts with
  `TenantCriteria.of(shopId)`. Another shop's variant → `404` (never `403`: ids must not be
  probeable). The variant is always found by `{shopId, "variants.variantId"}` (product index #2).
- **Ignored input:** `shopId`, `productId`, `onHand`, `reserved`, `available`, `type` in restock /
  adjustment bodies (unknown fields are ignored; the server decides all of them).
- Inactive variants / archived products: restock, adjustment and reserve allowed; hidden from lists by default.
- The log is never edited. Wrong restock of +10? Post an `ADJUSTMENT −10` with `COUNT_CORRECTION` and a note.

---

## 7. Backend structure

```
com.sellerdesk.inventory
├── controller  InventoryController
├── service     InventoryService (restock, adjust, reserve/release/deduct/restockReturn, lists),
│               StockStatusCalculator (effective threshold + status), InventoryUsageChecker,
│               InventoryErrors
├── repository  StockRepository (MongoTemplate on `products`: conditional $inc on stock.* only,
│               stock list aggregation), StockMovementRepository (Spring Data, shopId methods only),
│               StockMovementQueryRepository (filters + paging)
├── dto         RestockRequest, AdjustmentRequest, StockRow, VariantStockDetail,
│               StockChangeResponse, MovementResponse
└── model       StockMovement, MovementType, AdjustmentReason
com.sellerdesk.common.transaction
    TransactionRunner
```

Dependencies: `inventory → product` (reads `Product`/`Variant`, implements
`ProductUsageChecker`), `inventory → shop` (`ShopSettingsLookup`). Later `order → inventory`.
Nothing points back.

---

## 8. Tests

**Unit** (`InventoryServiceTest`, `StockStatusCalculatorTest`, `TransactionRunnerTest`):
- each type produces the right deltas and conditions; `reasonCode` required for `ADJUSTMENT`, note required for `OTHER`;
- first stock-in → `INITIAL`, later → `RESTOCK`;
- `requestId` repeat returns the stored result; same id, other change → `REQUEST_ID_REUSED`;
- effective threshold: variant value wins, `null` → shop default; status boundaries
  (`available` 0 → `OUT`; = threshold → `LOW`; threshold + 1 → `IN_STOCK`; threshold 0 → never `LOW`);
- repositories are always called with the `TenantContext` shop;
- `TransactionRunner` retries a `TransientTransactionError` up to 3 times, then gives up;
  never retries other exceptions (e.g. `INSUFFICIENT_STOCK`).

**Integration** (`InventoryIntegrationTest extends MongoIntegrationTestBase`):
- restock and adjustment: stock numbers and `*After` values correct; movement written;
- decrease below `available` → `409`, stock and log unchanged;
- rollback: movement insert forced to fail (spy) → the `$inc` is rolled back, stock unchanged;
- product edit / add variant / reorder images after a stock change → stock intact; inventory
  writes leave the product `version` unchanged;
- low-stock list: variant threshold vs shop default; changing `defaultLowStockThreshold` via
  `PUT /shop` changes the list on the next request; `includeInactive`; sorting; paging;
- movement filters (`variantId`, `productId`, `type`, `reasonCode`, `orderId`, `from`/`to`) and paging, newest first;
- `2.5` as quantity → `400`;
- reserve on an inactive variant / archived product succeeds;
- product with a movement → `DELETE /products/{id}` → `409 PRODUCT_IN_USE`.

**Concurrency** (real MongoDB, threads released together by a `CountDownLatch`):
- **Last unit:** stock `{1, 0, 1}`, 10 threads each run
  `transactionRunner.run(() -> inventoryService.reserve(shopId, [line(V, 1)], order_i))`
  → exactly **1** succeeds, **9** get `INSUFFICIENT_STOCK`; final stock `{1, 1, 0}`; exactly
  **1** `RESERVE` movement.
- 5 parallel `ADJUSTMENT −1` on `available = 3` → exactly 3 succeed; `available = 0`.
- 20 parallel restocks of `+1` on a new variant → `onHand = 20`; the 20 `onHandAfter` values are
  exactly 1…20; exactly one `INITIAL`.
- 5 parallel restocks with the **same** `requestId` → one movement, `onHand` increased once.

**Permissions:** `PACKER`, `MODERATOR`, `ACCOUNTANT` (have `STOCK_VIEW`) can read but get `403`
on restock / adjustment; a user without `STOCK_VIEW` gets `403` on reads; `PLATFORM_ADMIN` → `403`.

**`TenantIsolationTest` → `@Nested class Inventory`** (`assertHiddenFromOtherShop`):
- A with B's `variantId` → `404` on `GET /inventory/variants/{id}`, `POST .../restock`,
  `POST .../adjustments`; B's stock unchanged; no movement written for either shop.
- `GET /inventory`, `/inventory/low-stock`, `/inventory/movements` show only A's data, also when
  `q` matches B's SKU/name and when filtering by B's `variantId` / `productId` (empty page).
- `"shopId": "<B>"` in a restock / adjustment body → applied to A's own variant only.
- A `requestId` already used by B does not block A (uniqueness is per shop).

A module is done only when these pass (CLAUDE.md).

---

## 9. Resolved questions

Decided on 2026-11-28 (all recommended defaults):

1. **Reserve on an inactive variant / archived product:** allowed (§1). The Order module blocks
   inactive variants at order **creation**.
2. **Returns:** per-item `restock` flag on the order's return action (§1, `SCHEMA.md` §9).
3. **Adjustment reason:** preset `reasonCode` + optional `note`, note required for `OTHER` (§3).
4. **Limits:** 1,000,000 units on hand, 100,000 per movement (§1).
5. **Log visibility:** every `STOCK_VIEW` role, including `PACKER` (§1).
6. **Bulk restock:** deferred; one variant per request in Phase 1.
7. **Frontend:** separate task after the backend is built and tested.
