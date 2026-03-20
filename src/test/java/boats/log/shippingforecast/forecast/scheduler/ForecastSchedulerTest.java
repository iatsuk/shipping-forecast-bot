package boats.log.shippingforecast.forecast.scheduler;

import boats.log.shippingforecast.forecast.ForecastCache;
import boats.log.shippingforecast.forecast.ForecastCacheRepository;
import boats.log.shippingforecast.forecast.ForecastFetcher;
import boats.log.shippingforecast.forecast.ForecastProvider;
import boats.log.shippingforecast.forecast.GeoLocation;
import boats.log.shippingforecast.forecast.ShippingForecast;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

class ForecastSchedulerTest {

    /**
     * A zero-returning RandomGenerator gives the minimum jitter (MIN_JITTER_SECONDS = 120 s = 2 min)
     * and the minimum retry delay (MIN_RETRY_MINUTES = 15 min), making all timing
     * assertions exact and reproducible.
     * <p>
     * nextLong() is the only abstract method; all other methods (including nextInt) derive from it.
     */
    private static final RandomGenerator ZERO_RANDOM = () -> 0L;

    /** 2-minute jitter produced by ZERO_RANDOM. */
    private static final Duration JITTER = Duration.ofSeconds(120);

    // --- schedule: normal fetch ---

    @Test
    void checkAndFetch_fetchesAtJitteredTime() {
        LocalTime updateTime = LocalTime.of(6, 0);
        // Current time is exactly at updateTime + 2 min jitter — the first eligible moment.
        Clock clock = fixedClockAt(LocalDate.of(2024, 1, 1), updateTime.plusMinutes(2));
        RecordingFetcher fetcher = new RecordingFetcher("content");
        RecordingCacheRepository cache = new RecordingCacheRepository();

        scheduler(clock, fetcher, cache, new StubProvider("https://example.com", List.of(updateTime)))
                .checkAndFetch();

        assertThat(fetcher.fetchedUrls).containsExactly("https://example.com");
        assertThat(cache.savedEntries).hasSize(1);
    }

    @Test
    void checkAndFetch_skipsBeforeJitteredTime() {
        LocalTime updateTime = LocalTime.of(6, 0);
        // 1 min after updateTime — jitter requires 2 min, so too early.
        Clock clock = fixedClockAt(LocalDate.of(2024, 1, 1), updateTime.plusMinutes(1));
        RecordingFetcher fetcher = new RecordingFetcher("content");

        scheduler(clock, fetcher, new RecordingCacheRepository(), new StubProvider("https://example.com", List.of(updateTime)))
                .checkAndFetch();

        assertThat(fetcher.fetchedUrls).isEmpty();
    }

    @Test
    void checkAndFetch_skipsWhenNoUpdateTimeMatches() {
        LocalTime updateTime = LocalTime.of(6, 0);
        // Current time is well past the catch-up window for the only update time.
        Clock clock = fixedClockAt(LocalDate.of(2024, 1, 1), LocalTime.of(9, 0));
        RecordingFetcher fetcher = new RecordingFetcher("content");

        scheduler(clock, fetcher, new RecordingCacheRepository(), new StubProvider("https://example.com", List.of(updateTime)))
                .checkAndFetch();

        assertThat(fetcher.fetchedUrls).isEmpty();
    }

    @Test
    void checkAndFetch_fetchesWithinCatchUpWindow() {
        LocalTime updateTime = LocalTime.of(6, 0);
        // 20 min after updateTime — inside the 30-min catch-up window (jitter = 2 min, window ends at +32 min).
        Clock clock = fixedClockAt(LocalDate.of(2024, 1, 1), updateTime.plusMinutes(20));
        RecordingFetcher fetcher = new RecordingFetcher("content");

        scheduler(clock, fetcher, new RecordingCacheRepository(), new StubProvider("https://example.com", List.of(updateTime)))
                .checkAndFetch();

        assertThat(fetcher.fetchedUrls).containsExactly("https://example.com");
    }

    @Test
    void checkAndFetch_skipsPastCatchUpWindow() {
        LocalTime updateTime = LocalTime.of(6, 0);
        // 35 min after updateTime — past the window (jitter = 2 min, 30-min window ends at +32 min).
        Clock clock = fixedClockAt(LocalDate.of(2024, 1, 1), updateTime.plusMinutes(35));
        RecordingFetcher fetcher = new RecordingFetcher("content");

        scheduler(clock, fetcher, new RecordingCacheRepository(), new StubProvider("https://example.com", List.of(updateTime)))
                .checkAndFetch();

        assertThat(fetcher.fetchedUrls).isEmpty();
    }

