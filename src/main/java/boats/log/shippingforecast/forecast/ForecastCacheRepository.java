package boats.log.shippingforecast.forecast;

import java.time.Instant;
import java.util.Optional;

/**
 * Stores the most recently fetched page content for each forecast provider URL.
 * Only the latest entry per URL is retained — saving again replaces the previous content.
 */
public interface ForecastCacheRepository {

    void save(String url, String content, Instant fetchedAt);

    Optional<ForecastCache> findByUrl(String url);
}
