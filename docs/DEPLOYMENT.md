# SellerDesk — Deployment (Render + MongoDB Atlas + UptimeRobot)

SellerDesk runs as **one** Render web service. The root `Dockerfile` builds the React frontend,
copies it into the Spring Boot backend and builds one jar. That one app serves:

| Path | Answered by |
|------|-------------|
| `/api/**` | REST controllers (JSON). Unknown paths → JSON 404 / 401, never `index.html` |
| `/actuator/health` | public health status `{"status":"UP"}` |
| `/assets/**` | built JS/CSS/images, `Cache-Control: max-age=31536000, public, immutable` |
| anything else (`/`, `/login`, `/dashboard`...) | `index.html` with `Cache-Control: no-cache`; React Router shows the page |

Browser, frontend and API share one origin (e.g. `https://sellerdesk.onrender.com`). So there is
no CORS, no API URL baked into the frontend, and the refresh cookie is first-party.

Do the steps in order. Nothing here is automated, except Render rebuilding on every push.

---

## 0. Before you start

1. `main` is pushed to GitHub, and on the **Actions** tab the latest **CI** run is green: jobs
   `backend` (all tests), `frontend` and **`docker-image`**. The last one builds the same
   Dockerfile Render builds and starts it once (there is no Docker on the development machine,
   so this job is the proof that the image works).
2. You have a Render account connected to the GitHub repository.
3. You have SMTP credentials, e.g. a Gmail account with 2-step verification and an
   **App Password** (Google Account → Security → App passwords).

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
do **not** have a fixed outbound IP of their own on the free plan: traffic leaves from shared
Render address ranges that can change.

| Option | How | Security trade-off |
|--------|-----|--------------------|
| **A. Render's outbound IP ranges** (preferred if shown) | Render → your service → *Connect* → *Outbound*. Add each listed range in Atlas → *Network Access* → *Add IP Address*. | Narrower than open-to-all, but the ranges are **shared with other Render customers in the same region**: it reduces who can reach the database, it does not isolate it. If Render changes the ranges, the app loses the database until you update the list. |
| **B. Allow from anywhere** `0.0.0.0/0` | Atlas → *Network Access* → *Add IP Address* → *Allow access from anywhere*. | Anyone on the internet can **try** to connect. What still protects the data: TLS (always on in Atlas), a long random password, a user limited to `readWrite` on `sellerdesk`. Common for small projects on free hosting, but the password becomes the only lock. |
| C. Private networking / static IP | Paid Render feature or add-on, plus Atlas private endpoints (dedicated, paid clusters). | Strongest. Not needed for Phase 1. |

**Recommendation for the first deployment:** option A if Render shows outbound ranges for your
service (you can add them after step 2 creates the service), otherwise option B with the
restricted user from 1a. Write down which one you chose, and revisit option C before real
sellers depend on the system.

If you choose B, also:
- never reuse this password anywhere else;
- enable Atlas alerts for unusual connection counts (Atlas → *Alerts*);
- rotate the password if anyone who had it leaves the project.

---

## 2. Render Web Service (Docker, root Dockerfile)

Render dashboard → **New** → **Web Service** → select the repository.

| Setting | Value |
|---------|-------|
| Name | `sellerdesk` (becomes part of the URL) |
| Language / Runtime | **Docker** |
| Branch | `main` |
| Region | **Singapore** (closest to Bangladesh) |
| Root Directory | *(empty: the repository root)* |
| Dockerfile Path | `./Dockerfile` |
| Docker Build Context | `.` |
| Instance Type | Free for a first test; Starter or higher for real users (see §6) |
| Health Check Path | `/actuator/health` (Advanced settings) |
| Auto-Deploy | On commit |

**Environment variables** (Advanced → *Add Environment Variable*). Mark secrets as secret if the
dashboard offers it.

