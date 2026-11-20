# Staff module — Spec (Phase 1)

Owner and staff with `STAFF_MANAGE` invite people by email, see everyone in the shop,
change roles and permissions, and deactivate or reactivate staff. An invited person opens
the link from the email, chooses a name and password, and is logged in.

Status: **spec, not built**. Backend package `com.sellerdesk.staff`, frontend `features/staff`.

Related: `API.md` §3 (endpoint summary), `SECURITY.md` §3 (permissions, role defaults),
`SCHEMA.md` §3 (`users`).

---

## 1. Decisions (and why)

| Decision | Why |
|----------|-----|
| An invitation is its own document (`staff_invitations`), not a `users` row with status `INVITED`. | A pending invite can be resent, cancelled or expire without ever touching `users`. The global unique email index on `users` is only taken when the person really joins. Two shops may invite the same email; the first accept wins. |
| The invite link carries a random **token** (32 bytes from `SecureRandom`, base64url). The database stores only its **SHA-256** hash. | A database leak does not reveal usable links. SHA-256 (not BCrypt) because we must *look up* the invitation by hash; BCrypt hashes are salted and cannot be searched. With 256 random bits, brute force is not possible, so a slow hash is not needed (unlike 6-digit OTPs). |
| The link is `APP_URL/accept-invite#token=<token>` (fragment, not `?token=`). | Browsers never send the `#...` part to the server, so the token stays out of access logs and `Referer` headers. The frontend reads it, then removes it from the address bar. |
| The invited person enters their **name** on the accept page; the inviter enters only email + role. | The person knows their own name; the inviter often only has an email. |
| Accept logs the user in directly (access token + refresh cookie), like `verify-email`. | Clicking the link proves email ownership, so no extra OTP step. |
| Resend **rotates** the token. | The old email's link stops working, so only the newest email is valid. |
| One combined list `GET /staff` for members and invitations. | The UI shows one table with one set of filters and one pagination. The backend merges both collections in one aggregation (`$unionWith`), each side filtered by `shopId` first. |
| A non-owner with `STAFF_MANAGE` can only grant permissions they have themselves. | Stops a manager from creating a more powerful account than their own (privilege escalation). The owner has all permissions, so the owner can grant anything. |

---

## 2. Data

### New collection: `staff_invitations` (tenant-owned, has `shopId`)

| Field | Type | Req | Notes |
|-------|------|-----|-------|
| `_id` | ObjectId | Y | |
| `shopId` | ObjectId | Y | from the inviter's JWT |
| `email` | String | Y | lowercase, trimmed |
| `role` | String | Y | `MODERATOR`, `PACKER`, `ACCOUNTANT` |
| `permissions` | Array\<String\> | Y | role defaults, or the custom list given at invite time |
| `status` | String | Y | `PENDING`, `ACCEPTED`, `CANCELLED`. *Expired* is not stored: it is `PENDING` with `expiresAt < now`. |
| `tokenHash` | String | Y | SHA-256 hex of the token. Plain token is **never** stored or logged. |
| `expiresAt` | Date | Y | last send + **7 days** |
| `invitedBy` | ObjectId | Y | → `users._id` |
| `sendCount` | Int32 | Y | emails sent for this invitation (first send = 1) |
| `lastSentAt` | Date | Y | |
| `acceptedUserId` | ObjectId | N | set on accept |
| `version`, `createdAt`, `updatedAt` | | Y | `@Version` optimistic lock |

**Indexes**
- `{ tokenHash: 1 }` unique — accept / preview lookup.
- `{ shopId: 1, email: 1 }` unique, **partial** on `{ status: "PENDING" }` — at most one open
  invitation per email per shop.
- `{ shopId: 1, status: 1, createdAt: -1 }` — list.

### Changes to `users`

- New staff are created **at accept time** with status `ACTIVE`, `shopId`/`role`/`permissions`
  copied from the invitation, `invitedBy` from the invitation.
- `INVITED` status is no longer used. Remove it from `UserStatus` when this module is built,
  and drop the "activates `INVITED` users" behavior from password reset
  (`PENDING_VERIFICATION` activation stays).

---

## 3. Endpoints

Base `/api/v1`. `shopId` always from the JWT, never from the request.

### 3.1 List — `GET /staff` (`STAFF_MANAGE`)

Query: `q` (case-insensitive *contains* on name or email; regex-escaped), `role`, `status`,
`page` (0-based), `size` (default 20, max 100).

