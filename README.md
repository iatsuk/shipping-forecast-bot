# shipping-forecast-bot

A Telegram bot that fetches, caches, and delivers shipping weather forecasts from external data providers.
Built with plain Spring (no Boot), Java 25, Maven, HSQLDB, and Spring JDBC.

## 1. Package Structure

Root package: `boats.log.shippingforecast`

```
boats.log.shippingforecast
├── App.java                          — main entry point
├── AppConfig.java                    — Spring @Configuration (all beans wired here)
│
├── forecast/                         — domain contracts + domain records
│   ├── ForecastProvider.java         — interface (Strategy)
│   ├── ForecastFetcher.java          — interface (HTTP abstraction)
│   ├── ForecastCacheRepository.java  — interface (persistence abstraction)
│   ├── ForecastCache.java            — record (url, content, fetchedAt)
│   ├── ImageCacheRepository.java     — interface (image persistence abstraction)
│   ├── ImageCache.java               — record (url, data, fetchedAt)
│   ├── ShippingForecast.java         — record (location, text)
│   ├── GeoLocation.java              — record (name, latitude, longitude)
│   │
│   ├── infra/                        — infrastructure implementations
│   │   ├── HttpForecastFetcher.java          — java.net.http.HttpClient impl
│   │   ├── JdbcForecastCacheRepository.java  — Spring JdbcTemplate impl
│   │   └── JdbcImageCacheRepository.java     — Spring JdbcTemplate impl
│   │   └── ImageCacheService.java            — startup image fetcher (InitializingBean)
│   │
│   ├── provider/                     — concrete provider implementations
│   │   ├── DwdForecastProvider.java       — DWD North & Baltic Sea bulletin
│   │   └── MetOfficeForecastProvider.java — UK Met Office Shipping Forecast
│   │
│   └── scheduler/
│       ├── ForecastScheduler.java    — cron-driven orchestrator
│       └── ForecastDispatcher.java   — dispatches fresh forecasts to subscribers
│
├── user/                             — user domain
│   ├── TelegramUser.java             — record (chatId)
│   ├── UserRepository.java           — interface
│   └── JdbcUserRepository.java       — JDBC implementation
│
├── subscription/                     — subscription domain
│   ├── UserSubscription.java         — record (chatId, area)
│   ├── SubscriptionRepository.java   — interface
│   └── JdbcSubscriptionRepository.java — JDBC implementation
│
└── telegram/
    ├── TelegramBot.java              — Telegram long-polling consumer + BotInteraction impl
    ├── BotCommandHandler.java        — all command and menu navigation logic
    ├── BotInteraction.java           — interface: send + sendMenu + sendPhoto + answerCallbackQuery
    ├── MenuOption.java               — record (label, callbackData) for inline keyboard buttons
    ├── MessageSender.java            — interface for plain text sends (used by ForecastDispatcher)
    └── UserBlockedBotException.java  — signals permanent delivery failure (user blocked bot)
```

## 2. Persistence

- **Database:** HSQLDB in file mode, stored at `./data/sfb-db`
- **Access layer:** Spring `JdbcTemplate` — no JPA/Hibernate
- **Schema:** applied at startup via `ResourceDatabasePopulator` + `schema.sql` (idempotent `IF NOT EXISTS`)
- **Connection:** `DriverManagerDataSource` (no pooling; single-connection acceptable for low-traffic bot)
- **Tests:** use HSQLDB in-memory via `EmbeddedDatabaseBuilder` to keep tests isolated and fast

