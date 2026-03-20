# architecture.md

---

## Forecast Package Structure

**Problem**: The `forecast` package contained scheduler, repository, fetcher, and provider
classes in a single flat namespace, mixing domain contracts with infrastructure implementations.

**Decision**: Split into sub-packages following the dependency direction rule:
- `forecast` — domain records and contracts (`GeoLocation`, `ShippingForecast`, `ForecastCache`,
  `ForecastProvider`, `ForecastFetcher`, `ForecastCacheRepository`)
- `forecast.provider` — provider implementations (`DwdForecastProvider`)
- `forecast.infra` — infrastructure implementations (`JdbcForecastCacheRepository`, `HttpForecastFetcher`)
- `forecast.scheduler` — scheduling orchestration (`ForecastScheduler`)

**Why**: Contracts at the root are stable; implementations in sub-packages can grow without
cluttering the public surface. Sub-packages depend on `forecast`, never the reverse.

---

## Forecast Provider Abstraction

## Problem
The bot must support multiple forecast data sources (e.g. BBC, Met Office). Each source
has its own URL, publication schedule, geographic coverage, and page format. Without a
common interface the bot would need to hard-code knowledge of every source throughout the
codebase, making it fragile and hard to extend.

## Decision
Introduce a `ForecastProvider` interface that captures the full contract of a data source:
metadata (name, description, URL, update times, geographic areas) and the parsing behaviour.
Fetching the page is intentionally left outside the interface so that the HTTP concern can
evolve independently.

## Structure

- `ForecastProvider` — interface; one method per provider attribute plus `parse`
- `GeoLocation` — immutable record; `(name, latitude, longitude)` in WGS-84
- `ShippingForecast` — immutable record; `(location, text)` for one parsed area

## Applied Principles / Patterns

- **SOLID — Interface Segregation / Open-Closed**: new providers are added by implementing
  the interface; existing code is not modified
- **SOLID — Dependency Inversion**: the bot dispatcher will depend on `ForecastProvider`,
  not on any concrete scraper
- **GRASP — Information Expert**: each concrete provider owns all knowledge about how to
  parse its own page format
- **GRASP — Protected Variations**: the interface shields the rest of the application from
  changes in page structure or provider-specific parsing logic
- **GoF — Strategy**: `ForecastProvider` acts as a strategy; any number of implementations
  can be swapped or combined at runtime

## Why This Approach

An interface with plain accessor methods was chosen over an abstract class because the
providers share no common implementation — only a common contract. Records are used for
`GeoLocation` and `ShippingForecast` because they are pure value objects with no behaviour.

## Tradeoffs

- `parse` receives raw page content as a `String`; providers that need streaming or binary
  content will require the signature to be revisited.
- Update times are `List<LocalTime>` expressed in the provider's `publishingZone()`.
  Defaults to UTC; providers on local-time schedules (e.g. DWD on Europe/Berlin) override
  this so DST transitions are handled correctly by the scheduler.

## Notes

- `updateTimes()` and `geoLocations()` must never return an empty list; implementations
  should enforce this with a precondition check.
- `parse` must return one `ShippingForecast` per entry in `geoLocations()`, with matching
  `GeoLocation` references, so callers can correlate them reliably.

---

## User Registration and Subscription Persistence

## Problem
The bot needs to track which Telegram users have interacted with it and which forecast areas
each user has subscribed to. Subscriptions must be linked to a known user — a dangling
subscription referencing a non-existent user would be a data integrity error. Both must
survive restarts.

## Decision
Introduce two tables: `telegram_user` (user registry) and `user_subscription` (area
subscriptions), with a foreign key from `user_subscription` to `telegram_user`. The bot
operates exclusively in private chats, so `chat_id` is both the user identifier and the
dispatch destination — no separate user-ID column is needed.

The repository pattern is used for both tables to isolate persistence from the rest of the
application.

## Structure

- `TelegramUser` — immutable record `(chatId)`; represents a user known to the bot
- `UserRepository` — interface: `register(user)` (idempotent), `findById(chatId)`
- `JdbcUserRepository` — JDBC implementation backed by `telegram_user`
- `UserSubscription` — immutable record `(chatId, area)`
- `SubscriptionRepository` — interface: subscribe, unsubscribe, lookup by chat, lookup by
  area, delete all for a chat
