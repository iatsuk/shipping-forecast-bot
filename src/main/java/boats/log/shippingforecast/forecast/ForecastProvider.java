package boats.log.shippingforecast.forecast;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/**
 * Contract for a shipping forecast data provider.
 *
 * <p>Each provider knows where its forecast page lives, when updates are published,
 * which geographic areas it covers, and how to extract structured forecasts from
 * the raw page content. Fetching the page is a separate infrastructure concern and
 * is intentionally outside this interface.
 */
public interface ForecastProvider {

    /** Short, human-readable name of this provider (e.g. "BBC Shipping Forecast"). */
    String name();

    /** Brief description of the provider and its coverage. */
    String description();

    /** URL of the page that publishes the shipping forecast. */
    String url();

    /**
     * Times of day (UTC) at which this provider publishes a new forecast.
     * The list must not be empty.
     */
    List<LocalTime> updateTimes();

    /**
     * Geographic areas covered by this provider.
     * The list must not be empty and must be consistent with what {@link #parse} returns.
     */
    List<GeoLocation> geoLocations();

    /**
     * Returns true if {@code content} represents a forecast published after {@code expectedAfter}.
     *
     * <p>Providers that embed a publication timestamp in their page should override this method
     * to detect when the upstream page has not yet been refreshed despite the scheduled publication
     * time having passed.
     */
    boolean isFresh(String content, Instant expectedAfter);
    /**
     * Extracts structured forecasts from the raw page content.
     *
     * @param pageContent the raw HTML or text fetched from {@link #url()}
     * @return one {@link ShippingForecast} per covered area; never null, never empty
     */
    List<ShippingForecast> parse(String pageContent);
}
