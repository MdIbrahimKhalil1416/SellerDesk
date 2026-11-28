# Customer module — Design (Phase 1)

The shop's customer list: name, phone (one customer per phone per shop), addresses, notes,
tags, and order counters that the Order module keeps up to date. Customers are archived,
never deleted (orders reference them).

Status: **built** (backend and frontend). Backend package `com.sellerdesk.customer`, frontend `features/customers` (`/customers`, `/customers/new`, `/customers/:id`).

Related: `SCHEMA.md` §8 (`customers`), `API.md` §6, `SECURITY.md` §3 (`CUSTOMER_VIEW`,
`CUSTOMER_EDIT`, `ORDER_CREATE`), `ARCHITECTURE.md` §1 (`order → customer`; counters only
through `CustomerService`), `common.phone.BdPhone` (shared phone normalizer).

---

## 1. Decisions (and why)

| Decision | Why |
|----------|-----|
| **Phone is normalized with `common.phone.BdPhone`** (`01XXXXXXXXX`; `+880`, `880`, `00880`, separators, Bangla digits accepted) and **unique per shop**. | One person = one record. `+8801712345678` and `01712 345678` are the same customer; only one stored spelling lets the unique index see the duplicate. |
| **Archive, never delete.** `status` `ACTIVE` / `ARCHIVED`. Archived customers are hidden from the default list but keep their phone. | Orders reference `customerId` forever. |
| **An archived phone is reused, not duplicated.** `POST /customers` with an archived customer's phone → `409 PHONE_ALREADY_EXISTS` (the UI looks it up and offers "Restore"). Order's find-or-create returns the archived customer and makes it `ACTIVE` again. | History stays in one record; the moderator taking an order is never blocked. |
| **Addresses are embedded**, max 5, each with a stable `addressId`, optional `label` ("Home", "Office") and an `isDefault` flag. Exactly one address is default when there is at least one; the first one added becomes default. | Bounded, always shown with the customer. The order form pre-fills the default. A stable id makes update/remove safe even when two people edit. |
| **Every address has a `zone`** (`INSIDE_DHAKA` / `OUTSIDE_DHAKA`). The frontend **suggests** it from the district (Dhaka → inside) and the user confirms; the server just requires it. | The zone decides the default delivery charge. Dhaka district also has areas couriers treat as "outside" (e.g. Savar), so it cannot be derived blindly. |
| **Find-or-create by phone never overwrites the saved name.** It only adds the order's delivery address when it is new and there is room. | A typo on one order must not rename a customer. The order keeps its own snapshot of name and address anyway. |
| **Search**: text → name prefix; digits → phone **prefix** or **last 4+ digits**, on the main phone **and** `altPhone`. | Customers often say "the number ending 5678". Last-digits search uses a stored reversed copy so it can use an index. |
| **find-by-phone** (order form) matches **only the main phone**, exactly. | One number always means at most one customer: the form can auto-fill without asking. `altPhone` is not unique. |
| **Tags are free text**: up to 10, 1–30 chars each, trimmed, duplicates ignored regardless of case. `GET /customers/tags` lists tags in use for autocomplete. | Like product categories: no admin screen, nothing to sync. |
| **Counters are denormalized** in `stats` and changed **only** through `CustomerService` methods called by Order, inside Order's transaction. | The customer list shows "12 orders, 2 returned" without aggregating orders. |
| **No full-document saves after create.** Profile edits are a targeted `$set` with `{_id, shopId, version}`; address changes are atomic array updates; counters are `$inc`. | Order updates `stats` with `$inc` at any time; a full `save` would write back old counters (lost update), exactly as with product stock. |
| **`version` guards profile edits and status changes only.** Address operations and counter updates neither check nor bump it. | An open "edit name" form should not fail because an order came in or an address was added. Address changes are independent array-element updates. |

---

## 2. Data: `customers` (tenant-owned)

