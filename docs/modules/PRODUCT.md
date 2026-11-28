# Product module — Spec (Phase 1)

The shop's catalog: products with embedded variants (size, color...), each variant with its
own SKU and prices, plus product images stored in Cloudinary. Stock numbers live inside the
variants but are **owned by the Inventory module**; this module never changes them.

Status: **built** (backend and frontend). Backend package `com.sellerdesk.product` (+ `common.storage` for
images), frontend `features/products`.

Related: `API.md` §4 (endpoint summary), `SCHEMA.md` §6 (`products`), `SECURITY.md` §3
(permissions), `ARCHITECTURE.md` §1 (data ownership).

---

## 1. Decisions (and why)

| Decision | Why |
|----------|-----|
| **Money is `long` poisha** (৳1 = 100 poisha). Java `long`, BSON `Int64`, JSON integer. Range 0–100,000,000 (৳10 lakh). | `double` is binary floating point and cannot store 0.10 exactly (`0.1 + 0.2 = 0.30000000000000004`); totals drift by a poisha here and there. `BigDecimal` is exact, but Spring Data stores it as a **string** by default, which breaks sorting and `$sum`. A whole number of poisha is exact, fast, and MongoDB can sum it. The frontend converts `"450.50"` → `45050` by splitting the string (no float multiply) and formats for display. JS numbers are exact up to 2^53, far above our max. |
| **SKU**: trimmed, uppercased, `^[A-Z0-9][A-Z0-9._-]{0,39}$`, unique **per shop** across all products, **immutable** after the variant is created. | Labels, stock movements and spreadsheets refer to the SKU. To fix a typo, deactivate the variant and add a new one. |
| **Category is a plain field** (`category` for display + `categoryKey` lowercase for matching). `GET /products/categories` returns distinct values for autocomplete. | No CRUD screen, no orphan references, nothing to sync. A small shop has 5–20 categories that rarely change. If sellers later need ordering, renaming or sub-categories, introduce a `categories` collection and turn the field into a reference. |
| **Opening stock is entered through Inventory**, not here. A new variant starts with `stock = {0, 0, 0}`. | Every stock change must write a movement log entry, and only Inventory writes stock (`ARCHITECTURE.md` data ownership). Product can be built and shipped before Inventory. |
| **No full-document saves after create.** Edits are targeted `$set` updates through `MongoTemplate` with filter `{_id, shopId, version}` and `$inc: {version: 1}`. | Inventory changes `variants.$.stock.*` with `$inc` at any moment. `repository.save(product)` would write back the **whole** document, including stock numbers read seconds earlier, and silently undo a reservation (*lost update*). Targeted `$set` never touches `stock.*`. The `version` filter still catches two people editing the same product. |
| **Variants are never removed**, only `active = false`. 1–100 per product. | Orders and stock movements reference `variantId` forever. |
| Variant `name` optional; default = attribute values joined by `" / "` (`Red / M`). | Less typing. |
| Attribute combination is unique within a product. | Two "Red / M" variants are almost always a mistake. |
| **Archive** is the normal way to retire a product. **Hard delete** only for products never used by any order or stock movement. | Mistakes can be cleaned up; history is never broken. |
| Images are uploaded **through the backend** (multipart) to Cloudinary. | The backend checks type, size and count, and the Cloudinary secret never reaches the browser. |

---

## 2. Data

### `products` (tenant-owned)

| Field | Type | Req | Notes |
|-------|------|-----|-------|
| `_id` | ObjectId | Y | |
| `shopId` | ObjectId | Y | from the JWT |
| `name` | String | Y | 1–200 chars |
| `nameLower` | String | Y | lowercase copy for prefix search |
| `description` | String | N | ≤ 5000 chars |
| `category` | String | N | ≤ 50 chars, trimmed, single spaces |
| `categoryKey` | String | N | lowercase `category`; used for filter and distinct |
| `images` | Array\<ProductImage\> | Y | 0–8; first = cover |
| `status` | String | Y | `ACTIVE`, `ARCHIVED` |
| `minPrice`, `maxPrice` | Int64 | N | poisha, over **active** variants; recomputed on every variant write; `null` if none active |
| `variants` | Array\<Variant\> | Y | 1–100 |
| `version`, `createdAt`, `updatedAt`, `createdBy` | | Y | |

**`ProductImage`**: `imageId` (ObjectId, app-generated), `url` (Cloudinary `secure_url`),
`publicId` (never returned to clients), `width`, `height`, `bytes`, `format`.