- `JdbcSubscriptionRepository` — JDBC implementation backed by `user_subscription`
- `schema.sql` — DDL for both tables; `IF NOT EXISTS` makes it safe to run on every boot
- `AppConfig` — configures `DataSource` (HSQLDB file mode at `./data/sfb-db`) and `JdbcTemplate`

## Applied Principles / Patterns

- **SOLID — Dependency Inversion**: callers depend on `UserRepository` and
  `SubscriptionRepository` interfaces, not JDBC implementations
- **SOLID — Single Responsibility**: user registration and subscription management are
  separate repositories with separate tables and separate concerns
- **GRASP — Information Expert**: each repository owns all knowledge of how its entity is stored
- **GRASP — Protected Variations**: interfaces shield the rest of the app from the storage choice;
  swapping to PostgreSQL requires only new implementations

## Why This Approach

Separating `telegram_user` from `user_subscription` enforces referential integrity at the
database level (`ON DELETE CASCADE`). A user can exist without subscriptions, and a
subscription cannot exist without a user. Collapsing both into one table would allow orphan
rows and make user-level operations (e.g. blocking the bot) ambiguous.

`chat_id` is used as the single identifier because the bot only handles private chats, where
`chat_id == Telegram user ID`. Using a separate `telegram_id` column would be redundant.

Spring JDBC with `JdbcTemplate` was chosen over JPA/Hibernate because:
- The schema is simple and stable
- JPA adds significant startup complexity for no gain here
- SQL is explicit and easy to reason about for simple CRUD

## Tradeoffs

- `register()` must be called before `subscribe()` — the FK enforces this. The bot must
  register the user on every interaction (idempotent) before any subscription operation.
- `DriverManagerDataSource` has no connection pooling. Acceptable for a low-throughput bot;
  upgrade to HikariCP if needed.
- HSQLDB file mode is not suitable for multi-process deployments.

## Notes

- `register()` and `subscribe()` are both idempotent via `DuplicateKeyException` absorption.
- Integration tests use HSQLDB in-memory mode to stay fast and isolated from the file-backed DB.
- `ON DELETE CASCADE` on the FK means deleting a user from `telegram_user` automatically
  removes all their subscriptions.

---

## Scheduled Forecast Fetching

## Problem
Three interrelated concerns had to be addressed together:

1. **Missed-minute recovery** — a minute-exact cron check meant that any brief application
   downtime would skip a scheduled fetch entirely and leave the cache stale until the next
   update cycle (potentially hours away).
2. **Provider-side publication delays** — shipping-forecast providers sometimes publish their
   declared update time but serve content that is still from the previous cycle (delays up
   to three hours are common). Caching stale content is worse than not caching at all.
3. **Bot-pattern detection** — requests landing on round clock minutes (00, 05, 10 …) are
   a strong signal that the client is automated. Public forecast sites may rate-limit or
   block such traffic.

## Decision
Three complementary mechanisms were added to `ForecastScheduler`:

**Catch-up window**: instead of matching only the exact scheduled minute, the scheduler
fetches whenever the current time falls inside a 30-minute window starting at
`updateTime + jitter`. If the cache already contains content fetched after the last
scheduled update, the window check is skipped (no duplicate fetch).

**Freshness retry**: after a successful HTTP fetch, `ForecastProvider.isFresh(content,
expectedAfter)` is called. If the provider reports stale content, the cache is left
unchanged and a retry is scheduled 15–30 minutes later. While a retry is pending, the
normal catch-up window check is suppressed so the server is not hit on every minute tick.

**Per-provider jitter**: a fixed random offset of 2–4 minutes is computed for each
provider at scheduler startup. Fetch times therefore never land on round minutes, and
different providers fetch at slightly different offsets.

Both `Clock` and `RandomGenerator` are injected dependencies so all three behaviours are
fully exercisable in unit tests without real time or real randomness.

## Structure

