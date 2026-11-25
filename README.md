# Rush Promise

Voice ordering for mithai shops. A caller corrects an order in Hindi, hears the price and pickup time, and says yes. Rush Promise reserves the physically set-aside stock and pickup place together. If the last box is gone, it offers a later pickup from a weighed tray. Staff can pack the order and follow its notices to the customer.

## Run it locally

Use Java 21, Maven, Docker, OpenSSL, `curl` and `jq`. The quickstart runs the app on loopback with a disposable PostgreSQL 16 database. The booking API and staff view work without sponsor credentials. The microphone flow needs a server-side `SARVAM_API_KEY`.

```sh
export SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:55439/rush
export SPRING_DATASOURCE_USERNAME=rush
export SPRING_DATASOURCE_PASSWORD="$(openssl rand -hex 24)"
export POSTGRES_PASSWORD="$SPRING_DATASOURCE_PASSWORD"
export RUSH_STAFF_TOKEN="$(openssl rand -hex 24)"
export RUSH_OWNER_TOKEN="$(openssl rand -hex 24)"
export RUSH_OPERATOR_TOKEN="$(openssl rand -hex 24)"
export RUSH_BOOKING_API_TOKEN="$(openssl rand -hex 24)"
export SPRING_PROFILES_ACTIVE=demo
export SERVER_ADDRESS=127.0.0.1

docker run --rm -d --name rush-promise-db -p 127.0.0.1:55439:5432 \
  -e POSTGRES_USER=rush -e POSTGRES_DB=rush -e POSTGRES_PASSWORD postgres:16
until docker exec rush-promise-db pg_isready -q -h 127.0.0.1 -U rush -d rush; do sleep 1; done
mvn -B package
java -jar target/rush-promise-0.1.0-SNAPSHOT.jar > /tmp/rush-promise-web.log 2>&1 &
RUSH_WEB_PID=$!
```

Reuse an existing PostgreSQL 16 instance by setting the three `SPRING_DATASOURCE_*` variables to an empty database owned by an application role and skipping `docker run`. Flyway applies the schema on startup. The `demo` profile seeds a fictional shop only on an empty database. `DEMO_DATE` defaults to the current date in India. The fixture starts at 17:45 IST; set `RUSH_DEMO_TICKING=true` to advance it from there. The normal profile uses the real clock and disables the demo reset and clock actions.