**`Variant`**: `variantId` (ObjectId, app-generated, stable), `sku`, `name`, `attributes`
(map, ≤ 5 pairs, key ≤ 30, value ≤ 50), `price` (Int64, required), `costPrice` (Int64, optional),
`lowStockThreshold` (Int32 0–100000, optional → shop default), `active` (Boolean),
`stock {onHand, reserved, available}` (Int32; written only by Inventory, except the zeros on create).

**Indexes** (all start with `shopId`, `SCHEMA.md` §10):
1. `{shopId: 1, "variants.sku": 1}` **unique** — SKU per shop; also serves exact SKU search.
2. `{shopId: 1, "variants.variantId": 1}` — lookup by variant (orders, inventory, PATCH).
3. `{shopId: 1, status: 1, nameLower: 1}` — list, name prefix search, sort by name.
4. `{shopId: 1, status: 1, categoryKey: 1, nameLower: 1}` — category filter, distinct categories.
5. `{shopId: 1, status: 1, updatedAt: -1}` — default sort "recently updated".

Sort by `minPrice` or `createdAt` sorts in memory within one shop's matches. Fine for
hundreds to a few thousand products per shop.

### `image_cleanup_tasks` (owned by `common.storage`)

| Field | Type | Req | Notes |
|-------|------|-----|-------|
| `_id` | ObjectId | Y | |
| `shopId` | ObjectId | Y | for logging/tracing only |
| `publicId` | String | Y | Cloudinary file to delete |
| `reason` | String | Y | `ORPHAN_UPLOAD` (upload may not have been saved), `REMOVED` (image/product removed) |
| `attempts` | Int32 | Y | failed delete attempts |
| `notBefore` | Date | Y | the job ignores the task until then |
| `createdAt` | Date | Y | |

Index `{notBefore: 1}`. **Exception to the tenant rule:** the cleanup job sweeps all shops, so
its repository has no `shopId` parameter. Allowed because it lives in `common.storage` and
holds only file ids, never shop business data (`SECURITY.md` §4).

---

## 3. SKU uniqueness in MongoDB

- **Across products** — unique multikey index #1. Two racing requests: the database lets only
  one win; the other gets `DuplicateKeyException`, which `ProductService` turns into
  `409 SKU_ALREADY_EXISTS` (the generic `DUPLICATE_KEY` would not say which SKU).
- **Friendly error first** — before writing, one query
  `{shopId, "variants.sku": {$in: skus}, _id: {$ne: thisId}}` finds clashes and returns
  `409 SKU_ALREADY_EXISTS` with `fieldErrors` (`variants[2].sku`). The index stays the real guarantee.
- **Inside one product (MongoDB limitation)** — a unique multikey index only compares
  *different* documents, so one product with two `TSHIRT-M` variants would pass. Therefore:
  - Create: the service checks the request's SKUs with a `Set` → `400 VALIDATION_FAILED` on `variants[i].sku`.
  - Add variant: atomic `$push` with filter `{_id, shopId, version, "variants.sku": {$ne: SKU}}`.
    `modifiedCount == 0` → re-read to tell "duplicate SKU" from "version changed". No read-then-write race.
- Per shop: shop A and shop B may both use `TSHIRT-M`.

---

## 4. Images (Cloudinary)

- Dependency `com.cloudinary:cloudinary-http5` (latest 2.x at build time).
  Config `sellerdesk.cloudinary.url: ${CLOUDINARY_URL}`; no default in prod (app fails to start).
- `common.storage.ImageStorage` (`upload(bytes, publicId)`, `delete(publicId)`):
  `CloudinaryImageStorage` (dev/prod) and `FakeImageStorage` (tests; records calls, no network).

| Rule | Value |
|------|-------|
| Per request | one file, multipart field `file` |
| Max size | **5 MB** (`spring.servlet.multipart.max-file-size=5MB`, `max-request-size=6MB`) → `413 IMAGE_TOO_LARGE` |
| Max per product | **8** → `409 IMAGE_LIMIT_REACHED` |
| Allowed types | JPEG (`FF D8 FF`), PNG (`89 50 4E 47`), WebP (`RIFF....WEBP`), checked by **magic bytes**, not by `Content-Type` or extension (the client controls both) → `400 IMAGE_TYPE_NOT_ALLOWED` |
| Cloudinary options | `allowed_formats=jpg,png,webp`, folder `sellerdesk/{shopId}/products`, incoming transformation `c_limit,w_2000,h_2000` |
| Stored | `secure_url`, `publicId`, `width`, `height`, `bytes`, `format` |

### Upload flow — and what if the upload succeeds but saving fails?