**`forecast` package (contracts + domain):**
- `ForecastCache` — immutable record `(url, content, fetchedAt)`
- `ForecastCacheRepository` — interface; `save` (upsert) and `findByUrl`
- `ForecastFetcher` — interface; abstracts HTTP retrieval from scheduling logic
- `ForecastProvider.isFresh(content, expectedAfter)` — default method (returns `true`);
  override in providers that embed a publication timestamp in the page

**`forecast.infra` package:**
- `JdbcForecastCacheRepository` — JDBC implementation backed by the `forecast_cache` table
- `HttpForecastFetcher` — implementation using `java.net.http.HttpClient`

**`forecast.scheduler` package:**
- `ForecastScheduler` — Spring `@Component`; cron-driven (`0 * * * * *`), holds a
  `Map<url, Duration> fetchJitters` (computed at startup) and a
  `ConcurrentHashMap<url, Instant> pendingRetries` (updated at runtime)

## Applied Principles / Patterns

- **SOLID — Dependency Inversion**: `ForecastScheduler` depends on `ForecastFetcher` and
  `ForecastCacheRepository` interfaces, not concrete HTTP or JDBC classes
- **SOLID — Open-Closed**: adding a new provider requires only a new `ForecastProvider` bean;
  the scheduler picks it up automatically
- **GRASP — Information Expert**: freshness logic lives in `ForecastProvider`, the only class
  that knows the page format well enough to detect a stale response
- **GRASP — Protected Variations**: `ForecastFetcher` shields the scheduler from HTTP details;
  `ForecastCacheRepository` shields it from storage details
- **GoF — Strategy**: `ForecastFetcher` is interchangeable; test doubles substitute freely

## Why This Approach

The catch-up window replaces the original minute-equality check with a time-range check
against `cacheRepository` state. This is more robust than widening the cron expression
because the cache check acts as the true deduplication barrier — the same fetch will not
fire twice regardless of how many times the cron fires within the window.

Suppressing the normal schedule while a retry is pending prevents the catch-up window from
becoming a polling loop. Without the suppression, a stale provider would be hit on every
cron tick for up to 30 minutes.

Jitter is fixed per provider (not re-randomised on each tick) so the offset is stable
across restarts and the deduplication window calculation remains correct.

## Tradeoffs

- `pendingRetries` is in-memory; a restart clears it. On restart the catch-up window
  re-triggers a fetch immediately, which re-checks freshness, so correctness is preserved
  — only the retry delay is lost.
- Jitter uses `RandomGenerator` (the standard Java API), not `SecureRandom`; this is
  intentional — timing offsets do not require cryptographic quality.
- `isFresh` receives raw page content as a `String`.

## Notes

- `forecast_cache` DDL is in `schema.sql` alongside `user_subscription`, applied at startup.

---

## DWD Provider Implementation

## Decision
Implement `DwdForecastProvider` in `forecast.provider` as the first concrete `ForecastProvider`.
No new abstractions are needed; the existing contract covers all requirements.

## Structure

- `DwdForecastProvider` — `@Component`; implements `ForecastProvider` for the DWD
  North and Baltic Sea bulletin at `dwd.de/…/seewetternordostsee.html`

## Applied Principles / Patterns

- **GRASP — Information Expert**: parsing and freshness logic lives entirely in
  `DwdForecastProvider`; the scheduler has no knowledge of DWD page structure
- **SOLID — Open-Closed**: adding this provider required zero changes to existing code

## Why This Approach

`updateTimes` returns 00:15 and 12:15 as local Berlin times; `publishingZone` returns
`Europe/Berlin` so the scheduler converts them correctly across CET/CEST transitions.
`isFresh` parses the embedded timestamp (format `dd.MM.yyyy, HH:mm CET/CEST`)
and returns `true` only when the bulletin's own date is at or after `expectedAfter`.
Fail-open when no timestamp is found avoids infinite retries if the page layout changes.
`parse` strips HTML via Jsoup then slices the plain text between consecutive area headings.

## Tradeoffs

- Area coordinates are approximate centre-points; sub-area precision is not required
  for subscriber matching at the current level of granularity.
