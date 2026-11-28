# SellerDesk — MongoDB Schema (Phase 1)

Collections: `shops`, `users`, `otp_tokens`, `products` (variants embedded),
`stock_movements`, `customers`, `orders` (items embedded as snapshots).

Legend: **Req** = required (`Y`) or optional (`N`). Types are BSON types.

---

## 1. Conventions

| Topic | Rule | Why |
|-------|------|-----|
| IDs | `_id` is `ObjectId`. Java field type is `String`. | Spring converts `@Id String` to `ObjectId` automatically. |
| References | Stored as `ObjectId`; in Java `String` + `@Field(targetType = FieldType.OBJECT_ID)`. | Without this, Spring stores non-`@Id` strings as **strings**, and a query with an `ObjectId` silently matches nothing. One type everywhere avoids this classic bug. |
| Tenant field | Every tenant-owned document has `shopId: ObjectId` (required). | Multi-tenancy rule. |
| Timestamps | `createdAt`, `updatedAt`: `Date` (UTC), set by Spring auditing. | |
| Optimistic lock | `version: Int64` (`@Version`) on `shops`, `users`, `products`, `customers`, `orders`. | Two staff editing the same order: the second save gets `409 CONCURRENT_MODIFICATION` instead of silently overwriting. |
| Money | `Int64` in **poisha** (1 BDT = 100 poisha). | Floating point (`double`) cannot represent 0.10 exactly. `BigDecimal` works but Spring Data stores it as a **string** by default (sorting/summing breaks) unless every field is annotated for `Decimal128`. Integers are simplest and exact. |
| Phone | Bangladeshi mobile normalized to 11 digits `01XXXXXXXXX` before saving, always through `common.phone.BdPhone` (DTOs validate with `@BdMobile`). `+8801...`, `8801...`, `00880...`, separators and Bangla digits are converted. Stored regex `^01[3-9]\d{8}$`. | Uniqueness on phone only works if every copy has the same format. |
| Email | Trimmed, lowercased before saving. | Same reason. |
| Enums | Stored as strings (`"CONFIRMED"`). | Readable in Atlas; safe to reorder in Java. |
| Deletion | No hard delete of business data in Phase 1. Use `status` (`ARCHIVED`, `DISABLED`). Only exception: a product never used by any order or stock movement may be deleted (`docs/modules/PRODUCT.md`). | Orders and movements reference products/customers; deleting would break history. An unused product has no history to break. |

### Embedded vs referenced: the rule we follow

Embed when the child is **bounded**, **always read with the parent**, and **must change
atomically with it**. Reference when the child **grows without limit** or is **shared /
queried on its own**.

| Data | Choice | Reason |
|------|--------|--------|
| Product → variants | **Embed** | Bounded (cap: 100 per product). Always shown together. Stock update on one variant is atomic on one document. |
| Variant → stock levels | **Embed** (in variant) | Inventory list = read products, no join. Reserve is one atomic conditional update. |
| Stock movement log | **Separate collection** | Grows forever. Would eventually hit the 16 MB document limit if embedded. Queried by date/type on its own. |
| Order → items | **Embed as snapshot** | Bounded. An order must show what was sold at that time even if the product is later renamed, repriced or archived. |
| Order → customer | **Reference** (`customerId`) **+ snapshot** (name, phone, address) | Reference for "this customer's orders". Snapshot so the printed address is the address used at that time. |
| Order → status history | **Embed** | Bounded (≤ ~8 entries). Always shown with the order. |
| Customer → orders | **Not embedded**; query `orders` by `customerId` | Unbounded. |
| Customer → order stats | **Embed counters** (denormalized) | Customer list can show "12 orders, 2 returned" without aggregating orders. Updated in the same transaction as the order status change. |
| User → shop | **Reference** (`shopId`) | Users are queried per shop. |
| Customer → addresses | **Embed** | Bounded (cap: 5). |

---

## 2. `shops`