Four tables:
```sql
-- telegram_user: one row per known user; chat_id serves as both user identifier and dispatch destination
CREATE TABLE IF NOT EXISTS telegram_user (
    chat_id BIGINT NOT NULL,
    CONSTRAINT pk_telegram_user PRIMARY KEY (chat_id)
);

-- user_subscription: (chat_id, area), FK to telegram_user with cascade delete
CREATE TABLE IF NOT EXISTS user_subscription (
    chat_id BIGINT       NOT NULL,
    area    VARCHAR(100) NOT NULL,
    CONSTRAINT pk_user_subscription PRIMARY KEY (chat_id, area),
    CONSTRAINT fk_user_subscription_user FOREIGN KEY (chat_id) REFERENCES telegram_user(chat_id) ON DELETE CASCADE
);

-- forecast_cache: one row per provider URL; CLOB stores raw HTML
CREATE TABLE IF NOT EXISTS forecast_cache (
    url        VARCHAR(500) NOT NULL,
    content    CLOB         NOT NULL,
    fetched_at TIMESTAMP    NOT NULL,
    CONSTRAINT pk_forecast_cache PRIMARY KEY (url)
);

-- image_cache: one row per image URL; LONGVARBINARY stores raw bytes (e.g. provider area maps)
CREATE TABLE IF NOT EXISTS image_cache (
    url        VARCHAR(500)  NOT NULL,
    data       LONGVARBINARY NOT NULL,
    fetched_at TIMESTAMP     NOT NULL,
    CONSTRAINT pk_image_cache PRIMARY KEY (url)
);
```

The bot operates exclusively in private chats, so `chat_id` is both the Telegram user identifier
and the chat destination for message dispatch — no separate column is needed.

A user must be registered in `telegram_user` before they can subscribe. The FK with
`ON DELETE CASCADE` ensures subscriptions are automatically removed if a user is deleted.

## 3. Domain Models

All are **immutable Java records**:
```
┌──────────────────┬─────────────────────────────┬───────────────────────────────────────────┐
│      Record      │           Fields            │                   Role                    │
├──────────────────┼─────────────────────────────┼───────────────────────────────────────────┤
│ TelegramUser     │ chatId                      │ A user known to the bot                   │
├──────────────────┼─────────────────────────────┼───────────────────────────────────────────┤
│ UserSubscription │ chatId, area                │ A user subscribed to a named forecast area│
├──────────────────┼─────────────────────────────┼───────────────────────────────────────────┤
│ GeoLocation      │ name, latitude, longitude   │ A named WGS-84 point for a sea area       │
├──────────────────┼─────────────────────────────┼───────────────────────────────────────────┤
│ ShippingForecast │ location (GeoLocation),     │ One parsed forecast for one area          │
│                  │ text                        │                                           │
├──────────────────┼─────────────────────────────┼───────────────────────────────────────────┤
│ ForecastCache    │ url, content, fetchedAt     │ Latest cached raw HTML for one provider   │
│                  │                             │ URL                                       │
├──────────────────┼─────────────────────────────┼───────────────────────────────────────────┤
│ ImageCache       │ url, data, fetchedAt        │ Cached binary image for a provider        │
│                  │                             │ (e.g. area map)                           │
└──────────────────┴─────────────────────────────┴───────────────────────────────────────────┘
```

## 4. ForecastProvider Interface (Strategy Pattern)

`./src/main/java/boats/log/shippingforecast/forecast/ForecastProvider.java`

Key contract methods:
- `name()`, `description()`, `url()` — metadata
- `updateTimes()` — `List<LocalTime>` in the provider's local zone
- `publishingZone()` — defaults to UTC; DWD overrides to Europe/Berlin
- `geoLocations()` — the areas this provider covers
- `mapImageUrl()` — optional URL of a static area map image; default returns empty
- `isFresh(content, expectedAfter)` — detect whether the fetched page is actually new content
- `parse(pageContent)` — extract one `ShippingForecast` per area from raw HTML

Two concrete implementations:

- **`DwdForecastProvider`** — 9 North/Baltic Sea areas (Southwestern North Sea, German Bight,
  Fischer, Skagerrak, Kattegat, Belts and Sound, Western Baltic, Southern Baltic, Southeastern
  Baltic); publishes at 00:15 and 12:15 Berlin time; parses the embedded `dd.MM.yyyy, HH:mm
  CET/CEST` timestamp to detect staleness; uses Jsoup for HTML-to-text conversion.

