# SellerDesk — Security (Phase 1)

---

## 1. Authentication: email OTP

No passwords. A user proves they own their email by entering a 6-digit code sent to it.

### Flow

```
1. POST /auth/otp/request {email}
      → always 202 with the same message, whether or not the email has an account
        (so attackers cannot discover which emails are registered)
      → creates otp_tokens doc (BCrypt hash of code), emails the code

2. POST /auth/otp/verify {email, code}
      a) user exists (ACTIVE or INVITED) → INVITED becomes ACTIVE → access token + refresh cookie
      b) no user → { signupRequired: true, signupToken }   (short-lived, see §2)

3. (new seller only) POST /auth/register-shop {signupToken, ownerName, shopName, phone, ...}
      → creates shop + SHOP_OWNER user in one transaction → access token + refresh cookie
```

Staff never self-register. A shop owner (or anyone with `STAFF_MANAGE`) creates them as
`INVITED`; they receive an email and then log in with step 1–2.

### OTP rules

| Rule | Value |
|------|-------|
| Code | 6 digits from `SecureRandom` |
| Storage | BCrypt hash only |
| Expiry | 5 minutes (`expiresAt`, TTL index cleans up; service checks expiry itself) |
| Wrong attempts | max 5 per code, then the code is dead |
| Resend cooldown | 60 seconds per email |
| Request limit | 5 codes per email per hour; also per-IP limit (in-memory) |
| Single use | `consumedAt` set on success; new request deletes older codes |
| Disabled user | verify fails with the same generic `INVALID_OTP` |

Comparison of codes uses BCrypt's verify (constant-time), not `equals`.

---

## 2. Tokens

### Types

| Token | Lifetime | Where client keeps it | Sent as |
|-------|----------|-----------------------|---------|
| **Access** (JWT) | **15 min** | JavaScript memory only (not localStorage) | `Authorization: Bearer ...` |
| **Refresh** (JWT) | **30 days** | `HttpOnly; Secure; SameSite=Strict` cookie, `Path=/api/v1/auth` | cookie, only to auth endpoints |
| **Signup** (JWT) | **15 min** | memory, used once | body of `/auth/register-shop` |

Why this split:
- Access token in memory: an XSS bug cannot read it from storage after a reload; short life
  limits damage if stolen.
- Refresh token in an HttpOnly cookie: JavaScript cannot read it at all. `SameSite=Strict`
  plus the narrow `Path` blocks CSRF: other sites cannot make the browser send it.
  Requires frontend and API on the **same site** (e.g. `app.sellerdesk.com` and
  `api.sellerdesk.com`).
- On page reload the frontend calls `/auth/refresh` to get a new access token.

### Signing

- HS256 (HMAC-SHA256) with `JWT_SECRET` from environment (≥ 32 random bytes).
- One app signs and verifies, so a shared secret is enough. RS256 (public/private keys)
  would be needed only if other services verified our tokens.
- Library: `jjwt`.

### Access token claims

```json
{
  "iss": "sellerdesk",
  "sub": "6745a1...",              // userId
  "shopId": "6745b2...",           // null for PLATFORM_ADMIN
  "role": "MODERATOR",
  "perms": ["ORDER_VIEW", "ORDER_CREATE", "..."],
  "ver": 3,                        // user's tokenVersion
  "typ": "access",
  "iat": 1763460000,
  "exp": 1763460900
}
```

Refresh token: `sub`, `ver`, `typ: "refresh"`, `iat`, `exp`. No role/permissions:
they are re-read from the database on every refresh, so changes are picked up.
Signup token: `sub` = email, `typ: "signup"`.

The filter rejects a token whose `typ` is wrong for the endpoint (a refresh token cannot be
used as an access token).

### Revocation: `tokenVersion`

JWTs cannot be "deleted", so each user has a `tokenVersion` number:
- Every token carries `ver`.
- `JwtAuthFilter` compares `ver` with the user's current `tokenVersion` (cached lookup).
- Logout, disable, role change, permission change → `tokenVersion + 1` → every existing
  access **and** refresh token of that user stops working immediately.

