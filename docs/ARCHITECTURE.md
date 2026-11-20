# SellerDesk — Architecture (Phase 1)

Related docs: `SCOPE.md` (what we build), `SCHEMA.md` (data), `SECURITY.md` (auth and
tenant isolation), `API.md` (endpoints).

---

## 1. Style: modular monolith

SellerDesk backend is **one Spring Boot application** (one deployable JAR, one database),
split internally into **feature modules**.

| Option              | Why not / why                                                              |
|---------------------|----------------------------------------------------------------------------|
| Microservices       | Too much infrastructure for a small team. Order confirm must touch orders and stock atomically; across services that needs sagas. Not worth it now. |
| Layer-first packages (`controllers/`, `services/`...) | Every feature is spread across the whole tree. Hard to see module boundaries. |
| **Modular monolith (chosen)** | One deploy, one transaction manager, simple local dev. Clear feature boundaries, so a module can be extracted later if ever needed. |

### Modules

```
com.sellerdesk
├── common      shared: security, tenant context, errors, base document, config
├── auth        login (email + password, OTP for verification/reset), JWT, users, otp_tokens
├── shop        shop registration and settings
├── staff       invite / manage staff users, permissions
├── product     products and variants (catalog data)
├── inventory   stock levels inside variants, stock movement log
├── customer    customer records
└── order       order pipeline
```

Each module has: `controller`, `service`, `repository`, `dto`, `model`.

### Module rules

1. A module talks to another module **only through its service**, never its repository.
   Example: `OrderService` calls `InventoryService.reserve(...)`; it never touches
   `products` directly.
2. Allowed dependency direction (no cycles):

   ```
   order ──► inventory ──► product
     │  └──► customer
     └─────► product
   staff ──► auth
   auth  ──► shop          (shop registration, and the shop summary in /auth/me)
   all   ──► common
   ```

3. **Data ownership.** Each collection has exactly one owning module that writes it.

   | Collection        | Owner       | Note |
   |-------------------|-------------|------|
   | `shops`           | `shop`      | |
   | `users`           | `auth`      | `staff` changes users via `UserService` |
   | `otp_tokens`      | `auth`      | |
   | `products`        | `product`   | **except** `variants[].stock.*`, written only by `inventory` (product only writes the zeros when a variant is created) |
| `image_cleanup_tasks` | `common.storage` | Cloudinary files to delete; swept by a job across shops |
   | `stock_movements` | `inventory` | append-only |
   | `customers`       | `customer`  | order counters updated via `CustomerService` |
   | `orders`          | `order`     | |

   Why the stock exception: stock lives inside the variant (see `SCHEMA.md`) so reads are
   fast, but every stock change must also write a movement log entry. Keeping all stock
   writes in one module guarantees no stock change skips the log.

---

## 2. Layers

| Layer          | Job                                                                 | Must not |
|----------------|---------------------------------------------------------------------|----------|
| **Controller** | HTTP mapping, `@Valid` on request DTOs, permission check, call service, return DTO | Contain business logic, return entities, read `shopId` from request |
| **Service**    | Business rules, transactions, calls other modules' services, maps entity ⇄ DTO | Know about HTTP (`HttpServletRequest`, status codes) |
| **Repository** | Database access. Every tenant query takes `shopId` | Contain business rules |
| **Model**      | MongoDB document classes (`@Document`)                             | Leave the service layer |
| **DTO**        | Request/response shapes (Java `record`s)                           | Contain `shopId` in request DTOs |

DTO ⇄ entity mapping is done by hand in small mapper classes inside each module.
No MapStruct in Phase 1: one less tool to learn, and mappings are simple.

---

## 3. Request flow