| Key | Value | Secret? |
|-----|-------|---------|
| `SPRING_PROFILES_ACTIVE` | `prod` (the image already defaults to it; set it anyway so it is visible) | no |
| `APP_URL` | the service's public URL, e.g. `https://sellerdesk.onrender.com`: `https`, **no trailing slash, no path**. If you do not know it yet, enter the expected one and correct it in step 3. | no |
| `MONGODB_URI` | the connection string from 1a | **yes** |
| `MONGODB_DATABASE` | `sellerdesk` | no |
| `JWT_SECRET` | a **new** random value, never the dev one. Generate in Git Bash: `openssl rand -base64 48` | **yes** |
| `MAIL_HOST` | `smtp.gmail.com` | no |
| `MAIL_PORT` | `587` | no |
| `MAIL_USERNAME` | the Gmail address | **yes** |
| `MAIL_PASSWORD` | the Gmail **App Password** (16 characters, no spaces) | **yes** |
| `MAIL_FROM` | `SellerDesk <the same Gmail address>` (Gmail rewrites other senders) | no |
| `CLOUDINARY_URL` | from the Cloudinary console (Dashboard → API environment variable): `cloudinary://<api_key>:<api_secret>@<cloud_name>`. Use a separate Cloudinary account or folder from dev. Needed once the product module is deployed. | **yes** |

Optional: `JWT_ACCESS_TTL` (default `PT15M`), `JWT_REFRESH_TTL` (default `P30D`).

Do **not** set:
- `PORT`: Render sets it, the app listens on it.
- `MONGODB_TEST_URI`: tests only. It must never exist on a server (tests delete all data).
- Anything starting with `VITE_`: the frontend has no settings any more.

Click **Create Web Service**. The first build takes several minutes (npm and Maven download
dependencies); later builds reuse cached layers.

**Check:** open `https://<your-service>.onrender.com/actuator/health`. Expected:

```json
{"status":"UP"}
```

- `{"status":"DOWN"}` / 503 almost always means MongoDB is unreachable: check §1b and `MONGODB_URI`.
- Service does not start: read the deploy log. A wrong or missing `APP_URL` is named
  (`appUrl ... set APP_URL; no trailing slash`), other missing values appear as
  `Could not resolve placeholder 'JWT_SECRET'`.

---

## 3. Check APP_URL

Copy the exact URL at the top of the service page (Render may add a suffix if the name is taken).
If it differs from `APP_URL`: service → *Environment* → fix `APP_URL` → *Save*. Render redeploys.

Why it must be exact: the refresh endpoint compares the browser's `Origin` header with `APP_URL`
character for character, and answers `403 INVALID_ORIGIN` on any difference. Symptom of a wrong
value: login works, but after a page reload (F5) you are logged out.

With a custom domain later: add it in Render, then set `APP_URL` to `https://<custom-domain>`.

---

## 4. Smoke test in production

1. Open the service URL → you land on `/login`.
2. Register a shop with a real email address → the verification email arrives → enter the code
   → the dashboard shows your shop and name.
3. Press F5 on `/dashboard` → the page loads and you are still logged in (proves the SPA fallback
   and the refresh cookie).
4. DevTools → Application → Cookies → `sd_refresh` with `HttpOnly`, `Secure`, `SameSite=Strict`,
   Path `/api/v1/auth`.
5. DevTools → Network → an `/assets/index-….js` file has `Cache-Control: max-age=31536000,
   public, immutable`; the document (`/dashboard`) has `Cache-Control: no-cache`.
6. Log out → back on `/login`; F5 → stays logged out.
7. Intended answers: `/swagger-ui.html` and `/v3/api-docs` → 404; `/actuator/env` → 401;
   `/api/v1/anything-unknown` → JSON 401 (logged out).

---

## 5. UptimeRobot: keep an eye on the service

UptimeRobot calls a URL every few minutes and emails you when it fails. It also keeps a free
Render instance awake (see §6), because the instance only sleeps after ~15 minutes without
traffic.

