# Postman Testing Guide

Files in `postman/`:

| File | Purpose |
|---|---|
| `UrbanCompany.postman_collection.json` | 14 folders / 191 requests, each with `pm.test` assertions and auto-saved variables |
| `UrbanCompany.local.postman_environment.json` | `baseUrl` (http://localhost:8080) and `webhookSecret` |

## 1. Prerequisites
```bash
docker compose up -d postgres redis zookeeper kafka     # infra
mvn spring-boot:run                                      # Flyway migrates + DemoDataSeeder runs
curl localhost:8080/actuator/health                      # {"status":"UP"}
```
Import both files in Postman → select environment **Urban Company - Local**.

Seeded logins: `admin@urbancompany.local / Admin@12345`, `customer@demo.local`, `partner1..3@demo.local` (password `Demo@12345`).

## 2. Running
* **Collection Runner:** run the whole collection (or folder by folder) in order 00 → 13, delay 300 ms, *Persist variables* ON. Polling steps retry themselves (`postman.setNextRequest`).
* **Manual:** send requests top-to-bottom; for *Poll* requests re-send until the tests pass.
* Variables flow automatically: `customerToken, partnerToken, partner2Token, adminToken, addressId, bookingId, startOtp, endOtp, intentId, offerId, extraWorkId, partner1Id, …`
* Tokens live 15 min → re-run folder 00 on 401. Partner GPS heartbeat lives 30 s → scenarios re-send it just before dispatch.

## 3. Folder-by-folder: what is tested and what you should see

| # | Folder | Steps (summary) | What it proves |
|---|---|---|---|
| **00** | Setup & Logins | health → login admin/customer/partner1/partner2 → `/auth/me` | JWT auth, RBAC roles, seeded data present |
| **01** | Auth | OTP request → wrong OTP (400) → verify (creates user+wallet) → register → duplicate email (409) → weak password (400) → refresh (rotation) → reuse old refresh (401) → logout → refresh after logout (401) | Phone OTP + email auth, refresh-token rotation/revocation, validation |
| **02** | Catalog | category tree → services → service detail → package | Hierarchy Category→Sub-category→Service→Package→dynamic questions + spare-part rate card; JSONB attributes |
| **03** | Location | cities → serviceable (inside) → not serviceable (Delhi) → create address → address outside geofence (422 `NOT_SERVICEABLE`) → list/update | Geofencing by radius/polygon; multiple addresses, default flag |
| **04** | Partner availability | profile → working hours (24×7) → ONLINE → GPS heartbeat → leave add/list/delete | Redis GEO indexing, availability model |
| **05** | Cart & pricing | missing required option (422) → invalid option (422) → single-select violation (422) → add 2×AC+add-on (unit 798, subtotal 1596, 150 min) → update qty → exceed max qty → add 2nd category → quote w/o category (422 `MULTI_CATEGORY_CART`) → SAVE15 quote (GST 18 %) → invalid coupon | Smart cart validation, auto recalculation, split rule, pricing strategies |
| **06** | **Scenario A – Insta-Service E2E** | A1–A36: cart → instant quote (surcharge) → checkout → intent → forged webhook (401) → heartbeat → signed webhook → **poll offer** → accept → accept again (409) → en-route → arrive → wrong OTP (400) → start OTP → start → **extra work** → complete while pending (409) → approve → wrong end OTP → complete → **poll SETTLED** → earnings/TDS → wallet ledger → review → chat → notifications | Whole lifecycle: payment webhook, auto-dispatch, 30 s offer, state machine guards, OTPs, mid-job add-on, outbox→Kafka→earnings→settlement, notifications |
| **07** | Scenario B – Scheduled + cancel | slots (tomorrow) → checkout slot → wallet+UPI split payment → CONFIRMED (no dispatch yet) → free cancel → full refund → wallet REFUND ledger → cancel again (409) → foreign access (403) | Slot engine, Redisson slot lock, wallet split, cancellation policy, atomic refund |
| **08** | Scenario C – Escalation & admin override | partner 2 online → instant booking → partner 1 **rejects** → offer escalates to partner 2 → admin **manual reassign** to partner 1 → stale offer 409 → admin cancel with fee waived | Reject/timeout escalation, admin reassignment, penalty override |
| **09** | UC Plus & coupons | plans → subscribe → webhook → active → quote shows 10 % discount and ₹0 convenience fee | Membership strategy |
| **10** | Warranty & disputes | claim → duplicate (409) → admin approve re-service → zero-priced rebooking → dispute → admin resolves with refund | 30-day warranty, dispute workflow |
| **11** | Partner onboarding & KYC | register partner → profile+skills+zone+bank → submit w/o docs (422) → upload ID → submit → online before approval (403) → approve before verification (422) → admin verify doc → approve → online → scorecard → suspend (removed from GEO) → reinstate → withdrawal validations | KYC workflow, admin controls, earnings withdrawal rules |
| **12** | Admin console | CRUD category→sub→service→package(JSONB)→question→option→rate card → public catalog reflects it → polygon zone works → surge rule changes quote → coupon CRUD → commission tiers → reports → audit trail | Admin CRUD, dynamic pricing, analytics, Kafka-fed audit |
| **13** | Security / RBAC | no token / garbage token (401) → customer on admin/partner API (403) → ADMIN self-register downgraded → webhook no/invalid/valid-HMAC → cross-user access (403) → validation → past slot → metrics + OpenAPI | AuthN/Z, privilege escalation, webhook signing |

## 4. Detailed walkthrough of the main flow (Scenario A)

1. **A4 Checkout** returns `PAYMENT_PENDING` plus `startOtp`/`endOtp` (only the customer sees OTPs).
2. **A5/A8b Payment:** intent is created, then `/payments/simulate/{intentId}` plays the gateway and calls the signed webhook. In one transaction: payment `PAID` → booking `CONFIRMED` → outbox rows. After commit, dispatch moves it to `SEARCHING_PARTNER`, searches Redis GEO (2→5→10 km), scores candidates and creates an offer with a 30 s TTL.
3. **A9** polls `GET /partner/offers` until the offer shows (`secondsLeft ≤ 30`).
4. **A10 Accept** → `PARTNER_ASSIGNED`. **A11** repeating it returns `409 OFFER_NOT_ACTIVE`.
5. **A14–A18** drive `EN_ROUTE → (arrive) → ARRIVED_OTP_VERIFIED → IN_PROGRESS`; a wrong OTP returns `400 OTP_INVALID` (5 wrong attempts lock).
6. **A19–A21** partner quotes ₹250 extra work → `EXTRA_WORK_PENDING_APPROVAL`; completing now returns `409`; customer approves → back to `IN_PROGRESS`, wallet debited.
7. **A23** complete with the end OTP → `COMPLETED` and a `BOOKING_COMPLETED` outbox event.
8. **A24** polls until `SETTLED`: the Kafka consumer wrote the earnings ledger (commission 25 % Standard tier, TDS 1 %) and settled the booking. **A25** shows the full status history.

## 5. Concurrency (double-assignment) test
1. Run A1–A9 (stop before A10).
2. In Postman → **Runner** → select only request *A10 Partner 1 accepts* → iterations 2 **without delay**; or send the same `curl` twice in parallel:
   ```bash
   curl -XPOST $API/partner/offers/$OFFER/accept -H "Authorization: Bearer $P" & curl -XPOST $API/partner/offers/$OFFER/accept -H "Authorization: Bearer $P" & wait
   ```
3. Expected: exactly **one 200** and **one 409** (`OFFER_NOT_ACTIVE` / `BOOKING_NOT_AVAILABLE`), booking has a single partner. (Automated equivalent: `BookingDispatchIntegrationTest`.)

Offer **timeout** test: after A8b, do nothing for 30 s → the offer becomes `EXPIRED` and is escalated to the next partner / re-offered (see `job_offers` table or `/admin/reports/audit`).

## 6. WebSocket / STOMP testing (Postman *WebSocket* request or `websocat`)

Endpoint (raw WebSocket under SockJS): `ws://localhost:8080/ws/websocket`. Send each frame followed by a NUL (`\u0000`).

```
CONNECT
accept-version:1.2
heart-beat:10000,10000
Authorization:Bearer <partnerToken>

^@
```
Partner – receive offers and countdown:
```
SUBSCRIBE
id:sub-0
destination:/user/queue/offers

^@
SUBSCRIBE
id:sub-1
destination:/user/queue/offer-countdown

^@
```
Customer – live tracking, status, chat (booking id from A4):
```
SUBSCRIBE
id:sub-2
destination:/topic/bookings/<bookingId>/location

^@
SUBSCRIBE
id:sub-3
destination:/topic/bookings/<bookingId>/status

^@
```
Partner GPS heartbeat (every ~5 s) and chat:
```
SEND
destination:/app/partner/heartbeat
content-type:application/json

{"lat":12.9358,"lng":77.6249,"heading":90,"speedKmph":20}^@
SEND
destination:/app/chat/<bookingId>
content-type:application/json

{"content":"On my way"}^@
```
Expected: while a job is `EN_ROUTE`/`IN_PROGRESS` the customer's `/location` subscription receives each heartbeat; `/status` receives every transition; subscribing to someone else's booking returns an ERROR frame (authorisation), and `CONNECT` without a token is rejected.

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `A9` never gets an offer | Partner 1 not ONLINE or heartbeat > 30 s old → re-run 04.4/04.5 then A8a/A8b immediately; make sure Partner 2 is offline (C14) |
| `422 NO_PARTNERS_AVAILABLE` at A4 | same as above (instant bookings need an online partner within 5 km) |
| `A24` stays `COMPLETED` | Kafka consumers not running → check `docker compose ps kafka` and app logs; outbox rows have `status='NEW'` until Kafka is reachable |
| `401` everywhere | tokens expired → folder 00 |
| `SLOT_UNAVAILABLE` / no slots | run 04.2 (working hours) and ensure partner 1 has no leave; slots must be ≥ 60 min ahead |
| `COUPON_ALREADY_USED` | coupons have per-user limits; use a new coupon from 12.18 |
