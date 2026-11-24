# Urban Company Platform — Architecture (HLD + LLD)

Java 21 · Spring Boot 4.1.2 · PostgreSQL 16 + PostGIS · Redis 7 (cache, GEO, locks) · Apache Kafka · STOMP/SockJS WebSocket

---

## 1. High-Level Design

### 1.1 Architecture style
A **Clean, Domain-Driven Modular Monolith**. Each business capability is a top-level package that owns its entities, repositories, services and controllers:

`auth · catalog · location · cart · booking · dispatch · partner · payment · review · admin · notification` (+ `common`, `config`).

Rules that keep every module extractable into a microservice with near-zero friction:

1. Modules talk through **service classes and domain events only** (no cross-module repository writes, no shared mutable entities).
2. Cross-module data is referenced by **id** (`customer_id`, `partner_id`, `category_id` …), never by JPA associations.
3. Side effects that other modules react to are **events**: Spring in-process events (synchronous, same transaction) for refund/dispatch hooks, and **Kafka events via the Transactional Outbox** for everything asynchronous (notifications, earnings, cashback, audit).
4. The **API Gateway pattern** is realised by a single Spring Security filter chain (JWT, RBAC by URL prefix `/api/v1/admin/**`, `/api/v1/partner/**`) in front of all controllers; when modules are split, this chain moves to a gateway (Spring Cloud Gateway / Envoy) unchanged.

### 1.2 System context & infrastructure topology

```mermaid
flowchart LR
  subgraph Clients
    C[Customer App]
    P[Partner App]
    A[Admin Console]
  end
  subgraph Edge
    GW[API Gateway<br/>JWT + RBAC filter chain]
  end
  subgraph App["Spring Boot 4 Modular Monolith"]
    AUTH[auth]
    CAT[catalog]
    LOC[location]
    CART[cart + pricing]
    BOOK[booking + slots]
    DISP[dispatch]
    PART[partner + earnings]
    PAY[payment + wallet]
    REV[review + warranty]
    ADM[admin]
    NOTI[notification]
    WS[(STOMP / SockJS<br/>/ws)]
    OUT[Outbox relay]
  end
  PG[(PostgreSQL 16<br/>+ PostGIS)]
  R[(Redis 7<br/>Cart - GEO - Locks - OTP)]
  K[[Apache Kafka]]
  PR[Prometheus]
  GWY[Payment Gateway<br/>simulator + webhook]

  C & P & A --> GW --> App
  C & P <-. realtime .-> WS
  App --> PG
  App --> R
  OUT -->|publish| K
  K -->|consume| NOTI
  K -->|consume| PART
  K -->|consume| PAY
  K -->|consume| ADM
  GWY -->|HMAC webhook| PAY
  PR -->|scrape /actuator/prometheus| App
```

### 1.3 Real-time partner matching & auto-dispatch

```mermaid
sequenceDiagram
  autonumber
  participant Cu as Customer
  participant API as Booking/Payment API
  participant DB as PostgreSQL
  participant Dsp as DispatchService
  participant Geo as Redis GEO
  participant Lock as Redisson Lock
  participant WS as WebSocket
  participant P1 as Partner A
  participant P2 as Partner B

  Cu->>API: checkout (slot lock + capacity check)
  API->>DB: booking PAYMENT_PENDING
  Cu->>API: payment intent, gateway webhook (HMAC)
  API->>DB: booking CONFIRMED (+outbox event)
  API-->>Dsp: BookingConfirmedEvent (after commit)
  Dsp->>DB: CONFIRMED -> SEARCHING_PARTNER
  loop radius 2km -> 5km -> 10km until a candidate is found
    Dsp->>Geo: GEOSEARCH around booking location
    Dsp->>DB: filter online, approved, skill, capacity, working hours, leave, conflicts
  end
  Dsp->>Dsp: rank (distance, rating, acceptance, workload)
  Dsp->>DB: INSERT job_offer(OFFERED, expires = now+30s)
  Dsp->>WS: /user/queue/offers -> Partner A
  loop every second
    Dsp->>WS: /user/queue/offer-countdown (secondsLeft)
  end
  alt Partner A accepts in time
    P1->>Dsp: POST /offers/{id}/accept
    Dsp->>Lock: lock booking:assign:{bookingId}
    Dsp->>DB: re-validate offer + booking, assign partner, PARTNER_ASSIGNED
    Dsp->>Lock: unlock (after commit)
    Note over P2,Lock: a concurrent accept waits on the lock,<br/>then sees status != SEARCHING -> 409
  else reject or 30s timeout
    Dsp->>DB: offer REJECTED / EXPIRED
    Dsp->>Dsp: escalate to next best partner (partner B)
    Dsp->>WS: /user/queue/offers -> Partner B
  else nobody in 10 min window
    Dsp->>DB: CANCELLED + automatic full refund
  end
```

