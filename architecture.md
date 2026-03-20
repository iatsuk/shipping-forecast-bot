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
3. **Bot-pattern detection** — requests landing on round-clock minutes (00, 05, 10 …) are
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

## Forecast Dispatch Pipeline

## Problem
After a fresh forecast is fetched and cached, subscribed users must receive the relevant
area forecasts via Telegram. The dispatch logic needs to coordinate three concerns:
- parsing the fetched page content into structured forecasts (provider responsibility)
- looking up subscribers per area (subscription repository)
- delivering messages to each chat (Telegram messaging)

Embedding this logic directly in `ForecastScheduler` would violate Single Responsibility
and make the scheduler harder to test.

## Decision
Introduce a `ForecastDispatcher` in `forecast.scheduler` and a `MessageSender` interface
in `telegram`. The scheduler calls `dispatcher.dispatch(provider.parse(content))` immediately
after a successful cache save. The dispatcher iterates the forecasts, looks up subscribers
per area, and delegates delivery to `MessageSender`. `TelegramBot` implements `MessageSender`.

## Structure

- `MessageSender` (interface, `telegram` package) — single method `send(chatId, text)`;
  shields dispatch logic from the Telegram client
- `ForecastDispatcher` (`forecast.scheduler` package) — depends on `SubscriptionRepository`
  and `MessageSender`; loops over forecasts, queries subscribers by area name, delivers
  messages; individual failures are caught and logged without aborting remaining sends
- `TelegramBot` — implements `MessageSender` alongside `LongPollingSingleThreadUpdateConsumer`
- `ForecastScheduler` — after `cacheRepository.save(...)`, calls
  `dispatcher.dispatch(provider.parse(content))`

## Applied Principles / Patterns

- **SOLID — Single Responsibility**: `ForecastDispatcher` handles dispatch only;
  `ForecastScheduler` handles scheduling only
- **SOLID — Dependency Inversion**: `ForecastDispatcher` depends on `MessageSender`
  (interface), not on `TelegramBot` (concrete class)
- **SOLID — Open-Closed**: swapping the messaging backend requires only a new
  `MessageSender` implementation
- **GRASP — Low Coupling**: `ForecastDispatcher` has no knowledge of Telegram internals
- **GRASP — Protected Variations**: `MessageSender` isolates the rest of the application
  from Telegram API details and failure modes

## Why This Approach

Separating dispatch from scheduling makes both classes independently testable: the scheduler
tests use a no-op dispatcher; the dispatcher tests use an in-memory `MessageSender` stub.
Catching exceptions per-message ensures that a Telegram delivery failure for one subscriber
does not block the remaining subscribers.

Dispatch happens after the cache save so that, even if dispatch fails entirely, the cache
is updated and subscribers can retrieve the forecast on their next manual request.

## Tradeoffs

- Dispatch is synchronous and happens inside the scheduler's cron tick. For a large
  subscriber base this could delay the next scheduled check. Acceptable at the current scale;
  async dispatch can be introduced later if needed.