`status` filter values: `ACTIVE`, `DISABLED`, `INVITED` (pending, not expired), `EXPIRED`
(pending, expired). Omitted → all of them (accepted and cancelled invitations are never listed).

Order: owner first, then pending invitations (newest first), then members by name.

Response: page of `StaffRow`:

```json
{
  "kind": "MEMBER",              // or "INVITATION"
  "id": "6745...",               // userId for MEMBER, invitationId for INVITATION
  "name": "Rahim Uddin",         // null for INVITATION
  "email": "rahim@example.com",
  "role": "MODERATOR",
  "permissions": ["ORDER_VIEW", "..."],
  "status": "ACTIVE",            // ACTIVE | DISABLED | INVITED | EXPIRED
  "lastLoginAt": "2026-11-18T10:15:30Z",   // MEMBER only, may be null
  "invitedAt": "2026-11-10T08:00:00Z",     // INVITATION only (createdAt)
  "expiresAt": "2026-11-17T08:00:00Z",     // INVITATION only
  "lastSentAt": "2026-11-10T08:00:00Z"     // INVITATION only
}
```

### 3.2 Permission catalog — `GET /staff/permissions` (`STAFF_MANAGE`)

→ `{permissions: [{code, description}], roleDefaults: {MODERATOR: [...], PACKER: [...], ACCOUNTANT: [...]}}`.
Descriptions come from the backend enum (`SECURITY.md` §3 "Allows" column), so UI and docs
never drift. Cacheable (`@Cacheable`), it only changes with a deploy.

### 3.3 Member detail and changes (`STAFF_MANAGE`)

| Method | Path | Request → Response |
|--------|------|--------------------|
| GET | `/staff/{userId}` | → `StaffResponse {id, name, email, phone, role, permissions, status, lastLoginAt, createdAt, invitedBy{id, name}}` |
| PATCH | `/staff/{userId}` | `{role?, permissions?}` → `StaffResponse`. Role change without `permissions` → role defaults. Bumps `tokenVersion` if anything changed. |
| PATCH | `/staff/{userId}/status` | `{status: ACTIVE or DISABLED}` → `StaffResponse`. `DISABLED` bumps `tokenVersion` (logged out everywhere). Same status as now → no-op, `200`. |

### 3.4 Invitations (`STAFF_MANAGE`)

| Method | Path | Request → Response |
|--------|------|--------------------|
| POST | `/staff/invitations` | `{email, role, permissions?}` → `201 InvitationResponse {id, email, role, permissions, status, expiresAt, lastSentAt, createdAt}`. Email sent after the save, asynchronously (mail failure does not fail the request; it is logged). |
| POST | `/staff/invitations/{id}/resend` | no body → `200 InvitationResponse`. New token, `expiresAt = now + 7 days`, `sendCount + 1`. Works for expired invitations too. |
| DELETE | `/staff/invitations/{id}` | → `204`. `status = CANCELLED`; the link stops working. |

If `POST /staff/invitations` finds a `PENDING` but **expired** invitation for the same email in
this shop, it reuses it: new role/permissions, new token, new expiry (no `409`).

### 3.5 Accept (public)

| Method | Path | Request → Response |
|--------|------|--------------------|
| POST | `/auth/invitations/preview` | `{token}` → `200 {email, shopName, role, inviterName, expiresAt}`. Lets the page show "Join *Shop* as Moderator" before the form. Token in the body, not the URL, so it is not logged. |
| POST | `/auth/invitations/accept` | `{token, name, password}` → `200 {accessToken, expiresIn, user}` + refresh cookie (same as `verify-email`). One transaction: insert user (`ACTIVE`, `lastLoginAt = now`), set invitation `ACCEPTED` + `acceptedUserId`. |

Confirm-password is checked **only in the frontend**; the API takes one `password`.
Password rules are the registration rules (`SECURITY.md` §1), including "must not equal the email".

---

## 4. Rules and errors

All errors use the `ApiError` format. Frontend switches on `code`.

