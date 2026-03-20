package boats.log.shippingforecast.forecast.scheduler;

import boats.log.shippingforecast.forecast.ForecastCacheRepository;
import boats.log.shippingforecast.forecast.ForecastFetcher;
import boats.log.shippingforecast.forecast.ForecastProvider;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

/**
 * Checks each registered {@link ForecastProvider} once per minute and fetches its page
 * when new content is due. Three behaviours are built in:
 *
 * <ul>
 *   <li><b>Catch-up window</b>: instead of matching only the exact scheduled minute, the
 *       scheduler fetches any time within {@value #CATCH_UP_WINDOW_MINUTES} minutes after
 *       the jitter-adjusted fetch time, provided the cache still predates the last scheduled
 *       update. This recovers gracefully from brief application downtime.</li>
 *
 *   <li><b>Freshness retry</b>: after a successful HTTP fetch the provider is asked whether
 *       the content is actually new via {@link ForecastProvider#isFresh}. Providers with
 *       up-to-three-hour publication delays may return stale HTML even after their declared
 *       update time. When that happens the cache is left unchanged and a retry is scheduled
 *       {@value #MIN_RETRY_MINUTES}–{@value #MAX_RETRY_MINUTES} minutes later.</li>
 *
 *   <li><b>Request jitter</b>: a random offset of {@value #MIN_JITTER_SECONDS}–{@value
 *       #MAX_JITTER_SECONDS} seconds is added to each provider's fetch time at startup.
 *       This avoids the obvious pattern of requests landing on round-clock minutes (00, 05,
 *       10 …), reducing the risk of the application being identified as a bot.</li>
 * </ul>
 */
