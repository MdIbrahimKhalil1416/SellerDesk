# SellerDesk

[![Backend CI](https://github.com/MdIbrahimKhalil1416/SellerDesk/actions/workflows/backend-ci.yml/badge.svg?branch=main)](https://github.com/MdIbrahimKhalil1416/SellerDesk/actions/workflows/backend-ci.yml)
[![Frontend CI](https://github.com/MdIbrahimKhalil1416/SellerDesk/actions/workflows/frontend-ci.yml/badge.svg?branch=main)](https://github.com/MdIbrahimKhalil1416/SellerDesk/actions/workflows/frontend-ci.yml)

**Status: Phase 1 in progress.** Auth, shop registration, the multi-tenancy foundation and the
auth pages are built. Staff, products, inventory, customers and orders are next.
See [docs/SCOPE.md](docs/SCOPE.md).

## What it is

SellerDesk is a back-office web app for Bangladeshi **F-commerce** sellers: people who sell
through a Facebook page and Messenger instead of an online store.

These sellers usually run their business from chat threads, notebooks and spreadsheets. Orders
get lost between Messenger and the courier, nobody knows the real stock, and cash-on-delivery
(COD) money from couriers is hard to reconcile. SellerDesk gives the seller and their staff one
place to manage **orders, inventory, customers and courier COD payments**.

It is **not** a storefront: customers never use it. It is multi-tenant SaaS: many shops share
one deployment, and each shop sees only its own data.

## Tech stack

| Part | Technology |
|------|-----------|
| Backend | Java 21, Spring Boot 4, Maven, Spring Data MongoDB, Spring Security + JWT, Bean Validation, Spring Mail, Caffeine cache, Actuator, springdoc-openapi |
| Frontend | React 19, TypeScript (strict), Vite, Tailwind CSS, React Router, Axios, TanStack Query |
| Database | MongoDB Atlas (replica set, so multi-document transactions work) |
| CI | GitHub Actions: backend build + Docker image build; frontend type check + lint + build |
| Hosting | Render: backend as a Docker web service, frontend as a static site ([docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)) |

## Architecture

```
Browser (React SPA)
   │  Authorization: Bearer <access token>      + HttpOnly refresh cookie (auth endpoints only)
   ▼
Spring Boot API
   JwtAuthenticationFilter → @PreAuthorize(permission) → Controller → Service → Repository → MongoDB Atlas
   (verifies JWT, checks tokenVersion,
    puts shopId into TenantContext)
```

- **Feature modules** in `com.sellerdesk.<feature>` (`auth`, `shop`, `staff`, `product`,
  `inventory`, `customer`, `order`), each with `controller / service / repository / dto / model`.
  Shared code (security, errors, config) lives in `common`.
- **Controllers return DTOs only**, validated with Bean Validation. All errors use one JSON
  format (`ApiError`) from a global exception handler.
- **Auth:** email + password (BCrypt). An emailed 6-digit code verifies the email and resets
  passwords. 15-minute access token kept in memory, 30-day refresh token in an HttpOnly cookie,
  instant revocation through a per-user `tokenVersion`.
- **Permissions, not roles,** are checked: `@PreAuthorize("hasAuthority('ORDER_CREATE')")`.
  A role only provides a default set of permissions.

Details: [ARCHITECTURE](docs/ARCHITECTURE.md) · [SCHEMA](docs/SCHEMA.md) ·
[SECURITY](docs/SECURITY.md) · [API](docs/API.md) · [DEPLOYMENT](docs/DEPLOYMENT.md).

## Multi-tenancy

Each shop is a tenant. Every tenant-owned document has a `shopId`, and isolation is enforced in
layers ([SECURITY.md §4](docs/SECURITY.md)):

1. `shopId` comes **only** from the verified JWT. It is stored per request in `TenantContext` and
   cleared after every request. It is never read from the URL, query or body.
2. Request DTOs have no `shopId` field, and URLs have none (`GET /api/v1/shop` means "my shop").
3. Tenant repositories expose only methods that take `shopId` (`findByIdAndShopId`...), with no
   inherited `findAll()`.
4. Another shop's record by id answers **404**, never 403, so ids cannot be probed.
5. `TenantIsolationTest` proves this with two real shops. Every new module must add its own
   cross-tenant cases there.

## Run locally

Prerequisites: Java 21, Node.js 24, a MongoDB Atlas cluster (the free M0 is enough).

### Backend (port 8080)

```bash
cd backend
cp .env.example .env      # fill in the values (see "Environment variables" below)
./mvnw spring-boot:run    # dev profile; Swagger UI at http://localhost:8080/swagger-ui.html
```

`backend/.env` is git-ignored. For local development set `FRONTEND_URL=http://localhost:5173`,
and add real SMTP credentials, otherwise no verification codes arrive.

### Frontend (port 5173)

```bash
cd frontend
npm install
npm run dev               # http://localhost:5173
```

Start the backend first. The Vite dev server proxies `/api` to `http://localhost:8080`, so the
browser sees one origin and the refresh cookie works without CORS issues.
Checks: `npm run typecheck`, `npm run lint`, `npm run build`.

## Tests

All test commands run in `backend/`:

```bash
./mvnw test                                  # unit + integration tests
./mvnw test -Dtest='TenantIsolationTest*'    # one class
```

- **Unit tests** need nothing: no database, no secrets (dummy settings in
  `src/test/resources/application-test.yml`).
- **Integration tests** (classes extending `MongoIntegrationTestBase`, e.g.
  `AuthFlowIntegrationTest`, `TenantIsolationTest`) need a real MongoDB with a replica set. The
  source is chosen automatically:

  | Order | Condition | MongoDB used |
  |-------|-----------|--------------|
  | 1 | `MONGODB_TEST_URI` is set (environment variable or `backend/.env`) | that database |
  | 2 | Docker is running | a throwaway Testcontainers `mongo:8.0` |
  | 3 | neither | integration tests are **skipped**, with the reason in the report |

**Local Atlas test database (no Docker needed):** add to `backend/.env`

```properties
MONGODB_TEST_URI=mongodb+srv://<user>:<password>@<cluster-host>/sellerdesk-test?retryWrites=true&w=majority
```

> **Safety:** integration tests **delete all documents in the test database before every test**.
> The database name in `MONGODB_TEST_URI` must end with `-test`; otherwise the tests refuse to
> run. Never point it at the production database, and do not share one test database between
> people running tests at the same time.

**CI:** GitHub Actions currently **builds without running tests** (`-DskipTests`), then builds
the Docker image. Run the tests locally before pushing. When tests are switched back on in CI,
they use Testcontainers (Docker is available on GitHub runners) and need no secrets.

## Environment variables

Names only: never commit values. Real values live in `backend/.env` (local, git-ignored) or in
the Render dashboard. Templates: [backend/.env.example](backend/.env.example),
[frontend/.env.example](frontend/.env.example).

| Variable | Used by | Required | Purpose |
|----------|---------|----------|---------|
| `SPRING_PROFILES_ACTIVE` | backend | prod | `prod` on Render (the Docker image defaults to it); `dev` locally |
| `PORT` | backend | no | HTTP port; set by Render, default `8080` |
| `MONGODB_URI` | backend | yes | Atlas connection string |
| `MONGODB_DATABASE` | backend | no | database name, default `sellerdesk` |
| `JWT_SECRET` | backend | yes | HMAC key for tokens, at least 32 random characters |
| `JWT_ACCESS_TTL` | backend | no | access token lifetime, default `PT15M` |
| `JWT_REFRESH_TTL` | backend | no | refresh token lifetime, default `P30D` |
| `FRONTEND_URL` | backend | yes | frontend origin: CORS and the refresh-cookie Origin check |
| `MAIL_HOST` | backend | no | SMTP host, default `smtp.gmail.com` |
| `MAIL_PORT` | backend | no | SMTP port, default `587` |
| `MAIL_USERNAME` | backend | yes in prod | SMTP user |
| `MAIL_PASSWORD` | backend | yes in prod | SMTP password (e.g. a Gmail App Password) |
| `MAIL_FROM` | backend | no | sender shown in emails |
| `REFRESH_COOKIE_SAME_SITE` | backend | no | `Strict` / `Lax` / `None`; prod default `None` |
| `REFRESH_COOKIE_SECURE` | backend | no | dev only; always `true` in prod |
| `MONGODB_TEST_URI` | backend tests | no | integration test database (name ends with `-test`); never on Render |
| `VITE_API_URL` | frontend build | prod | backend base URL, baked in at build time; empty in dev |

## Deployment

Step-by-step instructions for Render and MongoDB Atlas: [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).