    @Test
    void checkAndFetch_skipsWhenCacheIsNewerThanLastUpdate() {
        LocalTime updateTime = LocalTime.of(6, 0);
        Instant updateInstant = LocalDate.of(2024, 1, 1).atTime(updateTime).toInstant(ZoneOffset.UTC);
        Instant fetchedAfterUpdate = updateInstant.plusSeconds(30);
        Clock clock = Clock.fixed(updateInstant.plus(JITTER), ZoneOffset.UTC);
        RecordingFetcher fetcher = new RecordingFetcher("content");
        RecordingCacheRepository cache = new RecordingCacheRepository();
        cache.preset("https://example.com", "cached", fetchedAfterUpdate);

        scheduler(clock, fetcher, cache, new StubProvider("https://example.com", List.of(updateTime)))
                .checkAndFetch();

        assertThat(fetcher.fetchedUrls).isEmpty();
    }

    @Test
    void checkAndFetch_fetchesMultipleProvidersWhenBothDue() {
        LocalTime updateTime = LocalTime.of(6, 0);
        Clock clock = fixedClockAt(LocalDate.of(2024, 1, 1), updateTime.plusMinutes(2));
        RecordingFetcher fetcher = new RecordingFetcher("content");
        RecordingCacheRepository cache = new RecordingCacheRepository();
        ForecastScheduler scheduler = new ForecastScheduler(
                List.of(
                        new StubProvider("https://a.com", List.of(updateTime)),
                        new StubProvider("https://b.com", List.of(updateTime))
                ),
                fetcher, cache, clock, ZERO_RANDOM
        );

        scheduler.checkAndFetch();

        assertThat(fetcher.fetchedUrls).containsExactlyInAnyOrder("https://a.com", "https://b.com");
    }

    // --- freshness / retry ---

    @Test
    void checkAndFetch_suppressesNormalScheduleWhileRetryIsPending() {
        LocalTime updateTime = LocalTime.of(6, 0);
        Instant updateInstant = LocalDate.of(2024, 1, 1).atTime(updateTime).toInstant(ZoneOffset.UTC);
        Instant firstFetch = updateInstant.plus(JITTER);

        MutableClock clock = new MutableClock(Clock.fixed(firstFetch, ZoneOffset.UTC));
        RecordingFetcher fetcher = new RecordingFetcher("content");
        RecordingCacheRepository cache = new RecordingCacheRepository();
        StubProvider staleProvider = new StubProvider("https://example.com", List.of(updateTime), false);
        ForecastScheduler scheduler = new ForecastScheduler(List.of(staleProvider), fetcher, cache, clock, ZERO_RANDOM);

        // First tick: fetches, gets stale content — retry scheduled 15 min later.
        scheduler.checkAndFetch();
        assertThat(fetcher.fetchedUrls).hasSize(1);

        // Subsequent ticks within the catch-up window: normal schedule suppressed by pending retry.
        clock.set(Clock.fixed(firstFetch.plusSeconds(60), ZoneOffset.UTC));
        scheduler.checkAndFetch();
        clock.set(Clock.fixed(firstFetch.plusSeconds(120), ZoneOffset.UTC));
        scheduler.checkAndFetch();

        assertThat(fetcher.fetchedUrls).hasSize(1); // no additional fetches
        assertThat(cache.savedEntries).isEmpty();
    }

    @Test
    void checkAndFetch_fetchesWhenRetryIsDue() {
        LocalTime updateTime = LocalTime.of(6, 0);
        Instant updateInstant = LocalDate.of(2024, 1, 1).atTime(updateTime).toInstant(ZoneOffset.UTC);
        Instant firstFetch = updateInstant.plus(JITTER);
        // ZERO_RANDOM gives minimum retry delay of 15 min.
        Instant retryTime = firstFetch.plus(Duration.ofMinutes(15));

        MutableClock clock = new MutableClock(Clock.fixed(firstFetch, ZoneOffset.UTC));
        RecordingFetcher fetcher = new RecordingFetcher("content");
        RecordingCacheRepository cache = new RecordingCacheRepository();

        // First attempt: stale
        ForecastScheduler scheduler = new ForecastScheduler(
                List.of(new StubProvider("https://example.com", List.of(updateTime), false)),
                fetcher, cache, clock, ZERO_RANDOM
        );
        scheduler.checkAndFetch();
        assertThat(fetcher.fetchedUrls).hasSize(1);
        assertThat(cache.savedEntries).isEmpty();

        // Advance to exactly the retry time — should fetch again.
        // Switch to a fresh provider (fresh content now available).
        ForecastScheduler retryScheduler = new ForecastScheduler(
                List.of(new StubProvider("https://example.com", List.of(updateTime), true)),
                fetcher, cache, Clock.fixed(retryTime, ZoneOffset.UTC), ZERO_RANDOM
        );
        // Cache still empty → normal schedule window also fires, simulating the retry scenario.
        retryScheduler.checkAndFetch();

        assertThat(fetcher.fetchedUrls).hasSize(2);
        assertThat(cache.savedEntries).hasSize(1);
    }

