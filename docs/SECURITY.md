# SellerDesk — Security (Phase 1)

---

## 1. Authentication: email + password

Users log in with email and password. A 6-digit email code (OTP) is used only to
**prove the user owns the email**: once after registration, and for password reset.
It is not needed on every login.

### Flows

```
Register (new seller)
1. POST /auth/register-shop {ownerName, email, password, shopName, shopPhone, ...}
      → creates shop + SHOP_OWNER user (status PENDING_VERIFICATION) in one transaction
      → creates otp_tokens doc (purpose EMAIL_VERIFICATION), emails the code
      → 201, no tokens yet
2. POST /auth/verify-email {email, code}
      → PENDING_VERIFICATION becomes ACTIVE → access token + refresh cookie

Login
   POST /auth/login {email, password}
      → ACTIVE user + correct password → access token + refresh cookie
      → wrong email, wrong password, DISABLED, INVITED → same generic 401 INVALID_CREDENTIALS
      → correct password but PENDING_VERIFICATION → 403 EMAIL_NOT_VERIFIED
        (safe to reveal: the caller already proved they know the password)

Forgot password (also used by invited staff to set their first password)
1. POST /auth/password/forgot {email}
      → always 202 with the same message, whether or not the email has an account
        (so attackers cannot discover which emails are registered)
      → if the user exists and is not DISABLED: OTP (purpose PASSWORD_RESET) emailed
2. POST /auth/password/reset {email, code, newPassword}
      → new passwordHash, tokenVersion + 1 (logs out every device)
      → INVITED or PENDING_VERIFICATION becomes ACTIVE (the code proved email ownership)
      → 204; the user then logs in normally
```

Staff never self-register. A shop owner (or anyone with `STAFF_MANAGE`) creates them as
`INVITED` with no password; the invite email points them to "set password", which is the
forgot-password flow above.

### Password rules

| Rule | Value |
|------|-------|
| Hash | BCrypt (Spring `BCryptPasswordEncoder`, strength 12). Plain password never stored or logged. |
| Length | 8–72 characters. 72 is BCrypt's input limit; longer input would be silently cut. |
| Content | At least one letter and one digit. Must not equal the email. Each broken rule gives its own field error. |
| Login failures | max 5 failed logins per email per 15 min, then `429 LOGIN_RATE_LIMITED` (in-memory, per instance). Per-IP limit: deferred until the app reads the real client IP behind the Render proxy (`X-Forwarded-For`). |
| Unknown email | still runs one BCrypt check against a dummy hash, so response time does not reveal whether the email exists |
| Password change / reset | `tokenVersion + 1` |

### OTP rules (email verification and password reset)

| Rule | Value |
|------|-------|
| Code | 6 digits from `SecureRandom` |
| Storage | BCrypt hash only |
| Expiry | 10 minutes (`expiresAt`, TTL index cleans up; service checks expiry itself) |
| Wrong attempts | max 5 per code, then the code is dead |
| Resend cooldown | 60 seconds per email and purpose |
| Request limit | 5 codes per email per hour. Per-IP limit: deferred (`SCOPE.md`). |
| Silent limits | Resend and forgot always answer `202` immediately; eligibility and limits are checked in a background task. Over the limit → no email, but the same response, so limits reveal nothing about which emails exist. |
| Single use | `consumedAt` set on success; new request deletes older codes of the same purpose |
| Purpose | A code is only valid for the purpose it was created for |
| Disabled user | verify / reset fails with the same generic `INVALID_OTP` |

Comparison of codes and passwords uses BCrypt's verify (constant-time), not `equals`.

---

## 2. Tokens

### Types

| Token | Lifetime | Where client keeps it | Sent as |
|-------|----------|-----------------------|---------|
| **Access** (JWT) | **15 min** | JavaScript memory only (not localStorage) | `Authorization: Bearer ...` |
| **Refresh** (JWT) | **30 days** | `HttpOnly` cookie `sd_refresh`, `Path=/api/v1/auth`; `SameSite` / `Secure` per profile (below) | cookie, only to auth endpoints |

Why this split:
- Access token in memory: an XSS bug cannot read it from storage after a reload; short life
  limits damage if stolen.
- Refresh token in an HttpOnly cookie: JavaScript cannot read it at all.
- On page reload the frontend calls `/auth/refresh` to get a new access token.
- Login, verify-email and refresh set a new cookie (rotation). The cookie's `Max-Age` equals
  the refresh token lifetime.

### Refresh cookie attributes per profile

