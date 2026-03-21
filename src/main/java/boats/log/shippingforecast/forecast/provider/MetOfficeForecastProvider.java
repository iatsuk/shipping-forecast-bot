package boats.log.shippingforecast.forecast.provider;

import boats.log.shippingforecast.forecast.ForecastProvider;
import boats.log.shippingforecast.forecast.GeoLocation;
import boats.log.shippingforecast.forecast.ShippingForecast;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Forecast provider for the UK Met Office Shipping Forecast.
 *
 * <p>The Met Office issues the shipping forecast four times a day at 0500, 1100, 1700, and 2300 UTC,
 * covering 31 sea areas around the British Isles. Each area has its own {@code <section>} element
 * with an {@code <h2>} heading and a {@code <dl>} of Wind / Sea state / Weather / Visibility fields.
 * The issued timestamp is in a {@code <time datetime="...">} element with a machine-readable
 * ISO-8601 value, which is used directly for freshness detection.
 */
@Component
public class MetOfficeForecastProvider implements ForecastProvider {

    private static final String URL =
            "https://weather.metoffice.gov.uk/specialist-forecasts/coast-and-sea/shipping-forecast";

    private static final String MAP_IMAGE_URL =
            "https://weather.metoffice.gov.uk/binaries/content/gallery/metofficegovuk/images/guide/marine/sea-areas-map.jpg";

    private static final List<LocalTime> UPDATE_TIMES = List.of(
            LocalTime.of(5,  0),
            LocalTime.of(11, 0),
            LocalTime.of(17, 0),
            LocalTime.of(23, 0)
    );

    // All 31 individual sea areas with representative central coordinates (WGS-84)
    static final List<GeoLocation> GEO_LOCATIONS = List.of(
            new GeoLocation("Viking",            59.5,   2.5),
            new GeoLocation("North Utsire",      60.0,   5.0),
            new GeoLocation("South Utsire",      58.0,   5.0),
            new GeoLocation("Forties",           57.0,   2.0),
            new GeoLocation("Cromarty",          58.0,  -2.0),
            new GeoLocation("Forth",             56.0,  -2.0),
            new GeoLocation("Tyne",              55.0,  -1.0),
            new GeoLocation("Dogger",            55.0,   2.5),
            new GeoLocation("Fisher",            56.5,   6.0),
            new GeoLocation("German Bight",      54.5,   7.0),
            new GeoLocation("Humber",            53.5,   2.0),
            new GeoLocation("Thames",            52.5,   2.5),
            new GeoLocation("Dover",             51.0,   1.5),
            new GeoLocation("Wight",             50.0,  -0.5),
            new GeoLocation("Portland",          49.5,  -2.5),
            new GeoLocation("Plymouth",          49.5,  -5.0),
            new GeoLocation("Biscay",            45.0,  -4.0),
            new GeoLocation("Trafalgar",         37.0, -11.0),
            new GeoLocation("FitzRoy",           46.0, -10.0),
            new GeoLocation("Sole",              49.5, -10.0),
            new GeoLocation("Lundy",             51.0,  -5.0),
            new GeoLocation("Fastnet",           51.5,  -7.0),
            new GeoLocation("Irish Sea",         53.5,  -5.0),
            new GeoLocation("Shannon",           52.0, -12.0),
            new GeoLocation("Rockall",           56.0, -12.5),
            new GeoLocation("Malin",             56.0,  -8.0),
            new GeoLocation("Hebrides",          58.0,  -8.0),
            new GeoLocation("Bailey",            60.5, -13.0),
            new GeoLocation("Fair Isle",         60.0,  -2.0),
            new GeoLocation("Faeroes",           61.5,  -7.0),
            new GeoLocation("Southeast Iceland", 64.0, -13.0)
    );

    @Override
    public String name() {
        return "Met Office UK";
    }