- Area matching is by name string (`forecast.location().name()` vs. subscription's area).
  Both sides must use the same naming convention; mismatches will silently produce no sends.

---

## Blocked-User Detection

## Problem
When a user blocks, stops, or deletes the bot in Telegram, the Telegram API returns HTTP 403
on any subsequent sent attempt. Without handling this, the application would keep attempting
delivery on every forecast cycle and log errors indefinitely for every blocked user.

## Decision
Introduce `UserBlockedBotException` as a typed signal for permanent delivery failure.
`TelegramBot.send()` catches `TelegramApiRequestException` with error code 403 and wraps
it into `UserBlockedBotException` instead of a generic `RuntimeException`. `ForecastDispatcher`
catches this specific exception and calls `userRepository.delete(chatId)`, which removes the
`telegram_user` row and lets the `ON DELETE CASCADE` FK remove all subscriptions automatically.

## Structure

- `UserBlockedBotException` (`telegram` package) — unchecked exception carrying the `chatId`
  of the user who blocked the bot; thrown by `TelegramBot.send()` on 403 responses
- `TelegramBot.send()` — distinguishes 403 (`UserBlockedBotException`) from other Telegram
  errors (generic `RuntimeException`)
- `ForecastDispatcher.dispatch()` — catches `UserBlockedBotException` before the generic
  `Exception` handler; calls `userRepository.delete(chatId)` (subscriptions cascade); continues
  processing remaining subscribers

## Applied Principles / Patterns

- **SOLID — Single Responsibility**: `TelegramBot` owns the knowledge of what constitutes a
  permanent Telegram failure; `ForecastDispatcher` owns the cleanup policy
- **GRASP — Protected Variations**: the rest of the application is shielded from Telegram
  error codes behind a typed exception; changing the detection logic (e.g. adding error 400
  "user deactivated") requires only a change in `TelegramBot`
- **Fail fast / explicit design**: a typed exception makes the "blocked" case explicit in code
  and testable without a real Telegram connection

## Why This Approach

Using a typed exception keeps the `MessageSender` interface clean (no error-code coupling
in the contract) while still allowing callers to distinguish recoverable from permanent
failures. The cleanup happens inside the per-subscriber catch block, so one blocked user
does not affect delivery to other subscribers.

## Tradeoffs

- Only HTTP 403 is treated as a permanent block. "user is deactivated" (deleted account)
  also arrives as 403 in practice, so it is covered by the same handler. Error 400
  "chat not found" is treated as a transient error for now.
- The full `telegram_user` row is deleted (subscriptions cascade). If the user re-enables
  the bot later, re-registration happens automatically on their first interaction (idempotent
  `register()` call) and they can subscribe again without any special handling.

---

## Bot Command Handling and Interactive Menu

## Problem
The bot needs an interactive menu-driven by inline keyboard buttons so users can browse
providers, read forecasts, and subscribe to areas without typing commands. The routing
logic and interaction flow must be separated from Telegram API mechanics, and the wiring
must avoid a circular Spring dependency: `TelegramBot` sends messages, `BotCommandHandler`
needs to send messages, and `TelegramBot` constructs the handler.

## Decision
Introduce `BotCommandHandler` as a plain (non-Spring) class constructed directly by
`TelegramBot`, which passes `this` as the `BotInteraction` dependency. This breaks the
Spring circular dependency at the wiring level while keeping the classes properly separated.

`BotInteraction` extends `MessageSender` with two additional methods: `sendMenu` (sends a
message with an attached `InlineKeyboardMarkup`) and `answerCallbackQuery` (acknowledges
the callback to dismiss the button's loading indicator). A `MenuOption` record carries the
display label and callback data for each button.

`TelegramBot.consume()` handles two update types: text messages (routed to `handleStart` or
`handleStop`) and `CallbackQuery` updates (routed to `handleCallback`). All other text is
ignored — navigation is entirely via inline keyboard buttons.

## Navigation Flow

```
/start  →  register user  →  greeting + provider list keyboard
[provider button]  →  send provider area map image + area keyboard
[area button]  →  send current forecast for that area  →  subscribe user  →  provider list keyboard
/stop  →  delete all user data  →  goodbye message
```

## Structure

- `MenuOption` — record `(label, callbackData)`; the domain model for a keyboard button
- `BotInteraction` — interface extending `MessageSender`; adds `sendMenu`, `sendPhoto`, and
  `answerCallbackQuery`
- `BotCommandHandler` — package-private class; owns all navigation and subscription logic;
  constructed by `TelegramBot`, not managed by Spring; holds a `providerByAreaName` map to
  look up the provider covering a given area (needed when an area button is pressed)
- `TelegramBot` — implements `BotInteraction` (Telegram mechanics) and `LongPollingSingleThreadUpdateConsumer`;
  constructs `BotCommandHandler` passing `this`; all domain dependencies are injected by Spring

Callback data format: `"provider:{name}"` for provider buttons, `"area:{name}"` for area
buttons. All names are within Telegram's 64-byte callback data limit.

## Applied Principles / Patterns

- **SOLID — Single Responsibility**: `TelegramBot` handles Telegram API mechanics only;
  `BotCommandHandler` owns all interaction logic
- **SOLID — Dependency Inversion**: `BotCommandHandler` depends on `BotInteraction`
  (interface), not on `TelegramBot` (concrete class)
- **GRASP — Low Coupling / Protected Variations**: `BotInteraction` and `MenuOption` shield
  command logic from Telegram library types (`InlineKeyboardMarkup`, `InlineKeyboardRow`)
- **GoF — Strategy**: `ForecastProvider` list is iterated polymorphically; the handler
  works identically for any number of providers

## Why This Approach

Constructing `BotCommandHandler` inside `TelegramBot` (passing `this`) is the simplest way
to break the circular dependency without `@Lazy` annotations or an extra factory bean.
`BotCommandHandler` is package-private because it is an implementation detail of the
`telegram` package and has no reason to be accessed from outside.

Inline keyboards were chosen over reply keyboards because buttons are attached to specific
messages (not persistent in the input area), giving a cleaner conversation flow and making
the navigation intent explicit.

## Tradeoffs

- `BotCommandHandler` is not a Spring bean and therefore not directly unit-testable via
  Spring context. It is tested indirectly through `TelegramBot`, or can be instantiated
  directly in a unit test.
- Callback data uses provider/area names as identifiers. If a provider or area is renamed,
  existing unacknowledged keyboard buttons in chats will route to the wrong handler entry.
  Acceptable at the current scale.

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

---

## Provider Map Image Caching

## Problem
When a user selects a forecast provider, showing a static area map helps them identify
which geographic areas to subscribe to. The map image must be available immediately at
interaction time — fetching it on-demand per request would add latency and create a Telegram
timeout risk. The image is a static asset that does not change between forecast updates.

## Decision
Extend `ForecastProvider` with an optional `mapImageUrl()` method (default: empty) and
introduce an `ImageCacheRepository` backed by a new `image_cache` table that stores raw
binary image data (`LONGVARBINARY`). An `ImageCacheService` (Spring `InitializingBean`)
fetches and caches images for all providers that supply a map URL on application startup.
If the image is already cached, no fetch is performed.

`BotCommandHandler.handleProviderSelected` reads the cached image and sends it via
`BotInteraction.sendPhoto` with the area keyboard attached. If the image is not yet
cached (first startup, network failure), it falls back to a plain text menu.

## Structure

- `ImageCache` — immutable record `(url, data, fetchedAt)` for a cached binary image
- `ImageCacheRepository` — interface: `save(url, data, fetchedAt)` and `findByUrl(url)`
- `JdbcImageCacheRepository` — JDBC implementation backed by the `image_cache` table
- `ImageCacheService` — `@Component`, `InitializingBean`; iterates providers, fetches
  map images that are not yet cached using `java.net.http.HttpClient` directly
- `ForecastProvider.mapImageUrl()` — default method returning `Optional.empty()`;
  overridden in providers that supply a map image
- `BotInteraction.sendPhoto(chatId, imageData, caption, rows)` — new method on the
  interaction interface; implemented in `TelegramBot` using `SendPhoto` + `InputFile`

## Applied Principles / Patterns

- **SOLID — Open-Closed**: adding map images to a new provider requires only overriding
  `mapImageUrl()` in that provider; no other code changes
- **SOLID — Dependency Inversion**: `BotCommandHandler` depends on `ImageCacheRepository`
  (interface), not the JDBC implementation
- **GRASP — Information Expert**: the provider knows its own map URL; the repository owns
  storage; the service owns the fetch-and-cache policy
- **GoF — Template Method (lightweight)**: `InitializingBean.afterPropertiesSet()` provides
  the startup hook; no subclassing is needed

## Why This Approach

`InitializingBean` gives a clean startup hook without scheduling complexity. Since map images
are static, a one-time fetch on startup is sufficient. The fallback to a text menu when the
image is unavailable keeps the bot functional even if the first startup fetch fails.

Storing images in HSQLDB (`LONGVARBINARY`) keeps all persistent state in one place and avoids
a separate file store or external blob service at the current scale.

## Tradeoffs

- Image data is loaded into memory as a `byte[]` when retrieved from the repository.
  For large images this is acceptable at the current scale; a streaming approach would be
  needed for very large assets.
- Images are never re-fetched after initial caching. If a provider changes its map, the
  old image persists until the database file is reset manually.
- `DwdForecastProvider.mapImageUrl()` returns the DWD area map for the North and Baltic Sea.