1. Create a free account at https://uptimerobot.com.
2. *Add New Monitor*:

   | Setting | Value |
   |---------|-------|
   | Monitor Type | **HTTP(s)** |
   | Friendly Name | `SellerDesk` |
   | URL | `https://<your-service>.onrender.com/actuator/health` |
   | Monitoring Interval | **5 minutes** (the free plan's minimum; under the ~15 minute sleep) |
   | Alert Contacts | your email |

3. Optional, stricter: monitor type **Keyword**, keyword `"status":"UP"`, alert when it does
   **not** exist. Then a running app with a broken database connection (503 `DOWN`) also alerts.
   (A plain HTTP monitor already alerts on 503.)
4. Save. The monitor turns green within one interval.

The health endpoint is public on purpose and safe for this: it returns only
`{"status":"UP"}` or `{"status":"DOWN"}`, no versions, database names or other details. Every
other actuator endpoint is not exposed and is denied by security.

Note: pinging around the clock uses free-plan instance hours (Render's free plan has a monthly
limit). Check the current limit in Render's documentation.

---

## 6. Production behavior and known limitations

Configured by `application-prod.yml` and `SecurityConfig` (checked in CI by the `docker-image`
smoke test):

| Item | Behavior |
|------|----------|
| Refresh cookie | `HttpOnly`, `Secure`, `SameSite=Strict`, Path `/api/v1/auth`. First-party: same origin as the app. |
| CORS | **Off.** Nothing is cross-origin. Other sites' JavaScript cannot read any response (no CORS headers). Refresh from another origin → `403 INVALID_ORIGIN`. |
| Actuator | Only `/actuator/health` is exposed and public; it shows only the overall status. Every other `/actuator/**` path → 401. |
| Swagger / OpenAPI | **Off** in prod (`/swagger-ui.html` and `/v3/api-docs` → 404). See below. |
| Static files | `/assets/**` cached for a year (file names change with content); `index.html` and other files `no-cache`, so a deploy reaches users on their next page load. |
| Proxy headers | `server.forward-headers-strategy: native`: the app trusts Render's `X-Forwarded-*` headers for scheme, host and client IP. |

**Why Swagger is not public in production:** a public, complete map of every endpoint and DTO
mainly helps attackers. The frontend does not need it, and its "Try it out" button would be a
second client against live data. Developers use it locally with the `dev` profile
(`http://localhost:8080/swagger-ui.html`). If it is ever needed in prod, enable it only behind
authentication, never openly.

**Limitations to know:**

- **Free instance spins down** after about 15 minutes without traffic, unless UptimeRobot (§5)
  keeps it awake. The first request after sleep waits roughly a minute. In-memory data (login
  rate limits, user cache) resets on every restart and deploy. Use a paid instance for real users.
- **Outbound email on the free plan:** Render has restricted outbound SMTP ports (25/465/587) on
  free instances. Check Render's current documentation. If they are blocked, verification and
  password-reset codes are never sent (the backend only logs `Could not send … email`), and
  registration cannot be completed. Fix: a paid instance type, or later an HTTP email API instead
  of SMTP (code change).
- **One service = one deploy.** Frontend and backend always change together; a rollback rolls
  back both. That is intended: they can never be out of step.
- **Deferred items** (docs/SCOPE.md): platform admin seeding, per-IP rate limiting.

---

## 7. Alternative: Blueprint (`render.yaml`)

`render.yaml` at the repository root describes the same single web service (root Dockerfile,
health check, environment variable names). Render dashboard → **New** → **Blueprint** → select
the repository. Render asks for every value marked `sync: false` (`APP_URL`, `MONGODB_URI`,
`JWT_SECRET`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`). Steps 1, 3, 4 and 5 still apply.
Use either the Blueprint or the manual setup, not both, or you get duplicate services.

**Moving from the old two-service setup** (`sellerdesk-api` + `sellerdesk-web` static site): create
the new service first, test it (§4), then delete the two old services. The old variables
`FRONTEND_URL`, `REFRESH_COOKIE_SAME_SITE` and `VITE_API_URL` are no longer read.

---

## 8. Updating and rolling back

- Every push to `main` redeploys (Auto-Deploy). Render does not wait for GitHub Actions, so check
  that CI is green first (or switch Auto-Deploy to "After CI checks pass" if Render offers it).
- A bad deploy: service → *Events* → pick the last good deploy → **Rollback**.
- Rotating `JWT_SECRET` logs everyone out (all existing tokens become invalid). That is the
  intended emergency action if the secret leaked.
