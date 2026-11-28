# SellerDesk — REST API (Phase 1, courier: Phase 2)

- Base path: `/api/v1`
- JSON only. Money in **poisha** (`long`). Times in ISO-8601 UTC.
- Auth: `Authorization: Bearer <accessToken>` unless marked **public**.
- **No `shopId` anywhere** in paths or bodies: the shop is always the logged-in user's shop.
- Errors: `ApiError` format (`ARCHITECTURE.md` §5). Lists: page object
  `{content, page, size, totalElements, totalPages}`; query params `page` (0-based), `size` (default 20, max 100), `sort`.
- "Auth" column: **public**, **auth** (any logged-in shop user), or a permission
  (`SECURITY.md` §3).

### Why action endpoints for order status?

`POST /orders/{id}/confirm` instead of `PATCH /orders/{id} {status}`.
Each transition has its own permission, its own body, and its own side effects (stock,
customer stats). Separate endpoints make each one easy to secure, document and test.

---

## 1. Auth

| Method | Path | Auth | Request → Response |
|--------|------|------|--------------------|
| POST | `/auth/register-shop` | public | `{ownerName, email, password, shopName, shopPhone, address?, facebookPageUrl?}` → `201 {message, email, resendAfterSeconds}`. Creates shop + SHOP_OWNER (`PENDING_VERIFICATION`) in one transaction, then emails a verification code (asynchronously; a mail failure does not fail registration). `409 EMAIL_ALREADY_REGISTERED`. |
| POST | `/auth/verify-email` | public | `{email, code}` → `200 {accessToken, expiresIn, user}` + refresh cookie. Wrong/expired code: `400 INVALID_OTP`. |
| POST | `/auth/verify-email/resend` | public | `{email}` → always `202 {message, resendAfterSeconds}`: identical for unknown, already-verified and rate-limited emails (never `429`). The code is sent in the background only if the account is `PENDING_VERIFICATION` and within the limits. |
| POST | `/auth/login` | public | `{email, password}` → `200 {accessToken, expiresIn, user}` + refresh cookie. `401 INVALID_CREDENTIALS`, `403 EMAIL_NOT_VERIFIED`, `429 LOGIN_RATE_LIMITED`. |
| POST | `/auth/password/forgot` | public | `{email}` → always `202 {message, resendAfterSeconds}`, same rules as resend (never `429`). Code sent in the background unless the account is unknown or `DISABLED`. |
| POST | `/auth/password/reset` | public | `{email, code, newPassword}` → `204`. Password rules as registration (`400 VALIDATION_FAILED`). Increments `tokenVersion` (all sessions end), activates `PENDING_VERIFICATION` users. `400 INVALID_OTP` (also for unknown/disabled emails). Log in afterwards. |
| POST | `/auth/refresh` | refresh cookie | no body → `200 {accessToken, expiresIn}` + new refresh cookie. `401 INVALID_REFRESH_TOKEN` if cookie missing/invalid/old version. `403 INVALID_ORIGIN` if `Origin` is present and not `APP_URL`. |
| POST | `/auth/logout` | auth (Bearer) | no body → `204`, deletes the cookie (`Max-Age=0`), increments `tokenVersion`: every access and refresh token of the user stops working (all devices). |
| GET | `/auth/me` | auth | → `{user, shop: {id, name, status}}`. `user` read fresh from the database. `shop` is `null` for `PLATFORM_ADMIN`. |

`user` object: `{id, name, email, phone, role, permissions[], status}`.

---

## 2. Shop

| Method | Path | Auth | Request → Response |
|--------|------|------|--------------------|
| GET | `/shop` | auth | → `ShopResponse {id, name, phone, email, address, facebookPageUrl, logoUrl, status, settings{deliveryChargeInsideDhaka, deliveryChargeOutsideDhaka, defaultLowStockThreshold, orderNumberPrefix}}`. Any shop user may read it (the order form needs the delivery charges). |
| PUT | `/shop` | `SHOP_SETTINGS` | `{name, phone, email?, address?, facebookPageUrl?, settings{deliveryChargeInsideDhaka, deliveryChargeOutsideDhaka, defaultLowStockThreshold, orderNumberPrefix}}` → `ShopResponse`. Full replace: an optional field left out is cleared. `400 VALIDATION_FAILED` with `fieldErrors`. |
| PUT | `/shop/logo` | `SHOP_SETTINGS` | multipart `file` (JPEG/PNG/WebP, ≤ 5 MB, same checks as product images) → `200 ShopResponse`. Replaces the old logo; the old file is deleted. `400 IMAGE_TYPE_NOT_ALLOWED`, `413 IMAGE_TOO_LARGE`, `502 IMAGE_UPLOAD_FAILED`. |
| DELETE | `/shop/logo` | `SHOP_SETTINGS` | → `200 ShopResponse` (`logoUrl: null`). The file is deleted. No logo: no change, still `200`. |

