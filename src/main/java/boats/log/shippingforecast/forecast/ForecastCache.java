package boats.log.shippingforecast.forecast;

import java.time.Instant;

/** The latest fetched page content for a single forecast provider URL. */
public record ForecastCache(String url, String content, Instant fetchedAt) {}