    @Override
    public String description() {
        return "Shipping forecast for 31 sea areas around the British Isles, "
                + "issued by the Met Office on behalf of the Maritime and Coastguard Agency.\n"
                + "Updated four times daily at 0500, 1100, 1700, and 2300 UTC.";
    }

    @Override
    public String url() {
        return URL;
    }

    @Override
    public List<LocalTime> updateTimes() {
        return UPDATE_TIMES;
    }

    @Override
    public List<GeoLocation> geoLocations() {
        return GEO_LOCATIONS;
    }

    @Override
    public Optional<String> mapImageUrl() {
        return Optional.of(MAP_IMAGE_URL);
    }

    /**
     * Returns true if the page contains a timestamp at or after {@code expectedAfter}.
     *
     * <p>The issued time is read from the {@code datetime} attribute of the
     * {@code <time>} element inside {@code #sea-forecast-time}, which contains a
     * machine-readable ISO-8601 UTC instant. Fails open when the element is absent
     * so a page restructure does not cause infinite retries.
     */
    @Override
    public boolean isFresh(String content, Instant expectedAfter) {
        Element timeEl = Jsoup.parse(content).selectFirst("#sea-forecast-time time[datetime]");
        if (timeEl == null) {
            return true;
        }
        Instant published = Instant.parse(timeEl.attr("datetime"));
        return !published.isBefore(expectedAfter);
    }

    /**
     * Extracts structured forecasts from the raw HTML.
     *
     * <p>Each area is a {@code <section>} inside {@code #shipping-forecast-areas} with:
     * <ul>
     *   <li>an {@code <h2>} carrying the area name</li>
     *   <li>an optional {@code .warning-info} {@code <dl>} for active gale warnings</li>
     *   <li>a {@code .forecast-info} {@code <dl>} with Wind / Sea state / Weather / Visibility</li>
     * </ul>
     */
    @Override
    public List<ShippingForecast> parse(String pageContent) {
        Document doc = Jsoup.parse(pageContent);
        String issuedLine = extractIssuedLine(doc);

        Map<String, String> forecastByArea = new HashMap<>();

        for (Element section : doc.select("#shipping-forecast-areas section")) {
            Element h2 = section.selectFirst("h2");
            if (h2 == null) continue;
            String areaName = h2.text().trim();

            StringBuilder body = new StringBuilder();

            // Prepend active gale warning when present.
            Element warningP = section.selectFirst(".warning-info dd p");
            if (warningP != null && !warningP.text().isBlank()) {
                body.append("⚠ Gale warning: ").append(warningP.text().trim()).append("\n");
            }

            // Build forecast from Wind / Sea state / Weather / Visibility fields.
            Element dl = section.selectFirst(".forecast-info dl");
            if (dl != null) {
                for (Element dt : dl.select("dt")) {
                    Element dd = dt.nextElementSibling();
                    if (dd != null && dd.tagName().equals("dd")) {
                        if (!body.isEmpty()) body.append("\n");
                        body.append(dt.text().trim()).append(": ").append(dd.text().trim());
                    }
                }
            }

            forecastByArea.put(areaName.toLowerCase(Locale.ROOT),
                    buildForecastText(issuedLine, areaName, body.toString().strip()));
        }

        return GEO_LOCATIONS.stream()
                .map(loc -> new ShippingForecast(
                        loc,
                        forecastByArea.getOrDefault(loc.name().toLowerCase(Locale.ROOT), "")))
                .toList();
    }

    private String extractIssuedLine(Document doc) {
        // Use the human-readable text from the <time> element for display.
        Element timeEl = doc.selectFirst("#sea-forecast-time time");
        if (timeEl == null) return "";
        String text = timeEl.text().trim();
        return text.isBlank() ? "" : "Issued: " + text;
    }

    private String buildForecastText(String issuedLine, String heading, String body) {
        if (body.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(name());
        if (!issuedLine.isEmpty()) {
            sb.append("\n").append(issuedLine);
        }
        sb.append("\n\n").append(heading).append("\n").append(body);
        return sb.toString();
    }
}
