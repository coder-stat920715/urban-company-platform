# Urban Company Platform (Home Services Marketplace)

Production-style backend: **Java 21 · Spring Boot 4.1.2 · PostgreSQL 16 + PostGIS · Redis (cache, GEO, Redisson locks) · Apache Kafka · STOMP/SockJS WebSocket**.
Modular monolith (auth, catalog, location, cart, booking, dispatch, partner, payment, review, admin, notification) with a transactional-outbox event backbone.

Full HLD/LLD with Mermaid diagrams (system topology, dispatch sequence, booking state machine, ERD): **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.

## Run it

```bash
docker compose up -d postgres redis zookeeper kafka prometheus   # infrastructure
mvn spring-boot:run                                               # app on :8080 (Flyway migrates + seeds)
# or everything in containers:  docker compose --profile full up --build
```

* Swagger UI: http://localhost:8080/swagger-ui.html · Health: `/actuator/health` · Metrics: `/actuator/prometheus` · Prometheus UI: http://localhost:9090
* WebSocket: `ws://localhost:8080/ws` (SockJS), STOMP `CONNECT` header `Authorization: Bearer <token>`
* Tests: `mvn test` (unit tests always run; the Testcontainers integration test needs Docker and is skipped without it)

Seeded accounts (password `Demo@12345`): `customer@demo.local` (wallet ₹1000), `partner1@demo.local`, `partner2@demo.local`, `partner3@demo.local` (approved, Bengaluru/Koramangala).
Admin: `admin@urbancompany.local` / `Admin@12345` (override with `ADMIN_PASSWORD`). Coupons: `WELCOME100`, `SAVE15`. Dev OTP mode is on (`app.otp.expose=true` returns the OTP in the response).

## Sample flow (cURL)

```bash
H='Content-Type: application/json'; API=http://localhost:8080/api/v1
tok(){ curl -s -XPOST $API/auth/login -H "$H" -d "{\"email\":\"$1\",\"password\":\"Demo@12345\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["accessToken"])'; }
C=$(tok customer@demo.local); P=$(tok partner1@demo.local)

# partner goes online (GPS heartbeat goes into the Redis GEO index)
curl -XPUT $API/partner/availability -H "$H" -H "Authorization: Bearer $P" -d '{"online":true,"lat":12.9352,"lng":77.6245}'

# customer: address -> cart -> instant checkout -> pay
curl -XPOST $API/addresses -H "$H" -H "Authorization: Bearer $C" -d '{"label":"Home","line1":"12 MG Road","lat":12.936,"lng":77.625,"makeDefault":true}'
curl -XPOST $API/cart/items -H "$H" -H "Authorization: Bearer $C" -d '{"packageId":8,"quantity":1,"optionKeys":["LEAKING_TAP"]}'
curl -XPOST $API/bookings/checkout -H "$H" -H "Authorization: Bearer $C" -d '{"addressId":1,"instant":true}'          # -> booking id, start/end OTPs
curl -XPOST $API/payments/intents -H "$H" -H "Authorization: Bearer $C" -d '{"bookingId":1,"method":"UPI","useWallet":false}'
curl -XPOST "$API/payments/simulate/<intentId>?outcome=SUCCESS" -H "Authorization: Bearer $C"                          # signed webhook

# partner: see the 30s offer, accept, run the job with OTPs
curl $API/partner/offers -H "Authorization: Bearer $P"
curl -XPOST $API/partner/offers/<offerId>/accept -H "Authorization: Bearer $P"
for s in en-route arrive; do curl -XPOST $API/partner/jobs/1/$s -H "Authorization: Bearer $P"; done
curl -XPOST $API/partner/jobs/1/verify-start-otp -H "$H" -H "Authorization: Bearer $P" -d '{"otp":"<startOtp>"}'
curl -XPOST $API/partner/jobs/1/start -H "Authorization: Bearer $P"
curl -XPOST $API/partner/jobs/1/extra-work -H "$H" -H "Authorization: Bearer $P" -d '{"description":"Replace tap fitting","amount":250,"extraMinutes":15}'
curl -XPOST $API/bookings/1/extra-work/1 -H "$H" -H "Authorization: Bearer $C" -d '{"approve":true}'
curl -XPOST $API/partner/jobs/1/complete -H "$H" -H "Authorization: Bearer $P" -d '{"otp":"<endOtp>"}'
curl $API/partner/earnings/summary -H "Authorization: Bearer $P"          # ledger written asynchronously via outbox -> Kafka
```

Scheduled booking: `GET /bookings/slots?addressId=1&date=YYYY-MM-DD` then checkout with `"scheduledStart":"<slot start>"`.

## Postman
Import `postman/UrbanCompany.postman_collection.json` + `postman/UrbanCompany.local.postman_environment.json` (191 requests with assertions, auto-chained variables). Step-by-step guide: **[docs/POSTMAN_GUIDE.md](docs/POSTMAN_GUIDE.md)**.

## Notes
* The payment gateway is a **simulator** (intent + HMAC-signed webhook); SMS/Email are log stubs. Replace `PaymentService`'s gateway calls / `NotificationService` with real providers.
* Boot 4 specifics: Jackson 3 serves MVC; Jackson 2 is pinned only for the internal `JsonUtil` and jjwt. Redisson is used as a plain client (no Boot starter). If a dependency coordinate differs in your Maven mirror, adjust `pom.xml`.
* `spring.jpa.hibernate.ddl-auto=validate` — Flyway owns the schema.