```
HTTP request
   │
   ▼
[1] JwtAuthFilter ─────── reads "Authorization: Bearer <token>", verifies signature + expiry,
   │                      checks tokenVersion against the user (cached), builds AuthUser
   ▼
[2] TenantContext ─────── stores AuthUser.shopId for this request (cleared after request)
   │
   ▼
[3] Spring Security ───── @PreAuthorize("hasAuthority('ORDER_CONFIRM')") on controller method
   │
   ▼
[4] Controller ────────── validates request DTO (@Valid), calls service
   │
   ▼
[5] Service ───────────── shopId = TenantContext.requireShopId(); business rules;
   │                      @Transactional where needed
   ▼
[6] Repository ────────── findByIdAndShopId(id, shopId) / MongoTemplate with shopId criteria
   │
   ▼
[7] MongoDB Atlas
   │
   ▼
Response: service returns DTO → controller → JSON
Errors at any step: → GlobalExceptionHandler (or security handlers) → ApiError JSON
```

### Step details

**[1] JwtAuthFilter** (`common.security`, extends `OncePerRequestFilter`)
- No header → continue anonymously. Spring Security rejects later if the endpoint needs auth.
- Invalid / expired token → `401` with `ApiError` (written by the `AuthenticationEntryPoint`).
- Valid token → loads `{status, tokenVersion}` for the user from a small cache
  (Spring Cache, ~60 s TTL, evicted on any user change). If the user is `DISABLED` or the
  token's `ver` ≠ current `tokenVersion` → `401`. This makes logout, disable and role change
  take effect immediately instead of waiting for the token to expire.
- Builds `AuthUser(userId, shopId, role, permissions)` and puts it in `SecurityContextHolder`.
  Permissions become Spring `GrantedAuthority`s, so `@PreAuthorize` can check them.

**[2] TenantContext** (`common.tenant`)
- A small class holding the current `shopId` in a `ThreadLocal`.
- Set by `JwtAuthFilter`, cleared in a `finally` block so a pooled thread never leaks one
  shop's id into the next request.
- `TenantContext.requireShopId()` throws (→ `403`) if there is no shopId (e.g. a
  `PLATFORM_ADMIN` calling a shop endpoint).
- Limitation: `ThreadLocal` does not travel to `@Async` or `@Scheduled` threads. Code
  running there must receive `shopId` as an explicit parameter.

**[3] Permission check**: `@EnableMethodSecurity` + `@PreAuthorize` on each controller
method. The permission per endpoint is listed in `API.md`.

---

## 4. How `shopId` flows from the JWT into every query

```
JWT claim "shopId"
   → JwtAuthFilter → AuthUser.shopId → TenantContext
      → Service: String shopId = TenantContext.requireShopId();
         → Repository method parameter: findByIdAndShopId(id, shopId)
            → MongoDB filter: { _id: ..., shopId: ... }
```

Rules (also in `CLAUDE.md` and `SECURITY.md`):

1. **Request DTOs have no `shopId` field.** A client cannot even send one that we read.
2. **No `shopId` in URLs.** `GET /api/v1/shop` means "my shop", not `/shops/{id}`.
3. **Services read `shopId` only from `TenantContext`**, then pass it explicitly down.
   Passing it as a parameter (instead of repositories reading the ThreadLocal themselves)
   keeps repositories plain and makes unit tests easy: no context setup, just pass a value.
4. **Tenant repositories do not extend `MongoRepository`.** They extend Spring Data's empty
   `Repository<T, ID>` and declare only methods that take `shopId`. This removes the
   inherited `findById`, `findAll`, `deleteById`, which would ignore the tenant.
   It turns a forgotten `shopId` from a silent data leak into a compile error.
5. **Custom `MongoTemplate` queries** start from a helper: `TenantCriteria.of(shopId)`,
   which returns `Criteria.where("shopId").is(shopId)` to extend.
6. **Writes stamp `shopId` in the service** from `TenantContext` before saving, never from
   the DTO.
7. **Not found and cross-tenant look the same**: both return `404`. We never say
   "exists but belongs to another shop".

Enforcement and tests are described in `SECURITY.md` §5.

---

## 5. Error handling

### Response format (one format for every error)

```json
{
  "timestamp": "2026-11-18T10:15:30Z",
  "status": 409,
  "code": "INSUFFICIENT_STOCK",
  "message": "Not enough stock for SKU TSHIRT-RED-M: requested 3, available 1",
  "path": "/api/v1/orders/6745.../confirm",
  "traceId": "a1b2c3d4",
  "fieldErrors": []
}
```

