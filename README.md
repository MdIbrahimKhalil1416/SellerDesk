# SellerDesk

[![CI](https://github.com/MdIbrahimKhalil1416/SellerDesk/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/MdIbrahimKhalil1416/SellerDesk/actions/workflows/ci.yml)

**Status: Phase 1 in progress.** Auth, shop registration, the multi-tenancy foundation, the
auth pages, the Product & Variant module (backend and frontend, Cloudinary images) and the
Staff & Permission backend (invitations, roles, permissions) are built.
Staff frontend, inventory, customers and orders are next.
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
| CI | GitHub Actions: backend tests, frontend type check + lint + build, then Docker image build + smoke test |
| Hosting | Render: **one** Docker web service ([docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)) |

## Architecture

```
Browser ── https://sellerdesk.onrender.com ── one origin for everything
   │
   ▼
Render web service: one Docker image, one Spring Boot app
   ├─ /api/**              REST API
   │     JwtAuthenticationFilter → @PreAuthorize(permission) → Controller → Service → Repository ─▶ MongoDB Atlas
   │     (verifies JWT, checks tokenVersion, puts shopId into TenantContext)
   ├─ /actuator/health     public, status only (Render health check, UptimeRobot)
   ├─ /assets/**           built JS/CSS/images, cached for a year (hashed file names)
   └─ anything else        index.html (no-cache) → React Router picks the page
```

- **Single-service deployment.** The root `Dockerfile` builds the React app, copies it into the
  backend's static resources and builds one jar. Spring Boot serves the API and the app from the
  same origin, so there is no CORS, no API URL to configure in the frontend, and the refresh
  cookie is first-party (`SameSite=Strict`). Unknown `/api/**` paths still answer JSON `404`.
- **Feature modules** in `com.sellerdesk.<feature>` (`auth`, `shop`, `staff`, `product`,
  `inventory`, `customer`, `order`), each with `controller / service / repository / dto / model`.
  Shared code (security, errors, config, SPA serving) lives in `common`.
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
Docker is **not** needed locally: CI builds and tests the Docker image.

Locally you run two processes. The browser only talks to the Vite dev server; Vite forwards
`/api` to the backend. So the browser sees one origin, the same as in production.

### 1. Backend (port 8080)

```bash
cd backend
cp .env.example .env      # fill in the values (see "Environment variables" below)
./mvnw spring-boot:run    # dev profile; Swagger UI at http://localhost:8080/swagger-ui.html
```

`backend/.env` is git-ignored. Add real SMTP credentials, otherwise no verification codes
arrive. `APP_URL` is optional in dev (default `http://localhost:5173`).

### 2. Frontend (port 5173)

```bash
cd frontend
npm install
npm run dev               # open http://localhost:5173
```

Start the backend first. Checks: `npm run typecheck`, `npm run lint`, `npm run build`.

## Tests

All backend test commands run in `backend/`:

```bash
./mvnw verify                                # all tests + jar (the same command CI runs)
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

**CI** ([.github/workflows/ci.yml](.github/workflows/ci.yml)) runs on every push and pull request:

1. `backend`: `./mvnw verify`. All tests run; integration tests use Testcontainers (GitHub
   runners have Docker) and need no secrets.
2. `frontend`: `npm ci`, type check, lint, build.
3. `docker-image` (only if 1 and 2 passed): builds the root `Dockerfile` (not pushed), checks it
   runs as non-root with Java 21, then starts it against a throwaway MongoDB and checks health,
   SPA routes, cache headers and JSON 404s.

## Environment variables

Names only: never commit values. Real values live in `backend/.env` (local, git-ignored) or in
the Render dashboard. Template: [backend/.env.example](backend/.env.example).
The frontend has **no** environment variables.

| Variable | Required | Purpose |
|----------|----------|---------|
| `SPRING_PROFILES_ACTIVE` | prod | `prod` on Render (the Docker image defaults to it); `dev` locally (default) |
| `PORT` | no | HTTP port; set by Render, default `8080` |
| `APP_URL` | prod | public origin of the app, e.g. the Render URL; refresh Origin check. Dev default `http://localhost:5173` |
| `MONGODB_URI` | yes | Atlas connection string |
| `MONGODB_DATABASE` | no | database name, default `sellerdesk` |
| `JWT_SECRET` | yes | HMAC key for tokens, at least 32 random characters |
| `JWT_ACCESS_TTL` | no | access token lifetime, default `PT15M` |
| `JWT_REFRESH_TTL` | no | refresh token lifetime, default `P30D` |
| `MAIL_HOST` | no | SMTP host, default `smtp.gmail.com` |
| `MAIL_PORT` | no | SMTP port, default `587` |
| `MAIL_USERNAME` | prod | SMTP user |
| `MAIL_PASSWORD` | prod | SMTP password (e.g. a Gmail App Password) |
| `MAIL_FROM` | no | sender shown in emails |
| `CLOUDINARY_URL` | prod | product images: `cloudinary://<api_key>:<api_secret>@<cloud_name>`; optional in dev (uploads then fail with `502`) |
| `MONGODB_TEST_URI` | no | tests only: integration test database (name ends with `-test`); never on Render |

## Deployment

Step-by-step instructions for Render, MongoDB Atlas and UptimeRobot:
[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

Live app: https://sellerdesk-lcrs.onrender.com
