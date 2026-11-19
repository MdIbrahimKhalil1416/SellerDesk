# SellerDesk — Deployment (Render + MongoDB Atlas)

First deployment of Phase 1. Two Render services:

| Service | Render type | Source | URL (example) |
|---------|-------------|--------|---------------|
| `sellerdesk-api` | Web Service, **Docker** | `backend/Dockerfile` | `https://sellerdesk-api.onrender.com` |
| `sellerdesk-web` | **Static Site** | `frontend/` (`npm run build` → `dist/`) | `https://sellerdesk-web.onrender.com` |

Do the steps in order. Nothing here is automated.

---

## 0. Before you start

1. `main` is pushed to GitHub, and on the **Actions** tab the latest **Backend CI** run is green,
   **including the `docker-image` job** (proves the Dockerfile builds), and **Frontend CI** is green.
2. You have a Render account connected to the GitHub repository.
3. You have SMTP credentials for sending emails, e.g. a Gmail account with 2-step verification
   and an **App Password** (Google Account → Security → App passwords).
4. Run the tests locally once: `cd backend && ./mvnw test` (CI does not run them right now).

---

## 1. MongoDB Atlas

### 1a. A database user only for production

Atlas → *Database Access* → *Add New Database User*:

- Authentication: **Password**. Generate a long random password (Atlas has a button).
- Privileges: *Specific Privileges* → `readWrite` on database **`sellerdesk`** only.
  Not "Atlas admin", not "read and write to any database": if the password ever leaks, the
  attacker cannot touch other databases (for example the test database).
- Use a **different user** from the one in your local `.env` and from the test database user.

Build the connection string (Atlas → *Connect* → *Drivers*) and put the database name in it:

```
mongodb+srv://<prod-user>:<password>@<cluster-host>/sellerdesk?retryWrites=true&w=majority&appName=SellerDesk
```

URL-encode special characters in the password (`@` → `%40`, `:` → `%3A`, `/` → `%2F`).

### 1b. Network access for Render

Atlas only accepts connections from IP addresses on its *Network Access* list. Render services
do **not** have a fixed, private outbound IP of their own on the free plan: traffic leaves from
shared Render address ranges that can change.

**Options:**

| Option | How | Security |
|--------|-----|----------|
| **A. Render's outbound IP ranges** (preferred if shown) | Render dashboard → your web service → *Connect* → *Outbound*. Add each listed range in Atlas → *Network Access* → *Add IP Address*. | Better than open-to-all, but the ranges are **shared with other Render customers in the same region**, so it narrows access, it does not isolate it. If Render changes the ranges, the app loses the database until you update the list. |
| **B. Allow from anywhere** `0.0.0.0/0` | Atlas → *Network Access* → *Add IP Address* → *Allow access from anywhere*. | Anyone on the internet can **try** to connect. What still protects the data: TLS (always on in Atlas), a long random password, a user limited to `readWrite` on `sellerdesk`. This is common for small projects on free hosting, but the password then becomes the only lock. |
| C. Private networking / static IP | A paid Render feature or add-on, and Atlas private endpoints (dedicated, paid clusters). | Strongest. Not needed for Phase 1. |

**Recommendation for the first deployment:** use option A if Render shows outbound ranges for
your service, otherwise option B with the restricted user from 1a. Write down which one you
chose. Before real sellers depend on the system, revisit option C.

If you choose B, also:
- never reuse this password anywhere else;
- enable Atlas alerts for unusual connection counts (Atlas → *Alerts*);
- rotate the password if anyone who had it leaves the project.

---

## 2. Backend: Render Web Service (Docker)

Render dashboard → **New** → **Web Service** → select the repository.

| Setting | Value |
|---------|-------|
| Name | `sellerdesk-api` (becomes part of the URL) |
| Language / Runtime | **Docker** |
| Branch | `main` |
| Region | **Singapore** (closest to Bangladesh) |
| Root Directory | `backend` |
| Dockerfile Path | `./Dockerfile` (relative to the root directory) |
| Docker Build Context | `.` |
| Instance Type | Free for a first test; Starter or higher for real users (see §6) |
| Health Check Path | `/actuator/health` (Advanced settings) |
| Auto-Deploy | On commit |