@Component
public class ForecastScheduler implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ForecastScheduler.class);

    private static final int CATCH_UP_WINDOW_MINUTES = 30;
    private static final int MIN_JITTER_SECONDS = 120; // 2 minutes
    private static final int MAX_JITTER_SECONDS = 240; // 4 minutes
    private static final int MIN_RETRY_MINUTES = 15;
    private static final int MAX_RETRY_MINUTES = 30;

    private final List<ForecastProvider> providers;
    private final ForecastFetcher fetcher;
    private final ForecastCacheRepository cacheRepository;
    private final ForecastDispatcher dispatcher;
    private final Clock clock;
    private final RandomGenerator random;

    /** Fixed per-provider jitter computed once at startup. */
    private final Map<String, Duration> fetchJitters;

    /**
     * Tracks URLs that returned stale content and the earliest instant at which a retry
     * should be attempted.
     */
    private final Map<String, Instant> pendingRetries = new ConcurrentHashMap<>();

    public ForecastScheduler(
            List<ForecastProvider> providers,
            ForecastFetcher fetcher,
            ForecastCacheRepository cacheRepository,
            ForecastDispatcher dispatcher,
            Clock clock,
            RandomGenerator random
    ) {
        this.providers = providers;
        this.fetcher = fetcher;
        this.cacheRepository = cacheRepository;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.random = random;
        this.fetchJitters = computeJitters(providers, random);
        logRegisteredProviders();
    }

    private void logRegisteredProviders() {
        log.info("ForecastScheduler started with {} provider(s)", providers.size());
        for (ForecastProvider provider : providers) {
            Duration jitter = fetchJitters.get(provider.url());
            log.info("  Provider '{}': update times {}, jitter +{}s, url: {}",
                    provider.name(), provider.updateTimes(), jitter.toSeconds(), provider.url());
        }
    }

    @Override
    public void afterPropertiesSet() {
        fetchOnStartup();
    }

    /**
     * Fetches any provider whose cache is absent or predates the last scheduled update.
     * This ensures fresh data is available immediately on cold start or after prolonged
     * downtime, without waiting for the next scheduled catch-up window.
     */
    public void fetchOnStartup() {
        Instant now = Instant.now(clock);
        for (ForecastProvider provider : providers) {
            Instant lastUpdate = lastScheduledUpdateBefore(provider, now);
            if (cacheIsOlderThan(provider.url(), lastUpdate)) {
                log.info("No fresh cache for '{}' on startup, fetching immediately", provider.name());
                fetchAndCache(provider, now);
            }
        }
    }

    @Scheduled(cron = "0 * * * * *")
    public void checkAndFetch() {
        Instant now = Instant.now(clock);
        for (ForecastProvider provider : providers) {
            if (isDueForFetch(provider, now)) {
                fetchAndCache(provider, now);
            }
        }
    }

    private boolean isDueForFetch(ForecastProvider provider, Instant now) {
        Instant retryAt = pendingRetries.get(provider.url());
        if (retryAt != null) {
            // While a retry is pending, suppress normal schedule checks entirely.
            // Without this, the catch-up window would re-trigger every minute,
            // hammering the provider until the retry window closes.
            return !now.isBefore(retryAt);
        }
        return isScheduledUpdateDue(provider, now);
    }

    /**
     * Returns true when the current instant falls inside the catch-up window for any of
     * the provider's declared update times, and the cache predates that update.
     *
     * <p>The window starts at {@code updateTime + jitter} and extends for
     * {@value #CATCH_UP_WINDOW_MINUTES} minutes, giving the application time to recover
     * after a brief outage without waiting for the next scheduled update.
     */
    private boolean isScheduledUpdateDue(ForecastProvider provider, Instant now) {
        Duration jitter = fetchJitters.get(provider.url());
        ZoneId zone = provider.publishingZone();
        return provider.updateTimes().stream().anyMatch(updateTime -> {
            Instant lastUpdate = mostRecentOccurrenceOf(updateTime, zone, now);
            Instant jitteredFetch = lastUpdate.plus(jitter);
            boolean inWindow = !now.isBefore(jitteredFetch)
                    && now.isBefore(jitteredFetch.plus(CATCH_UP_WINDOW_MINUTES, ChronoUnit.MINUTES));
            return inWindow && cacheIsOlderThan(provider.url(), lastUpdate);
        });
    }

    private void fetchAndCache(ForecastProvider provider, Instant now) {
        try {
            String content = fetcher.fetch(provider.url());
            Instant lastUpdate = lastScheduledUpdateBefore(provider, now);

            if (!provider.isFresh(content, lastUpdate)) {
                // Content has not been updated yet. Schedule a retry rather than caching stale data.
                Duration delay = randomRetryDelay();
                pendingRetries.put(provider.url(), now.plus(delay));
                log.info("Stale content from {}; retrying in {} min", provider.url(), delay.toMinutes());
                return;
            }

            pendingRetries.remove(provider.url());
            cacheRepository.save(provider.url(), content, now);
            log.info("Cached forecast from: {}", provider.url());
            dispatcher.dispatch(provider.parse(content));
        } catch (IOException e) {
            log.warn("Failed to fetch forecast from {}: {}", provider.url(), e.getMessage());
        }
    }

    /**
     * Returns the most recent instant at which {@code time} in {@code zone} occurred
     * relative to {@code now}. If today's occurrence is still in the future, returns
     * yesterday's occurrence instead. Using the provider's zone ensures DST transitions
     * are applied correctly when converting local times to instants.
     */
    private Instant mostRecentOccurrenceOf(LocalTime time, ZoneId zone, Instant now) {
        ZonedDateTime nowZoned = now.atZone(zone);
        ZonedDateTime todayOccurrence = nowZoned.toLocalDate().atTime(time).atZone(zone);
        ZonedDateTime occurrence = nowZoned.isBefore(todayOccurrence)
                ? todayOccurrence.minusDays(1)
                : todayOccurrence;
        return occurrence.toInstant();
    }

    private boolean cacheIsOlderThan(String url, Instant threshold) {
        return cacheRepository.findByUrl(url)
                .map(cache -> cache.fetchedAt().isBefore(threshold))
                .orElse(true);
    }

    /** Returns the most recent scheduled update instant across all of the provider's update times. */
    private Instant lastScheduledUpdateBefore(ForecastProvider provider, Instant now) {
        ZoneId zone = provider.publishingZone();
        return provider.updateTimes().stream()
                .map(t -> mostRecentOccurrenceOf(t, zone, now))
                .max(Instant::compareTo)
                .orElse(now);
    }

    private Duration randomRetryDelay() {
        int minutes = random.nextInt(MAX_RETRY_MINUTES - MIN_RETRY_MINUTES + 1) + MIN_RETRY_MINUTES;
        return Duration.ofMinutes(minutes);
    }

    private static Map<String, Duration> computeJitters(List<ForecastProvider> providers, RandomGenerator random) {
        Map<String, Duration> jitters = new HashMap<>();
        for (ForecastProvider provider : providers) {
            int seconds = random.nextInt(MAX_JITTER_SECONDS - MIN_JITTER_SECONDS + 1) + MIN_JITTER_SECONDS;
            jitters.put(provider.url(), Duration.ofSeconds(seconds));
        }
        return Map.copyOf(jitters);
    }
}
