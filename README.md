# SellerDesk

Back-office SaaS for Bangladeshi Facebook (F-commerce) sellers: orders, inventory, customers
and courier COD payments. Multi-tenant: every shop's data is isolated.

- `backend/` — Java 21, Spring Boot, MongoDB
- `docs/` — scope, architecture, schema, security and API design

Setup, Docker image and deploy instructions will be added here later.

## Running the tests

All commands run in `backend/`.

```bash
./mvnw verify          # everything: unit + integration tests (what CI runs)
./mvnw test            # same tests, without packaging
./mvnw test -Dtest=AuthFlowIntegrationTest   # a single class
```

### Unit tests

Need nothing: no database, no secrets. Dummy settings come from
`src/test/resources/application-test.yml`.

### Integration tests (real MongoDB)

Classes extending `MongoIntegrationTestBase` (e.g. `AuthFlowIntegrationTest`) need a MongoDB
with replica set (for transactions). The source is chosen automatically:

| Order | Condition | MongoDB used |
|-------|-----------|--------------|
| 1 | `MONGODB_TEST_URI` is set (environment variable or `backend/.env`) | that database |
| 2 | Docker is running | a throwaway Testcontainers `mongo:8.0` |
| 3 | neither | integration tests are **skipped**, with the reason in the test report |

**Local without Docker (Atlas test database):**

1. In Atlas, use the same cluster or a free M0 one. The database is created on first use.
2. Add to `backend/.env`:

   ```properties
   MONGODB_TEST_URI=mongodb+srv://<user>:<password>@<cluster-host>/sellerdesk-test?retryWrites=true&w=majority
   ```

3. Run `./mvnw verify`.

> **Safety:** the tests **delete all documents in the test database before every test**.
> The database name in `MONGODB_TEST_URI` must end with `-test`; otherwise the tests refuse
> to run. Never reuse the production database name. Two people (or machines) sharing one
> test database at the same time will delete each other's data mid-run: give each their own
> `-test` database.

### CI (GitHub Actions)

`.github/workflows/backend-ci.yml` runs `./mvnw verify` in `backend/` with Java 21 on every
push and pull request. GitHub's Ubuntu runners have Docker, so integration tests use
Testcontainers. No secrets are needed. Test reports are uploaded as an artifact when the
build fails.