Double-assignment is prevented in three layers: (1) **Redisson lock** `lock:booking:assign:{id}` serialising accept/expire/reassign; (2) **state check** inside the transaction (`status == SEARCHING_PARTNER && partner_id IS NULL`); (3) **optimistic locking** `@Version` on `bookings` as the final DB-level backstop.

### 1.4 Booking lifecycle (State pattern)

```mermaid
stateDiagram-v2
  [*] --> DRAFT
  DRAFT --> PAYMENT_PENDING: checkout
  DRAFT --> CANCELLED
  PAYMENT_PENDING --> CONFIRMED: payment webhook
  PAYMENT_PENDING --> CANCELLED: hold expired / user
  CONFIRMED --> SEARCHING_PARTNER: dispatch window opens
  CONFIRMED --> CANCELLED
  SEARCHING_PARTNER --> PARTNER_ASSIGNED: partner accepts
  SEARCHING_PARTNER --> CANCELLED: no partner / user
  PARTNER_ASSIGNED --> PARTNER_EN_ROUTE
  PARTNER_ASSIGNED --> SEARCHING_PARTNER: partner drops / admin reassign
  PARTNER_ASSIGNED --> CANCELLED
  PARTNER_EN_ROUTE --> ARRIVED_OTP_VERIFIED: arrive + start OTP
  PARTNER_EN_ROUTE --> SEARCHING_PARTNER
  PARTNER_EN_ROUTE --> CANCELLED
  ARRIVED_OTP_VERIFIED --> IN_PROGRESS: start work
  ARRIVED_OTP_VERIFIED --> CANCELLED
  IN_PROGRESS --> EXTRA_WORK_PENDING_APPROVAL: partner quotes add-on
  EXTRA_WORK_PENDING_APPROVAL --> IN_PROGRESS: customer approve / reject
  IN_PROGRESS --> COMPLETED: end OTP
  IN_PROGRESS --> DISPUTED
  EXTRA_WORK_PENDING_APPROVAL --> DISPUTED
  COMPLETED --> SETTLED: earnings ledger written (Kafka)
  COMPLETED --> DISPUTED
  SETTLED --> DISPUTED
  DISPUTED --> SETTLED: admin resolves
  DISPUTED --> CANCELLED
  SETTLED --> [*]
  CANCELLED --> [*]
```

### 1.5 Entity-relationship diagram