- **`MetOfficeForecastProvider`** — 31 sea areas around the British Isles; publishes at 0500,
  1100, 1700, 2300 UTC; the page groups related areas under shared `<h3>` headings (e.g.
  "Viking, North Utsire") — each individual area is mapped to its own `GeoLocation` and
  receives the group's forecast text; parses the "Issued by…at HH:mm (UTC)" line for
  staleness detection; Trafalgar's parenthetical note "(issued 0015 UTC)" is stripped from
  the heading during parsing.

Both providers prepend their `name()` as the first line of every forecast message so users can
always tell which source a forecast comes from, even when different providers cover overlapping
areas (e.g. both DWD and Met Office cover German Bight).

## 5. ForecastScheduler + ForecastDispatcher

`./src/main/java/boats/log/shippingforecast/forecast/scheduler/`

**ForecastScheduler** — cron: `0 * * * * *` (every minute). Four key behaviors:

- **Startup fetch** (`afterPropertiesSet` → `fetchOnStartup`): fetches any provider whose cache
  predates the last scheduled update, so data is immediately available on cold start.
- **Catch-up window:** instead of requiring an exact minute match, fetches if the current time
  is within 30 minutes of `updateTime + jitter` and cache is stale.
- **Freshness retry:** if `isFresh()` returns false, leaves the cache unchanged and schedules
  a retry 15–30 minutes later. Normal schedule checks are suppressed while a retry is pending.
- **Per-provider jitter:** a fixed random offset of 120–240 seconds computed once at startup.
  Prevents round-minute request patterns.

After a successful cache save, the scheduler immediately calls `ForecastDispatcher.dispatch()`
with the parsed forecasts.

**ForecastDispatcher** — for each `ShippingForecast`, looks up all chat IDs subscribed to
that area via `SubscriptionRepository` and delivers the forecast text via `MessageSender`.
Individual send failures are caught and logged without aborting the remaining sends.

`Clock` and `RandomGenerator` are injected into the scheduler, making it fully unit-testable
without real time.

## 6. Telegram Bot Integration

`./src/main/java/boats/log/shippingforecast/telegram/`

- Library: `telegrambots-longpolling` + `telegrambots-client` version 9.5.0
- Transport: `OkHttpTelegramClient`
- Bot registered in `AppConfig` via `TelegramBotsLongPollingApplication`
- Token injected from `application.properties` via `@Value`
- `TelegramBot` implements `LongPollingSingleThreadUpdateConsumer` and `BotInteraction`;
  it handles Telegram API mechanics only and delegates all logic to `BotCommandHandler`
- `BotCommandHandler` is a package-private, non-Spring class constructed by `TelegramBot`
  (passing `this` as `BotInteraction`) — this avoids a circular Spring dependency
- On Telegram 403 responses (user blocked/stopped/deleted), `send()` throws
  `UserBlockedBotException`; `ForecastDispatcher` catches it and deletes the user
  from `telegram_user` (subscriptions cascade) so no further delivery is attempted

**Interaction flow:**
```
/start  →  register user  →  greeting
            + optional "Active subscriptions (N)" button  →  subscribed area list
            + provider list keyboard
[provider button]   →  send area map image + area keyboard
[area button]       →  send latest forecast for that area
                        + "Subscribe to X" OR "Unsubscribe from X" button
[subscribe button]  →  subscribe user  →  return to provider list
[unsubscribe button]→  unsubscribe user  →  return to provider list
/stop   →  delete all user data  →  goodbye
```

Callback data prefixes: `provider:`, `area:`, `subscribe:`, `unsubscribe:`, `my_subscriptions`.

## 7. pom.xml Dependencies