    @Test
    void checkAndFetch_clearsRetryAfterFreshFetch() {
        LocalTime updateTime = LocalTime.of(6, 0);
        Instant updateInstant = LocalDate.of(2024, 1, 1).atTime(updateTime).toInstant(ZoneOffset.UTC);
        Instant firstFetch = updateInstant.plus(JITTER);
        Instant retryTime = firstFetch.plus(Duration.ofMinutes(15));

        MutableClock clock = new MutableClock(Clock.fixed(firstFetch, ZoneOffset.UTC));
        RecordingFetcher fetcher = new RecordingFetcher("content");
        RecordingCacheRepository cache = new RecordingCacheRepository();

        // Stale provider for the first fetch; fresh for the retry.
        StubProvider staleProvider = new StubProvider("https://example.com", List.of(updateTime), false);
        StubProvider freshProvider = new StubProvider("https://example.com", List.of(updateTime), true);

        ForecastScheduler staleScheduler = new ForecastScheduler(
                List.of(staleProvider), fetcher, cache, clock, ZERO_RANDOM
        );
        staleScheduler.checkAndFetch();
        assertThat(cache.savedEntries).isEmpty();

        // Advance to retry time with fresh content available.
        clock.set(Clock.fixed(retryTime, ZoneOffset.UTC));
        ForecastScheduler freshScheduler = new ForecastScheduler(
                List.of(freshProvider), fetcher, cache, clock, ZERO_RANDOM
        );
        freshScheduler.checkAndFetch();

        assertThat(cache.savedEntries).hasSize(1);

        // After a fresh fetch the cache is newer than the last update, so no further fetch fires.
        clock.set(Clock.fixed(retryTime.plusSeconds(60), ZoneOffset.UTC));
        freshScheduler.checkAndFetch();

        assertThat(fetcher.fetchedUrls).hasSize(2); // only the two fetches above
    }

    @Test
    void checkAndFetch_setsNewRetryWhenContentIsStillStaleOnRetry() {
        LocalTime updateTime = LocalTime.of(6, 0);
        Instant updateInstant = LocalDate.of(2024, 1, 1).atTime(updateTime).toInstant(ZoneOffset.UTC);
        Instant firstFetch = updateInstant.plus(JITTER);
        Instant retryTime = firstFetch.plus(Duration.ofMinutes(15));

        MutableClock clock = new MutableClock(Clock.fixed(firstFetch, ZoneOffset.UTC));
        RecordingFetcher fetcher = new RecordingFetcher("content");
        RecordingCacheRepository cache = new RecordingCacheRepository();
        StubProvider alwaysStaleProvider = new StubProvider("https://example.com", List.of(updateTime), false);
        ForecastScheduler scheduler = new ForecastScheduler(
                List.of(alwaysStaleProvider), fetcher, cache, clock, ZERO_RANDOM
        );

        // First fetch: stale → retry at firstFetch + 15 min.
        scheduler.checkAndFetch();
        assertThat(fetcher.fetchedUrls).hasSize(1);

        // Advance to first retry: still stale → next retry at retryTime + 15 min.
        clock.set(Clock.fixed(retryTime, ZoneOffset.UTC));
        scheduler.checkAndFetch();
        assertThat(fetcher.fetchedUrls).hasSize(2);

        // No fetch between retryTime and retryTime + 15 min.
        clock.set(Clock.fixed(retryTime.plusSeconds(60), ZoneOffset.UTC));
        scheduler.checkAndFetch();
        assertThat(fetcher.fetchedUrls).hasSize(2);

        assertThat(cache.savedEntries).isEmpty();
    }

    // --- startup fetch ---

    @Test
    void fetchOnStartup_fetchesWhenCacheIsEmpty() {
        LocalTime updateTime = LocalTime.of(6, 0);
        // The current time is well past the catch-up window — checkAndFetch would skip this.
        Clock clock = fixedClockAt(LocalDate.of(2024, 1, 1), LocalTime.of(9, 0));
        RecordingFetcher fetcher = new RecordingFetcher("content");
        RecordingCacheRepository cache = new RecordingCacheRepository();

        scheduler(clock, fetcher, cache, new StubProvider("https://example.com", List.of(updateTime)))
                .fetchOnStartup();

        assertThat(fetcher.fetchedUrls).containsExactly("https://example.com");
        assertThat(cache.savedEntries).hasSize(1);
    }