```mermaid
erDiagram
  USERS ||--o| PARTNERS : "is"
  USERS ||--o| WALLETS : owns
  USERS ||--o{ ADDRESSES : saves
  USERS ||--o{ BOOKINGS : places
  USERS ||--o{ MEMBERSHIPS : subscribes
  CITIES ||--o{ ZONES : contains
  ZONES ||--o{ ADDRESSES : covers
  ZONES ||--o{ PARTNERS : serves
  ZONES ||--o{ BOOKINGS : "dispatched in"
  CATEGORIES ||--o{ CATEGORIES : "sub-category"
  CATEGORIES ||--o{ SERVICES : groups
  SERVICES ||--o{ SERVICE_PACKAGES : offers
  SERVICES ||--o{ RATE_CARDS : "spare-part rates"
  SERVICE_PACKAGES ||--o{ ADDON_QUESTIONS : asks
  ADDON_QUESTIONS ||--o{ ADDON_OPTIONS : has
  PARTNERS ||--o{ PARTNER_SKILLS : has
  CATEGORIES ||--o{ PARTNER_SKILLS : "skill in"
  PARTNERS ||--o{ PARTNER_DOCUMENTS : uploads
  PARTNERS ||--o{ PARTNER_WORKING_HOURS : defines
  PARTNERS ||--o{ PARTNER_LEAVES : takes
  PARTNERS ||--o{ JOB_OFFERS : receives
  PARTNERS ||--o{ PARTNER_EARNINGS : earns
  PARTNERS ||--o{ WITHDRAWAL_REQUESTS : requests
  BOOKINGS ||--|{ BOOKING_ITEMS : contains
  BOOKINGS ||--o{ BOOKING_STATUS_HISTORY : audits
  BOOKINGS ||--o{ EXTRA_WORK_REQUESTS : "mid-job add-ons"
  BOOKINGS ||--o{ JOB_OFFERS : "offered via"
  BOOKINGS ||--o{ PAYMENTS : "paid by"
  BOOKINGS ||--o{ REFUNDS : refunded
  BOOKINGS ||--o| PARTNER_EARNINGS : settles
  BOOKINGS ||--o| REVIEWS : rated
  BOOKINGS ||--o{ WARRANTY_CLAIMS : claims
  BOOKINGS ||--o{ CHAT_MESSAGES : chat
  WALLETS ||--o{ WALLET_TRANSACTIONS : ledger
  COUPONS ||--o{ COUPON_REDEMPTIONS : redeemed
  MEMBERSHIP_PLANS ||--o{ MEMBERSHIPS : "plan of"
  USERS ||--o{ NOTIFICATIONS : receives
```

### 1.6 Non-functional & scalability design

| Concern | Design |
|---|---|
| **Geospatial indexing** | Online partners are kept in Redis (`GEOADD geo:partners:online`). Dispatch uses `GEOSEARCH` with radius expansion **2 → 5 → 10 km**. A per-partner heartbeat key (`partner:hb:{id}`, 30 s TTL, refreshed by the 5-second GPS ping) evicts stale/offline positions at read time. PostGIS stores generated `geography(Point)` columns + GiST indexes on zones/addresses for ad-hoc geo analytics. |
| **Geofencing** | A location is serviceable if inside an active zone's polygon (ray-casting) or radius (haversine). Used on address creation and checkout. |
| **Slot reservation** | `lock:slot:{zone}:{category}:{date}` (Redisson) serialises capacity re-check + booking insert, so two customers can never take the last partner slot. Unpaid bookings hold capacity for 15 min. |
| **Job acceptance** | `lock:booking:assign:{id}` + status guard + `@Version` (see §1.3). |
| **Money** | Wallet rows use `@Version` optimistic locking; every movement is a ledger row. A concurrent writer gets HTTP 409 `CONCURRENT_UPDATE`. Coupon redemption uses a conditional `UPDATE … WHERE used_count < usage_limit`. |
| **Transactional Outbox** | Domain events are inserted into `outbox_events` in the **same DB transaction** as the state change. `OutboxRelay` polls with `FOR UPDATE SKIP LOCKED` (safe with N instances), publishes to Kafka (`acks=all`, idempotent producer) and marks rows `SENT`. Delivery is at-least-once; consumers are idempotent (`partner_earnings.booking_id UNIQUE`, wallet reference check, `payment_events.event_id UNIQUE`). |
| **Kafka topics** | `uc.booking-events`, `uc.dispatch-events`, `uc.payment-events`, `uc.earnings-events`, `uc.partner-events` — keyed by aggregate id (per-booking ordering). Consumers: Notification, Earnings ledger (+settlement), Cashback, Audit. |
| **Observability** | Actuator (`/actuator/health` with liveness/readiness, `/actuator/prometheus`), Micrometer metrics, Micrometer Tracing bridge to OpenTelemetry (trace/span ids in every log line; add `opentelemetry-exporter-otlp` + `management.otlp.tracing.endpoint` to export), **structured JSON logging** (`logging.structured.format.console=ecs`). |
| **Stateless scale-out** | No HTTP sessions; JWT access (15 min) + refresh (7 d, rotated, stored in Redis). Schedulers are multi-instance safe (SKIP LOCKED, Redisson locks). |
| **Security** | BCrypt passwords, HS256 JWT, RBAC (`ROLE_CUSTOMER/PARTNER/ADMIN`), OTP rate-limit + lockout, HMAC-SHA256 webhook signatures (constant-time compare), STOMP CONNECT authentication and per-booking SUBSCRIBE authorisation. |