- `code`: stable machine-readable string. The frontend switches on `code`, never on `message`.
- `message`: human-readable, safe to show. Never contains stack traces or internal details.
- `fieldErrors`: only for validation errors: `[{ "field": "items[0].quantity", "message": "must be at least 1" }]`.
- `traceId`: also written to the server log, so a user report can be matched to the log line.

### Where errors are converted

| Source                                   | Handler                                    |
|------------------------------------------|--------------------------------------------|
| Exceptions from controllers / services   | `GlobalExceptionHandler` (`@RestControllerAdvice` in `common.error`) |
| Missing / bad token (inside filter chain)| `RestAuthenticationEntryPoint` → 401, same `ApiError` JSON |
| Missing permission                       | `RestAccessDeniedHandler` → 403, same `ApiError` JSON |

Why two places: Security filters run **before** Spring MVC, so `@RestControllerAdvice` never
sees their exceptions. Both security handlers reuse the same `ApiError` builder.

### Exception → status mapping

Our own exceptions extend `AppException` (in `common.error`), which carries an HTTP status
and a `code`.

| Exception                                  | HTTP | Example `code`                  |
|--------------------------------------------|------|---------------------------------|
| `MethodArgumentNotValidException`, `HandlerMethodValidationException`, `ConstraintViolationException` | 400 | `VALIDATION_FAILED` |
| `HttpMessageNotReadableException`          | 400  | `MALFORMED_REQUEST`              |
| authentication failure (security filter)   | 401  | `UNAUTHORIZED`, `TOKEN_EXPIRED`  |
| `AccessDeniedException`                    | 403  | `FORBIDDEN`                      |
| `ResourceNotFoundException`                | 404  | `ORDER_NOT_FOUND`                |
| `BusinessRuleException` (default 409)      | 409  | `INSUFFICIENT_STOCK`, `INVALID_STATUS_TRANSITION`, `PHONE_ALREADY_EXISTS` |
| `BusinessRuleException` with explicit status | 400 / 429 | `INVALID_OTP`, `OTP_RATE_LIMITED` |
| `DuplicateKeyException` (Mongo unique index) | 409 | `DUPLICATE_KEY` (mapped to a specific code where known) |
| `OptimisticLockingFailureException`        | 409  | `CONCURRENT_MODIFICATION`        |
| Spring MVC errors (`ErrorResponse`: unknown URL, wrong method...) | their own (404, 405, 415...) | `NOT_FOUND`, `METHOD_NOT_ALLOWED` |
| anything else                              | 500  | `INTERNAL_ERROR` (generic message; full stack trace logged) |

### Success responses

- Single resource: the DTO itself, no wrapper (`{ "id": ..., ... }`).
- Lists: a page object

  ```json
  { "content": [ ... ], "page": 0, "size": 20, "totalElements": 135, "totalPages": 7 }
  ```

- Create → `201`, delete-like actions with no body → `204`.

---

## 6. Cross-cutting concerns

| Concern      | Phase 1 approach |
|--------------|------------------|
| Config       | `application.yml` with `${ENV_VAR}` placeholders only. Profiles: `dev`, `test`, `prod`. |
| Validation   | Bean Validation annotations on request DTO records. Business rules in services. |
| Auditing     | `@EnableMongoAuditing`: `createdAt`, `updatedAt`, `createdBy` on documents. |
| Concurrency  | `@Version` (optimistic locking) on mutable documents; atomic conditional updates for stock and status. |
| Transactions | `MongoTransactionManager` + `@Transactional` only where several documents must change together (see `SCHEMA.md` §11). |
| Cache        | Spring Cache (in-memory, Caffeine) for the auth user lookup in `JwtAuthFilter`. In-memory means one app instance; with several instances we'd need Redis or shorter TTLs. |
| Mail         | Spring Mail for OTP and staff invite emails. Sent after the DB work succeeds. |
| Scheduling   | Enabled, no jobs needed in Phase 1 (OTP cleanup is done by a MongoDB TTL index). |
| Docs         | springdoc-openapi: Swagger UI at `/swagger-ui.html`, disabled in `prod`. |
| Health       | Actuator: only `/actuator/health` exposed publicly; prod shows only `{"status":"UP"}`. |
| Static files | `common/config/SpaWebConfig` serves the built React app: `/assets/**` cached 1 year, everything else `no-cache`, SPA routes → `index.html`. See §8. |
| Time         | Store `Instant` (UTC). Frontend shows `Asia/Dhaka` time. |
| Money        | Integer **poisha** (`long`, 1 BDT = 100 poisha). See `SCHEMA.md` §2. |