Trade-offs:
- Logout signs the user out on **all devices**. Per-device logout needs a stored
  refresh-token table: deferred.
- Refresh tokens are rotated (a new cookie on each refresh), but without storage we cannot
  detect reuse of an old stolen refresh token. Same fix later: a `refresh_tokens` collection.
- The filter does one cached DB lookup per request. That's the price of instant revocation.

---

## 3. Authorization: roles and permissions

**Role** = a label and a default set of permissions.
**Permissions** = what is actually checked. Stored per user, so an owner can give one
moderator extra rights without inventing a new role.

### Permission list

| Permission | Allows |
|------------|--------|
| `SHOP_SETTINGS` | Edit shop profile and settings |
| `STAFF_MANAGE` | Invite, edit, disable staff; change their role and permissions |
| `PRODUCT_VIEW` | See products and variants |
| `PRODUCT_EDIT` | Create/edit/archive products and variants |
| `STOCK_VIEW` | See stock levels and movement log |
| `STOCK_ADJUST` | Manual stock in/out |
| `CUSTOMER_VIEW` | See customers |
| `CUSTOMER_EDIT` | Create/edit customers |
| `ORDER_VIEW` | See orders |
| `ORDER_CREATE` | Create orders |
| `ORDER_EDIT` | Edit orders (items only while PENDING) |
| `ORDER_CONFIRM` | PENDING → CONFIRMED |
| `ORDER_FULFILL` | CONFIRMED → PACKED → SHIPPED |
| `ORDER_CLOSE` | SHIPPED → DELIVERED or RETURNED |
| `ORDER_CANCEL` | → CANCELLED |
| `REPORT_VIEW` | Money totals on the dashboard, cost prices |

### Default permissions per role

| Permission | SHOP_OWNER | MODERATOR | PACKER | ACCOUNTANT |
|------------|:---:|:---:|:---:|:---:|
| `SHOP_SETTINGS` | ✓ | | | |
| `STAFF_MANAGE`  | ✓ | | | |
| `PRODUCT_VIEW`  | ✓ | ✓ | ✓ | ✓ |
| `PRODUCT_EDIT`  | ✓ | | | |
| `STOCK_VIEW`    | ✓ | ✓ | ✓ | ✓ |
| `STOCK_ADJUST`  | ✓ | | | |
| `CUSTOMER_VIEW` | ✓ | ✓ | | ✓ |
| `CUSTOMER_EDIT` | ✓ | ✓ | | |
| `ORDER_VIEW`    | ✓ | ✓ | ✓ | ✓ |
| `ORDER_CREATE`  | ✓ | ✓ | | |
| `ORDER_EDIT`    | ✓ | ✓ | | |
| `ORDER_CONFIRM` | ✓ | ✓ | | |
| `ORDER_FULFILL` | ✓ | | ✓ | |
| `ORDER_CLOSE`   | ✓ | ✓ | | ✓ |
| `ORDER_CANCEL`  | ✓ | ✓ | | |
| `REPORT_VIEW`   | ✓ | | | ✓ |

Rules:
- `SHOP_OWNER` always has **all** shop permissions; they cannot be removed. Exactly one owner per shop.
- Staff roles can be customized by adding/removing permissions. Changing the role resets
  permissions to that role's defaults.
- Nobody can assign `SHOP_OWNER` or `PLATFORM_ADMIN` through the staff API, and nobody can
  edit or disable the owner.
- A user cannot change their own role or permissions (prevents self-escalation).
- `PLATFORM_ADMIN`: seeded from `PLATFORM_ADMIN_EMAIL` at startup, no `shopId`, **no shop
  permissions**. All shop endpoints return `403` for it. Platform endpoints are Phase 2+.
- Permissions are defined in one Java `enum`; role defaults in one place in `common.security`.
- Cost prices (`costPrice`) are only included in product responses when the user has
  `REPORT_VIEW` or `PRODUCT_EDIT`.