- [Caller](http://127.0.0.1:8080/caller) uses the microphone and displays the transcript, read-back, and booking result. On a separate handset, use the approved public **HTTPS** gateway.
- [Staff](http://127.0.0.1:8080/staff) uses the live inventory, pickup capacity, orders, and delivery states. Enter the appropriate role token from your private terminal. Staff access stays on a private web connection.
- [Health](http://127.0.0.1:8080/actuator/health) confirms the local process and database are available.

### Try the last box without a microphone

Run these in the same local terminal after health returns `UP`.

```sh
RUSH_BASE=http://127.0.0.1:8080
RUSH_QUOTE=$(curl --fail-with-body -sS "$RUSH_BASE/api/quotes" \
  -H "X-Booking-Token: $RUSH_BOOKING_API_TOKEN" -H 'Content-Type: application/json' \
  -d '{"sessionId":"quickstart-lata","customerName":"Lata","phone":"+12025550101","sku":"kaju-katli","grams":500,"pickupTime":"18:30"}')
printf '%s\n' "$RUSH_QUOTE" | jq '.quote | {itemName,grams,pricePaise,pickupTime,batchId}'
RUSH_QUOTE_ID=$(printf '%s\n' "$RUSH_QUOTE" | jq -er '.quote.id')
curl --fail-with-body -sS "$RUSH_BASE/api/quotes/$RUSH_QUOTE_ID/accept" \
  -H "X-Booking-Token: $RUSH_BOOKING_API_TOKEN" -H 'Content-Type: application/json' \
  -d '{"sessionId":"quickstart-lata","turnId":"lata-yes"}' | jq '{code,order:.order.id}'
```

The first acceptance returns `201` and order `4821`. Repeating the acceptance returns the same order with `200`. The quote alone leaves the stock and slot free. On `/staff`, mark the order packed and check it has one `CONFIRMED` and one `READY` job. In local web-only mode they remain queued. To exercise delivery without external messages, run the same jar against the same database in a second process using profile `worker`, `NOTIFIER=console` and another loopback port. Its delivery state is `SIMULATED`.

```sh
SPRING_PROFILES_ACTIVE=worker NOTIFIER=console SERVER_PORT=8081 \
  java -jar target/rush-promise-0.1.0-SNAPSHOT.jar
```

Stop the worker with Ctrl-C. To stop local web and PostgreSQL, run `kill "$RUSH_WEB_PID"` and `docker stop rush-promise-db`. The database container above uses no persistent volume.

## Roles and provider settings

Set distinct random values for role tokens. Send `X-Staff-Token` on private staff API requests. The browser staff view takes the token at sign-in and resolves the role through `GET /api/staff/session`.

| Role or credential | Scope |
|---|---|
| `RUSH_STAFF_TOKEN` | Read staff data. Pack, mark a shortage, return packed stock, or mark a weighed batch ready. |
| `RUSH_OWNER_TOKEN` | Staff capabilities plus approve, reject, cancel and unblock with a weighed count. |
| `RUSH_OPERATOR_TOKEN` | Read staff data and operate `/ops/**`. `POST /ops/demo/reset` and `POST /api/demo/clock` require the demo profile. |
| `RUSH_BOOKING_API_TOKEN` | Direct quote, acceptance, and outcome resolution only, via `X-Booking-Token`. The browser voice adapter invokes the booking service. |

Load live credentials from an environment or secret manager. For the hosted demo, use the server environment key from the Flagsmith **Development** environment. Keep the account tokens, staff tokens, customer numbers and private cluster endpoints out of the repo.

| Integration | Runtime settings and entry point |
|---|---|
| **Sarvam AI** | `SARVAM_API_KEY`. `/caller` sends multipart audio to `POST /api/voice/browser/turn`. Saaras v4 transcribes the input and Bulbul v4 Flash speaks the reply. `RUSH_DEMO_HANDSET` restricts the hosted browser to the enrolled stage number when set. |
| **Flagsmith** | `FLAGSMITH_ENVIRONMENT_KEY`; optional `FLAGSMITH_API_URL` for a self-hosted Flagsmith `/api/v1/flags/` endpoint. Create server-only `rush_mode` OFF in the Development environment. The demo refreshes every five seconds and reports version and last successful fetch. An unreachable provider uses the last successful state or the conservative rush profile on cold start. |
| **Twilio WhatsApp Sandbox** | Keep `NOTIFIER=console` for local simulations. The isolated live worker uses `NOTIFIER=twilio` with `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_MSG_FROM`, `RUSH_PUBLIC_BASE_URL`, and the enrolled `RUSH_DEMO_HANDSET`. The signed status endpoint is `POST /webhooks/twilio/status`. Follow the one-order release procedure in [ops/README.md](ops/README.md). |
| **NudgeBee** | The cluster agent relays the Prometheus/Alertmanager queue signal. See [ops/README.md](ops/README.md) for the isolated worker outage, approval reference and recovery commands. |
| **Sarvam phone tool** | `RUSH_SARVAM_TOOL_TOKEN` for the bearer-protected `POST /api/voice/sarvam/turn`. The tool can consume a provider transcript and share the booking engine. An active verified inbound phone line and attached provider agent are a separate setup. The browser is the current speech path. |

### Run the isolated kind deployment

The [kind handoff](ops/README.md) uses a prepared cluster with private PostgreSQL, the provider secrets, Prometheus, Alertmanager and the NudgeBee agent. Run `bash ops/deploy-kind.sh` on that Codespace. It packages Java, builds the Docker image and loads the same image into separate web and worker deployments. The web pod owns booking, staff actions and callbacks. The worker leases rows from the same PostgreSQL database.

Use separate connections for the caller gateway and the staff web service:

```sh
kubectl -n dev-data port-forward --address 127.0.0.1 svc/rush-promise-gateway 18081:8080
# In a separate terminal for staff, keep this forward Private.
kubectl -n dev-data port-forward --address 127.0.0.1 svc/rush-promise-web 18082:8080
```

The stage caller uses `/caller` on the approved HTTPS origin for port 18081. That gateway serves only the caller page and assets, the voice POST paths and the signed Twilio status endpoint. Staff uses `/staff` on the private port 18082. Order APIs, reset, metrics and PostgreSQL stay private. See the [one-order release and operator recovery procedure](ops/README.md#one-live-order) before enabling delivery to the enrolled handset.

## The three demo moments

1. **The last box.** At 17:45 IST the phone tray A has 500 g of kaju katli and the 18:30 slot has one pickup place. Say, “Ek kilo kaju katli… actually aadha kilo, saade chhe baje.” The quote is 500 g, ₹600, 6:30 PM. A yes consumes the stock and place. A second caller sees 7:00 PM from B, a separate weighed 1,000 g still setting until 6:45 PM. Capture confirmation and ready receipt on the enrolled phone in a live run.
2. **Rush terms change.** With `rush_mode` off, ask for 4 kg barfi at 8 PM, ₹3,600. Turn the Development flag on while the quote awaits acceptance. The first yes causes a fresh read-back requiring approval. A new yes creates the pending request. Confirm that order `4821` keeps the original status and policy. Restore the flag to off after the demo.
3. **Packed, message waiting.** Queue the ready notice while the isolated worker is down. Follow the alert from the private web metrics to NudgeBee. After approval, restore the worker and verify the same outbox record and actual handset receipt. Keep the event evidence tied to one order and attempt. A console run is a simulation.

`POST /ops/demo/reset` with the operator token recreates the fixture and order sequence. A body of `{"preset":"incident","phone":"<enrolled E.164>"}` seeds `4821` and marks its fixture confirmation delivered. That fixture is useful for rehearsal. For an actual receipt use the live-order procedure and record the customer’s observed handset result.

## Why two callers cannot book the last box

PostgreSQL locks the selected tray and time slot in the same transaction. It checks grams, the 15-minute preparation buffer, capacity and the current order terms before inserting an order. The transaction writes the reservation, event, terminal booking decision and outbox job together. A rejected quote can include a persisted alternative. The database keeps each accepted quote unique, even if two clients choose different idempotency keys. Retrying a lost response reads the same decision. Prices are stored in paise and stock in grams.

The original order retains its policy snapshot. Fresh quotes use the result of the latest coherent Flagsmith decision. Bulk approval holds are limited to one pending request per phone. Approval, expiry, and packing transitions serialize with stock updates. An uncertain delivery response waits for evidence before any resend.

A PostgreSQL voice journal stores the current prompt and binds the customer's yes to its quote before requesting a booking. Turn leases fence late results. If the web process stops after assent, its replacement reuses the same booking key. While the booking is being resolved, the browser polls the same route for its receipt without retranscribing. Audio and raw transcripts stay in the request. A lost audio request before durable assent needs the same turn's audio to be sent again. The staff screen uses polling.

## Verification and disclosure

Run `mvn test` for available unit tests. The PostgreSQL booking suite uses a disposable database named `rush_test_*`. For the quickstart container, create it and run the suite against it:

```sh
docker exec rush-promise-db createdb -U rush rush_test_booking
RUSH_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:55439/rush_test_booking \
RUSH_TEST_JDBC_USER=rush RUSH_TEST_JDBC_PASSWORD="$SPRING_DATASOURCE_PASSWORD" \
  mvn -B -Dtest=BookingPostgresTest test
```

### Event build evidence · 25 November 2026

- **Hosted Flagsmith and booking** (`331fcea`, PostgreSQL 16.13). The application read the hosted Development `rush_mode` OFF at 06:41:46 UTC (12:11:46 IST) and ON at 06:42:28 UTC (12:12:28 IST). A waiting fixture quote for 4 kg barfi changed to `409 TERMS_CHANGED`. Repeating the old acceptance returned the same replacement. A fresh yes created pending order `4822`; previously packed `4821` kept its normal-policy snapshot. The app read the restored OFF state at 06:43:14 UTC (12:13:14 IST). Both flag changes used Flagsmith Cloud. Quote and order inputs were fixtures.
- **PostgreSQL booking load** (`2da3d87`, `mvn -B -Dtest=BookingPostgresTest test`, 06:51 UTC / 12:21 IST). C04 ran 50 independent two-buyer races. L01 used 200 buyers against 10,000 g. Exactly 20 committed and 180 received conflicts, with no stock or slot violations. Mean batch-lock query time was 18.02 ms including database round-trip. Booking request latency was 464.91 ms p50 and 710.49 ms p95. The group of requests finished in 775.29 ms. Replaying each request twice preserved the same 20 orders and queued messages. These timings cover booking requests; the 20-notification delivery drain was unmeasured in this run.
- **Interleaved booking retries and clocks** (`533534a`, 06:55:31 UTC / 12:25:31 IST). The extended booking suite passed 22 tests against PostgreSQL 16.13. Six hundred interleaved requests for 200 distinct quotes yielded 20 original commits, 40 successful replays and 540 conflicts. The database held 20 orders and 20 outbox jobs. Fault injection checked failure before a transaction and loss of acknowledgement after a real commit. Approval expiration advanced with both ticking demo time and real database time.
- **Sarvam browser adapter** (`8a07e07`, 06:50:36 UTC / 12:20:36 IST). A generated Hindi fixture uploaded as WebM/Opus went through actual Saaras and Bulbul endpoints. The app returned the 500 g, ₹600, 18:30 quote in 2,888 ms. A generated yes booked `4821` in 1,890 ms and a second request returned 19:00 in 2,162 ms. The stored stock and slot matched. A Chromium MediaRecorder run with synthetic input independently repeated the correction and booking at 12:25 IST. The timings are individual synthetic-audio observations. Ten-turn live p50/p90 and a real inbound call remain distinct acceptance checks.
- **Durable voice recovery** (`37794bb`, 07:13:50 UTC / 12:43:50 IST). A clean packaged JVM stopped after a synthetic accepted checkpoint was written to PostgreSQL with zero orders. A replacement JVM recovered it on its own and saved order `4821`, one successful key and one queued confirmation. The combined native suite passed 33 tests at 07:11:31 UTC (12:41:31 IST), including lease reclamation, late prompt and hangup rejection, recovery after commit, changed terms and uncertain receipt lookup. No notification was dispatched in the restart check.
- **Clean checkout** (`533534a`, 06:58:36 UTC / 12:28:36 IST). Built the jar with `mvn -B package` using the warmed Maven cache and connected it to a new local PostgreSQL 16.13 database. On fixture date 9 February 2034, caller and staff loaded, an API quote booked `4821`, a repeated acceptance replayed `4821`, pack succeeded once, and a separate console worker recorded two `SIMULATED` notices. Reusing that database with the normal profile gave the operator 404 for reset and clock writes and used the current date.
- **Public gateway and private staff** (06:53:17 UTC / 12:23:17 IST). The anonymous gateway returned 200 for `/caller` and its core assets, and 404 for staff, orders, reset and Prometheus. The separate staff forward required GitHub authentication. The app returned 401 for an unauthenticated staff API request and for a Sarvam tool request without its bearer token. The Twilio status route returned 403 for an invalid signature and 204 without mutation for a signed test callback with an unknown attempt. This exercised a status callback; a live inbound phone call is a separate gate.
- **NudgeBee application alert rehearsal** (`14f1909`). At 07:03:30 UTC (12:33:30 IST) the isolated worker was stopped by the blocked revision and the packed fixture's READY notice stayed queued. At 07:03:53 UTC (12:33:53 IST) the actual backlog alert was firing in Prometheus and active in Alertmanager. NudgeBee Cloud showed the same alert firing. The operator manually restored the earlier revision. The exact queued READY job moved to `SIMULATED`, and the alert resolved at 07:08:27 UTC (12:38:27 IST). NudgeBee Cloud showed it closed. The transport was the console worker.
- **Delivery regression** (`2963065`, `ops/test-delivery.sh`, 06:48:06 UTC / 12:18:06 IST). Six tests passed using PostgreSQL and a local Twilio test endpoint. The live worker orders, callbacks and handset proof belong to the one-order procedure. Follow [ops/README.md](ops/README.md) for the exact steps and scope.

The [booking regression](src/test/java/com/rushpromise/booking/BookingPostgresTest.java) also checks the original fixture IDs at the precise boundaries: F01 preserves both counter grams and its count timestamp. F10 keeps distinct saved policy versions and pickup grace values. F11 restores a six-minute-old fetch without refreshing its age. F20 covers the milliseconds on either side of the approval deadline and approval after expiry before a sweep. F21 tries both sequential and concurrent requests from one phone and checks the loser holds zero stock or slots. F22 accepts the exact 30-minute rush boundary. F24 rejects the 14-minute lead time before TTL expires without a reservation or notice.

The original acceptance suite contains additional voice, UI, live-handset and operations cases. Live handset evidence requires a human microphone turn, the corresponding committed order and the actual notice on the device. Worker recovery evidence requires an alert, approval and the restored message. This prototype defers counter-sale recording, no-show handling and additional merchant analytics.

OpenAI Codex helped design the implementation, write code and tests, and prepare demo materials. Sarvam provides the speech AI in the product. The event team chose the inventory and approval rules, controls the live run and reviews the evidence before presenting it.