| Field | Type | Req | Notes |
|-------|------|-----|-------|
| `_id` | ObjectId | Y | |
| `shopId` | ObjectId | Y | from the JWT |
| `name` | String | Y | 1–100 chars, trimmed, single spaces |
| `nameLower` | String | Y | lowercase copy for prefix search and sort |
| `phone` | String | Y | `01XXXXXXXXX` (BdPhone); unique per shop |
| `altPhone` | String | N | `01XXXXXXXXX`; not unique; must differ from `phone` |
| `phoneKeys` | Array\<String\> | Y | `[phone, altPhone?]`, for prefix search |
| `phoneKeysReversed` | Array\<String\> | Y | the same numbers reversed (`87654321710`), for last-digits search |
| `facebookProfileUrl` | String | N | `http(s)://…`, ≤ 300 |
| `addresses` | Array\<CustomerAddress\> | Y | 0–5 |
| `addressRevision` | Int32 | Y | +1 on every address change. Address updates replace the array with a compare-and-set on this number (retried up to 5 times), so two parallel address changes never lose one. Internal, not in responses. |
| `note` | String | N | ≤ 1000 |
| `tags` | Array\<String\> | Y | 0–10, as typed (trimmed) |
| `tagKeys` | Array\<String\> | Y | lowercase `tags`, for filter and distinct |
| `status` | String | Y | `ACTIVE`, `ARCHIVED` |
| `stats.totalOrders` | Int32 | Y | orders created |
| `stats.deliveredOrders` | Int32 | Y | |
| `stats.returnedOrders` | Int32 | Y | |
| `stats.cancelledOrders` | Int32 | Y | |
| `stats.totalSpent` | Int64 | Y | poisha, sum of delivered order totals |
| `stats.lastOrderAt` | Date | N | newest order creation time |
| `version`, `createdAt`, `updatedAt`, `createdBy` | | Y | |

**`CustomerAddress`**: `addressId` (ObjectId, app-generated, stable), `label` (≤ 30, optional),
`line` (full address, 1–200), `area` (thana/upazila, ≤ 100, optional), `district` (1–50),
`zone` (`INSIDE_DHAKA` / `OUTSIDE_DHAKA`), `note` (landmark, ≤ 200, optional), `isDefault`.
Same fields as the shared `Address` (§5 of `SCHEMA.md`) plus `addressId`, `label`, `isDefault`.

**Indexes** (all start with `shopId`, `SCHEMA.md` §10):

1. `{shopId: 1, phone: 1}` **unique**: one customer per phone per shop; exact find-by-phone.
2. `{shopId: 1, status: 1, nameLower: 1}`: name prefix search, sort by name.
3. `{shopId: 1, status: 1, createdAt: -1}`: default list "newest first".
4. `{shopId: 1, phoneKeys: 1}`: phone prefix search (multikey).
5. `{shopId: 1, phoneKeysReversed: 1}`: last-digits search (multikey).
6. `{shopId: 1, tagKeys: 1}`: tag filter and tag list (multikey).

Sort by `lastOrderAt` sorts in memory within one shop's matches (a few thousand customers).

---

## 3. Endpoints

Base `/api/v1`. No `shopId` in any path, query or body; one sent in a body is ignored.