    @Test
    void fetchOnStartup_skipsWhenCacheIsAlreadyFresh() {
        LocalTime updateTime = LocalTime.of(6, 0);
        Instant updateInstant = LocalDate.of(2024, 1, 1).atTime(updateTime).toInstant(ZoneOffset.UTC);
        Instant fetchedAfterUpdate = updateInstant.plusSeconds(30);
        Clock clock = Clock.fixed(updateInstant.plus(Duration.ofHours(3)), ZoneOffset.UTC);
        RecordingFetcher fetcher = new RecordingFetcher("content");
        RecordingCacheRepository cache = new RecordingCacheRepository();
        cache.preset("https://example.com", "cached", fetchedAfterUpdate);

        scheduler(clock, fetcher, cache, new StubProvider("https://example.com", List.of(updateTime)))
                .fetchOnStartup();

        assertThat(fetcher.fetchedUrls).isEmpty();
    }

    @Test
    void fetchOnStartup_schedulesRetryWhenContentIsStale() {
        LocalTime updateTime = LocalTime.of(6, 0);
        Clock clock = fixedClockAt(LocalDate.of(2024, 1, 1), LocalTime.of(9, 0));
        RecordingFetcher fetcher = new RecordingFetcher("content");
        RecordingCacheRepository cache = new RecordingCacheRepository();

        scheduler(clock, fetcher, cache, new StubProvider("https://example.com", List.of(updateTime), false))
                .fetchOnStartup();

        // Fetched but not cached — stale content triggers a retry.
        assertThat(fetcher.fetchedUrls).containsExactly("https://example.com");
        assertThat(cache.savedEntries).isEmpty();
    }

    // --- helpers ---

    private static Clock fixedClockAt(LocalDate date, LocalTime time) {
        return Clock.fixed(date.atTime(time).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    private static ForecastScheduler scheduler(Clock clock, ForecastFetcher fetcher,
                                               ForecastCacheRepository cache,
                                               ForecastProvider... providers) {
        return new ForecastScheduler(List.of(providers), fetcher, cache, clock, ZERO_RANDOM);
    }

    // --- test doubles ---

    private static class StubProvider implements ForecastProvider {
        private final String url;
        private final List<LocalTime> updateTimes;
        private final boolean fresh;

        StubProvider(String url, List<LocalTime> updateTimes) {
            this(url, updateTimes, true);
        }

        StubProvider(String url, List<LocalTime> updateTimes, boolean fresh) {
            this.url = url;
            this.updateTimes = updateTimes;
            this.fresh = fresh;
        }

        @Override public String name() { return "Stub"; }
        @Override public String description() { return ""; }
        @Override public String url() { return url; }
        @Override public List<LocalTime> updateTimes() { return updateTimes; }
        @Override public List<GeoLocation> geoLocations() { return List.of(); }
        @Override public List<ShippingForecast> parse(String pageContent) { return List.of(); }
        @Override public boolean isFresh(String content, Instant expectedAfter) { return fresh; }
    }

    private static class RecordingFetcher implements ForecastFetcher {
        final List<String> fetchedUrls = new ArrayList<>();
        private final String response;

        RecordingFetcher(String response) { this.response = response; }

        @Override
        public String fetch(String url) {
            fetchedUrls.add(url);
            return response;
        }
    }

    private static class RecordingCacheRepository implements ForecastCacheRepository {
        final List<ForecastCache> savedEntries = new ArrayList<>();
        private final Map<String, ForecastCache> store = new HashMap<>();

        void preset(String url, String content, Instant fetchedAt) {
            store.put(url, new ForecastCache(url, content, fetchedAt));
        }

        @Override
        public void save(String url, String content, Instant fetchedAt) {
            ForecastCache entry = new ForecastCache(url, content, fetchedAt);
            savedEntries.add(entry);
            store.put(url, entry);
        }

        @Override
        public Optional<ForecastCache> findByUrl(String url) {
            return Optional.ofNullable(store.get(url));
        }
    }

    /** Clock whose delegate can be replaced between scheduler ticks. */
    private static class MutableClock extends Clock {
        private Clock delegate;

        MutableClock(Clock initial) { this.delegate = initial; }

        void set(Clock next) { this.delegate = next; }

        @Override public ZoneId getZone() { return delegate.getZone(); }
        @Override public Clock withZone(ZoneId zone) { return delegate.withZone(zone); }
        @Override public Instant instant() { return delegate.instant(); }
    }
}
