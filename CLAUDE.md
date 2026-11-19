# SellerDesk — Project Rules

SellerDesk is a multi-tenant back-office SaaS for Bangladeshi Facebook (F-commerce) sellers.
It is NOT a storefront. Sellers and their staff use it to manage orders, inventory, customers
and courier COD payments. See `docs/SCOPE.md` for what is in and out of scope.

## Monorepo layout

- `backend/` — Java 21, Spring Boot, Maven, Spring Data MongoDB, Spring Security, JWT,
  Bean Validation, Spring Mail, Scheduling, Cache, Actuator, Lombok, springdoc-openapi
- `frontend/` — React, TypeScript, Vite, Tailwind CSS, React Router, Axios, TanStack Query
- Database — MongoDB Atlas

## Frontend structure

`frontend/src/`: `api` (Axios instance + endpoint wrappers), `features/<feature>` (pages, hooks,
components of one feature), `components` (shared UI), `layouts`, `routes`, `lib` (helpers),
`types` (backend DTO / ApiError types).

- All HTTP calls go through `api/client.ts` (`api` instance). Never call `axios` directly.
- Access token only in memory (`api/tokenStore.ts`). Never use localStorage/sessionStorage for tokens.
- Show backend errors with `getApiError` / `fieldErrorsOf` from `lib/apiError.ts`; switch on `code`.

## Backend package structure (feature-based)

Base package: `com.sellerdesk.<feature>`

Features: `auth`, `shop`, `staff`, `product`, `inventory`, `customer`, `order`, `common`

Each feature contains these sub-packages:

```
com.sellerdesk.<feature>
├── controller
├── service
├── repository
├── dto
└── model
```

`common` holds shared code: security, global exception handler, error response format,
base classes and utilities.

## Multi-tenancy rules (critical)

- Every tenant-owned document has a `shopId` field.
- No repository query on tenant data may run without `shopId`.
- `shopId` always comes from the authenticated user (security context / JWT).
  Never read it from the request body, path or query parameters.
- A user from one shop must never be able to read or change another shop's data.

## API rules

- Controllers never return entities (`model`). They return DTOs only.
- Validate incoming DTOs with Bean Validation (`@Valid`).
- All errors go through one global exception handler (`@RestControllerAdvice` in `common`)
  that returns one consistent error response format.

## Secrets and configuration

- Secrets come only from environment variables (DB URI, JWT secret, mail credentials, etc.).
- Never hardcode secrets. Never commit them. Keep `.env` files out of git.

## Testing

- Every feature needs unit tests for its business logic (service layer).
- Tests that need a real MongoDB extend `com.sellerdesk.testsupport.MongoIntegrationTestBase`
  (never add `@SpringBootTest` + Testcontainers by hand). The base picks MongoDB from
  `MONGODB_TEST_URI` (name must end with `-test`), else Testcontainers, else skips; it deletes all
  documents before each test. Use `uniqueEmail(...)` / `uniqueName(...)` for test data.
- Run all tests: `cd backend && ./mvnw verify` (same command as CI, `.github/workflows/ci.yml`).
- Never point `MONGODB_TEST_URI` at the real database.
- **Every new tenant-owned module (staff, product, inventory, customer, order) must extend
  `TenantIsolationTest`** with its own `@Nested` class: another shop's record by id → `404`
  (never 403) for every `{id}` endpoint, lists show only own data, a `shopId` in the request body
  is ignored, and referenced ids from another shop → `404`. Use `assertHiddenFromOtherShop(...)`.
  A module is not done until these cases pass.

## Working style

- Work in small steps.
- After each task, stop and report:
  1. What changed
  2. Why
  3. How to verify it
- The developer is learning Spring Boot. Explain important decisions in simple terms.
- Do not run `git commit` unless explicitly asked.