| Profile | `SameSite` | `Secure` | Why |
|---------|-----------|----------|-----|
| dev | `Strict` | `false` | `localhost:5173` → `localhost:8080` is the **same site** (ports don't count), and plain `http` cannot carry `Secure` cookies. |
| prod | `None` | `true` | Frontend and API run on different Render subdomains, which is **cross-site** (see below). Browsers only accept `SameSite=None` together with `Secure`. |

Configured by `sellerdesk.refresh-cookie.same-site` / `.secure`
(env `REFRESH_COOKIE_SAME_SITE`, `REFRESH_COOKIE_SECURE`). The app refuses to start with
`SameSite=None` and `Secure=false`.

**Frontend requirement:** every call to `/api/v1/auth/*` must send credentials
(`axios` `withCredentials: true`, `fetch` `credentials: "include"`), otherwise the browser
neither sends nor stores the cookie. CORS allows credentials for `FRONTEND_URL` only (never `*`).

**Why Render subdomains are cross-site:** `onrender.com` is on the Public Suffix List, so
`app.onrender.com` and `api.onrender.com` are two different *sites*, like two unrelated domains.

**Known limitation:** Safari (and iOS browsers) block third-party cookies by default, and a
`SameSite=None` cookie on a different site is a third-party cookie. Refresh can fail there:
the user then has to log in again after a reload. Fix: a custom domain, e.g.
`app.sellerdesk.com` + `api.sellerdesk.com`. That is the same site, so prod can switch to
`SameSite=Strict` with no code change.

### CSRF and `SameSite=None`

`SameSite=Strict` would stop other sites from making the browser send the cookie. With `None`
the browser **does** attach it to a request that `evil.com` triggers. What that attacker can and
cannot do, and how we limit it:

| Risk | Mitigation |
|------|-----------|
| `evil.com` posts to `/auth/refresh` | `Origin` check: the endpoint rejects any request whose `Origin` header is present and not `FRONTEND_URL` (`403 INVALID_ORIGIN`). Browsers always send `Origin` on cross-site POSTs. |
| Attacker reads the new access token | Impossible: CORS lets only `FRONTEND_URL` read responses. The token is in the body, never in a cookie. |
| Cookie sent to other endpoints | `Path=/api/v1/auth` limits it to auth endpoints. All other endpoints authenticate with the `Authorization` header only, which another site cannot set. |
| Forced logout | `/auth/logout` requires the Bearer access token, not the cookie. |

So even with `SameSite=None` the refresh cookie alone cannot be used to act as the user.

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

The filter rejects a token whose `typ` is wrong for the endpoint (a refresh token cannot be
used as an access token).

### Revocation: `tokenVersion`

JWTs cannot be "deleted", so each user has a `tokenVersion` number:
- Every token carries `ver`.
- `JwtAuthFilter` compares `ver` with the user's current `tokenVersion` (cached lookup).
- Logout, disable, role change, permission change, password change/reset → `tokenVersion + 1` → every existing
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
- Class `TenantIsolationTest`, extending `MongoIntegrationTestBase`: real MongoDB (Atlas
  `*-test` database via `MONGODB_TEST_URI` locally, Testcontainers in CI), real replica set,
  real indexes, real transactions.
- Fixture: **two shops**, A and B, created through the real register → verify flow with unique
  emails; each module adds its own records (products, customers, orders...) in its `@Nested` class.
- Covered today: own shop only (`/shop`, `/auth/me`); tampered `shopId` claim, other signing key,
  correctly signed token whose `shopId` differs from the user's shop, expired token, refresh
  token as access token → `401`; access token issued before logout or password reset → `401`
  (the filter checks `ver` against the user's current `tokenVersion` on **every** request, and
  logout/reset evict the cache, so there is no delay); `shopId` in query, header or body ignored;
  `TenantContext` empty after every request.
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
| CSRF | Not needed for Bearer-token endpoints. Refresh cookie protected by path + `Origin` check (+ `SameSite=Strict` where same-site), see §2. Spring CSRF disabled. |
| Sessions | Stateless (`SessionCreationPolicy.STATELESS`). |
| Rate limits | Login, OTP request/verify (see §1). In-memory; per instance. |
| Input | Bean Validation on all DTOs; max lengths on every string; max list sizes (items ≤ 50, variants ≤ 100). |
| NoSQL injection | Only typed repository methods / `Criteria` builders. Never build queries from raw JSON strings from the client. |
| Errors | No stack traces, class names or DB messages in responses. |
| Logging | Never log passwords, password hashes, OTP codes, tokens, or the full `MONGODB_URI`. |
| Responses | `passwordHash` never leaves the server: user DTOs have no such field. |
| Actuator | Only `/actuator/health` public; everything else off. |
| Secrets | Environment variables only (see `ARCHITECTURE.md` §6). `.env` in `.gitignore`. |