1. Load the product by `{_id, shopId}` (else `404`). 8 images already → `409 IMAGE_LIMIT_REACHED`. Validate bytes.
2. Generate the `publicId` ourselves. **Insert a cleanup task first** (`ORPHAN_UPLOAD`, `notBefore = now + 1 h`).
3. Upload to Cloudinary (outside any DB transaction: no lock held during the network call).
   Failure → delete the task, `502 IMAGE_UPLOAD_FAILED`.
4. Atomic `$push` with filter `{_id, shopId, "images.7": {$exists: false}}` (= fewer than 8 images; safe under parallel uploads).
5. Saved → delete the task. **Not saved** (DB error, parallel upload hit the limit, product deleted
   meanwhile) → try `ImageStorage.delete` immediately. If that also fails, or the server crashes
   anywhere between steps 2 and 5, the task remains, and `ImageCleanupJob` (`@Scheduled`, every
   15 min) deletes the file once `notBefore` has passed.

Result: a product never references a missing file, and an orphan file lives about an hour at most.

### Removing images

- Remove one image or delete a product: `$pull` the image / delete the product, and insert
  `REMOVED` tasks (`notBefore = now`). `ImagesRemovedEvent` → `ImageCleanupListener`
  (`@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)` + `@Async`) deletes them right away.
- The job retries failures with backoff (`notBefore = now + 2^attempts min`), max 10 attempts,
  then logs an error. Cloudinary "not found" counts as success (delete is idempotent).

---

## 5. Endpoints

Base `/api/v1`. Shop always from the JWT.

| Method | Path | Permission | Request → Response |
|--------|------|------------|--------------------|
| GET | `/products` | `PRODUCT_VIEW` | `?q=&category=&status=&sort=&page=&size=` → page of `ProductSummary` |
| GET | `/products/lookup` | `PRODUCT_VIEW` or `ORDER_CREATE` | → `[ProductLookupItem]` (cached, §7) |
| GET | `/products/categories` | `PRODUCT_VIEW` | → `[{name, productCount}]` |
| GET | `/products/{id}` | `PRODUCT_VIEW` | → `ProductResponse` |
| POST | `/products` | `PRODUCT_EDIT` | `CreateProductRequest` → `201 ProductResponse` |
| PUT | `/products/{id}` | `PRODUCT_EDIT` | `UpdateProductRequest` → `ProductResponse` |
| PATCH | `/products/{id}/status` | `PRODUCT_EDIT` | `{status: ACTIVE or ARCHIVED, version}` → `ProductResponse` |
| POST | `/products/{id}/variants` | `PRODUCT_EDIT` | `VariantRequest` + `version` → `201 ProductResponse` |
| PATCH | `/products/{id}/variants/{variantId}` | `PRODUCT_EDIT` | `UpdateVariantRequest` → `ProductResponse` |
| POST | `/products/{id}/images` | `PRODUCT_EDIT` | multipart `file` → `201 ProductResponse` |
| DELETE | `/products/{id}/images/{imageId}` | `PRODUCT_EDIT` | → `ProductResponse` |
| PUT | `/products/{id}/images/order` | `PRODUCT_EDIT` | `{imageIds[], version}` (same set, new order) → `ProductResponse` |
| DELETE | `/products/{id}` | `PRODUCT_EDIT` | → `204`; `409 PRODUCT_IN_USE` if ever used |

### List query

