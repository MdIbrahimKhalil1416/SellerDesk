# Courier module — Design (Phase 2)

Books packed orders with a courier, follows each parcel through the courier's status updates
(webhooks and tracking), and moves the order forward through `OrderService`. The courier collects
the COD amount; this module records what the courier reports (`collectedAmount`). Matching that
against the courier's payouts is the later COD reconciliation module.

Status: **backend and frontend built** (2026-11-28). Phase 2 ships only a
`MockCourierProvider`; real providers (Steadfast, Pathao) are added later behind the same
interface. Backend package `com.sellerdesk.courier`, plus `common.security.SystemActor` /
`SystemActorScope`. Configuration: `sellerdesk.courier.*` in `application.yml` (the mock is off in
prod); secret `COURIER_WEBHOOK_SECRET`.

Related: `ORDER.md` §4 (status machine), §6 (transactions), §8 (bulk); `INVENTORY.md` §4
(`DEDUCT` at shipping); `SECURITY.md` §4–5 (tenant isolation and its tests); `ARCHITECTURE.md`
§1 (module rules, data ownership), §3 (`TenantContext`); `SCHEMA.md` §10 (indexes start with
`shopId`), §11 (transactions).

---

## 1. Decisions (and why)

| Decision | Why |
|----------|-----|
| **Booking does not ship the order.** "Send to courier" creates a shipment (`CREATED`); the order stays `PACKED`. The order becomes `SHIPPED` (stock `DEDUCT`) when the courier reports **`PICKED_UP`**. | Decided 2026-11-28. Until the rider picks it up, the parcel is still on the seller's shelf. Cancelling a booking before pickup then never has to undo a stock deduction. |
| **Courier `DELIVERED` delivers the order automatically. Courier `RETURNED` only marks the shipment**; staff then run the existing `POST /orders/{id}/return` with the per-item restock choice. | Decided 2026-11-28. Delivery needs no human choice. A return does: a damaged item must not go back on the shelf (`INVENTORY.md` §1), and only the person who opens the parcel knows. |
| **Phase 2 = mock provider only**, one platform webhook secret from an env var. Per-shop courier credentials are designed (§2) but deferred. | Decided 2026-11-28. The whole flow (booking, webhook security, idempotency, order effects) can be built and tested without a courier contract. Each seller has their own Steadfast / Pathao account, so real providers will need per-shop credentials. |
| **Internal `BOOKING` status** while the provider call is in progress. | Decided 2026-11-28. After a timeout we do not know whether the courier created the parcel. A `BOOKING` record with a stable `merchantRef` lets a retry finish the same booking instead of creating a second parcel. |
| **Business logic only talks to the `CourierProvider` interface**, with our own types (our statuses, poisha). Each provider adapter maps its own statuses and units. | A new courier is one new class; `ShipmentService`, the webhook service and `OrderService` do not change. |
| **One active shipment per order**, enforced by a unique partial index, not only by a check in code. | Two people pressing "Send" at the same time must not book two parcels. A check-then-insert has a gap; a unique index does not. |
| **No provider HTTP call inside a database transaction.** | A courier call can take seconds. Holding a transaction open that long causes write conflicts and timeouts. The DB writes before and after the call are short and conditional instead (§6). |
| **The order changes only through `OrderService`**; the courier module never writes `orders`, stock or customers. | `ARCHITECTURE.md` §1: one owner per collection. Stock and customer counters stay in the same transaction as the order status, exactly as in Phase 1. |
| **The webhook takes `shopId` from the stored shipment**, never from the request. | The webhook has no JWT. Anything in the body could be forged by whoever learns the URL; the shipment record is ours. |
| **Money stays `long` poisha.** The adapter converts to taka only when talking to a provider that wants taka. | Same rule as everywhere (`SCHEMA.md` §2). |
| **Permissions: reuse `ORDER_FULFILL`** (send, cancel, bulk) and **`ORDER_VIEW`** (get, list, track). | Decided 2026-11-28. Booking a courier is part of shipping, which `ORDER_FULFILL` already covers (`PACKER`). No new permission until a shop needs it. |
| **Lost webhooks are caught by a tracking sweep**: active shipments with no event for 12 hours are tracked automatically. | Decided 2026-11-28. Webhooks can be lost (courier outage, our deploy). Without a fallback, an order could stay `SHIPPED` forever although the parcel was delivered. |
| **No manual shipment status override.** Staff use `track`; if the courier's API is wrong, they close the order by hand (`/deliver`, `/return`). | Decided 2026-11-28. The shipment mirrors the courier's view; a hand-edited status would hide real courier problems. The order endpoints already cover the human decision. |

---

## 2. Provider abstraction

```java
public interface CourierProvider {
    CourierCode code();                                            // MOCK; later STEADFAST, PATHAO

    BookingResult createShipment(CreateShipmentCommand command);   // idempotent on command.merchantRef()
    Optional<BookingResult.Created> findByMerchantRef(String merchantRef);   // timeout recovery
    TrackResult track(String consignmentId);
    CancelResult cancel(String consignmentId);

    /** Verifies the signature first, then maps the payload. Throws InvalidSignatureException. */
    WebhookEvent parseWebhook(HttpHeaders headers, byte[] rawBody);
}

record CreateShipmentCommand(String merchantRef, Recipient recipient, long codAmountPoisha,
                             String note, int itemCount) {}
record Recipient(String name, String phone, Address address) {}

sealed interface BookingResult {
    record Created(String consignmentId, String trackingCode, ShipmentStatus status) implements BookingResult {}
    record Rejected(String reason, Map<String, String> fieldHints) implements BookingResult {}  // definite "no"
    record Unavailable(String cause) implements BookingResult {}    // timeout, 5xx, network: outcome unknown
}

record WebhookEvent(String consignmentId, String merchantRef, ShipmentStatus status,
                    String providerStatus, String providerEventId, Instant providerTime,
                    Long collectedAmountPoisha, String note) {}
```

