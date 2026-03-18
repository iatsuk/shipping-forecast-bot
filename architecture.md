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

All three live in `boats.log.sfb.forecast`.

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
- Update times are modelled as `List<LocalTime>` (UTC assumed). Timezone awareness may be
  needed if providers publish on local-time schedules.

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