Only the owner has `SHOP_SETTINGS` by default; it can be granted to staff. Missing it → `403 FORBIDDEN`.

**`PUT /shop` rules** (each broken rule is one entry in `fieldErrors`, `field` as shown):

| Field | Rule |
|-------|------|
| `name` | required, 2–100 characters, trimmed |
| `phone` | required, Bangladeshi mobile: `01XXXXXXXXX`, `+8801XXXXXXXXX`, `8801XXXXXXXXX` (also `00880…`; spaces, dashes, dots, parentheses and Bangla digits allowed). Operator digit 3–9. Stored and returned as `01XXXXXXXXX`. |
| `email` | optional, valid email, ≤ 254; stored lowercase |
| `address` | optional; if given: `line` and `district` required, `zone` `INSIDE_DHAKA` / `OUTSIDE_DHAKA` |
| `facebookPageUrl` | optional, `http(s)://…`, ≤ 300 |
| `settings` | required |
| `settings.deliveryChargeInsideDhaka`, `settings.deliveryChargeOutsideDhaka` | required, poisha, `0`–`500000` (৳0–৳5,000) |
| `settings.defaultLowStockThreshold` | required, `0`–`100000` (used for variants without their own threshold) |
| `settings.orderNumberPrefix` | required, 2–6 letters or digits (`^[A-Za-z0-9]{2,6}$`), stored uppercase. Orders copy the prefix when created, so a change only affects future orders. |

Settings are cached per shop (`ShopSettingsLookup`, 10 min); `PUT /shop` evicts the shop's entry.

---

## 3. Staff

Full spec, errors and decisions: `docs/modules/STAFF.md`.

| Method | Path | Auth | Request → Response |
|--------|------|------|--------------------|
| GET | `/staff` | `STAFF_MANAGE` | `?q=&role=&status=` (`ACTIVE`/`DISABLED`/`INVITED`/`EXPIRED`) → page of `StaffRow {kind (MEMBER/INVITATION), id, name, email, role, permissions, status, lastLoginAt, invitedAt, expiresAt, lastSentAt}`. Members (incl. owner) and pending invitations in one list. Fixed order: owner, invitations (newest first), members by name. |
| GET | `/staff/permissions` | `STAFF_MANAGE` | → `{permissions: [{code, description}], roleDefaults: {MODERATOR: [...], ...}}` (for the staff edit UI) |
| GET | `/staff/{userId}` | `STAFF_MANAGE` | → `StaffResponse {id, name, email, phone, role, permissions, status, lastLoginAt, createdAt, invitedBy{id, name}}` |
| PATCH | `/staff/{userId}` | `STAFF_MANAGE` | `{role?, permissions?}` → `StaffResponse`. Role change resets permissions to defaults unless `permissions` also given. Bumps `tokenVersion`. `403 OWNER_READ_ONLY` / `CANNOT_MODIFY_SELF` / `PERMISSION_NOT_GRANTABLE`. |
| PATCH | `/staff/{userId}/status` | `STAFF_MANAGE` | `{status: ACTIVE or DISABLED}` → `StaffResponse`. Disable bumps `tokenVersion`. Not allowed on owner or self. |
| POST | `/staff/invitations` | `STAFF_MANAGE` | `{email, role (MODERATOR/PACKER/ACCOUNTANT), permissions?}` → `201 InvitationResponse`, emails a link valid 7 days. `409 ALREADY_A_MEMBER` / `ALREADY_INVITED` / `EMAIL_ALREADY_REGISTERED`. |
| POST | `/staff/invitations/{id}/resend` | `STAFF_MANAGE` | → `200 InvitationResponse`, new token (old link dies), new expiry. `429 INVITE_RESEND_TOO_SOON` / `INVITE_RESEND_LIMIT` (with `Retry-After`), `409 INVITATION_NOT_PENDING`. |
| DELETE | `/staff/invitations/{id}` | `STAFF_MANAGE` | → `204`. `409 INVITATION_NOT_PENDING`. |
| POST | `/auth/invitations/preview` | public | `{token}` → `{email, shopName, role, inviterName, expiresAt}`. `400 INVALID_INVITATION` / `INVITATION_EXPIRED`. |
| POST | `/auth/invitations/accept` | public | `{token, name, password}` → `200 {accessToken, expiresIn, user}` + refresh cookie. Creates the `ACTIVE` user. `400 INVALID_INVITATION` / `INVITATION_EXPIRED` / `VALIDATION_FAILED`, `409 EMAIL_ALREADY_REGISTERED`. |