### Environment variables

| Variable             | Purpose |
|----------------------|---------|
| `MONGODB_URI`        | Atlas connection string (includes credentials) |
| `JWT_SECRET`         | HMAC key for signing tokens, ≥ 32 random bytes, base64 |
| `JWT_ACCESS_TTL`     | default `PT15M` |
| `JWT_REFRESH_TTL`    | default `P30D` |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | SMTP |
| `APP_URL`            | Public origin of the app (frontend + API); the refresh endpoint's `Origin` check. Dev default `http://localhost:5173` |
| `PORT`               | HTTP port, set by Render; default `8080` |
| `PLATFORM_ADMIN_EMAIL` | Seeds the single platform admin user at startup |
| `CLOUDINARY_URL`     | `cloudinary://<api_key>:<api_secret>@<cloud_name>` for product images. Required in prod. |

---

## 7. Frontend (brief)

- `frontend/src/features/<feature>/` mirrors backend modules (api calls, hooks, pages).
- One Axios instance (`api/client.ts`) with **relative** paths (`/api/v1/...`) and no base URL:
  the app and the API always share one origin. Interceptor adds the access token; on `401` it
  calls `/auth/refresh` once, then retries. The frontend has no environment variables.
- TanStack Query for all server data. Query keys start with the feature: `['orders', filters]`.
- The access token is kept **in memory only**; the refresh token is an HttpOnly cookie
  (see `SECURITY.md` §2).
- The UI hides buttons the user lacks permission for, but the backend is the real check.

---

## 8. Deployment: one service

```
Browser ──── https://sellerdesk.onrender.com (one origin) ────▶ Render web service
                                                                one Docker image, one JAR
   /api/**            → controllers (JSON; unknown path → JSON 404/401)
   /actuator/health   → public status
   /assets/**         → hashed JS/CSS/images, cached 1 year, immutable
   other GET paths    → index.html (no-cache) → React Router
                                   │
                                   ▼
                             MongoDB Atlas
```

- **Build:** the root `Dockerfile` has three stages: Node builds `frontend/` → Maven copies
  `frontend/dist` into `src/main/resources/static/` and builds the jar (tests skipped; CI runs
  them) → a small Java 21 JRE image runs the jar as a non-root user on `$PORT`.
- **Why one service:** one origin means no CORS, a first-party `SameSite=Strict` refresh cookie
  (works in Safari too), no API URL baked into the frontend, and frontend and backend can never
  be deployed out of step.
- **Request routing:** controllers always win. Then `SpaWebConfig`'s resource handlers: a real
  file is served; a path that is not a backend path (`api/`, `actuator/`, Swagger) and has no
  file extension falls back to `index.html`; everything else is a JSON 404 from
  `GlobalExceptionHandler`.
- **Caching:** Vite puts a content hash in every asset file name, so `/assets/**` can be cached
  for a year. `index.html` is `no-cache`, so after a deploy the next page load picks up the new
  asset names.
- **Local dev:** two processes. Vite (`:5173`) serves the frontend with hot reload and proxies
  `/api` to Spring Boot (`:8080`) with the `Host` header unchanged, so it is one origin as well.
- **CI** (`.github/workflows/ci.yml`): backend `./mvnw verify` and the frontend build run in
  parallel; only if both pass, the Docker image is built (not pushed) and smoke-tested against a
  throwaway MongoDB.

Step-by-step setup: `DEPLOYMENT.md`.
