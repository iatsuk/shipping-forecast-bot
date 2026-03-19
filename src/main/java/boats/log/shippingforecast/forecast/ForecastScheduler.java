package boats.log.shippingforecast.forecast;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Checks each registered {@link ForecastProvider} once per minute. When the current
 * UTC minute matches one of a provider's declared update times — and the provider has
 * not already been fetched during that minute — the page is retrieved and persisted
 * via {@link ForecastCacheRepository}, replacing any previously stored content.
 */
@Component
public class ForecastScheduler {

    private static final Logger log = Logger.getLogger(ForecastScheduler.class.getName());

    private final List<ForecastProvider> providers;
    private final ForecastFetcher fetcher;
    private final ForecastCacheRepository cacheRepository;
    private final Clock clock;

    public ForecastScheduler(
            List<ForecastProvider> providers,
            ForecastFetcher fetcher,
            ForecastCacheRepository cacheRepository,
            Clock clock
    ) {
        this.providers = providers;
        this.fetcher = fetcher;
        this.cacheRepository = cacheRepository;
        this.clock = clock;
    }

    @Scheduled(cron = "0 * * * * *")
    public void checkAndFetch() {
        LocalTime currentMinute = LocalTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
        for (ForecastProvider provider : providers) {
            if (isDueForUpdate(provider, currentMinute)) {
                fetchAndCache(provider, currentMinute);
            }
        }
    }

    private boolean isDueForUpdate(ForecastProvider provider, LocalTime currentMinute) {
        return provider.updateTimes().stream()
                .map(t -> t.truncatedTo(ChronoUnit.MINUTES))
                .anyMatch(currentMinute::equals);
    }

    private void fetchAndCache(ForecastProvider provider, LocalTime currentMinute) {
        if (alreadyFetchedThisMinute(provider.url(), currentMinute)) {
            return;
        }
        try {
            String content = fetcher.fetch(provider.url());
            cacheRepository.save(provider.url(), content, Instant.now(clock));
            log.info("Cached forecast from: " + provider.url());
        } catch (IOException e) {
            log.warning("Failed to fetch forecast from " + provider.url() + ": " + e.getMessage());
        }
    }

    private boolean alreadyFetchedThisMinute(String url, LocalTime currentMinute) {
        Optional<ForecastCache> cached = cacheRepository.findByUrl(url);
        if (cached.isEmpty()) {
            return false;
        }
        LocalTime cachedMinute = cached.get().fetchedAt()
                .atZone(ZoneOffset.UTC)
                .toLocalTime()
                .truncatedTo(ChronoUnit.MINUTES);
        return cachedMinute.equals(currentMinute);
    }
}
