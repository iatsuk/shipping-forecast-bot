package boats.log.shippingforecast.forecast;

import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/**
 * Forecast provider for the DWD (Deutscher Wetterdienst) North and Baltic Sea bulletin.
 *
 * <p>DWD publishes at approx. 00:15 and 12:15 CET (UTC+1), i.e. 23:15 and 11:15 UTC.
 * The page embeds a publication timestamp in the format "dd.MM.yyyy, HH:mm CET/CEST"
 * which is used by {@link #isFresh} to detect stale content.
 */
@Component
public class DwdForecastProvider implements ForecastProvider {

    private static final String URL =
            "https://www.dwd.de/EN/ourservices/seewetternordostseeen/seewetternordostsee.html";

    // 00:15 and 12:15 CET (UTC+1) expressed in UTC
    private static final List<LocalTime> UPDATE_TIMES = List.of(
            LocalTime.of(23, 15),
            LocalTime.of(11, 15)
    );

    // Matches: "19.03.2026, 12:30 CET" or "19.03.2026, 12:30 CEST"
    private static final Pattern TIMESTAMP_PATTERN =
            Pattern.compile("(\\d{2}\\.\\d{2}\\.\\d{4}),\\s*(\\d{2}:\\d{2})\\s*(CES?T)");

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    // ZoneOffset constants: CET = UTC+1, CEST = UTC+2
    private static final ZoneOffset CET  = ZoneOffset.ofHours(1);
    private static final ZoneOffset CEST = ZoneOffset.ofHours(2);

    static final List<GeoLocation> GEO_LOCATIONS = List.of(
            new GeoLocation("Southwestern North Sea",  54.5,  4.0),
            new GeoLocation("German Bight",            54.5,  7.5),
            new GeoLocation("Fischer",                 55.5,  5.5),
            new GeoLocation("Skagerrak",               58.0,  9.5),
            new GeoLocation("Kattegat",                56.5, 11.5),
            new GeoLocation("Belts and Sound",         55.5, 10.5),
            new GeoLocation("Western Baltic",          55.0, 13.0),
            new GeoLocation("Southern Baltic",         54.5, 17.0),
            new GeoLocation("Southeastern Baltic",     55.0, 22.0)
    );

    // Area names exactly as they appear in the bulletin (case-insensitive match used in parse)
    private static final List<String> AREA_NAMES = GEO_LOCATIONS.stream()
            .map(GeoLocation::name)
            .toList();

    @Override
    public String name() {
        return "DWD North and Baltic Sea";
    }

    @Override
    public String description() {
        return "Weather and sea bulletin for the North and Baltic Sea, "
                + "issued by DWD marine weather service Hamburg.";
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

    /**
     * Returns true if the bulletin embedded in {@code content} was published at or after
     * {@code expectedAfter}.
     *
     * <p>DWD sometimes serves cached HTML for several minutes after a declared update time.
     * Parsing the timestamp lets the scheduler detect and retry rather than cache stale content.
     * If no timestamp is found, freshness is assumed (fail-open) so a temporary page change
     * does not cause infinite retries.
     */
    @Override
    public boolean isFresh(String content, Instant expectedAfter) {
        Matcher m = TIMESTAMP_PATTERN.matcher(content);
        if (!m.find()) {
            return true;
        }
        LocalDate date = LocalDate.parse(m.group(1), DATE_FORMAT);
        LocalTime time = LocalTime.parse(m.group(2), TIME_FORMAT);
        ZoneOffset zone = "CEST".equals(m.group(3)) ? CEST : CET;
        Instant published = ZonedDateTime.of(date, time, zone).toInstant();
        return !published.isBefore(expectedAfter);
    }

    /**
     * Extracts one {@link ShippingForecast} per covered sea area from the raw HTML.
     *
     * <p>Each area's text runs from its heading (e.g. "Southwestern North Sea:") up to
     * the next area heading (or end of content). HTML tags are stripped before splitting
     * so the parser is insensitive to markup changes.
     */
    @Override
    public List<ShippingForecast> parse(String pageContent) {
        String text = stripHtml(pageContent);
        List<ShippingForecast> forecasts = new ArrayList<>();
        for (int i = 0; i < AREA_NAMES.size(); i++) {
            String area     = AREA_NAMES.get(i);
            String nextArea = (i + 1 < AREA_NAMES.size()) ? AREA_NAMES.get(i + 1) : null;
            String areaText = extractSection(text, area, nextArea);
            forecasts.add(new ShippingForecast(GEO_LOCATIONS.get(i), areaText));
        }
        return forecasts;
    }

    private String extractSection(String text, String areaName, String nextAreaName) {
        int start = indexOfIgnoreCase(text, areaName + ":", 0);
        if (start < 0) {
            return "";
        }
        int end = nextAreaName != null
                ? indexOfIgnoreCase(text, nextAreaName + ":", start + 1)
                : text.length();
        if (end < 0) {
            end = text.length();
        }
        return text.substring(start, end).strip();
    }

    private int indexOfIgnoreCase(String text, String search, int from) {
        return text.toLowerCase(Locale.ROOT).indexOf(search.toLowerCase(Locale.ROOT), from);
    }

    /** Strips HTML tags and decodes common HTML entities to produce plain text. */
    private String stripHtml(String html) {
        return html
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;",  "&")
                .replace("&lt;",   "<")
                .replace("&gt;",   ">")
                .replace("&auml;", "ä")
                .replace("&ouml;", "ö")
                .replace("&uuml;", "ü")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n[ \\t]+", "\n")
                .strip();
    }
}