---

## 4. Tenant isolation: how it is enforced

Defense in layers. Each layer alone should be enough; together they make mistakes unlikely.

| # | Layer | Mechanism |
|---|-------|-----------|
| 1 | Source of truth | `shopId` only from the verified JWT → `TenantContext`. |
| 2 | Input | Request DTOs have no `shopId` field. URLs have no `shopId`. |
| 3 | Repository API | Tenant repositories extend `Repository<T, ID>`, **not** `MongoRepository`. Only methods with a `shopId` parameter exist (`findByIdAndShopId`, `findAllByShopId`...). No inherited `findById`/`findAll`. |
| 4 | Custom queries | `MongoTemplate` queries start from `TenantCriteria.of(shopId)`. |
| 5 | Writes | Service sets `entity.shopId` from context on create. Updates filter by `{_id, shopId}`. |
| 6 | Cross-reference checks | When an order references a `customerId` / `variantId`, the service loads it **with the same `shopId`**. A valid id from another shop → `404`. |
| 7 | Response | Cross-tenant access returns `404`, never `403`, so ids from other shops can't be probed. |
| 8 | Unique indexes | Per-shop uniqueness (`{shopId, phone}`, `{shopId, variants.sku}`) so one shop's data never blocks another's. |

---

## 5. Tenant isolation: how it is tested

### a) Unit tests (services)
- Mock repositories; set `TenantContext` to shop A; assert repositories were called with
  shop A's id (`verify(repo).findByIdAndShopId(id, "A")`).
- Assert creating an entity sets `shopId` from context.

### b) Integration tests (the important ones)
- `@SpringBootTest` + Testcontainers MongoDB (real replica set, real indexes, real transactions).
- Fixture: **two shops**, A and B, each with an owner, products, customers, orders.
- For **every** endpoint that takes an `{id}`: user of shop A uses shop B's id → expect `404`,
  and B's document unchanged in the DB.
- For every list endpoint: A's user sees only A's data.
- Create endpoints: send a JSON body that includes `"shopId": "<B>"` → the saved document
  has `shopId = A`.
- Order create with B's `customerId` or `variantId` → `404`.
- Token tests: expired token → `401`; tampered signature → `401`; refresh token used as
  access token → `401`; old `ver` after logout → `401`; missing permission → `403`;
  `PLATFORM_ADMIN` on shop endpoint → `403`.

### c) Architecture tests (ArchUnit)
- Classes in `..repository..` of tenant modules must not be assignable to `MongoRepository`
  / `CrudRepository`.
- Every method in a tenant repository must have a parameter named `shopId`.
- Controllers must not return types from `..model..` packages.
- `..controller..` must not depend on `..repository..`.

These run on every build, so a new endpoint or repository that breaks the rules fails CI.

---

## 6. Other protections

| Topic | Phase 1 approach |
|-------|------------------|
| HTTPS | Required in production (hosting / proxy terminates TLS). `Secure` cookie flag depends on it. |
| CORS | Only `FRONTEND_URL` allowed, with credentials. |
| CSRF | Not needed for Bearer-token endpoints. Refresh cookie protected by `SameSite=Strict` + path. Spring CSRF disabled. |
| Sessions | Stateless (`SessionCreationPolicy.STATELESS`). |
| Rate limits | OTP request/verify (see §1). In-memory; per instance. |
| Input | Bean Validation on all DTOs; max lengths on every string; max list sizes (items ≤ 50, variants ≤ 100). |
| NoSQL injection | Only typed repository methods / `Criteria` builders. Never build queries from raw JSON strings from the client. |
| Errors | No stack traces, class names or DB messages in responses. |
| Logging | Never log OTP codes, tokens, or the full `MONGODB_URI`. |
| Actuator | Only `/actuator/health` public; everything else off. |
| Secrets | Environment variables only (see `ARCHITECTURE.md` §6). `.env` in `.gitignore`. |