Not tenant-owned in the usual sense: the document's `_id` **is** the `shopId`.

| Field | Type | Req | Notes |
|-------|------|-----|-------|
| `_id` | ObjectId | Y | = `shopId` everywhere else |
| `name` | String | Y | 2–100 chars |
| `ownerUserId` | ObjectId | Y | → `users._id` |
| `phone` | String | Y | normalized |
| `email` | String | N | business contact email |
| `address` | Object `Address` | N | see §5 |
| `facebookPageUrl` | String | N | |
| `logo` | Object | N | `{url, publicId, width, height, bytes, format}`; file in Cloudinary under `sellerdesk/{shopId}/logo/{random id}`. `publicId` is never sent to clients. |
| `status` | String | Y | `ACTIVE`, `SUSPENDED` |
| `settings.deliveryChargeInsideDhaka` | Int64 | Y | poisha, `0`–`500000`, default `6000` (৳60) |
| `settings.deliveryChargeOutsideDhaka` | Int64 | Y | poisha, `0`–`500000`, default `12000` (৳120) |
| `settings.defaultLowStockThreshold` | Int32 | Y | `0`–`100000`, default `5` |
| `settings.orderNumberPrefix` | String | Y* | 2–6 uppercase letters/digits, default `SD`. *Missing in shops created before it existed: read as `SD` (see below). |
| `orderSeq` | Int64 | Y | last used order number; incremented atomically |
| `version`, `createdAt`, `updatedAt` | | Y | |

**Indexes:** `{ ownerUserId: 1 }` unique.

**Writes.** Settings and logo changes use a targeted `$set` / `$unset` of their own fields
(`ShopQueryRepository`), never a full-document `save`: a `save` of a shop loaded before an order
was created would write back the old `orderSeq` and reuse an order number.

**Defaults for existing shops.** New settings fields get their default **on read**: the
`ShopSettings` record replaces a missing (`null`) `orderNumberPrefix` with `SD`. No migration
script; the next `PUT /shop` stores the field. `logo` is optional, so a missing field means "no logo".

**Logo files.** Same pipeline as product images (`docs/modules/PRODUCT.md` §4): orphan cleanup task
before the upload, `$set` of the new logo + `REMOVED` task for the old file in one transaction.

