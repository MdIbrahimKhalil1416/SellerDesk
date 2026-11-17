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
| POST | `/auth/otp/request` | public | `{email}` → `202 {message, resendAfterSeconds}`. Same response whether or not the account exists. `429` if rate-limited. |
| POST | `/auth/otp/verify` | public | `{email, code}` → existing user: `200 {accessToken, expiresIn, user}` + refresh cookie. New email: `200 {signupRequired: true, signupToken}`. Wrong/expired code: `400 INVALID_OTP`. |
| POST | `/auth/register-shop` | public (signup token) | `{signupToken, ownerName, shopName, shopPhone, address?, facebookPageUrl?}` → `201 {accessToken, expiresIn, user, shop}` + refresh cookie. Creates shop + SHOP_OWNER. |
| POST | `/auth/refresh` | refresh cookie | no body → `200 {accessToken, expiresIn}` + new refresh cookie. `401` if cookie invalid/old version. |
| POST | `/auth/logout` | auth | no body → `204`, clears cookie, increments `tokenVersion` (all devices). |
| GET | `/auth/me` | auth | → `{user: {id, name, email, role, permissions}, shop: {id, name, status}}` |

`user` object: `{id, name, email, phone, role, permissions[], status}`.

---

## 2. Shop

| Method | Path | Auth | Request → Response |
|--------|------|------|--------------------|
| GET | `/shop` | auth | → `ShopResponse {id, name, phone, email, address, facebookPageUrl, status, settings}` |
| PUT | `/shop` | `SHOP_SETTINGS` | `{name, phone, email?, address?, facebookPageUrl?, settings{deliveryChargeInsideDhaka, deliveryChargeOutsideDhaka, defaultLowStockThreshold}}` → `ShopResponse` |

---

## 3. Staff

| Method | Path | Auth | Request → Response |
|--------|------|------|--------------------|
| GET | `/staff` | `STAFF_MANAGE` | `?role=&status=` → page of `StaffResponse {id, name, email, phone, role, permissions, status, lastLoginAt}` (includes owner) |
| POST | `/staff` | `STAFF_MANAGE` | `{email, name, phone?, role (MODERATOR/PACKER/ACCOUNTANT), permissions?}` → `201 StaffResponse` (status `INVITED`), sends invite email. `409 EMAIL_ALREADY_REGISTERED` if the email has any account. |
| GET | `/staff/{userId}` | `STAFF_MANAGE` | → `StaffResponse` |
| PATCH | `/staff/{userId}` | `STAFF_MANAGE` | `{name?, phone?, role?, permissions?}` → `StaffResponse`. Role change resets permissions to defaults unless `permissions` also given. Bumps `tokenVersion`. Not allowed on owner or self. |
| PATCH | `/staff/{userId}/status` | `STAFF_MANAGE` | `{status: ACTIVE or DISABLED}` → `StaffResponse`. Disable bumps `tokenVersion`. Not allowed on owner or self. |
| GET | `/staff/permissions` | `STAFF_MANAGE` | → `{permissions: [{code, description}], roleDefaults: {MODERATOR: [...], ...}}` (for the staff edit UI) |

---

## 4. Products

| Method | Path | Auth | Request → Response |
|--------|------|------|--------------------|
| GET | `/products` | `PRODUCT_VIEW` | `?q=` (name prefix or exact SKU) `&status=&category=` → page of `ProductSummary {id, name, category, status, variantCount, priceRange, totalAvailable, imageUrl}` |
| POST | `/products` | `PRODUCT_EDIT` | `{name, description?, category?, imageUrls?, variants: [{sku, name, attributes?, price, costPrice?, lowStockThreshold?, initialStock?}]}` → `201 ProductResponse`. `initialStock > 0` writes an `INITIAL` movement. `409 SKU_ALREADY_EXISTS`. |
| GET | `/products/{id}` | `PRODUCT_VIEW` | → `ProductResponse {id, name, description, category, imageUrls, status, variants: [{variantId, sku, name, attributes, price, costPrice*, lowStockThreshold, active, stock{onHand, reserved, available}}]}` (*only with `PRODUCT_EDIT`/`REPORT_VIEW`) |
| PUT | `/products/{id}` | `PRODUCT_EDIT` | `{name, description?, category?, imageUrls?}` → `ProductResponse`. Product fields only. |
| POST | `/products/{id}/variants` | `PRODUCT_EDIT` | `{sku, name, attributes?, price, costPrice?, lowStockThreshold?, initialStock?}` → `201 ProductResponse` |
| PATCH | `/products/{id}/variants/{variantId}` | `PRODUCT_EDIT` | `{name?, attributes?, price?, costPrice?, lowStockThreshold?, active?}` → `ProductResponse`. SKU and stock cannot be changed here. |
| PATCH | `/products/{id}/status` | `PRODUCT_EDIT` | `{status: ACTIVE or ARCHIVED}` → `ProductResponse`. Archived products cannot be added to new orders. |

Stock fields are never accepted by product endpoints (except `initialStock` on create). All
other stock changes go through Inventory.

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