**Environment variables** (Advanced → *Add Environment Variable*). Mark secrets as secret if the
dashboard offers it:

| Key | Value |
|-----|-------|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `MONGODB_URI` | the connection string from 1a |
| `MONGODB_DATABASE` | `sellerdesk` |
| `JWT_SECRET` | a **new** random value, never the dev one. Generate in Git Bash: `openssl rand -base64 48` |
| `FRONTEND_URL` | for now `https://sellerdesk-web.onrender.com`; you correct it in step 4 |
| `MAIL_HOST` | `smtp.gmail.com` |
| `MAIL_PORT` | `587` |
| `MAIL_USERNAME` | the Gmail address |
| `MAIL_PASSWORD` | the Gmail **App Password** (16 characters, no spaces) |
| `MAIL_FROM` | `SellerDesk <the same Gmail address>` (Gmail rewrites other senders) |

Do **not** set: `PORT` (Render sets it; the app reads it), `MONGODB_TEST_URI` (tests only; it
must never exist on a server), `REFRESH_COOKIE_SECURE` (always `true` in prod).
`REFRESH_COOKIE_SAME_SITE` is `None` by default in prod; only set it to `Strict` after moving to
a custom domain (§6).

Click **Create Web Service**. The first build takes a few minutes (Maven downloads dependencies).

**Check:** open `https://<your-api>.onrender.com/actuator/health`. Expected:

```json
{"groups":["liveness","readiness"],"status":"UP"}
```

`DOWN` / 503 almost always means MongoDB is unreachable: check §1b and the `MONGODB_URI`.
If the service does not start at all, read the deploy log: a missing required variable is named
in the error (`Could not resolve placeholder 'JWT_SECRET'`...).

Copy the exact service URL shown at the top of the service page. Render may add a suffix if the
name is taken.

---

## 3. Frontend: Render Static Site

Render dashboard → **New** → **Static Site** → select the repository.

| Setting | Value |
|---------|-------|
| Name | `sellerdesk-web` |
| Branch | `main` |
| Root Directory | `frontend` |
| Build Command | `npm ci && npm run build` |
| Publish Directory | `dist` |

**Environment variable:**

| Key | Value |
|-----|-------|
| `VITE_API_URL` | the backend URL from step 2, e.g. `https://sellerdesk-api.onrender.com`, **no trailing slash, no `/api`** |

Vite bakes this value into the JavaScript **at build time**. After changing it, trigger a new
deploy (*Manual Deploy* → *Clear build cache & deploy*). It is visible to every visitor, so never
put secrets in `VITE_*` variables. Node.js 24 comes from `frontend/.node-version`.

**Rewrite rule (required):** service → *Redirects/Rewrites* → *Add rule*:

| Source | Destination | Action |
|--------|-------------|--------|
| `/*` | `/index.html` | **Rewrite** |

Without it, refreshing the page on `/dashboard` or opening a link to `/verify-email` gives
Render's 404. The rewrite serves `index.html` for every path, and React Router shows the right
page. Real files such as `/assets/index-….js` are still served directly.

Optional security headers (service → *Headers*, path `/*`): `X-Frame-Options: DENY`,
`X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`.

Click **Create Static Site** and copy its URL.

---

## 4. Connect the two

1. Backend service → *Environment* → set `FRONTEND_URL` to the **exact** static site origin, e.g.
   `https://sellerdesk-web.onrender.com`. Use `https`, no trailing slash, no path. It must match
   the browser's `Origin` header character for character: it controls CORS and the refresh
   cookie's Origin check.
2. Save. Render redeploys the backend.
3. If the backend URL differed from what you put in `VITE_API_URL`, fix it on the static site
   and redeploy it with a cleared build cache.

