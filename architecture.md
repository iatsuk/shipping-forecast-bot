# architecture.md

---

## Overview
Abstraction for shipping forecast providers.

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

All three live in `boats.log.shippingforecast.forecast` alongside the core interfaces (`ForecastProvider`, `ForecastFetcher`, `ForecastCacheRepository`).

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

## Overview
Persistence layer for user subscriptions using embedded HSQLDB with Spring JDBC.

---

## Problem
The bot needs to remember which forecast areas each Telegram chat has subscribed to, and
to look up all subscribers when dispatching a forecast. This state must survive restarts.

---

## Decision
Introduce an embedded HSQLDB file-mode database accessed through Spring JDBC (`JdbcTemplate`).
The repository pattern is used to isolate persistence details from the rest of the application.

---

## Structure

- `UserSubscription` — immutable domain record holding `(chatId, area)`
- `SubscriptionRepository` — interface defining subscription operations (subscribe, unsubscribe,
  lookup by chat, lookup by area, delete all for a chat)
- `JdbcSubscriptionRepository` — JDBC implementation, registered as a Spring `@Repository`
- `schema.sql` — DDL for the `user_subscription` table, applied at startup via
  `ResourceDatabasePopulator`; uses `IF NOT EXISTS` so it is safe to run on every boot
- `AppConfig` — configures `DataSource` (HSQLDB file mode at `./data/sfb-db`) and `JdbcTemplate`

---

## Applied Principles / Patterns

- **SOLID — Dependency Inversion**: `TelegramBot` and future services depend on
  `SubscriptionRepository` (interface), not the JDBC implementation
- **GRASP — Information Expert**: repository owns all knowledge of how subscriptions are stored
- **GRASP — Protected Variations**: the interface shields the rest of the app from the choice
  of embedded HSQLDB; swapping to PostgreSQL later requires only a new implementation

---

## Why This Approach

Spring JDBC with `JdbcTemplate` was chosen over JPA/Hibernate because:
- The schema is simple (one table, two columns)
- JPA adds significant startup complexity and classpath weight for no gain here
- SQL is explicit and easy to reason about for simple CRUD

HSQLDB embedded file mode was chosen because:
- No external process to manage
- Data survives restarts (file-backed, not in-memory)
- Well-supported by Spring's `EmbeddedDatabaseBuilder` for in-memory testing

---

## Tradeoffs

- `DriverManagerDataSource` creates a new physical connection per operation (no pooling).
  Acceptable for a low-throughput Telegram bot; upgrade to HikariCP if connection overhead
  becomes a concern.
- HSQLDB file mode is not suitable for multi-process deployments. If the bot is ever
  scaled horizontally, switch to a networked database and replace `JdbcSubscriptionRepository`.

---

## Notes

- The database files are created at `./data/sfb-db` relative to the working directory.
- `subscribe()` is idempotent: duplicate inserts are silently absorbed via `DuplicateKeyException`.
- Integration tests use HSQLDB in-memory mode (`EmbeddedDatabaseBuilder`) to avoid touching
  the file-backed database and to keep tests fast and isolated.

---

## Overview
Scheduled fetching of forecast page content with persistent caching per provider URL.

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

Both `Clock` and `Random` are injected dependencies so all three behaviours are fully
exercisable in unit tests without real time or real randomness.

## Structure

**`forecast` package (contracts + domain):**
- `ForecastCache` — immutable record `(url, content, fetchedAt)`
- `ForecastCacheRepository` — interface; `save` (upsert) and `findByUrl`
- `ForecastFetcher` — interface; abstracts HTTP retrieval from scheduling logic
- `ForecastProvider.isFresh(content, expectedAfter)` — default method (returns `true`);
  override in providers that embed a publication timestamp in the page

**`forecast.infra` package (infrastructure implementations):**
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

## Overview
First concrete `ForecastProvider` implementation: DWD North and Baltic Sea bulletin.

## Problem
The scheduler needs at least one registered provider to do useful work.
DWD publishes its bulletin as a public HTML page with a machine-readable
timestamp and a consistent area structure, making it a good first implementation.

## Decision
Implement `DwdForecastProvider` as a `@Component` in the `forecast.provider` sub-package.
No new abstractions are needed; the existing `ForecastProvider` contract covers
all requirements.

## Structure

- `DwdForecastProvider` (`forecast.provider`) — `@Component`; implements `ForecastProvider`
  for the DWD North and Baltic Sea bulletin at `dwd.de/…/seewetternordostsee.html`

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
`parse` strips HTML then slices the plain text between consecutive area headings.

## Tradeoffs

- HTML parsing uses Jsoup, which correctly handles entity decoding, whitespace
  normalisation, and malformed markup. The bulletin text is extracted via `Document.text()`
  and then split on area headings.
- Area coordinates are approximate centre-points; sub-area precision is not required
  for subscriber matching at the current level of granularity.
