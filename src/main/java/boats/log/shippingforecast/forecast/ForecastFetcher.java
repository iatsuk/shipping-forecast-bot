package boats.log.shippingforecast.forecast;

import java.io.IOException;

/**
 * Fetches raw page content from a URL.
 * Kept separate from {@link ForecastProvider} so HTTP concerns evolve independently
 * from provider-specific parsing and scheduling logic.
 */
public interface ForecastFetcher {

    String fetch(String url) throws IOException;
}