`TrackResult` and `CancelResult` use the same three shapes (`Ok`, `Rejected`, `Unavailable`).

- **Only our types cross the interface.** Each adapter has its own mapping table from the
  provider's status strings to `ShipmentStatus`. An unknown provider status maps to "no status
  change, event only" and is logged, never guessed.
- **`Rejected` vs `Unavailable`** is the key distinction. `Rejected` (bad address, invalid
  phone) means the courier said no, so the shipment can safely become `FAILED`. `Unavailable` means
  we do not know, so the shipment stays `BOOKING` (§6).
- **`CourierProviderRegistry`** collects all enabled `CourierProvider` beans into a
  `Map<CourierCode, CourierProvider>`. A provider is enabled by `sellerdesk.courier.<code>.enabled`.
  An unknown or disabled `provider` in a request → `400 COURIER_NOT_AVAILABLE`.

### MockCourierProvider

- `consignmentId` = `MOCK-` + 10 random digits; `trackingCode` = `MCK` + 8 random characters.
- **Idempotent on `merchantRef`**: a second `createShipment` with the same ref returns the same
  consignment. The mock keeps its parcels in memory; on restart it rebuilds them from
  `shipments`, so dev data survives a restart.
- **Failure injection**: `sellerdesk.courier.mock.mode = OK | TIMEOUT | REJECT | FLAKY` (`FLAKY` =
  every second call `Unavailable`), plus a per-call override for tests
  (`mockCourier.nextResult(...)`).
- **`MockCourierSimulator`** (`@Profile("dev")`, `@Scheduled`, every 30 s): moves each mock parcel
  one step `CREATED → PICKED_UP → IN_TRANSIT → DELIVERED` (a configurable share,
  default 10 %, ends `RETURNED`). It sends a **signed webhook over HTTP to our own endpoint**, so
  the real verification and processing path runs in dev, not a shortcut.

### Adding a real provider later (Steadfast, Pathao)

1. A new class, e.g. `courier.provider.steadfast.SteadfastCourierProvider implements CourierProvider`.
2. Its config properties (`sellerdesk.courier.steadfast.base-url`, timeouts, `enabled`).
3. Its status mapping table and its own webhook check in `parseWebhook`.
4. Unit tests with recorded sample payloads.

`ShipmentService`, `CourierWebhookService`, `OrderService` and the frontend do not change: the
new code is added to the existing `CourierCode` enum and the provider dropdown. To check against
the providers' current API docs when we build them (not verified now): Steadfast takes an
`invoice` (our `merchantRef`) and COD in taka, and authenticates webhooks with a Bearer token;
Pathao uses OAuth tokens, a `merchant_order_id` and a signature header.

### Per-shop credentials (deferred)

Real providers need each shop's own account. Planned, not built in Phase 2:

- `courier_accounts` (tenant-owned): `{shopId, provider, apiKey, apiSecret, webhookSecret, active}`,
  the secrets encrypted with AES-GCM, key from `COURIER_CREDENTIALS_KEY`. Never returned by the API.
- The webhook path then carries a public, random `accountKey`:
  `/webhooks/courier/{provider}/{accountKey}`. The key selects the account and its secret; after
  verification, the shipment found by consignment id must belong to that same account's shop,
  otherwise `404`.
- `CreateShipmentCommand` gets the account's credentials from `ShipmentService`, so adapters stay
  stateless.

---

## 3. Data: `shipments` (tenant-owned, owner `courier`)

One document per booking attempt. A failed or cancelled booking stays as history; resending
creates a new document.

| Field | Type | Req | Notes |
|-------|------|-----|-------|
| `_id` | ObjectId | Y | |
| `shopId` | ObjectId | Y | from the JWT (or, in webhook / job code, from the shipment itself, §5) |
| `orderId` | ObjectId | Y | → `orders._id` |
| `orderNumber` | String | Y | display snapshot, `SD-1042` |
| `provider` | String | Y | `CourierCode`: `MOCK` (later `STEADFAST`, `PATHAO`) |
| `merchantRef` | String | Y | our reference sent to the courier = `_id` as hex. Unique by construction, never reused; a retry sends the same value |
| `consignmentId` | String | N | the courier's id, set when `CREATED` |
| `trackingCode` | String | N | code shown to the customer, set when `CREATED` |
| `status` | String | Y | `BOOKING`, `CREATED`, `PICKED_UP`, `IN_TRANSIT`, `DELIVERED`, `RETURNED`, `CANCELLED`, `FAILED` |
| `active` | Boolean | Y | `true` for `BOOKING`, `CREATED`, `PICKED_UP`, `IN_TRANSIT`. Set in the same update as `status`; exists only for the partial unique index |
| `codAmount` | Int64 | Y | poisha, copy of `order.codAmount` at booking (frozen since `CONFIRMED`, `ORDER.md` §5) |
| `collectedAmount` | Int64 | N | poisha, as reported by the courier on `DELIVERED` |
| `codMismatch` | Boolean | N | `true` when `collectedAmount ≠ codAmount` (partial delivery, courier error). Only stored and shown; handled by the later COD reconciliation module |
| `courierFee` | Int64 | N | poisha, stored whenever the courier reports it (the mock does), for later COD reconciliation |
| `lastEventAt` | Date | N | time of the last courier event (webhook or poll); drives the tracking sweep (§6) |
| `lastTrackedAt` | Date | N | last call to the courier's track API; the track endpoint calls it at most once per 60 s |
| `itemCount` | Int32 | Y | units in the parcel, sent with every booking call |
| `recipient` | Object | Y | snapshot `{name, phone, address}` from the order, so a retry sends exactly the same data |
| `note` | String | N | ≤ 200, instructions for the rider |
| `bookingAttempts` | Int32 | Y | provider calls made for the booking |
| `nextAttemptAt` | Date | N | while `BOOKING` after a timeout: when the reconciler tries again |
| `failureReason` | String | N | for `FAILED`: provider reason or `COURIER_TIMEOUT` |
| `needsManualCheck` | Boolean | N | `true` if we gave up without knowing whether the courier created the parcel |
| `cancelReason` | String | N | 1–500 |
| `events` | Array\<ShipmentEvent\> | Y | newest last, capped at 100 (`$push` with `$slice: -100`) |
| `createdBy`, `createdByName` | ObjectId, String | Y | who pressed "Send" |
| `version` | Int64 | Y | `@Version` |
| `createdAt`, `updatedAt` | Date | Y | |

