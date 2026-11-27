# SellerDesk — REST API (Phase 1)

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
| PATCH | `/products/{id}/variants/{variantId}` | `PRODUCT_EDIT` | `{name?, attributes?, price?, costPrice?, lowStockThreshold?, active?, version}` → `ProductResponse`. SKU and stock cannot be changed. |
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

| Method | Path | Auth | Request → Response |
|--------|------|------|--------------------|
| GET | `/inventory` | `STOCK_VIEW` | `?q=&lowStock=true&page=` → page of `StockRow {productId, productName, variantId, sku, variantName, onHand, reserved, available, lowStockThreshold, isLow}` (one row per variant) |
| POST | `/inventory/adjustments` | `STOCK_ADJUST` | `{productId, variantId, type: ADJUST_IN or ADJUST_OUT, quantity (≥1), reason}` → `201 {movement, stock{onHand, reserved, available}}`. `409 INSUFFICIENT_STOCK` if `ADJUST_OUT` would make `available < 0` (reserved units cannot be removed). |
| GET | `/inventory/movements` | `STOCK_VIEW` | `?productId=&variantId=&type=&orderId=&from=&to=` → page of `MovementResponse {id, sku, type, quantity, onHandAfter, reservedAfter, orderId, orderNumber, reason, performedBy{id, name}, createdAt}`, newest first |

---

## 6. Customers

| Method | Path | Auth | Request → Response |
|--------|------|------|--------------------|
| GET | `/customers` | `CUSTOMER_VIEW` | `?q=` (phone or name prefix) `&tag=` → page of `CustomerSummary {id, name, phone, district, stats, tags}` |
| GET | `/customers/lookup` | `CUSTOMER_VIEW` or `ORDER_CREATE` | `?phone=` → `CustomerResponse` or `404`. Used on the order form: type a phone, auto-fill the customer. |
| POST | `/customers` | `CUSTOMER_EDIT` | `{name, phone, altPhone?, facebookProfileUrl?, addresses?, note?, tags?}` → `201 CustomerResponse`. `409 PHONE_ALREADY_EXISTS`. |
| GET | `/customers/{id}` | `CUSTOMER_VIEW` | → `CustomerResponse {id, name, phone, altPhone, facebookProfileUrl, addresses, note, tags, stats, createdAt}` |
| PUT | `/customers/{id}` | `CUSTOMER_EDIT` | same body as create → `CustomerResponse` |
| GET | `/customers/{id}/orders` | `CUSTOMER_VIEW` + `ORDER_VIEW` | → page of `OrderSummary` |

No delete in Phase 1 (orders reference customers).

---

## 7. Orders

| Method | Path | Auth | Request → Response |
|--------|------|------|--------------------|
| GET | `/orders` | `ORDER_VIEW` | `?status=&from=&to=&q=` (order number or phone) `&source=` → page of `OrderSummary {id, orderNumber, status, customerName, customerPhone, district, itemCount, total, codAmount, createdAt}` |
| POST | `/orders` | `ORDER_CREATE` | see below → `201 OrderResponse` (status `PENDING`) |
| GET | `/orders/{id}` | `ORDER_VIEW` | → `OrderResponse` (full: customer snapshot, items, money fields, shipping, statusHistory, createdBy) |
| PUT | `/orders/{id}` | `ORDER_EDIT` | Same shape as create. PENDING: everything editable. CONFIRMED/PACKED: only `address`, `note`. SHIPPED: only `note`. Else `409 ORDER_NOT_EDITABLE`. Uses `version` → `409 CONCURRENT_MODIFICATION`. |
| POST | `/orders/{id}/confirm` | `ORDER_CONFIRM` | `{note?}` → `OrderResponse`. Reserves stock in one transaction. `409 INSUFFICIENT_STOCK` (lists SKUs) or `INVALID_STATUS_TRANSITION`. |
| POST | `/orders/{id}/pack` | `ORDER_FULFILL` | `{note?}` → `OrderResponse` |
| POST | `/orders/{id}/ship` | `ORDER_FULFILL` | `{courierName?, trackingId?, note?}` → `OrderResponse`. Stock `SHIP_OUT`. |
| POST | `/orders/{id}/deliver` | `ORDER_CLOSE` | `{note?}` → `OrderResponse`. Updates customer stats. |
| POST | `/orders/{id}/return` | `ORDER_CLOSE` | `{reason, restock (default true)}` → `OrderResponse`. Stock `RETURN_IN` if restock. |
| POST | `/orders/{id}/cancel` | `ORDER_CANCEL` | `{reason}` → `OrderResponse`. Allowed from PENDING, CONFIRMED, PACKED. Releases reserved stock. |

### Create order request

```json
{
  "customerId": "6745...",               // either customerId ...
  "customer": {                          // ... or customer (find-or-create by phone)
    "name": "Rahim", "phone": "01712345678",
    "address": { "line": "House 5, Road 3", "area": "Mirpur", "district": "Dhaka", "zone": "INSIDE_DHAKA" }
  },
  "deliveryAddress": { ... },            // optional; default = customer's first address
  "items": [
    { "variantId": "6746...", "quantity": 2, "unitPrice": 45000 }   // unitPrice optional, default = variant price
  ],
  "deliveryCharge": 6000,                // optional; default from shop settings by zone
  "discount": 0,
  "advancePaid": 0,
  "source": "MESSENGER",
  "note": "Call before delivery"
}
```

Server computes `subtotal`, `total`, `codAmount`, item snapshots and `orderNumber`.
The client never sends totals. Inline customer creation is allowed with `ORDER_CREATE`
alone, so moderators can take orders fast. Stock is **not** checked at create: it is
reserved at confirm. The response shows current `available` per item so the moderator sees
shortages early.

---

## 8. Dashboard

| Method | Path | Auth | Request → Response |
|--------|------|------|--------------------|
| GET | `/dashboard/summary` | `ORDER_VIEW` | → `{ordersByStatus: {PENDING: 12, ...}, todayOrders, lowStockCount}`. Money fields (`todayRevenue`, `codPendingAmount`) only with `REPORT_VIEW`. |

Simple counts only. Real analytics is Phase 2+.

---

## 9. Operational (not under `/api/v1`)

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| GET | `/actuator/health` | public | liveness for hosting |
| GET | `/swagger-ui.html`, `/v3/api-docs` | public in `dev` only | disabled in `prod` |