```
┌──────────────────────────┬───────────────┬────────────────────────────────────────────┐
│        Dependency        │    Version    │                  Purpose                   │
├──────────────────────────┼───────────────┼────────────────────────────────────────────┤
│ spring-context           │ 7.0.6         │ Spring IoC, scheduling (@EnableScheduling) │
├──────────────────────────┼───────────────┼────────────────────────────────────────────┤
│ spring-jdbc              │ 7.0.6         │ JdbcTemplate, EmbeddedDatabaseBuilder      │
├──────────────────────────┼───────────────┼────────────────────────────────────────────┤
│ hsqldb                   │ 2.7.4         │ Embedded file-backed database              │
├──────────────────────────┼───────────────┼────────────────────────────────────────────┤
│ telegrambots-longpolling │ 9.5.0         │ Long-polling Telegram bot framework        │
├──────────────────────────┼───────────────┼────────────────────────────────────────────┤
│ telegrambots-client      │ 9.5.0         │ Telegram API client (OkHttp)               │
├──────────────────────────┼───────────────┼────────────────────────────────────────────┤
│ jsoup                    │ 1.22.1        │ HTML parsing for forecast providers        │
├──────────────────────────┼───────────────┼────────────────────────────────────────────┤
│ slf4j-api                │ 2.0.17        │ Logging facade                             │
├──────────────────────────┼───────────────┼────────────────────────────────────────────┤
│ logback-classic          │ 1.5.32        │ Logging implementation                     │
├──────────────────────────┼───────────────┼────────────────────────────────────────────┤
│ junit-jupiter-api        │ via BOM 6.0.3 │ Test framework                             │
├──────────────────────────┼───────────────┼────────────────────────────────────────────┤
│ assertj-core             │ 3.27.7        │ Fluent test assertions                     │
└──────────────────────────┴───────────────┴────────────────────────────────────────────┘
```

Java target: 25. No Lombok, no Hibernate, no Spring Boot.

## 8. architecture.md Summary

The architecture doc (at the repo root) covers six documented design decisions:

1. **Forecast package structure** — split into `forecast` (contracts), `forecast.infra` (HTTP +
   JDBC), `forecast.provider` (concrete parsers), `forecast.scheduler` (orchestration).
   Dependency flows inward only.
2. **ForecastProvider abstraction** — Strategy interface; each provider is self-contained;
   records for value objects.
3. **User registration and subscription persistence** — separate `telegram_user` and
   `user_subscription` tables with a FK. `UserRepository` handles user registration;
   `SubscriptionRepository` handles area subscriptions. `chat_id` doubles as the user
   identifier since the bot operates exclusively in private chats.
4. **Scheduled forecast fetching** — catch-up window + freshness retry + per-provider
   jitter. `Clock` and `RandomGenerator` are injectable for full test control.
5. **Forecast dispatch pipeline** — `ForecastDispatcher` + `MessageSender` interface;
   dispatch is triggered after every successful cache save; per-subscriber failure isolation.
6. **Blocked-user detection** — `UserBlockedBotException` on Telegram 403; user fully
   erased so blocked users are never retried.
7. **Bot command handling and interactive menu** — `BotCommandHandler` + `BotInteraction`
   interface; inline keyboard navigation; circular Spring dependency avoided by constructing
   the handler inside `TelegramBot` with `this` as the interaction target.
8. **Provider implementations** — `DwdForecastProvider` (DWD North/Baltic Sea) and
   `MetOfficeForecastProvider` (UK Met Office, 31 areas, grouped `<h3>` headings); each
   prepends its `name()` to forecast text so overlapping areas (e.g. German Bight) are
   always attributed to the correct source.
9. **Provider map image caching** — `ForecastProvider.mapImageUrl()` optional method;
   `ImageCacheRepository` + `JdbcImageCacheRepository` for `LONGVARBINARY` storage;
   `ImageCacheService` (InitializingBean) fetches once at startup; `BotInteraction.sendPhoto`
   delivers the image with the area keyboard attached.

## 9. Build and Deployment

### Fat JAR