- `q`: uppercased `q` = exact SKU (index #1) **or** `nameLower` prefix `^<regex-escaped q>` (index #3), one `$or`.
  "Contains" search is deferred (Atlas Search later, no schema change).
- `category`: matches `categoryKey`. `status`: `ACTIVE` (default), `ARCHIVED`, or `ALL`.
- `sort`: `updatedAt,desc` (default), `name,asc|desc`, `createdAt,desc`, `minPrice,asc|desc`. Other values → `400`.
- `page` 0-based, `size` default 20, max 100.

### DTOs (`product.dto`, Java records, `@Valid`)

```
CreateProductRequest  {name, description?, category?, variants: [VariantRequest] (1–100)}
VariantRequest        {sku, name?, attributes?, price, costPrice?, lowStockThreshold?}
UpdateProductRequest  {name, description?, category?, version}
UpdateVariantRequest  {name?, attributes?, price?, costPrice?, lowStockThreshold?,
                       useShopDefaultThreshold? (true = $unset own threshold), active?, version}
ProductStatusRequest  {status, version}
ReorderImagesRequest  {imageIds, version}

ProductSummary    {id, name, category, status, variantCount, priceRange{min, max},
                   totalAvailable*, coverImageUrl, updatedAt}
ProductResponse   {id, name, description, category, status, images[{id, url, width, height}],
                   variants[{variantId, sku, name, attributes, price, costPrice**, lowStockThreshold,
                             active, stock{onHand, reserved, available}*}],
                   version, createdAt, updatedAt}
ProductLookupItem {productId, name, coverImageUrl, variants[{variantId, sku, name, price}]}
CategoryResponse  {name, productCount}
```

`*` only with `STOCK_VIEW`. `**` only with `PRODUCT_EDIT` or `REPORT_VIEW` (`SECURITY.md` §3).
The mapper decides from `CurrentUser`. Request DTOs have no `sku` (on update), no `stock`, no
`shopId`; unknown JSON fields are ignored. `version` is the value the client loaded: a stale
form gets `409 CONCURRENT_MODIFICATION` instead of overwriting someone else's change.

### Rules

- Archived products stay editable (fix typos) but are excluded from `lookup`, so they cannot be
  added to new orders. Unarchive is allowed.
- An `ACTIVE` product may have all variants inactive; it then simply does not appear in `lookup`.
- Delete is refused (`409 PRODUCT_IN_USE`) when any `ProductUsageChecker` says the product is
  used, or any variant has `onHand > 0` or `reserved > 0` (safety check).
- **`ProductUsageChecker`** — interface in `product.service`:
  `boolean isUsed(String shopId, String productId)`. `inventory` (any stock movement) and
  `order` (any order item) implement it (Inventory: `InventoryUsageChecker`, built; Order: when built); `ProductService` injects
  `List<ProductUsageChecker>` (empty until then). This is *dependency inversion*: product
  defines the question, the other modules answer it, so the dependency still points
  `order / inventory → product` (`ARCHITECTURE.md` §1).

---

## 6. Errors

| Situation | Status | `code` |
|-----------|--------|--------|
| Bean Validation (lengths, ranges, sizes, SKU format, duplicate SKU inside one request) | 400 | `VALIDATION_FAILED` (`fieldErrors`) |
| Unknown `sort` / `status` value | 400 | `VALIDATION_FAILED` |
| SKU used by another product of this shop | 409 | `SKU_ALREADY_EXISTS` (`fieldErrors` on the SKU) |
| Same attribute combination twice in one product | 409 | `DUPLICATE_VARIANT_ATTRIBUTES` |
| 100 variants already | 409 | `VARIANT_LIMIT_REACHED` |
| 8 images already | 409 | `IMAGE_LIMIT_REACHED` |
| File is not JPEG / PNG / WebP (by bytes) or empty | 400 | `IMAGE_TYPE_NOT_ALLOWED` |
| File over 5 MB | 413 | `IMAGE_TOO_LARGE` |
| Cloudinary upload failed | 502 | `IMAGE_UPLOAD_FAILED` |
| Delete a used product | 409 | `PRODUCT_IN_USE` |
| `version` does not match | 409 | `CONCURRENT_MODIFICATION` |
| Product / variant / image id unknown **or from another shop** | 404 | `NOT_FOUND` |
| Missing permission | 403 | `FORBIDDEN` (existing security handler code) |

---

## 7. Cache (order entry)

- Cache `productLookup`, key = `shopId`, value = immutable `List<ProductLookupItem>`
  (active products, active variants only).
- **No permission-dependent fields** in the cached value: every user of the shop gets the same
  list, so it holds no stock and no cost price. The order form gets live stock from
  Inventory/Order; stock must never come from a cache.
- `CacheConfig` gets per-cache Caffeine specs: `productLookup` = 10 min expiry (safety net),
  max 1000 entries. Other caches keep today's spec.
- **Evict after commit:** every product write publishes `ProductChangedEvent(shopId, productId)`;
  `ProductLookupCacheEvictor` (`@TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)`)
  evicts `shopId`. After commit, so a rolled-back write never evicts too early;
  `fallbackExecution` because most product writes run without a transaction.
- Limitation: in-memory, per instance. Fine on one Render instance. With several instances,
  switch to Redis or accept up to 10 minutes of stale names/prices in the order form.

---

## 8. Events

| Event | Published on | Listener |
|-------|--------------|----------|
| `ProductChangedEvent(shopId, productId)` | every product write (create, update, status, variant, image, delete) | `ProductLookupCacheEvictor` (after commit) |
| `ImagesRemovedEvent(shopId, publicIds)` | image removed, product deleted, upload not saved | `ImageCleanupListener` (after commit, `@Async`) |

Inventory and Order call `ProductService` methods directly; no events are needed for them.

---

## 9. Backend structure

```
com.sellerdesk.product
├── controller  ProductController, ProductImageController
├── service     ProductService, ProductImageService, ProductMapper,
│               ProductUsageChecker, ProductLookupCacheEvictor, events
├── repository  ProductRepository (Repository<Product, String>, shopId methods only),
│               ProductQueryRepository (MongoTemplate: search, targeted updates, lookup, categories)
├── dto         requests / responses above
└── model       Product, Variant, VariantStock, ProductImage, ProductStatus
com.sellerdesk.common.storage
    ImageStorage, CloudinaryImageStorage, ImageCleanupTask (+ repository), ImageCleanupJob,
    ImageCleanupListener, ImageValidator, ImageTypeNotAllowedException, ImageUploadFailedException
    (shared with the shop logo, so both uploads follow the same rules)
com.sellerdesk.common.security
    TenantCriteria.of(shopId)   (start of every MongoTemplate tenant query, SECURITY.md §4)
```

Other changes: `GlobalExceptionHandler` (413 handler), `CacheConfig` (per-cache specs),
`application*.yml` (multipart limits, Cloudinary URL), `pom.xml`, `render.yaml` (`CLOUDINARY_URL`).

---

## 10. Tests

**Unit** (`ProductServiceTest`, `ProductImageServiceTest`, `ImageValidatorTest`, `ProductMapperTest`):
SKU normalization; duplicate SKU in one request → field error; duplicate attributes; limits;
default variant name; `minPrice`/`maxPrice` recomputed; archive/unarchive; delete refused when
used or stock > 0; repositories called with the `TenantContext` shop; image flow (limit → no
upload; upload failure → no DB write, task removed; DB failure → immediate delete, task kept;
success → task removed); magic bytes (JPEG/PNG/WebP pass; GIF, renamed `.exe`, right
`Content-Type` + wrong bytes, empty file fail); mapper hides `costPrice` / `stock` by
permission; `ProductChangedEvent` on every write.

**Integration** (`ProductIntegrationTest extends MongoIntegrationTestBase`, `FakeImageStorage`):
same SKU in two products → `409`; two parallel creates with one SKU → exactly one `201`; same
SKU in shops A and B → both `201`; **lost-update guard**: set stock with `mongoTemplate`, then
edit product, add variant, reorder images → stock unchanged; stale `version` → `409`; search
(prefix, exact SKU, category, status, sorts, pagination); cache filled, evicted on edit,
archived/inactive excluded; 6 MB → `413`; spoofed type → `400`; 9th image → `409`; parallel
uploads never exceed 8; cleanup job deletes due tasks and retries failures.

**Permissions** (users per role from a test fixture until the staff module exists):
`PACKER` → `403` on every write; no `PRODUCT_VIEW` → `403` on list/detail; `ORDER_CREATE` only
→ `lookup` works; `costPrice` hidden for `MODERATOR`, shown for `ACCOUNTANT` and owner;
`PLATFORM_ADMIN` → `403`.

**`TenantIsolationTest` → `@Nested class Products`** (`assertHiddenFromOtherShop`):
- A with B's product id → `404` on `GET`/`PUT`/`DELETE /products/{id}`, `PATCH .../status`,
  `POST .../variants`, `PATCH .../variants/{variantId}`, `POST .../images`,
  `DELETE .../images/{imageId}`, `PUT .../images/order`; B's document unchanged; no upload recorded.
- A's product with B's `variantId` / `imageId` → `404`.
- `GET /products`, `/products/lookup`, `/products/categories` show only A's data, also when
  `q` matches B's name or SKU.
- `"shopId": "<B>"` in create / update body → saved with A's shop.
- B owning SKU `X` does not stop A from creating `X`.

---

## 11. Frontend

| Piece | Where |
|-------|-------|
| API wrappers + types | `api/products.ts`, `types/product.ts` |
| Money helpers | `lib/money.ts` (`formatTaka`, `parseTakaToPoisha`), `components/ui/MoneyInput.tsx` |
| Permission helpers | `lib/permissions.ts`, `usePermissions`, `<Can>`, `RequirePermission` (shared with Staff; built by whichever module comes first) |
| `/products` | list: table on desktop, cards on mobile; search (300 ms debounce), category and status filters, sort, server-side pagination, state in the URL; loading / empty / error states |
| `/products/new`, `/products/:id` | one form page: product fields with category autocomplete; variant editor (add, deactivate, SKU read-only once saved, "sizes × colors" generator); image manager (upload with progress, client-side type/size pre-check, reorder, delete, cover badge); archive / unarchive; delete (on `409 PRODUCT_IN_USE` explain to archive instead). Read-only without `PRODUCT_EDIT`. |
| Sidebar | "Products", visible with `PRODUCT_VIEW` |

After create, link to "Add opening stock" on the Inventory page once that exists.
Hiding buttons is a convenience only; the backend checks every permission.