---

## 2. Low-Level Design

### 2.1 Database
Flyway: `V1__init_schema.sql` (≈40 tables, FKs, composite/partial-purpose indexes, `JSONB` package attributes with GIN index, PostGIS generated geography columns with GiST indexes, audit timestamps) and `V2__seed_data.sql` (Bengaluru city + 3 zones, 3-level catalog, packages, add-on questions/options, spare-part rate cards, commission tiers, surge rules, coupons, UC Plus plans). `DemoDataSeeder` creates the admin and demo customer/partners at startup.

### 2.2 Design patterns (explicit)

| Pattern | Where |
|---|---|
| **State** | `BookingStatus` (legal-next map) + `BookingStateMachine` (actor guards, history rows, outbox event on every transition). |
| **Strategy – pricing** | `PricingStrategy` implementations ordered by `@Order`: `SurgePricingStrategy(10)` → `InstantSurchargeStrategy(15)` → `MembershipDiscountStrategy(20)` → `CouponPricingStrategy(30)` → `ConvenienceFeeStrategy(40)` → `TaxPricingStrategy(50)`, executed by `PricingEngine`. Adding a rule = adding a bean. |
| **Strategy – dispatch ranking** | `PartnerScoringStrategy` / `WeightedPartnerScoring`: `0.4·(1−dist/radius) + 0.3·rating/5 + 0.2·acceptance + 0.1·(1−load)` (weights in `application.yml`). |
| **Factory / Builder** | `PackageAssembler` assembles `PackageDetail` (Lombok `@Builder`) from package + dynamic add-on questions/options. |
| **Transactional Outbox** | `DomainEventPublisher` (MANDATORY propagation) + `OutboxRelay`. |
| **Observer / Event-driven** | Spring events (`BookingConfirmedEvent`, `BookingCancelledEvent`, `BookingRefundEvent`) and Kafka consumers. |
| **Repository, Service Layer, DTO records** | Throughout. |

### 2.3 Pricing formula
```
subtotal      = Σ (base + Σ option deltas) × qty            (recomputed from catalog on every read)
surge         = subtotal × (max matching rule multiplier − 1) + instant surcharge
membership    = subtotal × plan% ; convenience fee waived for UC Plus
coupon        = min(discount, subtotal + surge − membership)     (PERCENT/FLAT, caps, per-user & global limits)
taxable       = subtotal + surge − membership − coupon + convenience fee
total         = taxable + GST(18 %)
partner gross = subtotal + surge + approved extra work
commission    = gross × tier %   (Standard 25 → Platinum 18, by jobs completed) ; TDS = gross × 1 %
```

### 2.4 Cancellation policy
Free before a partner is assigned or > 2 h before start; 10 % of total inside 2 h; 25 % (min ₹100) once the partner is en route/on site. Admin can override the fee (`cancellationFee`, 0 = waive). Refunds go to the wallet for the wallet-funded share and are recorded as a processed gateway refund for the rest.

### 2.5 REST API summary (full interactive spec: Swagger UI `/swagger-ui.html`, OpenAPI 3.1 JSON `/v3/api-docs`)