---

## 4. Products

Full spec, errors and decisions: `docs/modules/PRODUCT.md`.

| Method | Path | Auth | Request → Response |
|--------|------|------|--------------------|
| GET | `/products` | `PRODUCT_VIEW` | `?q=` (name prefix or exact SKU) `&status=` (`ACTIVE` default / `ARCHIVED` / `ALL`) `&category=&sort=` → page of `ProductSummary {id, name, category, status, variantCount, priceRange{min, max}, totalAvailable*, coverImageUrl, updatedAt}` |
| GET | `/products/lookup` | `PRODUCT_VIEW` or `ORDER_CREATE` | → `[ProductLookupItem {productId, name, coverImageUrl, variants[{variantId, sku, name, price}]}]`: active products and variants only, no stock, no cost price. Cached per shop, evicted on every product change. For the order form. |
| GET | `/products/categories` | `PRODUCT_VIEW` | → `[{name, productCount}]` (autocomplete) |
| GET | `/products/{id}` | `PRODUCT_VIEW` | → `ProductResponse {id, name, description, category, status, images[{id, url, width, height}], variants: [{variantId, sku, name, attributes, price, costPrice**, lowStockThreshold, active, stock{onHand, reserved, available}*}], version, createdAt, updatedAt}` |
| POST | `/products` | `PRODUCT_EDIT` | `{name, description?, category?, variants: [{sku, name?, attributes?, price, costPrice?, lowStockThreshold?}]}` → `201 ProductResponse`. Stock starts at 0; opening stock is entered through Inventory. `409 SKU_ALREADY_EXISTS`. |
| PUT | `/products/{id}` | `PRODUCT_EDIT` | `{name, description?, category?, version}` → `ProductResponse`. Product fields only. |
| PATCH | `/products/{id}/status` | `PRODUCT_EDIT` | `{status: ACTIVE or ARCHIVED, version}` → `ProductResponse`. Archived products cannot be added to new orders. |
| POST | `/products/{id}/variants` | `PRODUCT_EDIT` | `{sku, name?, attributes?, price, costPrice?, lowStockThreshold?, version}` → `201 ProductResponse` |
| PATCH | `/products/{id}/variants/{variantId}` | `PRODUCT_EDIT` | `{name?, attributes?, price?, costPrice?, lowStockThreshold?, useShopDefaultThreshold?, active?, version}` → `ProductResponse`. SKU and stock cannot be changed. `useShopDefaultThreshold: true` removes the variant's own threshold (the shop's `defaultLowStockThreshold` applies again); together with `lowStockThreshold` → `400`. |
| POST | `/products/{id}/images` | `PRODUCT_EDIT` | multipart `file` (JPEG/PNG/WebP, ≤ 5 MB, ≤ 8 per product) → `201 ProductResponse`. `400 IMAGE_TYPE_NOT_ALLOWED`, `413 IMAGE_TOO_LARGE`, `409 IMAGE_LIMIT_REACHED`, `502 IMAGE_UPLOAD_FAILED`. |
| DELETE | `/products/{id}/images/{imageId}` | `PRODUCT_EDIT` | → `ProductResponse`. File deleted from Cloudinary. |
| PUT | `/products/{id}/images/order` | `PRODUCT_EDIT` | `{imageIds[], version}` → `ProductResponse`. First image = cover. |
| DELETE | `/products/{id}` | `PRODUCT_EDIT` | → `204`. Only for products never used by an order or stock movement, else `409 PRODUCT_IN_USE` (archive instead). |