**Order numbers.** Per-shop sequence: `findAndModify({_id: shopId}, {$inc: {orderSeq: 1}}, returnNew)`.
Atomic, no extra `counters` collection. Done **outside** any transaction: if it were inside,
two concurrent order creations would conflict on the shop document and one would have to retry.
Trade-off: a failed order creation leaves a gap in numbers (#1041 then #1043). Acceptable.

---

## 3. `users`

One user belongs to **at most one shop** in Phase 1.

| Field | Type | Req | Notes |
|-------|------|-----|-------|
| `_id` | ObjectId | Y | |
| `email` | String | Y | lowercase; login identity |
| `passwordHash` | String | N | BCrypt hash. Never returned by the API. |
| `name` | String | Y | |
| `phone` | String | N | normalized |
| `shopId` | ObjectId | N | required for all roles except `PLATFORM_ADMIN` (null) |
| `role` | String | Y | `PLATFORM_ADMIN`, `SHOP_OWNER`, `MODERATOR`, `PACKER`, `ACCOUNTANT` |
| `permissions` | Array\<String\> | Y | effective permissions; starts as role defaults (`SECURITY.md` §3) |
| `status` | String | Y | `PENDING_VERIFICATION` (registered, email not verified yet), `ACTIVE`, `DISABLED`. Staff are created `ACTIVE` when they accept an invitation (`staff_invitations`, `modules/STAFF.md`). Only `ACTIVE` users can log in. |
| `tokenVersion` | Int32 | Y | starts `0`; incremented on logout / disable / role or permission change / password change. Invalidates all tokens. |
| `invitedBy` | ObjectId | N | → `users._id` |
| `lastLoginAt` | Date | N | |
| `version`, `createdAt`, `updatedAt` | | Y | |

**Indexes:**
- `{ email: 1 }` **unique** (global: one account per email).
- `{ shopId: 1, role: 1 }`: staff list.

**Trade-off:** global unique email means a person cannot be staff in two shops at once.
Supporting that needs a separate `memberships` collection and a "choose shop" step at login.
Deferred; the `shopId` claim design does not block adding it later.

### `staff_invitations` (tenant-owned)

Pending, accepted and cancelled staff invitations. Fields and indexes: `modules/STAFF.md` §2
(`shopId`, `email`, `role`, `permissions`, `status` `PENDING`/`ACCEPTED`/`CANCELLED`, `tokenHash`
(SHA-256, never the token), `expiresAt`, `invitedBy`, `sendCount`, `lastSentAt`, `recentSends`,
`acceptedUserId`, `version`). The `users` document is only created on accept.

---

## 4. `otp_tokens`

| Field | Type | Req | Notes |
|-------|------|-----|-------|
| `_id` | ObjectId | Y | |
| `email` | String | Y | lowercase |
| `purpose` | String | Y | `EMAIL_VERIFICATION`, `PASSWORD_RESET`. A code only works for its own purpose. |
| `codeHash` | String | Y | BCrypt hash of the 6-digit code. Plain code is **never** stored. |
| `attempts` | Int32 | Y | verify tries so far; max 5. Incremented atomically **before** the code is compared. |
| `expiresAt` | Date | Y | `createdAt + 10 min`. After this the code is rejected. |
| `purgeAt` | Date | Y | `createdAt + 1 h`. TTL field: MongoDB deletes the document after this. |
| `consumedAt` | Date | N | set when used successfully |
| `createdAt` | Date | Y | |

**Indexes:**
- `{ purgeAt: 1 }` with **`expireAfterSeconds: 0`**: TTL index. MongoDB deletes the
  document once `purgeAt` is in the past.
- `{ email: 1, purpose: 1, createdAt: -1 }`: find latest OTP; count requests per hour for rate limiting.

**Why two dates:** the "max 5 codes per hour" limit counts this collection's documents from the
last hour. If MongoDB deleted a document at `expiresAt` (10 min), the count would forget it.
So validity (`expiresAt`, checked by the service) and cleanup (`purgeAt`, TTL) are separate.

**TTL limitation (important):** the TTL background job runs about **every 60 seconds** and
can lag further under load. So the TTL index is **only cleanup**. The service always checks
`expiresAt > now && consumedAt == null && attempts < 5` itself.

**Only the newest code counts:** verify always loads the latest document for
`{email, purpose}`. Requesting a new code makes every older one useless without deleting it.

---

## 5. Shared embedded type: `Address`

| Field | Type | Req | Notes |
|-------|------|-----|-------|
| `line` | String | Y | house/road/area free text |
| `area` | String | N | thana / upazila |
| `district` | String | Y | |
| `zone` | String | Y | `INSIDE_DHAKA`, `OUTSIDE_DHAKA` (decides default delivery charge) |
| `note` | String | N | landmark |

---

## 6. `products` (variants embedded)

| Field | Type | Req | Notes |
|-------|------|-----|-------|
| `_id` | ObjectId | Y | |
| `shopId` | ObjectId | Y | |
| `name` | String | Y | |
| `nameLower` | String | Y | lowercase copy for prefix search |
| `description` | String | N | |
| `category` | String | N | free text in Phase 1 (display form) |
| `categoryKey` | String | N | lowercase `category`, for filter and distinct |
| `images` | Array\<ProductImage\> | Y | 0–8, first = cover: `{imageId, url, publicId, width, height, bytes, format}`. Uploaded to Cloudinary through the backend (`docs/modules/PRODUCT.md` §4). |
| `status` | String | Y | `ACTIVE`, `ARCHIVED` |
| `minPrice`, `maxPrice` | Int64 | N | poisha, over active variants; recomputed on every variant write (sort, price range) |
| `variants` | Array\<Variant\> | Y | 1–100 items |
| `version`, `createdAt`, `updatedAt`, `createdBy` | | Y | |

**`Variant`**

| Field | Type | Req | Notes |
|-------|------|-----|-------|
| `variantId` | ObjectId | Y | generated by the app; stable forever |
| `sku` | String | Y | uppercase; unique per shop; immutable |
| `name` | String | Y | e.g. "Red / M"; defaults to the attribute values joined by " / " |
| `attributes` | Object (map String→String) | N | `{ "color": "Red", "size": "M" }` |
| `price` | Int64 | Y | selling price, poisha |
| `costPrice` | Int64 | N | poisha |
| `lowStockThreshold` | Int32 | N | falls back to shop default |
| `active` | Boolean | Y | inactive variants cannot be ordered; never removed (old orders refer to them) |
| `stock.onHand` | Int32 | Y | ≥ 0; physical units |
| `stock.reserved` | Int32 | Y | ≥ 0; held by CONFIRMED/PACKED orders |
| `stock.available` | Int32 | Y | **always = onHand − reserved** |

**Why store `available` if it can be computed?** To make reservation one atomic, index-free
conditional update:

```
filter: { _id: P, shopId: S, variants: { $elemMatch: { variantId: V, "stock.available": { $gte: q } } } }
update: { $inc: { "variants.$.stock.reserved": q, "variants.$.stock.available": -q } }
```

If `modifiedCount == 0`, there was not enough stock. No read-then-write race: two orders
grabbing the last unit cannot both succeed. Expressing `onHand − reserved ≥ q` without the
stored field would need `$expr` inside `$elemMatch`, which MongoDB does not support well.
Cost: three numbers must stay consistent. Rule: only `InventoryService` writes `stock.*`, and
every update changes them together.

**Indexes:**
- `{ shopId: 1, "variants.sku": 1 }` **unique**: SKU unique per shop.
- `{ shopId: 1, status: 1, nameLower: 1 }`: list and prefix search.
- `{ shopId: 1, "variants.variantId": 1 }`: lookup by variant (orders, adjustments).
- `{ shopId: 1, status: 1, categoryKey: 1, nameLower: 1 }`: category filter, distinct categories.
- `{ shopId: 1, status: 1, updatedAt: -1 }`: default sort "recently updated".

**Lost-update rule:** after create, the product module never saves the whole document.
It uses targeted `$set` updates with `{_id, shopId, version}` in the filter, so it can never
write back stale `stock.*` numbers over a concurrent Inventory `$inc`.

**MongoDB limitation: unique index on an array field.** A unique multikey index stops two
**different** products from sharing a SKU, but it does **not** stop the **same** product
from having two variants with the same SKU. The service must check duplicates inside one
product before saving.

**Search limitation:** case-insensitive "contains" search (`/shirt/i`) cannot use an index
efficiently. Phase 1 uses prefix search on `nameLower` (`^shirt`), which can. If sellers need
full-text search later, use Atlas Search (separate index, no schema change).

**Low-stock filter:** comparing two fields (`available ≤ lowStockThreshold`) cannot use an
index. It runs as an aggregation (`$match shopId` → `$unwind variants` → `$match $expr`).
Fine because it only scans one shop's products.

**Contention trade-off:** all variants of a product share one document, so two orders for
different sizes of the same hot product write the same document. Inside transactions this
causes `WriteConflict` and a retry (§11). Acceptable at F-commerce volumes. If it ever
becomes a bottleneck, stock can move to its own `inventory_items` collection.

---

## 7. `stock_movements` (append-only log)

Full design: `modules/INVENTORY.md` §3–§4 (built: `inventory.model.StockMovement`). Summary:

| Field | Type | Req | Notes |
|-------|------|-----|-------|
| `_id` | ObjectId | Y | |
| `shopId` | ObjectId | Y | |
| `productId` | ObjectId | Y | |
| `variantId` | ObjectId | Y | |
| `sku` | String | Y | snapshot |
| `variantName` | String | Y | snapshot |
| `type` | String | Y | `INITIAL`, `RESTOCK`, `ADJUSTMENT`, `RESERVE`, `RELEASE`, `DEDUCT`, `RETURN` |
| `onHandDelta` | Int32 | Y | signed change of `onHand` |
| `reservedDelta` | Int32 | Y | signed change of `reserved` |
| `onHandAfter`, `reservedAfter`, `availableAfter` | Int32 | Y | balances after the change (from `findAndModify` returning the new document) |
| `reasonCode` | String | N | `ADJUSTMENT` only, required: `DAMAGED`, `LOST`, `FOUND`, `COUNT_CORRECTION`, `OTHER` |
| `note` | String | N | ≤ 200; required when `reasonCode = OTHER` |
| `orderId` | ObjectId | N | for order-driven movements |
| `orderNumber` | String | N | display snapshot, e.g. `SD-1042` |
| `requestId` | String | N | client UUID for restock / adjustment (idempotency) |
| `createdBy` | ObjectId | Y | → `users._id` |
| `createdByName` | String | Y | snapshot |
| `createdAt` | Date | Y | |

**`type` values and their effect** (`q` > 0)

| type | onHand | reserved | available | Trigger |
|------|--------|----------|-----------|---------|
| `INITIAL` | +q | | +q | first stock-in of a variant (automatic; products are created with stock 0) |
| `RESTOCK` | +q | | +q | stock-in (purchase) |
| `ADJUSTMENT` | ±q | | ±q | manual correction, `reasonCode` required; a decrease cannot make available < 0 |
| `RESERVE` | | +q | −q | order → CONFIRMED |
| `RELEASE` | | −q | +q | CONFIRMED/PACKED order → CANCELLED |
| `DEDUCT` | −q | −q | | order → SHIPPED |
| `RETURN` | +q | | +q | order → RETURNED with `restock = true` |

**Indexes:**
- `{ shopId: 1, variantId: 1, createdAt: -1 }`: history of one variant.
- `{ shopId: 1, createdAt: -1 }`: shop-wide log with date filter.
- `{ shopId: 1, productId: 1, createdAt: -1 }`: history of one product; "is this product used?".
- `{ shopId: 1, orderId: 1 }`, partial (`orderId` exists): movements of one order.
- `{ shopId: 1, requestId: 1 }` **unique**, partial (`requestId` exists): idempotency.

Never updated or deleted. Corrections are new movements.

---

## 8. `customers`

| Field | Type | Req | Notes |
|-------|------|-----|-------|
| `_id` | ObjectId | Y | |
| `shopId` | ObjectId | Y | |
| `name` | String | Y | |
| `nameLower` | String | Y | for prefix search |
| `phone` | String | Y | normalized; unique per shop |
| `altPhone` | String | N | |
| `facebookProfileUrl` | String | N | |
| `addresses` | Array\<Address\> | N | max 5; first is default |
| `note` | String | N | |
| `tags` | Array\<String\> | N | e.g. "VIP" |
| `stats.totalOrders` | Int32 | Y | orders created |
| `stats.deliveredOrders` | Int32 | Y | |
| `stats.returnedOrders` | Int32 | Y | |
| `stats.cancelledOrders` | Int32 | Y | |
| `stats.totalSpent` | Int64 | Y | poisha, sum of delivered order totals |
| `stats.lastOrderAt` | Date | N | |
| `version`, `createdAt`, `updatedAt`, `createdBy` | | Y | |

**Indexes:**
- `{ shopId: 1, phone: 1 }` **unique**: the same phone can exist in two shops, but only once per shop.
- `{ shopId: 1, nameLower: 1 }`: prefix search.
- `{ shopId: 1, createdAt: -1 }`: default list.

Stats are denormalized: fast to read, but they must be updated in the same transaction as
the order status change, or they drift. A rebuild-from-orders admin task can repair them if needed.

---

## 9. `orders` (items embedded as snapshots)

| Field | Type | Req | Notes |
|-------|------|-----|-------|
| `_id` | ObjectId | Y | |
| `shopId` | ObjectId | Y | |
| `orderNumber` | Int64 | Y | per-shop sequence |
| `orderNumberPrefix` | String | Y | copy of `shops.settings.orderNumberPrefix` at creation; shown as `SD-1042`. A copy, so changing the shop's prefix never renames existing orders. |
| `customerId` | ObjectId | Y | |
| `customer.name` | String | Y | snapshot |
| `customer.phone` | String | Y | snapshot |
| `customer.address` | Address | Y | snapshot, delivery address |
| `items` | Array\<OrderItem\> | Y | 1–50 items |
| `subtotal` | Int64 | Y | Σ lineTotal |
| `deliveryCharge` | Int64 | Y | ≥ 0 |
| `discount` | Int64 | Y | ≥ 0, ≤ subtotal |
| `total` | Int64 | Y | subtotal + deliveryCharge − discount |
| `advancePaid` | Int64 | Y | ≥ 0 (bKash/Nagad advance), ≤ total |
| `codAmount` | Int64 | Y | total − advancePaid; what the courier must collect |
| `status` | String | Y | `PENDING`, `CONFIRMED`, `PACKED`, `SHIPPED`, `DELIVERED`, `RETURNED`, `CANCELLED` |
| `statusHistory` | Array | Y | `{ from, to, at, by, note }` |
| `source` | String | Y | `FACEBOOK_PAGE`, `MESSENGER`, `PHONE`, `OTHER` |
| `note` | String | N | internal note |
| `shipping.courierName` | String | N | free text (courier integration is Phase 2) |
| `shipping.trackingId` | String | N | free text |
| `cancelReason` | String | N | |
| `returnReason` | String | N | |
| `createdBy` | ObjectId | Y | |
| `version`, `createdAt`, `updatedAt` | | Y | |

**`OrderItem`** (snapshot, never re-read from product)

| Field | Type | Req |
|-------|------|-----|
| `productId` | ObjectId | Y |
| `variantId` | ObjectId | Y |
| `sku` | String | Y |
| `productName` | String | Y |
| `variantName` | String | Y |
| `unitPrice` | Int64 | Y |
| `quantity` | Int32 | Y (1–999) |
| `lineTotal` | Int64 | Y |

**Indexes:**
- `{ shopId: 1, orderNumber: 1 }` **unique**.
- `{ shopId: 1, status: 1, createdAt: -1 }`: pipeline tabs ("all PACKED orders").
- `{ shopId: 1, createdAt: -1 }`: default list, date range.
- `{ shopId: 1, customerId: 1, createdAt: -1 }`: customer order history.
- `{ shopId: 1, "customer.phone": 1 }`: search orders by phone.

### Status transitions and side effects

| From → To | Stock effect | Customer stats | Editable after? |
|-----------|--------------|----------------|-----------------|
| (create) → PENDING | none | `totalOrders +1`, `lastOrderAt` | items, prices, address |
| PENDING → CONFIRMED | `RESERVE` each item | | address, note |
| CONFIRMED → PACKED | none | | address, note |
| PACKED → SHIPPED | `DEDUCT` each item | | note only |
| SHIPPED → DELIVERED | none | `deliveredOrders +1`, `totalSpent += total` | none |
| SHIPPED → RETURNED | `RETURN` if restock | `returnedOrders +1` | none |
| PENDING → CANCELLED | none | `cancelledOrders +1` | none |
| CONFIRMED / PACKED → CANCELLED | `RELEASE` each item | `cancelledOrders +1` | none |

Any other transition → `409 INVALID_STATUS_TRANSITION`.
The status update itself is conditional: `{ _id, shopId, status: <expected from> }`. If it
matches nothing, someone else already moved the order → `409`.

---

## 10. Indexes: how they are created

Indexes are declared on model classes (`@Indexed`, `@CompoundIndex`) and created at startup
with `spring.data.mongodb.auto-index-creation: true`.
- Simple, and indexes live next to the model.
- Trade-off: on a large production collection, building a new index at startup can be slow.
  Before real data grows, switch to creating indexes in a deploy step and turn auto-creation off.
- Index creation at startup also makes sure collections exist before the first transaction.

Every tenant index starts with `shopId`. Reason: every tenant query filters by `shopId`
(equality), and a compound index can only be used from its leftmost field. `shopId` first
means each shop's data is one contiguous range in the index.

---

## 11. Multi-document transactions

### Where they are needed

| Operation | Documents changed together |
|-----------|----------------------------|
| **Order confirm** | order status; each item's variant stock (`RESERVE`), possibly in several products; one `stock_movements` entry per item |
| Order ship | order; stock (`DEDUCT`) per item; movements |
| Order cancel (from CONFIRMED/PACKED) | order; stock (`RELEASE`); movements; customer stats |
| Order deliver / return | order; customer stats; (return) stock `RETURN` + movements |
| Order create | order; customer stats (and customer insert if new) |
| Restock, manual stock adjustment | product stock; one movement (`modules/INVENTORY.md` §4) |
| Shop registration | shop; owner user |
| Staff invitation accept | new staff user; invitation `ACCEPTED` |

Not needed: single-document updates (product edit, customer edit). One document write is
always atomic in MongoDB.

### How (Spring)

- Bean `MongoTransactionManager` → `@Transactional` on the service method.
- Inside, stock checks use the conditional update from §6. If any item has
  `modifiedCount == 0`, throw `ConflictException(INSUFFICIENT_STOCK)` → the whole
  transaction rolls back, including items already reserved.

### Limitations on MongoDB (and how we handle each)

| Limitation | Effect | Our handling |
|------------|--------|--------------|
| Requires a **replica set** | A plain standalone `mongod` cannot run transactions. | Atlas clusters (including free M0) are replica sets. Tests use Testcontainers `MongoDBContainer`, which starts a single-node replica set. Local Docker Mongo must be started with `--replSet`. |
| **No lock waiting**: concurrent writes to the same document fail fast with `WriteConflict` (`TransientTransactionError`) | Two orders confirming the same variant at the same moment: one aborts. | Retry the whole transaction on `TransientTransactionError` / `UnknownTransactionCommitResult` (random pause, at most 10 s / 50 attempts). Spring's `@Transactional` does **not** retry by itself (unlike the driver's `withTransaction`), so a small retry wrapper (`common.transaction.TransactionRunner`, `modules/INVENTORY.md` §2) runs the service call again. |
| Default **60 s** max lifetime | Long transactions are aborted. | Keep transactions small: only DB writes; send emails and do slow work after commit. |
| Slower than single-document writes | Extra round-trips, more server memory. | Use only where listed above. |
| `@Transactional` works only through the Spring proxy | Calling a `@Transactional` method from the same class skips the transaction. | Transactional methods are public service methods called from controllers or other services. |
| Reads outside the transaction session don't see uncommitted writes | | Use Spring repositories/`MongoTemplate` inside the method; Spring binds them to the session automatically. |
| Total oplog entry size limit (16 MB) per transaction | | Orders have ≤ 50 items; never close to the limit. |
| Order number `$inc` on `shops` | Hot document inside transactions → constant conflicts. | Done **before** and outside the transaction (§2). |

### Why not avoid transactions entirely?

We could reserve stock item by item with conditional updates and "compensate" (undo
previous reservations) on failure. That works without transactions but is easy to get wrong
(crash in the middle leaves stock reserved forever). Atlas supports transactions, order
confirm is not high-frequency, so transactions are the simpler, safer choice.