| Area | Endpoints |
|---|---|
| **Auth** | `POST /api/v1/auth/otp/request`, `/otp/verify`, `/register`, `/login`, `/refresh`, `/logout`, `GET /me` |
| **Catalog (public)** | `GET /api/v1/catalog/categories`, `/categories/{id}/services`, `/services/{id}`, `/packages/{id}` |
| **Location** | `GET /api/v1/locations/cities`, `/serviceability?lat&lng`; `GET/POST/PUT/DELETE /api/v1/addresses` |
| **Cart** | `GET/DELETE /api/v1/cart`, `POST /cart/items`, `PUT/DELETE /cart/items/{lineId}`, `POST /cart/quote` |
| **Booking** | `GET /bookings/slots`, `POST /bookings/checkout`, `GET /bookings`, `GET /bookings/{id}`, `POST /bookings/{id}/cancel`, `POST /bookings/{id}/extra-work/{reqId}`, `POST /bookings/{id}/dispute`, `GET/POST /bookings/{id}/chat` |
| **Payment** | `POST /payments/intents`, `POST /payments/webhook` (HMAC), `POST /payments/simulate/{intentId}`, `GET /wallet`, `/wallet/transactions`, `GET /membership/plans`, `GET /membership`, `POST /membership/subscribe` |
| **Review / warranty** | `POST /reviews`, `GET /reviews/partner/{id}`, `POST/GET /warranty/claims` |
| **Notifications** | `GET /notifications`, `POST /notifications/read-all` |
| **Partner** | `POST/PUT/GET /partner/profile`, `POST /partner/documents`, `POST /partner/kyc/submit`, `PUT /partner/availability`, `POST /partner/location`, `PUT/GET /partner/working-hours`, `POST/GET/DELETE /partner/leaves`, `GET /partner/offers`, `POST /partner/offers/{id}/accept\|reject`, `GET /partner/jobs`, `POST /partner/jobs/{id}/en-route\|arrive\|verify-start-otp\|start\|extra-work\|complete\|release`, `GET /partner/earnings/summary\|history\|tds-summary`, `POST/GET /partner/withdrawals` |
| **Admin** | `/admin/catalog/*` (CRUD categories, services, packages, questions, options, rate-cards), `/admin/locations/*` (cities, zones – radius or polygon), `/admin/partners/*` (list, scorecard, KYC review, approve/reject/suspend/reinstate, withdrawals), `/admin/pricing/*` (surge rules, coupons, commission tiers, UC Plus plans), `/admin/bookings/*` (list, reassign, cancel with fee override, refund, resolve-dispute), `/admin/warranty-claims/*`, `/admin/wallet-credit`, `/admin/reports/*` (summary, bookings-daily, top-partners, audit) |

All responses use the envelope `{ "success", "code", "message", "data", "timestamp" }`. Errors: `400` validation, `401/403` auth, `404`, `409` state/concurrency conflict, `422` business-rule violation (stable machine-readable `code`, e.g. `SLOT_UNAVAILABLE`, `MULTI_CATEGORY_CART`, `OTP_INVALID`).

### 2.6 WebSocket contract (STOMP over SockJS, endpoint `/ws`)

Connect with header `Authorization: Bearer <accessToken>` in the STOMP `CONNECT` frame.

| Direction | Destination | Payload |
|---|---|---|
| Server → partner | `/user/queue/offers` | `{offerId, bookingId, distanceKm, scheduledStart, durationMinutes, estimatedGross, expiresAt, ttlSeconds}` |
| Server → partner | `/user/queue/offer-countdown` | `{offerId, bookingId, secondsLeft}` (1 Hz) |
| Server → any user | `/user/queue/notifications` | `{id, title, body}` |
| Server → booking parties | `/topic/bookings/{id}/location` | `{partnerId, lat, lng, heading, speedKmph, ts}` (live GPS) |
| Server → booking parties | `/topic/bookings/{id}/status` | `{bookingId, status, from, at}` |
| Server → booking parties | `/topic/bookings/{id}/extra-work` | add-on quote / decision |
| Both ways | `/topic/bookings/{id}/chat` (send to `/app/chat/{id}`) | `{id, senderId, content, sentAt}` |
| Partner → server | `/app/partner/heartbeat` | `{lat, lng, heading?, speedKmph?}` every ~5 s (REST fallback `POST /api/v1/partner/location`) |

`SUBSCRIBE` to `/topic/bookings/{id}/**` is authorised: only the booking's customer, its assigned partner, or an admin.

### 2.7 Extraction path to microservices
`dispatch`, `payment`, `notification` and `catalog` have the clearest seams: they already integrate through ids + Kafka. To extract: move the package into its own Boot app, give it its own schema (tables are already module-prefixed logically), replace the in-process Spring events (`BookingConfirmedEvent`, `BookingCancelledEvent`) with outbox events on Kafka, and point the gateway route at the new service.
