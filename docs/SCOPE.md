# SellerDesk — Scope

## Phase 1 (in scope now)

### Auth
- Login with email and password.
- Email verification after signup and password reset with an email OTP (one-time code).
- JWT-based authenticated sessions.

### Shop registration
- A new seller registers a shop and becomes its `SHOP_OWNER`.
- Each shop is a tenant. All its data is isolated by `shopId`.

### Staff and permissions
- Shop owner invites and manages staff.
- Staff get a role that controls what they can do.

### Product with variants
- Products with variants (e.g. size, color), each variant with its own SKU and price.
- Product images uploaded through the backend to Cloudinary (max 8 per product).
- Archive products; delete only products never used.

### Inventory
- Stock per variant tracked as:
  - `onHand` — physical stock in hand
  - `reserved` — stock held by confirmed, not-yet-shipped orders
  - `available` = `onHand` − `reserved`
- Stock movement log: every stock change is recorded (who, when, why, quantity).

### Customer
- Customer records per shop (name, phone, address, order history).

### Order pipeline

```
PENDING -> CONFIRMED -> PACKED -> SHIPPED -> DELIVERED
                                         \-> RETURNED
```

- `CANCELLED` is a separate terminal state for orders stopped before shipping.
- Status changes update inventory (reserve, release, deduct) and are logged.

## Roles

| Role             | Purpose                                              |
|------------------|------------------------------------------------------|
| `PLATFORM_ADMIN` | Operates the SellerDesk platform itself              |
| `SHOP_OWNER`     | Owns a shop, full access to that shop                |
| `MODERATOR`      | Handles customers and orders                         |
| `PACKER`         | Packs and ships orders                               |
| `ACCOUNTANT`     | Views financial and payment data                     |

## Deferred (Phase 1 items postponed on purpose)

Part of Phase 1 in the design docs, but not built yet. Build before going to production.

- **Platform admin seeding**: create the single `PLATFORM_ADMIN` user from
  `PLATFORM_ADMIN_EMAIL` at startup (`SECURITY.md` §3). Until then no admin account exists.
- **Per-IP rate limiting** for login and OTP requests (`SECURITY.md` §1). Needs the real
  client IP behind the Render proxy (`X-Forwarded-For`, `server.forward-headers-strategy`).
  Today only per-email limits exist (login: 5 failures / 15 min; codes: 1 / 60 s, 5 / hour).

## Phase 2 and 3 (explicitly OUT of scope for now)

Do not build any of these until they are moved into scope:

- Courier integration
- Returns module
- COD reconciliation
- Analytics
- Invoice PDF
- Expenses
- Customer risk score
- Subscription plans
- Platform admin panel
- WebSocket (real-time updates)