`maven-shade-plugin` (3.6.2) is configured in `pom.xml`. Running `mvn package` produces a
self-contained `target/app.jar` with all dependencies bundled and the correct `Main-Class`
manifest entry. Signature files from signed JARs are stripped to avoid runtime verification
errors; `META-INF/services` entries from all dependencies are merged via
`ServicesResourceTransformer`.

### VPS Setup (Debian 12)

```bash
sudo bash scripts/vps-setup.sh "<your-ssh-public-key>"
```

What it does (idempotent for most steps):
1. Installs Eclipse Temurin 25 JRE via the Adoptium APT repository
2. Creates a `sfb` system user with home at `/opt/sfb`
3. Creates `/opt/sfb/{data,logs,deploy}` and sets ownership
4. Configures SSH access (key, password, or both — see script usage)
5. Grants `sfb` passwordless `sudo` for `systemctl {start,stop,restart,status} sfb` only
6. Creates a placeholder `/opt/sfb/env` (overwritten by GitHub Actions on every deploy)
7. Installs and enables the `sfb` systemd unit at `/etc/systemd/system/sfb.service`
8. Configures journald retention (500 MB, 1 month)
9. Configures UFW: deny all inbound except SSH; allow all outbound

After setup, trigger the first deploy from GitHub Actions (or `workflow_dispatch`). It will
write the token and deploy the JAR. Then start the service:

```bash
sudo systemctl start sfb
sudo journalctl -u sfb -f
```

### CI/CD — GitHub Actions

`.github/workflows/deploy.yml` triggers on every push to `main` and on manual dispatch.

Pipeline: checkout → Java 25 setup (with Maven cache) → `mvn package` (builds + tests) →
write `TELEGRAM_BOT_TOKEN` to `/opt/sfb/env` → SCP `target/app.jar` and
`scripts/logback-prod.xml` → atomic `mv` to `/opt/sfb/app.jar` →
`sudo systemctl restart sfb` → status check.

Required GitHub Actions secrets:

| Secret | Value |
|---|---|
| `VPS_HOST` | Server IP or hostname |
| `VPS_USER` | `sfb` |
| `VPS_SSH_KEY` | Private key matching the public key passed to `vps-setup.sh` |

### JVM Settings (in systemd unit)

| Flag | Reason |
|---|---|
| `-Xms32m` | Start small; the bot is idle most of the time |
| `-Xmx192m` | Sufficient for Spring context + HSQLDB + Telegram client buffers |
| `-XX:MaxMetaspaceSize=96m` | Caps native metaspace growth |
| `-XX:+UseZGC -XX:+ZGenerational` | Sub-millisecond GC pauses; well-suited for long-running low-traffic daemons on Java 25 |
| `-XX:SoftMaxHeapSize=128m` | ZGC targets 128 MB; may use up to 192 MB under pressure |
| `-Xlog:gc:...` | Rotating GC log for post-hoc diagnostics |

### Production Logging

`scripts/logback-prod.xml` must be deployed to `/opt/sfb/logback-prod.xml`.

- Writes to `/opt/sfb/logs/app.log` with daily rotation, 30-day retention, 500 MB cap
- Wrapped in an async appender (queue 512; drops TRACE/DEBUG under pressure, never WARN/ERROR)
- Also writes to stdout (captured by systemd journal — `journalctl -u sfb -f`)
- Third-party libraries (Spring, Telegram, OkHttp) suppressed to WARN
- `boats.log.shippingforecast` at INFO

### scripts/ Directory

```
scripts/
├── vps-setup.sh       — full VPS provisioning (run once as root)
└── logback-prod.xml   — production Logback configuration
```

## 10. Key Gaps / What Is Not Yet Implemented

- Provider map images are fetched once at startup and never refreshed. If a provider changes
  its map image, the old one persists until the database is reset.
- The subscription table stores only the area name, not the provider. Two providers that cover
  an area with the same name (e.g. German Bight) share the same subscription row; a user
  subscribed to that area will receive forecasts from both providers.
