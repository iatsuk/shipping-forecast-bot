# architecture.md

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