`*` only with `STOCK_VIEW`. `**` only with `PRODUCT_EDIT` or `REPORT_VIEW`. `version` is the
value the client loaded; a mismatch gives `409 CONCURRENT_MODIFICATION`.

Product endpoints never accept stock fields. All stock changes, including opening stock, go
through Inventory.

---

## 5. Inventory

Full design: `modules/INVENTORY.md`. Order-driven stock changes (reserve, release, deduct,
return) have no endpoint; they happen inside order status changes.

| Method | Path | Auth | Request → Response |
|--------|------|------|--------------------|
| GET | `/inventory` | `STOCK_VIEW` | `?q=&stockStatus=ALL\|LOW\|OUT\|LOW_OR_OUT&includeInactive=false&sort=name,asc\|available,asc&page=&size=` → page of `StockRow {productId, productName, coverImageUrl, productStatus, variantId, sku, variantName, active, onHand, reserved, available, lowStockThreshold (effective), thresholdSource: VARIANT\|SHOP, stockStatus: IN_STOCK\|LOW\|OUT}` (one row per variant) |
| GET | `/inventory/low-stock` | `STOCK_VIEW` | `?page=&size=` → page of `StockRow`: `LOW` or `OUT`, active only, lowest `available` first |
| GET | `/inventory/variants/{variantId}` | `STOCK_VIEW` | → `{row: StockRow, recentMovements: [MovementResponse] (last 10)}` |
| POST | `/inventory/variants/{variantId}/restock` | `STOCK_ADJUST` | `{quantity (1–100000), note?, requestId (UUID)}` → `201 {movement, stock{onHand, reserved, available}}`. First stock-in of a variant is recorded as `INITIAL`, later ones as `RESTOCK`. `409 STOCK_LIMIT_EXCEEDED` above 1,000,000 on hand. |
| POST | `/inventory/variants/{variantId}/adjustments` | `STOCK_ADJUST` | `{quantityChange (≠0, −100000…100000), reasonCode (DAMAGED\|LOST\|FOUND\|COUNT_CORRECTION\|OTHER), note? (≤200, required for OTHER), requestId (UUID)}` → `201 {movement, stock}`. `409 INSUFFICIENT_STOCK` if a decrease would make `available < 0` (reserved units cannot be removed). |
| GET | `/inventory/movements` | `STOCK_VIEW` | `?variantId=&productId=&type=&reasonCode=&orderId=&from=&to=&page=&size=` → page of `MovementResponse {id, productId, variantId, sku, variantName, type, onHandDelta, reservedDelta, onHandAfter, reservedAfter, availableAfter, reasonCode, note, orderId, orderNumber, createdBy{id, name}, createdAt}`, newest first |

A repeated `requestId` returns the first result without applying the change again; the same
`requestId` with a different change → `409 REQUEST_ID_REUSED`. Another shop's `variantId` (or a
malformed one) → `404 NOT_FOUND`; an id filter of another shop on `/movements` → empty page.

The threshold is the variant's own `lowStockThreshold`, else the shop's
`settings.defaultLowStockThreshold`. `stockStatus`: `OUT` if `available ≤ 0`, `LOW` if
`0 < available ≤ threshold`, else `IN_STOCK`.

Errors: `400 VALIDATION_FAILED` (`fieldErrors`: `quantity`, `quantityChange`, `reasonCode`,
`note`, `requestId`, query parameters), `400 MALFORMED_REQUEST` (decimal quantity, unknown
`reasonCode`), `403 FORBIDDEN` (`STOCK_VIEW` / `STOCK_ADJUST` missing), `404 NOT_FOUND`,
`409 INSUFFICIENT_STOCK` (message names SKU, requested and available), `409 STOCK_LIMIT_EXCEEDED`
(more than 1,000,000 on hand), `409 REQUEST_ID_REUSED`.

---

## 6. Customers

Full design: `modules/CUSTOMER.md`. Phones in any accepted Bangladeshi format (`01…`, `+880…`,
`880…`, `00880…`, separators, Bangla digits); always stored and returned as `01XXXXXXXXX`.