**`ShipmentEvent`**: `at` (our time), `source` (`USER`, `SYSTEM`, `WEBHOOK`, `POLL`), `status`
(our status after the event, or `null`), `providerStatus` (raw string, ≤ 50), `providerTime`,
`eventKey` (idempotency key, §5), `ignored` (Boolean), `note` (≤ 300, e.g. the reason an event
was ignored, or the courier's location text).

### Indexes

1. `{shopId: 1, orderId: 1, createdAt: -1}`: all shipments of an order (order page).
2. `{shopId: 1, orderId: 1}` **unique, partial `{active: true}`**: at most one active shipment
   per order. A boolean field is used because partial indexes support equality on a field more
   simply and reliably than `$in` on `status`.
3. `{shopId: 1, status: 1, updatedAt: -1}`: lists by status, e.g. "returned by courier, return not
   confirmed yet".
4. `{provider: 1, consignmentId: 1}` **unique, partial `{consignmentId: {$exists: true}}`**: webhook
   lookup.
5. `{status: 1, nextAttemptAt: 1}` partial `{status: "BOOKING"}`: the booking reconciler.
6. `{active: 1, lastEventAt: 1}` partial `{active: true}`: the tracking sweep (§6).

**Documented exception to "every index starts with `shopId`"** (`SCHEMA.md` §10): indexes 4, 5
and 6. A webhook arrives without any tenant: the only thing we know is the provider and the
consignment id, which is unique per provider. The reconciler and the tracking sweep are
background jobs for all shops.
Both reads live in **one** class, `ShipmentRoutingRepository`. It returns only
`ShipmentRoute(shipmentId, shopId)`, nothing else, and it gets an ArchUnit exclusion like the
`common.storage` cleanup repository (`SECURITY.md` §4). Everything after the routing step uses the
normal shopId repositories.

### Shipment status machine

```
          booked                pickup              transit              delivered
BOOKING ─────────► CREATED ─────────► PICKED_UP ─────────► IN_TRANSIT ─────────► DELIVERED
   │                  │                    │                    │
   │ rejected /       │ cancel             └────────────────────┴──── returned ──► RETURNED
   │ gave up          ▼
   ▼               CANCELLED  (also from BOOKING)
 FAILED
```

- `BOOKING → CREATED | FAILED | CANCELLED`.
- From `CREATED` on, **forward moves only**, by rank `CREATED 1 < PICKED_UP 2 < IN_TRANSIT 3 <
  DELIVERED 4 = RETURNED 4`. A forward skip is allowed (couriers sometimes skip a step, or the
  pickup webhook is lost): `CREATED → IN_TRANSIT` or `CREATED → DELIVERED` counts as a pickup too.
- `CANCELLED` only from `BOOKING` or `CREATED`. `RETURNED` only from `PICKED_UP` or `IN_TRANSIT`
  (nothing can be returned before it was picked up).
- `DELIVERED`, `RETURNED`, `CANCELLED`, `FAILED` are terminal.
- The **same status again** (e.g. a second `IN_TRANSIT` with a new location) is not a change: the
  event is appended, the status stays.
- Every other pair is an **illegal jump** (examples: `DELIVERED → IN_TRANSIT`,
  `CREATED → RETURNED`, `CANCELLED → PICKED_UP`). It is recorded as an ignored event and changes
  nothing (§5).

`ShipmentStatusMachine` holds this as one table, like `OrderTransitions`.

---

## 4. Endpoints

Base `/api/v1`. The shop is always the logged-in user's (JWT). No `shopId` in any path, query or
body; one sent in a body is ignored.

| Method | Path | Permission | Request → Response |
|--------|------|------------|--------------------|
| POST | `/orders/{id}/shipment` | `ORDER_FULFILL` | `{provider, note?}` → `201 ShipmentResponse` (`CREATED`), or `202 ShipmentResponse` (`BOOKING`: courier did not answer, retry is automatic). Rules below. |
| GET | `/orders/{id}/shipments` | `ORDER_VIEW` | → `[ShipmentSummary]`, all attempts, newest first. Unknown / other shop's order → `404`. |
| GET | `/shipments` | `ORDER_VIEW` | `?status=&provider=&page=&size=` → page of `ShipmentSummary` (+ `orderNumber`), newest `updatedAt` first (index #3). Used by the "Returned by courier" tab (`status=RETURNED`) and the "Booking failed" view. |
| GET | `/shipments/{id}` | `ORDER_VIEW` | → `ShipmentResponse` |
| POST | `/shipments/{id}/track` | `ORDER_VIEW` | → `ShipmentResponse`. Asks the provider for the current status and applies it like a webhook (source `POLL`). At most one provider call per shipment per 60 s; within that window the stored shipment is returned. Terminal shipments are returned without a call. |
| POST | `/shipments/{id}/cancel` | `ORDER_FULFILL` | `{reason}` → `ShipmentResponse` (`CANCELLED`). Only from `BOOKING` / `CREATED`. The order stays `PACKED` and can be sent again. |
| POST | `/shipments/bulk` | `ORDER_FULFILL` | `{provider, orderIds (1–20, unique)}` → `200 BulkShipmentResponse` |
| POST | `/webhooks/courier/{provider}` | public, signed | §5 |

### Send to courier: rules

1. The order is loaded with `{_id, shopId}`. Unknown or another shop's → `404`.
2. The order must be `PACKED`, else `409 ORDER_NOT_PACKED` (message names the current status).
3. An **active shipment already exists** for the order:
   - same `provider` → `200` with that shipment, nothing new (a double click or a retry after a
     slow response is harmless);
   - other `provider` → `409 SHIPMENT_ALREADY_ACTIVE` (cancel it first).
4. Otherwise the booking runs as in §6. Outcomes: `201` (`CREATED`), `202` (`BOOKING`), or
   `409 COURIER_REJECTED` with the provider's reason (and `fieldErrors` where the adapter can map
   them, e.g. `recipient.phone`); the shipment is then `FAILED` and the user can fix the order's
   address (`PATCH /orders/{id}/details`) and send again.

### Bulk send

Same pattern as order bulk status (`ORDER.md` §8): each order runs the same service method as the
single endpoint, one after the other, and gets its own result. A failure does not stop the
others.

```
BulkShipmentResponse {provider, succeeded, failed,
                      results[{orderId, orderNumber?, ok, shipmentId?, status?, errorCode?, message?}]}
```

`BOOKING` counts as `ok: true` (it will finish by itself). Another shop's or a malformed id →
`NOT_FOUND`. Max **20** ids, no duplicates → else `400`. Why 20, not 50 like order bulk: each
order may wait up to 10 s for the courier, so 20 keeps the worst case near 3 minutes. A bigger
batch is sent as several requests by the frontend. A background job with a progress view can
replace this later if sellers need it.

The provider is always given in the request (Phase 2 has only `MOCK`). A per-shop default
courier (`settings.defaultCourier`) is added together with the first real provider.

### Responses

```
ShipmentResponse {id, orderId, orderNumber, provider, consignmentId, trackingCode, status,
                  codAmount, collectedAmount, codMismatch, courierFee, recipient{name, phone, address},
                  note, failureReason, needsManualCheck, cancelReason,
                  events[{at, source, status, providerStatus, ignored, note}],
                  allowedActions [TRACK, CANCEL],          // for the current user and status
                  createdBy{id, name}, createdAt, updatedAt}
ShipmentSummary  {id, orderId, orderNumber, provider, consignmentId, trackingCode, status, codAmount,
                  collectedAmount, codMismatch, failureReason, needsManualCheck, createdAt, updatedAt}
```

`eventKey` is internal and never returned.

### Errors

| Situation | Status | `code` |
|-----------|--------|--------|
| Bean Validation (missing `provider` / `reason`, sizes, bulk size, duplicate ids) | 400 | `VALIDATION_FAILED` |
| Unknown enum value, broken JSON | 400 | `MALFORMED_REQUEST` |
| Unknown or disabled provider | 400 | `COURIER_NOT_AVAILABLE` |
| Webhook signature missing / wrong / too old | 401 | `INVALID_SIGNATURE` |
| Order or shipment unknown **or of another shop** | 404 | `NOT_FOUND` |
| Order not `PACKED` | 409 | `ORDER_NOT_PACKED` |
| Active shipment with another provider | 409 | `SHIPMENT_ALREADY_ACTIVE` |
| Cancel after pickup or in a terminal status | 409 | `SHIPMENT_NOT_CANCELLABLE` |
| Courier refused the booking | 409 | `COURIER_REJECTED` (`fieldErrors` where known) |
| Manual `/ship` or `/cancel` of an order with an active shipment (order endpoints) | 409 | `SHIPMENT_ACTIVE` |
| Courier did not answer a cancel / track call | 503 | `COURIER_UNAVAILABLE` |
| Missing permission | 403 | `FORBIDDEN` |

---

## 5. Webhook

`POST /api/v1/webhooks/courier/{provider}`. The courier calls it when a parcel's status changes.

### How it is secured

1. **Public path, own check.** `SecurityConfig` adds `/api/v1/webhooks/courier/**` to the public
   paths: no JWT, no cookie, no session, CSRF not relevant (no cookie is used), no CORS (the caller
   is a server, not a browser). The path is under `/api/`, as `SECURITY.md` §6 requires.
2. **Signature before anything else.** The body is read as **raw bytes** (max 64 KB, else `413`),
   because a signature is computed over the exact bytes; parsing JSON first and re-serialising
   would change them. `provider.parseWebhook(headers, rawBody)` verifies the signature **before
   any database access**. The mock scheme (real adapters use their provider's scheme):

   ```
   X-SD-Timestamp: 1764320000                 (Unix seconds)
   X-SD-Signature: sha256=<hex(HMAC_SHA256(COURIER_WEBHOOK_SECRET, timestamp + "." + rawBody))>
   ```

   - compared with `MessageDigest.isEqual` (constant time, so response time reveals nothing);
   - timestamp more than **5 minutes** away from our clock → rejected, so a captured request
     cannot be replayed later;
   - any failure → `401 INVALID_SIGNATURE`, the same response for every cause; the log line
     names the cause but never the body or the signature.
3. **Secret from the environment.** `COURIER_WEBHOOK_SECRET` (≥ 32 random bytes, base64). The app
   refuses to start if a provider is enabled and the secret is missing or too short. Never logged,
   never in git.
4. **The body is not trusted for the tenant.** Only these fields are used: consignment id /
   merchant reference, provider status, provider event id and time, collected amount, note. Any
   `shopId`, `orderId` or similar in the body is ignored.
5. Unknown `{provider}` → `404`.

### How the tenant is found, and how `TenantContext` is handled

A webhook request has no logged-in user, so `TenantContext` is **empty** when it arrives
(`ARCHITECTURE.md` §3). Normal tenant code (`CurrentUser.shopId()`, `OrderService`,
`InventoryService`) would fail with `403`. The webhook handles this in two clear steps:

1. **Route.** `ShipmentRoutingRepository.findRoute(provider, consignmentId)` (index #4), or, if the
   webhook comes before our `CREATED` write has stored the consignment id, by `merchantRef`
   (= `_id`). It returns only `(shipmentId, shopId)`. Not found → `404`, so the courier retries
   later (by then the booking write is done).
2. **Run as the courier, inside that shop.** `SystemActorScope.runAs(shopId, Actor.courier(provider),
   () -> webhookService.apply(shipmentId, event))`:
   - sets `TenantContext` to the shipment's `shopId`;
   - sets a `SecurityContext` with a **system principal**: a reserved user id
     (`SystemActor.COURIER_ID`, a fixed ObjectId no real user can have), name e.g.
     `"Courier (Mock)"`, role `SYSTEM`, **no permissions**. It is never a JWT and cannot call any
     endpoint;
   - clears both in a `finally` block, exactly like `JwtAuthenticationFilter`, so a pooled thread
     never keeps a shop id.

   Inside the scope, everything is ordinary tenant code: shopId repositories, `CurrentUser`,
   `OrderService`, `InventoryService`. The order's status history and the stock movements show
   "Courier (Mock)" as who did it. The booking reconciler and the dev simulator use the same scope.

Why not pass `shopId` into `OrderService` as a parameter? `OrderService` and `InventoryService`
deliberately read the shop only from `CurrentUser` (so a caller cannot pass the wrong shop by
mistake). Setting the context from a **trusted source** (our own shipment record) keeps that rule
intact, and keeps the "which shop" decision in one small, tested place.

### Processing (one transaction)

Inside `runAs`, `TransactionRunner.run(...)`:

```
1. load shipment {_id, shopId}
2. eventKey seen before in shipment.events?          → DUPLICATE: return, change nothing
3. ShipmentStatusMachine.check(current, event.status)
     illegal jump                                   → push event {ignored: true, note: reason}
                                                      only; return IGNORED (log warn)
     same status                                    → push event only
     allowed                                        → continue
4. updateFirst {_id, shopId, status: current, "events.eventKey": {$ne: key}}
     $set status, active, collectedAmount?, codMismatch?, courierFee?, lastEventAt, updatedAt;
     $push event ($slice -100); $inc version
   matched 0                                        → changed meanwhile: re-read, go to 2
5. order effect through OrderService.applyCourierEvent(orderId, effect)   (MANDATORY)
})                                                   // commit: shipment + order + stock + counters
```

**Courier status → order effect** (only through `OrderService`, never a direct write):

| New shipment status | Order is | Effect |
|---------------------|----------|--------|
| `PICKED_UP`, or `IN_TRANSIT` (skip) | `PACKED` | `SHIP`: `PACKED → SHIPPED`, stock `DEDUCT`, `shipping = {courierName: provider name, trackingId: trackingCode}` |
| `PICKED_UP` / `IN_TRANSIT` | `SHIPPED` or later | none |
| `DELIVERED` | `PACKED` | `SHIP`, then `DELIVER` (both in this transaction) |
| `DELIVERED` | `SHIPPED` | `DELIVER`: `recordDelivered(total)` |
| `DELIVERED` | `DELIVERED` (closed by hand) | none (order transitions are idempotent, `ORDER.md` §4) |
| `RETURNED` | any | none. The shipment shows `RETURNED`; the UI asks staff to confirm the return with restock choices |
| `CANCELLED`, `FAILED` | `PACKED` | none. The order can be sent again |
| any | `CANCELLED` / `RETURNED` / other unexpected | none; event recorded with a note, warning logged |

An order effect that is not allowed (`409 INVALID_STATUS_TRANSITION` from `OrderService`) is
**not** an error for the courier: the shipment change is still saved, the event gets a note, and
the webhook answers `200`. The courier's view of the parcel is a fact; the order may have been
closed by hand.

### Idempotency

Couriers deliver webhooks **at least once**: the same event can arrive twice, or two copies at the
same time.

- **`eventKey`** = the provider's event id if it has one, else
  `sha256(consignmentId | providerStatus | providerTime)`.
- A repeat finds the key in `events` (step 2) or fails the `$ne` filter (step 4) → `DUPLICATE`,
  nothing changes.
- Two copies in parallel both write the same shipment document → one gets a `WriteConflict` →
  `TransactionRunner` runs it again → it now finds the key → `DUPLICATE`.
- Even without the key, a repeated status is "same status" (no change) and order transitions are
  idempotent. The key makes the event log clean as well.
- The 100-event cap could in theory drop an old key. A courier does not resend a week-old event,
  and the status rules still prevent a second order effect.

### Responses

| Result | Status | Body |
|--------|--------|------|
| Processed / duplicate / ignored (illegal jump) | 200 | `{result: PROCESSED \| DUPLICATE \| IGNORED}` |
| Signature invalid | 401 | `ApiError INVALID_SIGNATURE` |
| Unknown provider or consignment | 404 | `ApiError NOT_FOUND` |
| Database error | 500 | `ApiError INTERNAL_ERROR`; the courier retries, idempotency makes that safe |

Illegal jumps answer `200` on purpose: retrying will never make them legal, and a non-2xx answer
would make the courier resend it again and again.

---

## 6. Failure handling

Rule: **the order never changes because of a provider call alone.** Order changes come only from a
courier status (webhook / track) inside one transaction with the shipment update. Provider calls
happen outside transactions; their results are applied with short conditional updates.

### Send to courier

```
1. checks (order PACKED, own shop, no other active shipment)
   insert shipment {status: BOOKING, active: true, merchantRef = _id, recipient, codAmount}
     DuplicateKey on index #2 (parallel send)       → return the existing active shipment
2. provider.createShipment(command)                  // outside any transaction
     timeouts: connect 3 s, read 10 s; no retry inside the request
3. updateFirst {_id, shopId, status: BOOKING} with the outcome:
     Created      → CREATED, consignmentId, trackingCode, event        → 201
     Rejected     → FAILED, active false, failureReason, event         → 409 COURIER_REJECTED
     Unavailable  → stays BOOKING, bookingAttempts+1,
                    nextAttemptAt = now + 1 min, event                 → 202
```

What the user sees:
- `201`: "Booked with Mock courier, tracking MCK1A2B3C4D". The order stays in the `PACKED` tab
  with a "Booked" badge until pickup.
- `202`: "The courier did not answer. SellerDesk will retry automatically." The order shows
  "Booking…"; the page polls `GET /shipments/{id}`.
- `409 COURIER_REJECTED`: the courier's reason ("Invalid phone number"), the field highlighted
  where known. Fix and send again.

If the server crashes between steps 1 and 3, the shipment stays `BOOKING` with no
`nextAttemptAt`; the reconciler also picks up `BOOKING` shipments older than 2 minutes, so nothing
is stuck forever.

### Booking reconciler

`ShipmentBookingReconciler`, `@Scheduled` every 1 min:

1. `ShipmentRoutingRepository` finds `BOOKING` shipments with `nextAttemptAt ≤ now` (or no
   `nextAttemptAt` and older than 2 minutes), all shops, max 50 per run (index #5).
2. Each one runs in `SystemActorScope.runAs(shopId, Actor.system(), ...)`.
3. `provider.findByMerchantRef(merchantRef)` first: the parcel may already exist from the call
   that timed out. Found → `CREATED`.
4. Not found → `createShipment` again with the **same** `merchantRef` (the provider deduplicates
   on it).
5. Still `Unavailable` → back off: 1, 2, 5, 10, 30 minutes. After the 6th call (first + 5 retries) → `FAILED`,
   `failureReason: COURIER_TIMEOUT`, `needsManualCheck: true` (we do not know whether the courier
   has it). The UI tells the user to check the courier's merchant panel before sending again.

Each result is the same conditional `{status: BOOKING}` update as step 3. If the user cancelled in
the meantime, the update matches nothing and the result is dropped (and a parcel created by that
late call is cancelled at the provider).

### No drift between order and shipment

- While a shipment is **active**, the order **cannot be cancelled or shipped by hand**
  (`409 SHIPMENT_ACTIVE`, through `ShipmentGuard`, §7). Otherwise the courier could pick up a
  parcel for a cancelled order. Cancel the shipment first.
- Manual `/deliver` and `/return` stay allowed (an accountant closing orders from the courier's
  COD report). A later webhook then finds the order already closed and does nothing.
- An address change after booking (`PATCH /orders/{id}/details`) does **not** change the booked
  shipment. The UI warns: "Already booked: cancel and resend to use the new address."

### Cancel shipment

1. `BOOKING` or `CREATED`, else `409 SHIPMENT_NOT_CANCELLABLE`.
2. `BOOKING` without a consignment id: cancelled locally; a late booking result is dropped and
   cancelled at the provider (above).
3. `CREATED`: `provider.cancel(consignmentId)` outside a transaction.
   - `Ok` → conditional update `{status: CREATED}` → `CANCELLED`, `active: false`.
   - `Rejected` (already picked up) → `409 SHIPMENT_NOT_CANCELLABLE`; a track call updates the status.
   - `Unavailable` → `503 COURIER_UNAVAILABLE`, shipment unchanged. The user tries again (cancel is
     idempotent at the provider). A `CANCELLED` webhook arriving later gives the same final state.

### Track

`Ok` → applied exactly like a webhook (same service, source `POLL`, same transaction and order
effects). `Unavailable` → `503 COURIER_UNAVAILABLE`, nothing changed.

### Tracking sweep (lost webhooks)

`ShipmentTrackingSweeper`, `@Scheduled` every hour: `ShipmentRoutingRepository` finds active
shipments (`CREATED`, `PICKED_UP`, `IN_TRANSIT`) whose `lastEventAt` (or `createdAt`) is older
than 12 hours, all shops, max 200 per run (index #6). Each one runs in
`SystemActorScope.runAs(shopId, Actor.system(), ...)` and calls the same track logic (source
`POLL`). `Unavailable` → skipped, tried again next hour. Every applied event sets `lastEventAt`,
so a shipment is polled at most every 12 hours while the courier sends nothing.

### Partial delivery

On `DELIVERED`, `collectedAmount` is stored and `codMismatch = (collectedAmount ≠ codAmount)`.
The order is still delivered (the parcel reached the customer); the mismatch is shown on the
shipment card and left to the COD reconciliation module. No raw webhook log is kept: every
webhook writes one log line with `traceId`, provider, consignment id, result and cause.

### Webhook failures

A database error rolls back the shipment update **and** the order change together (one
transaction) → `500` → the courier retries → idempotency makes the retry safe.

---

## 7. Changes in existing code (when built)

1. **`OrderService`**: extract the body of `transition(...)` into a method that runs inside the
   caller's transaction (`MANDATORY`), used by the existing endpoints (inside their own
   `TransactionRunner.run`) and by the new
   `applyCourierEvent(String orderId, CourierOrderEffect effect)` (`SHIP` with shipping info,
   `DELIVER`, or `SHIP_THEN_DELIVER`). The user-name lookup for history entries accepts the system
   actor (name from the principal, no `users` lookup).
2. **`order.service.ShipmentGuard`** (interface, defined in `order`, implemented by `courier`, like
   `ProductUsageChecker`): `boolean hasActiveShipment(String orderId)`. Checked by manual `ship`
   and `cancel` (also in bulk) → `409 SHIPMENT_ACTIVE`. `order` never imports `courier`.
3. **`common.security.SystemActorScope`** and the system principal (`Role.SYSTEM` or a separate
   principal type, no permissions, reserved ObjectId). Check that `InventoryService` takes the
   movement's `createdBy` / `createdByName` from it.
4. **`SecurityConfig`**: public `/api/v1/webhooks/courier/**`. The webhook controller reads the raw
   body (`byte[]`), max 64 KB.
5. **ArchUnit**: exclusion for `ShipmentRoutingRepository` (the only cross-shop reader in
   `courier`), with the reason in the test.
6. **Config**: `sellerdesk.courier.mock.enabled`, `.mock.mode`, timeouts; env
   `COURIER_WEBHOOK_SECRET` (startup check).
7. **Frontend** (separate task): "Send to courier" on `PACKED` orders and in bulk, shipment card on
   the order page (status, tracking, events, cancel, track, COD mismatch badge), a "Returned by
   courier" tab and a "Booking failed" view (`GET /shipments?status=`), bulk send split into
   batches of 20.
8. **Docs**: `SCOPE.md` (courier integration in scope), `ARCHITECTURE.md` (`courier → order`,
   `courier → shop`; `shipments` owned by `courier`; `SystemActorScope` next to `TenantContext`),
   `SCHEMA.md` (`shipments`, index exceptions), `API.md` (new section), `SECURITY.md` (public
   webhook, signature, system actor), `CLAUDE.md` (add `courier` to the feature list and the
   `TenantIsolationTest` module list).

Dependencies: `courier → order`, `courier → auth` (`UserService`, for the name of who sent a
parcel), `courier → common`. Nothing points back (`order` only knows the `ShipmentGuard` interface).

---

## 8. Backend structure

```
com.sellerdesk.courier
├── controller  ShipmentController (order shipment, shipments, bulk),
│               CourierWebhookController (raw body, public)
├── service     ShipmentService (send, cancel, track, bulk, reads),
│               CourierWebhookService (route, runAs, apply), ShipmentStatusMachine,
│               ShipmentBookingReconciler, ShipmentTrackingSweeper,
│               OrderShipmentGuard (implements ShipmentGuard),
│               ShipmentMapper, CourierErrors
├── provider    CourierProvider, CourierProviderRegistry, BookingResult, TrackResult,
│               CancelResult, WebhookEvent, CreateShipmentCommand,
│               mock/ MockCourierProvider, MockCourierSimulator, WebhookSigner
├── repository  ShipmentRepository (Repository<Shipment, String>, shopId methods + insert),
│               ShipmentQueryRepository (MongoTemplate: conditional status updates),
│               ShipmentRoutingRepository (cross-shop routing only, returns ShipmentRoute)
├── dto         SendShipmentRequest, CancelShipmentRequest, BulkShipmentRequest,
│               ShipmentResponse, ShipmentSummary, BulkShipmentResponse, WebhookAck
└── model       Shipment, ShipmentEvent, ShipmentStatus, CourierCode, EventSource, Recipient
```

---

## 9. Tests

**Unit** (`ShipmentStatusMachineTest`, `WebhookSignerTest`, `MockCourierProviderTest`,
`ShipmentServiceTest`, `CourierWebhookServiceTest`, `ShipmentBookingReconcilerTest`):
- status machine, parameterized over all 8 × 8 pairs: allowed, same status, illegal; forward skips
  count as pickup;
- signature: valid; body changed by one byte; wrong secret; timestamp 6 min old and 6 min in the
  future; missing headers; bad format (`sha1=`, not hex); comparison is constant-time
  (`MessageDigest.isEqual` used);
- mock: id formats; same `merchantRef` → same consignment; `TIMEOUT` / `REJECT` / `FLAKY` modes;
- courier status × order status → expected `CourierOrderEffect` (the table in §5);
- send: not `PACKED` → 409; active same provider → returns it; other provider → 409; the three
  outcomes give the right status, code and HTTP result; repositories are called with the
  `TenantContext` shop;
- cancel: allowed only from `BOOKING` / `CREATED`; provider `Unavailable` → 503 and no change;
- reconciler: `findByMerchantRef` before a new create; backoff schedule; gives up after 5 →
  `FAILED` + `needsManualCheck`; cancelled meanwhile → result dropped;
- tracking sweep: picks only active shipments silent for > 12 h; `Unavailable` → skipped, no change;
- `codMismatch` set when `collectedAmount ≠ codAmount`, not set when equal;
- `SystemActorScope` clears `TenantContext` and `SecurityContext` also when the action throws.

**Integration** (`ShipmentIntegrationTest extends MongoIntegrationTestBase`):
- send a `PACKED` order → `201`, `CREATED`; order still `PACKED`; stock and movements unchanged;
- send `PENDING` / `CONFIRMED` / `SHIPPED` order → `409 ORDER_NOT_PACKED`;
- send twice → same shipment; **5 parallel sends** (`CountDownLatch`) → exactly one shipment,
  one provider booking;
- webhook `PICKED_UP` → order `SHIPPED`, one `DEDUCT` per item, history entry by "Courier (Mock)",
  `shipping.trackingId` = tracking code;
- `DELIVERED` → order `DELIVERED`, `deliveredOrders +1`, `totalSpent += total`,
  `collectedAmount` stored; `DELIVERED` straight after `CREATED` → ship and deliver in one go;
- `RETURNED` → shipment `RETURNED`, order still `SHIPPED`, stock unchanged; then the manual return
  works as in Phase 1;
- cancel before pickup → `CANCELLED`, order `PACKED`, send again works (new shipment); cancel after
  pickup → `409`;
- manual order `cancel` / `ship` (single and bulk) with an active shipment → `409 SHIPMENT_ACTIVE`;
  after cancelling the shipment → allowed;
- mock `TIMEOUT` → `202 BOOKING`; reconciler run → `CREATED`; mock `REJECT` → `409`, `FAILED`,
  resend works;
- order delivered by hand, then `DELIVERED` webhook → `200`, no second counter update;
- forced failure of the order update (a `collMod` validator on `orders`) during a `PICKED_UP`
  webhook → `500`; shipment still `CREATED`, no movement; the same webhook again after removing
  the validator → processed;
- bulk send: 5 orders, one not `PACKED`, one with a mock rejection → 3 ok, 2 failed with codes;
  21 ids → `400`;
- tracking sweep: a `CREATED` shipment with no event for 13 h whose mock parcel is `PICKED_UP` →
  order `SHIPPED` after one sweep run; a shipment with a recent event is not polled;
- partial delivery: `DELIVERED` with a smaller `collectedAmount` → order `DELIVERED`,
  `codMismatch: true`;
- `GET /shipments?status=RETURNED` lists only returned shipments, newest first, paged.

**Webhook** (`CourierWebhookIntegrationTest`):
- **duplicate delivery**: the same signed payload twice → second `DUPLICATE`; **5 copies in
  parallel** → one event, one order transition, one set of `DEDUCT` movements, one history entry;
- **invalid signature**: changed body, wrong secret, stale timestamp, missing headers → `401
  INVALID_SIGNATURE`; shipment, order and stock unchanged (and, with a repository spy, no
  database read happened);
- **illegal status jump**: `DELIVERED → IN_TRANSIT`, `CREATED → RETURNED`, `CANCELLED →
  PICKED_UP` → `200 IGNORED`, an ignored event stored, status and order unchanged;
- out of order: `IN_TRANSIT` before `PICKED_UP` → treated as pickup; the late `PICKED_UP` is
  ignored as an illegal backward move with no effect;
- unknown consignment → `404`; webhook arriving before the consignment id is stored → found by
  `merchantRef`;
- the request needs no token; `TenantContext` and `SecurityContext` are empty after the request.

**Controller** (`ShipmentControllerTest`): `PACKER` can send, cancel, bulk; `MODERATOR` (no
`ORDER_FULFILL`) → `403` on send / cancel / bulk but can view and track; `ACCOUNTANT` can view
and track; `PLATFORM_ADMIN` → `403`; no token → `401`; validation `400`s (missing provider,
unknown provider, missing reason, bulk size, duplicate ids, malformed ids → `404`).

**`TenantIsolationTest` → `@Nested class Shipments`** (`assertHiddenFromOtherShop`):
- A with B's shipment id → `404` on `GET /shipments/{id}`, `POST .../track`, `POST .../cancel`;
  B's shipment unchanged, no provider call;
- A sends B's order (`POST /orders/{B's id}/shipment`) → `404`, nothing created;
  `GET /orders/{B's id}/shipments` → `404`;
- bulk send with B's order id → that entry `NOT_FOUND`, B unchanged;
- `GET /shipments` (all statuses and with `status=`) shows only A's shipments;
- the tracking sweep updates B's shipment with `TenantContext = B` and never touches A's data;
- `"shopId": "<B>"` in send / cancel / bulk bodies → ignored; the shipment is saved with A's shop;
- webhook for **B's** consignment whose body contains A's `shopId` and A's `orderId` → only B's
  shipment and order change, A's order is untouched, and the code runs with `TenantContext = B`;
- A and B both have order `#1` → different `merchantRef`s, both can be booked, no clash.

A module is done only when these pass (CLAUDE.md).

---

## 10. Resolved questions

Decided on 2026-11-28:

1. **When the order ships**: on the courier's `PICKED_UP`, not at booking (§1).
2. **Courier delivered / returned**: `DELIVERED` delivers the order automatically; `RETURNED` only
   marks the shipment, staff confirm the return with restock choices (§1, §5).
3. **Providers and secrets**: mock only in Phase 2, one platform webhook secret from
   `COURIER_WEBHOOK_SECRET`; per-shop credentials designed, deferred (§2).
4. **Booking in progress**: internal `BOOKING` status with a stable `merchantRef` and a reconciler
   (§3, §6).
5. **Permissions**: reuse `ORDER_FULFILL` (send, cancel, bulk) and `ORDER_VIEW` (get, list,
   track); no `COURIER_MANAGE` (§1).
6. **Default courier per shop**: not in Phase 2; `provider` is required. `settings.defaultCourier`
   comes with the first real provider (§4).
7. **Lost webhooks**: hourly tracking sweep of active shipments with no event for 12 hours (§6).
8. **Partial delivery**: store `collectedAmount`, set `codMismatch`, deliver the order anyway;
   handling belongs to the COD reconciliation module (§6).
9. **Raw webhook log**: none; one structured log line per webhook with `traceId` (§6).
10. **Shipment list**: `GET /shipments?status=` for a "Returned by courier" tab and a "Booking
    failed" view (§4).
11. **Bulk send**: synchronous, max 20 orders per request; the frontend splits bigger batches (§4).
12. **Manual status override**: no; use `track`, or close the order by hand (§1).
13. **Courier fee**: stored in `courierFee` whenever the courier reports it (§3).

No open questions remain.
