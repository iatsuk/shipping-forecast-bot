package boats.log.shippingforecast.forecast;

import java.time.Instant;

/**
 * Cached binary image for a forecast provider (e.g. an area map).
 */
public record ImageCache(String url, byte[] data, Instant fetchedAt) {}