| Method | Path | Auth | Request → Response |
|--------|------|------|--------------------|
| GET | `/customers` | `CUSTOMER_VIEW` | `?q=&tag=&status=ACTIVE\|ARCHIVED\|ALL&sort=createdAt,desc\|name,asc\|lastOrderAt,desc&page=&size=` → page of `CustomerSummary {id, name, phone, altPhone, defaultAddress{district, area, zone}, tags, status, stats, createdAt}`. `q`: digits (3+) match the start of the phone or alt phone, 4+ digits also the end ("…5678"); text matches the start of the name. |
| GET | `/customers/tags` | `CUSTOMER_VIEW` | → `[{name, customerCount}]` (active customers) |
| GET | `/customers/lookup` | `CUSTOMER_VIEW` or `ORDER_CREATE` | `?phone=` → `CustomerResponse` (also when archived) or `404 NOT_FOUND`. Exact match on the main phone only. Invalid phone → `400`. Used on the order form. |
| POST | `/customers` | `CUSTOMER_EDIT` | `{name, phone, altPhone?, facebookProfileUrl?, note?, tags? (≤10, ≤30 chars), addresses? (≤5, AddressRequest)}` → `201 CustomerResponse`. `409 PHONE_ALREADY_EXISTS` (also for an archived customer's phone). |
| GET | `/customers/{id}` | `CUSTOMER_VIEW` | → `CustomerResponse {id, name, phone, altPhone, facebookProfileUrl, addresses[{addressId, label, line, area, district, zone, note, isDefault}], note, tags, status, stats{totalOrders, deliveredOrders, returnedOrders, cancelledOrders, totalSpent, lastOrderAt}, version, createdAt, updatedAt}` |
| PUT | `/customers/{id}` | `CUSTOMER_EDIT` | `{name, phone, altPhone?, facebookProfileUrl?, note?, tags?, version}` → `CustomerResponse`. Profile only; optional fields left out are cleared. `409 PHONE_ALREADY_EXISTS` / `CONCURRENT_MODIFICATION`. |
| PATCH | `/customers/{id}/status` | `CUSTOMER_EDIT` | `{status: ACTIVE\|ARCHIVED, version}` → `CustomerResponse` (archive / restore) |
| POST | `/customers/{id}/addresses` | `CUSTOMER_EDIT` | `AddressRequest {label?, line, area?, district, zone, note?, isDefault?}` → `201 CustomerResponse`. `409 ADDRESS_LIMIT_REACHED` (5). |
| PUT | `/customers/{id}/addresses/{addressId}` | `CUSTOMER_EDIT` | `AddressRequest` → `CustomerResponse` |
| DELETE | `/customers/{id}/addresses/{addressId}` | `CUSTOMER_EDIT` | → `CustomerResponse`; the oldest remaining address becomes default if needed |
| GET | `/customers/{id}/orders` | `CUSTOMER_VIEW` + `ORDER_VIEW` | → page of `OrderSummary` (built with the Order module) |

No delete in Phase 1 (orders reference customers): archive instead. `isDefault: true` moves the
default mark; `false` keeps the current default. Address changes and order counters do not change
`version`. `stats` is never accepted from the client; `totalSpent` is in poisha.

Errors: `400 VALIDATION_FAILED` (`fieldErrors`: `name`, `phone`, `altPhone` (also when equal to
`phone`), `facebookProfileUrl`, `tags[i]`, `addresses[i].line`/`district`/`zone`, `version`,
query parameters), `403 FORBIDDEN`, `404 NOT_FOUND` (unknown, malformed or other shop's customer
or address id), `409 PHONE_ALREADY_EXISTS` (`fieldErrors[phone]`), `409 ADDRESS_LIMIT_REACHED`,
`409 CONCURRENT_MODIFICATION`.

---

## 7. Orders

Full design: `modules/ORDER.md`. Money in poisha; the server computes every total.

**Built:** `POST /orders`, `GET /orders` (list), `GET /orders/{id}`, `PUT /orders/{id}`, the six
status actions (`confirm`, `pack`, `ship`, `deliver`, `return`, `cancel`), `POST /orders/bulk-status`
and `GET /orders/{id}/timeline`. **Not built yet:** `PATCH /orders/{id}/details`,
`GET /customers/{id}/orders`.

The body of `confirm`, `pack`, `ship` and `deliver` is optional (`note` ≤ 500). `cancel` and
`return` need a `reason` (1–500).

| Method | Path | Auth | Request → Response |
|--------|------|------|--------------------|
| POST | `/orders` | `ORDER_CREATE` | `CreateOrderRequest` (below) → `201 OrderResponse` (`PENDING`). Same `requestId` again → `200` with the existing order; with a different body → `409 REQUEST_ID_REUSED`. |
| GET | `/orders` | `ORDER_VIEW` | `?status=` (repeatable) `&source=&from=&to=` (Asia/Dhaka dates, inclusive) `&q=` (order number `SD-1042` / `1042`, or full phone) `&sort=createdAt,desc\|createdAt,asc&page=&size=` → page of `OrderSummary {id, orderNumber, status, source, customerId, customerName, customerPhone, district, zone, itemCount (units), total, codAmount, createdAt}`. Sorted by `createdAt` then `_id` (stable paging). Unknown `status`/`source`/`sort`, a bad date, `from` after `to`, or a `q` that is neither an order number nor a phone → `400 VALIDATION_FAILED`. |
| GET | `/orders/{id}` | `ORDER_VIEW` | → `OrderResponse` (customer snapshot, items, money fields, shipping, reasons, statusHistory, `allowedActions`, createdBy, version) |
| PUT | `/orders/{id}` | `ORDER_EDIT` | `{customerName?, deliveryAddress, items, deliveryCharge?, discount?, advancePaid?, source, note?, version}` → `OrderResponse`. **`PENDING` only**, full replace; the customer cannot change. Else `409 ORDER_NOT_EDITABLE`; stale `version` → `409 CONCURRENT_MODIFICATION`. |
| PATCH | `/orders/{id}/details` | `ORDER_EDIT` | `{deliveryAddress?, note?, version}` → `OrderResponse`. `PENDING` / `CONFIRMED` / `PACKED`: address and note; `SHIPPED`: note only. Money does not change. |
| POST | `/orders/{id}/confirm` | `ORDER_CONFIRM` | `{note?}` → `OrderResponse`. Reserves stock in one transaction: all items or none. `409 INSUFFICIENT_STOCK` with one `fieldErrors` entry per short item (`items[i].quantity`, `"SKU: requested 3, available 1"`). |
| POST | `/orders/{id}/pack` | `ORDER_FULFILL` | `{note?}` → `OrderResponse` |
| POST | `/orders/{id}/ship` | `ORDER_FULFILL` | `{courierName?, trackingId?, note?}` → `OrderResponse`. Stock `DEDUCT`. |
| POST | `/orders/{id}/deliver` | `ORDER_CLOSE` | `{note?}` → `OrderResponse`. Updates customer stats. |
| POST | `/orders/{id}/return` | `ORDER_CLOSE` | `{reason, items?: [{variantId, restock}]}` → `OrderResponse`. From `SHIPPED` only. Items left out are restocked; only restocked items get a `RETURN` movement. |
| POST | `/orders/{id}/cancel` | `ORDER_CANCEL` | `{reason}` → `OrderResponse`. From `PENDING`, `CONFIRMED`, `PACKED`; releases reserved stock. |
| POST | `/orders/bulk-status` | permission of `action` | `{action: CONFIRM\|PACK\|SHIP\|DELIVER\|CANCEL, orderIds (1–50), note?, reason? (CANCEL), courierName?}` → `200 {action, succeeded, failed, results[{orderId, orderNumber?, ok, status?, errorCode?, message?}]}`. One transaction per order, one after the other; failures do not stop the others. Already in the target status → `ok: true`. Unknown id or another shop's order → `errorCode: NOT_FOUND`. Missing the action's permission → `403` for the whole request. Duplicate ids, `RETURN`, or `CANCEL` without `reason` → `400`. |
| GET | `/orders/{id}/timeline` | `ORDER_VIEW` | → `[{type: STATUS\|EDIT, at, by{id, name}, from?, to?, note?, fields?, summary?}]`, oldest first |

Transitions not in the status table (`SCHEMA.md` §9) → `409 INVALID_STATUS_TRANSITION`. A
transition to the status the order already has → `200`, nothing changes.

`OrderResponse`: `{id, orderNumber, status, source, customer{id, name, phone, address}, items[{productId,
variantId, sku, productName, variantName, unitPrice, quantity, lineTotal, returnedRestocked, available*}],
subtotal, discount, deliveryCharge, total, advancePaid, codAmount, note, shipping{courierName, trackingId},
cancelReason, returnReason, statusHistory[{from, to, at, by{id, name}, note}], allowedActions, createdBy{id,
name}, version, createdAt, updatedAt}`. `*` `available` (live stock) only with `STOCK_VIEW` and only while
`PENDING`. `allowedActions` lists the actions possible from the current status for which the user has the
permission.

### Create order request

```json
{
  "requestId": "3f0c...-uuid",           // required, one per form
  "customerId": "6745...",               // either customerId ...
  "customer": {                          // ... or customer (find-or-create by phone)
    "name": "Rahim", "phone": "01712345678",
    "address": { "line": "House 5, Road 3", "area": "Mirpur", "district": "Dhaka", "zone": "INSIDE_DHAKA" }
  },
  "deliveryAddress": { ... },            // optional; default = inline address, else customer's default address
  "items": [
    { "variantId": "6746...", "quantity": 2, "unitPrice": 45000 }   // unitPrice optional, default = variant price
  ],
  "deliveryCharge": 6000,                // optional; default from shop settings by zone
  "discount": 0,
  "advancePaid": 0,
  "source": "MESSENGER",                 // FACEBOOK | MESSENGER | WHATSAPP | PHONE | OTHER
  "note": "Call before delivery"
}
```

Server computes `subtotal`, `total`, `codAmount`, item snapshots and `orderNumber`.
The client never sends totals. Inline customer creation is allowed with `ORDER_CREATE`
alone. Archived products and inactive variants → `409 VARIANT_NOT_ORDERABLE`. Stock is
**not** checked at create: it is reserved at confirm. The response shows current `available`
per item (with `STOCK_VIEW`) so the moderator sees shortages early.

Errors: `400 VALIDATION_FAILED`, `400 MALFORMED_REQUEST`, `403 FORBIDDEN`, `404 NOT_FOUND`
(unknown or other shop's order, customer or variant), `409 VARIANT_NOT_ORDERABLE`,
`INVALID_STATUS_TRANSITION`, `ORDER_NOT_EDITABLE`, `INSUFFICIENT_STOCK`,
`CONCURRENT_MODIFICATION`, `REQUEST_ID_REUSED`, `SHIPMENT_ACTIVE` (`ship` / `cancel` while the order is
booked with a courier, §8).

---

## 8. Shipments (courier)

Full design: `modules/COURIER.md`. Phase 2 has one courier, `MOCK` (a pretend courier for dev and
tests; off in prod). Money in poisha.

**Rules.** Only `PACKED` orders can be sent. Booking does **not** ship the order: it stays
`PACKED` until the courier reports the pickup, then it becomes `SHIPPED` (stock `DEDUCT`).
Courier `DELIVERED` delivers the order; courier `RETURNED` only marks the shipment (staff then call
`POST /orders/{id}/return` with the restock choices). While an order has an active shipment
(`BOOKING`, `CREATED`, `PICKED_UP`, `IN_TRANSIT`), `POST /orders/{id}/ship` and `/cancel` (also in
bulk) answer `409 SHIPMENT_ACTIVE`.

| Method | Path | Auth | Request → Response |
|--------|------|------|--------------------|
| POST | `/orders/{id}/shipment` | `ORDER_FULFILL` | `{provider: MOCK, note? (≤200)}` → `201 ShipmentResponse` (`CREATED`), or `202` (`BOOKING`: the courier did not answer; SellerDesk retries automatically with the same reference, so no second parcel), or `200` (already booked with this courier). `409 ORDER_NOT_PACKED`, `409 SHIPMENT_ALREADY_ACTIVE` (booked with another courier), `409 COURIER_REJECTED` (the courier refused; `fieldErrors` where known, e.g. `recipient.phone`; the shipment is `FAILED` and the order can be sent again), `400 COURIER_NOT_AVAILABLE`. |
| GET | `/orders/{id}/shipments` | `ORDER_VIEW` | → `[ShipmentSummary]`, every booking attempt, newest first |
| GET | `/shipments` | `ORDER_VIEW` | `?status=&provider=&page=&size=` → page of `ShipmentSummary`, newest change first (e.g. `status=RETURNED` for the "Returned by courier" tab). Unknown value → `400`. |
| GET | `/shipments/{id}` | `ORDER_VIEW` | → `ShipmentResponse` |
| POST | `/shipments/{id}/track` | `ORDER_VIEW` | no body → `ShipmentResponse`. Asks the courier (at most once a minute per shipment) and applies the answer like a webhook, which may move the order. `503 COURIER_UNAVAILABLE` → nothing changed. |
| POST | `/shipments/{id}/cancel` | `ORDER_FULFILL` | `{reason (1–500)}` → `ShipmentResponse` (`CANCELLED`). Only `BOOKING` / `CREATED`, else `409 SHIPMENT_NOT_CANCELLABLE`. Again on a cancelled shipment → `200`. The order stays `PACKED`. `503 COURIER_UNAVAILABLE` → nothing changed. |
| POST | `/shipments/bulk` | `ORDER_FULFILL` | `{provider, orderIds (1–20, unique)}` → `200 {provider, succeeded, failed, results[{orderId, orderNumber?, ok, shipmentId?, status?, errorCode?, message?}]}`. One order after the other, like the single send; `BOOKING` counts as ok; another shop's or unknown id → `NOT_FOUND`. |
| POST | `/webhooks/courier/{provider}` | **public, signed** | Called by the courier (below) → `200 {result: PROCESSED\|DUPLICATE\|IGNORED}`. `401 INVALID_SIGNATURE`, `404 NOT_FOUND` (unknown courier or consignment: the courier retries later), `400 MALFORMED_REQUEST`, `413 PAYLOAD_TOO_LARGE` (> 64 KB). |

`ShipmentResponse`: `{id, orderId, orderNumber, provider, consignmentId, trackingCode, status (BOOKING |
CREATED | PICKED_UP | IN_TRANSIT | DELIVERED | RETURNED | CANCELLED | FAILED), codAmount, collectedAmount,
codMismatch, courierFee, recipient{name, phone, address}, note, failureReason, needsManualCheck,
cancelReason, events[{at, source (USER|SYSTEM|WEBHOOK|POLL), status, providerStatus, ignored, note}],
allowedActions (TRACK, CANCEL), createdBy{id, name}, createdAt, updatedAt}`. `needsManualCheck = true`:
SellerDesk gave up after repeated timeouts and does not know whether the courier has the parcel; check
the courier's panel before sending again. `codMismatch = true`: the courier collected a different
amount than `codAmount`.

`ShipmentSummary`: `{id, orderId, orderNumber, provider, consignmentId, trackingCode, status, codAmount,
collectedAmount, codMismatch, failureReason, needsManualCheck, createdAt, updatedAt}`.

### Webhook (mock courier)

```
POST /api/v1/webhooks/courier/mock
X-SD-Timestamp: 1764320000
X-SD-Signature: sha256=<hex(HMAC_SHA256(COURIER_WEBHOOK_SECRET, timestamp + "." + rawBody))>

{"eventId": "e-123", "consignmentId": "MOCK-0123456789", "merchantRef": "<shipment id>",
 "status": "booked|picked_up|in_transit|location_update|delivered|returned|cancelled",
 "time": "2026-11-28T10:00:00Z", "collectedAmount": 96000, "courierFee": 6000, "note": "..."}
```

- No token. The signature is checked before anything else; a timestamp more than 5 minutes off is
  refused (no replays). Every signature problem gives the same `401`.
- The shop is taken from the stored shipment (found by `consignmentId`, else `merchantRef`). A
  `shopId` or `orderId` in the body is ignored.
- Idempotent: the same event again (same `eventId`, or same consignment + status + time) →
  `DUPLICATE`, nothing changes.
- A status jump that is not allowed (e.g. `delivered` → `in_transit`) → `IGNORED`: stored as an
  ignored event, nothing else changes.

---

## 9. Dashboard

| Method | Path | Auth | Request → Response |
|--------|------|------|--------------------|
| GET | `/dashboard/summary` | `ORDER_VIEW` | → `{ordersByStatus: {PENDING: 12, ...}, todayOrders, lowStockCount}`. Money fields (`todayRevenue`, `codPendingAmount`) only with `REPORT_VIEW`. |

Simple counts only. Real analytics is Phase 2+.

---

## 10. Operational (not under `/api/v1`)

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| GET | `/actuator/health` | public | liveness for hosting |
| GET | `/swagger-ui.html`, `/v3/api-docs` | public in `dev` only | disabled in `prod` |