| Method | Path | Permission | Request → Response |
|--------|------|------------|--------------------|
| GET | `/customers` | `CUSTOMER_VIEW` | `?q=&tag=&status=ACTIVE\|ARCHIVED\|ALL&sort=createdAt,desc\|name,asc\|lastOrderAt,desc&page=&size=` → page of `CustomerSummary` |
| GET | `/customers/tags` | `CUSTOMER_VIEW` | → `[{name, customerCount}]`, active customers, sorted by name |
| GET | `/customers/lookup` | `CUSTOMER_VIEW` **or** `ORDER_CREATE` | `?phone=` (any accepted format) → `CustomerResponse` (also archived; see `status`) or `404 NOT_FOUND`. Invalid phone → `400`. For the order form. |
| POST | `/customers` | `CUSTOMER_EDIT` | `CreateCustomerRequest` → `201 CustomerResponse`. `409 PHONE_ALREADY_EXISTS` (also for an archived customer's phone). |
| GET | `/customers/{id}` | `CUSTOMER_VIEW` | → `CustomerResponse` |
| PUT | `/customers/{id}` | `CUSTOMER_EDIT` | `UpdateCustomerRequest` → `CustomerResponse`. Profile only (addresses have own endpoints). Full replace of these fields: an optional field left out is cleared. `409 PHONE_ALREADY_EXISTS`, `409 CONCURRENT_MODIFICATION`. |
| PATCH | `/customers/{id}/status` | `CUSTOMER_EDIT` | `{status: ACTIVE\|ARCHIVED, version}` → `CustomerResponse` (archive / restore) |
| POST | `/customers/{id}/addresses` | `CUSTOMER_EDIT` | `AddressRequest` → `201 CustomerResponse`. `409 ADDRESS_LIMIT_REACHED` (5). |
| PUT | `/customers/{id}/addresses/{addressId}` | `CUSTOMER_EDIT` | `AddressRequest` → `CustomerResponse` (replaces that address) |
| DELETE | `/customers/{id}/addresses/{addressId}` | `CUSTOMER_EDIT` | → `CustomerResponse`. If it was the default, the oldest remaining address becomes default. |

`GET /customers/{id}/orders` belongs to the Order module (`CUSTOMER_VIEW` + `ORDER_VIEW`).

### DTOs (`customer.dto`, records, `@Valid`)

```
CreateCustomerRequest {name, phone, altPhone?, facebookProfileUrl?, note?, tags?, addresses? (0–5, AddressRequest)}
UpdateCustomerRequest {name, phone, altPhone?, facebookProfileUrl?, note?, tags?, version}
CustomerStatusRequest {status, version}
AddressRequest        {label?, line, area?, district, zone, note?, isDefault?}

CustomerResponse {id, name, phone, altPhone, facebookProfileUrl, addresses[{addressId, label, line, area,
                  district, zone, note, isDefault}], note, tags, status, stats{totalOrders, deliveredOrders,
                  returnedOrders, cancelledOrders, totalSpent, lastOrderAt}, version, createdAt, updatedAt}
CustomerSummary  {id, name, phone, altPhone, defaultAddress{district, area, zone}|null, tags, status,
                  stats, createdAt}
TagCount         {name, customerCount}
```

`isDefault: true` on add/update moves the default mark to that address; `false` or missing
leaves the current default as it is (the default can only be moved, never removed by hand).
On create, the first address is the default unless another one says `isDefault: true`.

### Search (`q`)

- Normalized for phones: separators removed, Bangla digits → 0–9, a leading `+880`/`880`/`00880`
  turned into `0`. If what is left is **only digits**: phone search, else name search.
- **Digits, 3+**: `phoneKeys` starts with `q` **or** (4+ digits) `phoneKeysReversed` starts with
  reversed `q` ("ends with"). One `$or`, both use their index.
- **Text**: `nameLower` starts with lowercase `q` (regex-escaped).
- `tag`: matches `tagKeys` (case-insensitive). `status`: `ACTIVE` (default), `ARCHIVED`, `ALL`.
- `page` 0-based, `size` default 20, max 100. Unknown `sort` / `status` → `400`.

---

## 4. Rules

- **Multi-tenancy**: `shopId` only from the JWT (`CurrentUser.shopId()`); every query starts with
  `TenantCriteria.of(shopId)` or a repository method taking `shopId`. Another shop's customer or
  address id → `404 NOT_FOUND` (never 403). The same phone may exist in two shops.
- **Phone**: `@BdMobile` on the DTO (400 with the shared message), then `BdPhone.normalize`.
  `altPhone` the same; `altPhone == phone` → `400` on `altPhone`.
- **Order of checks on `PUT`**: unknown or other shop's id → `404` **before** the phone check, so
  an id that is not yours never reveals whether a phone exists.
- **Duplicate phone** in the same shop (create or phone change) → `409 PHONE_ALREADY_EXISTS`
  with `fieldErrors[phone]`. A friendly pre-check query first; the unique index is the guarantee
  (`DuplicateKeyException` → same 409).
- **Name**: trimmed, inner whitespace collapsed, 1–100. **Note** ≤ 1000. **Tags** as above.
- **Addresses**: max 5 (`409 ADDRESS_LIMIT_REACHED`, checked atomically with
  `"addresses.4": {$exists: false}` in the `$push` filter); `line`, `district`, `zone` required.
- **Archive** (`PATCH .../status`): no delete endpoint. Archived customers are editable, found by
  `lookup` and by search with `status=ARCHIVED|ALL`, hidden from the default list and from tags.
- **Counters** are never accepted from the client (no `stats` in request DTOs; unknown fields ignored).

### Errors

| Situation | Status | `code` |
|-----------|--------|--------|
| Bean Validation (lengths, phone format, URL, tags, address fields) | 400 | `VALIDATION_FAILED` (`fieldErrors`) |
| `altPhone` equals `phone`; unknown `sort`/`status`; invalid `lookup` phone | 400 | `VALIDATION_FAILED` |
| Phone already used by another customer of this shop | 409 | `PHONE_ALREADY_EXISTS` |
| 5 addresses already | 409 | `ADDRESS_LIMIT_REACHED` |
| `version` does not match | 409 | `CONCURRENT_MODIFICATION` |
| Customer / address unknown **or of another shop**; `lookup` without match | 404 | `NOT_FOUND` |
| Missing permission | 403 | `FORBIDDEN` |

---

## 5. Java API for the Order module

`CustomerService` methods (Order never touches the `customers` collection):

```java
// Order create with an inline customer (ORDER_CREATE is enough: the permission check is the order endpoint's).
// Find by normalized phone in the current shop; create if missing.
OrderCustomer findOrCreateByPhone(NewOrderCustomer customer)
record NewOrderCustomer(String name, String phone, AddressDto address /* nullable */)
record OrderCustomer(String customerId, String name, String phone, CustomerAddress defaultAddress)

// Order create with a customerId: 404 if unknown or of another shop.
OrderCustomer getForOrder(String customerId)

// Counters, @Transactional(propagation = MANDATORY): inside Order's transaction.
void recordOrderCreated(String customerId, Instant createdAt)   // totalOrders +1, lastOrderAt = max
void recordDelivered(String customerId, long orderTotal)        // deliveredOrders +1, totalSpent += total
void recordReturned(String customerId)                          // returnedOrders +1
void recordCancelled(String customerId)                         // cancelledOrders +1
```

**`findOrCreateByPhone` step by step** (shop from `CurrentUser`):

1. Normalize the phone with `BdPhone`; invalid → `400` on `customer.phone`.
2. Find `{shopId, phone}`.
   - **Found**: if `ARCHIVED` → set `ACTIVE`. If an address was given and none of the saved ones
     has the same `line`/`area`/`district`/`zone` (compared trimmed, case-insensitive) and there
     are fewer than 5 → `$push` it (default if it is the only one). **Name is never changed.**
   - **Not found**: insert a new customer with the name, phone and address (default).
3. Race: two orders for the same new phone at the same moment → the second insert hits the unique
   index (`DuplicateKeyException`, or a `WriteConflict` inside a transaction that
   `TransactionRunner` retries) → read again and continue as "found". No duplicate customer.

It works inside or outside a transaction (`REQUIRED`), so Order can call it before or inside its
own transaction.

---

## 6. Backend structure

```
com.sellerdesk.customer
├── controller  CustomerController
├── service     CustomerService, CustomerRules (normalization, search parsing), CustomerMapper,
│               CustomerErrors, NewOrderCustomer, OrderCustomer
├── repository  CustomerRepository (Repository<Customer, String>, shopId methods + insert),
│               CustomerQueryRepository (MongoTemplate: search, targeted updates, address array ops,
│               counters, tags)
├── dto         requests / responses above
└── model       Customer, CustomerAddress, CustomerStats, CustomerStatus
```

---

## 7. Tests

**Unit** (`CustomerServiceTest`, `CustomerRulesTest`):
- phone normalization variants on create, update, lookup and find-or-create: `01712345678`,
  `+8801712345678`, `8801712345678`, `008801712345678`, `01712-345 678`, `(017) 1234.5678`,
  Bangla digits → all `01712345678`; `01212345678`, too short / long → 400;
- `altPhone == phone` → 400; name/tag normalization (trim, single spaces, case-insensitive dedupe);
- search parsing: digits → phone (prefix + reversed suffix), text → name prefix, `+880…` → `0…`;
- duplicate phone → `PHONE_ALREADY_EXISTS` (pre-check and `DuplicateKeyException`);
- find-or-create: new → created with default address; existing → name unchanged, new address
  added, same address not added twice, 5 addresses → not added; archived → reactivated;
  duplicate-key race → re-read;
- default address rules (first is default; `isDefault` moves it; removing default promotes oldest);
- repositories always called with the `TenantContext` shop.

**Integration** (`CustomerIntegrationTest extends MongoIntegrationTestBase`): create / get /
update / archive / restore; same phone twice → 409 (also in different spelling, also archived);
parallel creates with one phone → exactly one 201; same phone in two shops → both 201; search by
name prefix, phone prefix, last 4 digits, `altPhone`, tag, status, sorts, paging; tags list;
address add / update / remove / default / limit; stale version → 409; counters via the Java API
inside a transaction; find-or-create race (parallel calls, one customer).

**Controller** (`CustomerControllerTest`): `403` for `PACKER` on everything; `ACCOUNTANT` reads
but cannot write; a user with only `ORDER_CREATE` can call `lookup` but not list/get; validation
`400`s (phone, URL, tags, address fields, version); `404` for unknown ids; `409`s; no token → `401`.

**`TenantIsolationTest` → `@Nested class Customers`**: B's customer id on `GET`/`PUT`/`PATCH status`/
address `POST`/`PUT`/`DELETE` → `404`, B unchanged; A's customer with B's `addressId` → `404`; list,
search (B's name/phone), `lookup` with B's phone and tags show nothing of B; `shopId` in body
ignored; A may create a customer with B's phone.

---

## 8. Resolved questions

Decided on 2026-11-28:

1. Archived customer's phone: reuse + reactivate in find-or-create; 409 on manual create.
2. Zone: required; the frontend suggests it from the district, the user confirms.
3. Find-or-create on an existing customer: add a new address only; never overwrite the name.
4. Phone search: prefix + last 4+ digits, on phone and `altPhone`.
5. Keep `stats.cancelledOrders` and `facebookProfileUrl`.
6. `altPhone`: list search only; `lookup` matches the main phone only.
7. Tags: free text, normalized, with a tag list for autocomplete.
8. Default address: `isDefault` flag with stable `addressId`s.