---

## 5. Smoke test in production

1. Open the static site → you land on `/login`.
2. Register a shop with a real email address → the verification email arrives → enter the code
   → dashboard shows your shop and name.
3. Press F5 on `/dashboard` → still logged in, page loads (proves the rewrite rule and the refresh
   cookie work).
4. Browser DevTools → Application → Cookies → the **API** domain has `sd_refresh` with
   `HttpOnly`, `Secure`, `SameSite=None`, Path `/api/v1/auth`.
5. Log out → back on `/login`; F5 → stays logged out.
6. `https://<api>/swagger-ui.html` → 404, `https://<api>/actuator/env` → 401 (both intended).

---

## 6. Production behavior and known limitations

**Configuration checked for the `prod` profile** (`application-prod.yml`, verified locally with
the prod profile):

| Item | Behavior |
|------|----------|
| Refresh cookie | `HttpOnly`, `Secure=true` (fixed), `SameSite=None` (default), Path `/api/v1/auth` |
| CORS | Only `FRONTEND_URL` is allowed, with credentials. Any other origin gets 403; refresh from another origin gets `403 INVALID_ORIGIN`. |
| Actuator | Only `/actuator/health` is exposed, and it shows only the overall status (no database names, disk, versions). Every other actuator path answers 401. |
| Swagger / OpenAPI | **Off** in prod (`/swagger-ui.html` and `/v3/api-docs` answer 404). See below. |
| Proxy headers | `server.forward-headers-strategy: native`: the app trusts Render's `X-Forwarded-*` headers for scheme, host and client IP. |

**Why Swagger is not public in production:** a public, complete list of endpoints and DTOs mainly
helps attackers map the API. The frontend does not need it, and its "Try it out" button would
be a second client against live data. Developers use it locally with the `dev` profile.
If it is ever needed in prod, enable it only behind authentication, not openly.

**Limitations to know:**

- **Free instance spins down** after about 15 minutes without traffic. The next request waits
  roughly a minute while it starts. In-memory data (login rate limits, user cache) resets on
  restart. Use a paid instance for real users.
- **Outbound email on the free plan:** Render has restricted outbound SMTP ports (25/465/587) on
  free instances. Check Render's current documentation. If they are blocked, verification and
  password-reset codes are never sent (the backend only logs `Could not send … email`), and
  registration cannot be completed. Fix: a paid instance type, or later an HTTP email API instead
  of SMTP (code change).
- **Safari / iOS and cross-site cookies:** `*.onrender.com` subdomains count as different sites,
  so the refresh cookie is a third-party cookie, which Safari blocks by default. Users there must
  log in again after every page reload. Fix: a custom domain with the frontend and API under the
  same domain (e.g. `app.sellerdesk.com` and `api.sellerdesk.com`), then set
  `REFRESH_COOKIE_SAME_SITE=Strict` and update `FRONTEND_URL` / `VITE_API_URL`
  (docs/SECURITY.md §2).
- **Deferred items** (docs/SCOPE.md): platform admin seeding, per-IP rate limiting.

---

## 7. Alternative: Blueprint (`render.yaml`)

`render.yaml` at the repository root describes the same two services (Docker web service with
health check, static site with the rewrite rule and headers). Render dashboard → **New** →
**Blueprint** → select the repository. Render asks for every value marked `sync: false`
(`MONGODB_URI`, `JWT_SECRET`, `FRONTEND_URL`, `MAIL_*`, `VITE_API_URL`). Steps 1, 4 and 5 still
apply. Use either the Blueprint or the manual setup, not both, or you get duplicate services.

---

## 8. Updating and rolling back

- Every push to `main` redeploys both services (Auto-Deploy). Check that CI is green first.
- A bad deploy: service → *Events* → pick the last good deploy → **Rollback**.
- Rotating `JWT_SECRET` logs everyone out (all existing tokens become invalid). That is the
  intended emergency action if the secret leaked.