| Situation | Status | `code` |
|-----------|--------|--------|
| Bean Validation (bad email, missing role, password rules, name blank / > 100) | 400 | `VALIDATION_FAILED` (with `fieldErrors`) |
| Role is `SHOP_OWNER` / `PLATFORM_ADMIN` or unknown; unknown permission code | 400 | `VALIDATION_FAILED` (`fieldErrors` on `role` / `permissions`) |
| Invite: email belongs to a member of **this** shop | 409 | `ALREADY_A_MEMBER` |
| Invite: email has an account in **another** shop (or platform admin) | 409 | `EMAIL_ALREADY_REGISTERED` |
| Invite: a non-expired `PENDING` invitation for this email exists in this shop | 409 | `ALREADY_INVITED` |
| Resend within 60 s of the last send | 429 | `INVITE_RESEND_TOO_SOON` (`Retry-After` header) |
| Resend: more than 5 sends for one invitation in 24 h | 429 | `INVITE_RESEND_LIMIT` |
| Resend / cancel an invitation that is not `PENDING` | 409 | `INVITATION_NOT_PENDING` |
| Change role / permissions / status of the owner | 403 | `OWNER_READ_ONLY` |
| Change own role / permissions / status | 403 | `CANNOT_MODIFY_SELF` |
| Non-owner grants (invite or edit) a permission they do not have | 403 | `PERMISSION_NOT_GRANTABLE` |
| Two people edit the same member at once | 409 | `CONCURRENT_MODIFICATION` |
| Missing `STAFF_MANAGE` | 403 | `ACCESS_DENIED` (security handler) |
| Member or invitation id unknown **or from another shop** | 404 | `NOT_FOUND` |
| Preview / accept: token unknown, cancelled or already used | 400 | `INVALID_INVITATION` |
| Preview / accept: token valid but expired | 400 | `INVITATION_EXPIRED` |
| Accept: the email got an account since the invite (e.g. another shop's invite accepted first) | 409 | `EMAIL_ALREADY_REGISTERED` |
| Accept: the shop is not `ACTIVE` | 400 | `INVALID_INVITATION` |

Why `ALREADY_A_MEMBER` and `EMAIL_ALREADY_REGISTERED` may be different: the inviter can already
see their own shop's members, so naming that case leaks nothing. "Registered elsewhere" is
the same answer registration already gives (`409 EMAIL_ALREADY_REGISTERED`).

Other rules:
- Changing a member's role or permissions, or disabling them, bumps `tokenVersion`: their
  current session ends at the next request and they log in again with the new rights.
- Reactivating (`DISABLED → ACTIVE`) does not change role or permissions.
- Owner rows are returned by the list and `GET /staff/{id}` but are read-only.
- No rate limit on preview/accept beyond the token's strength. Per-IP limits stay deferred
  (`SCOPE.md`).

---

## 5. Emails

Invite email (subject: "*Inviter* invited you to *Shop* on SellerDesk"): shop name, inviter
name, role, the link, "expires on *date*", and "ignore this email if you did not expect it".
Sent by an `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`, like OTP mails. The plain
token lives only in the event object and the email, never in logs.

---

## 6. Frontend

| Piece | Where |
|-------|-------|
| API wrappers + types | `api/staff.ts`, `types/staff.ts` |
| Permission helper | `lib/permissions.ts` (`hasPermission`), `features/auth/usePermissions.ts` (`can`, `canAny`), `components/Can.tsx`, `routes/RequirePermission.tsx`. Shared with Products; built by whichever module comes first. |
| Pages | `features/staff/StaffPage.tsx` (`/staff`), `features/staff/AcceptInvitePage.tsx` (`/accept-invite`, public) |
| Dialogs / drawer | `InviteDialog`, `StaffDrawer` (role, permissions, deactivate/reactivate with confirm) |

- Sidebar "Staff" item shown only with `STAFF_MANAGE`; the route also requires it (shows a
  "no access" page instead of redirecting).
- Filters, search (debounced 300 ms) and page live in the URL query (`?q=&role=&status=&page=`),
  so refresh and back/forward keep them.
- Below the `md` breakpoint the table becomes a list of cards.
- Hiding a button is only a convenience. The backend checks every permission again.

---

## 7. Tests (backend)

Service unit tests: invite (each `409`), reuse of expired invitation, resend cooldown/limit and
token rotation, cancel, accept (creates `ACTIVE` user with invitation's shop/role/permissions,
marks `ACCEPTED`, second accept fails), expired token, owner/self rules, grant-subset rule,
role change resets permissions, `tokenVersion` bumps.

`TenantIsolationTest` → `@Nested Staff`:
- `GET/PATCH /staff/{id}`, `PATCH /staff/{id}/status` with another shop's user id → `404`.
- `POST /staff/invitations/{id}/resend`, `DELETE /staff/invitations/{id}` with another shop's
  invitation id → `404`.
- `GET /staff` lists only own members and invitations (incl. with `q` matching the other shop).
- `shopId` in the invite / patch body is ignored.
- Accepting shop A's invitation creates a user in shop A, whatever the body contains.
