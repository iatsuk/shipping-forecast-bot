package boats.log.shippingforecast.forecast.provider;

import boats.log.shippingforecast.forecast.GeoLocation;
import boats.log.shippingforecast.forecast.ShippingForecast;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetOfficeForecastProviderTest {

    private final MetOfficeForecastProvider provider = new MetOfficeForecastProvider();

    // --- metadata ---

    @Test
    void updateTimes_areFourDailyUtcTimes() {
        assertThat(provider.updateTimes()).containsExactlyInAnyOrder(
                LocalTime.of(5,  0),
                LocalTime.of(11, 0),
                LocalTime.of(17, 0),
                LocalTime.of(23, 0)
        );
    }

    @Test
    void publishingZone_isUtc() {
        assertThat(provider.publishingZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void geoLocations_coversAllThirtyOneAreas() {
        List<GeoLocation> locations = provider.geoLocations();
        assertThat(locations).hasSize(31);
        assertThat(locations).extracting(GeoLocation::name).containsExactly(
                "Viking", "North Utsire", "South Utsire", "Forties",
                "Cromarty", "Forth", "Tyne", "Dogger", "Fisher", "German Bight",
                "Humber", "Thames", "Dover", "Wight", "Portland",
                "Plymouth", "Biscay", "Trafalgar", "FitzRoy",
                "Sole", "Lundy", "Fastnet", "Irish Sea", "Shannon",
                "Rockall", "Malin", "Hebrides", "Bailey",
                "Fair Isle", "Faeroes", "Southeast Iceland"
        );
    }

    // --- isFresh ---

    @Test
    void isFresh_returnsTrueWhenPublishedTimestampIsAfterExpected() {
        String content = pageWithDatetime("2026-03-21T17:25:00Z");
        Instant expectedAfter = Instant.parse("2026-03-21T17:00:00Z");

        assertThat(provider.isFresh(content, expectedAfter)).isTrue();
    }

    @Test
    void isFresh_returnsFalseWhenPublishedTimestampIsBeforeExpected() {
        // Page still shows the 11:00 UTC bulletin when the 17:00 UTC update was expected.
        String content = pageWithDatetime("2026-03-21T11:05:00Z");
        Instant expectedAfter = Instant.parse("2026-03-21T17:00:00Z");

        assertThat(provider.isFresh(content, expectedAfter)).isFalse();
    }

    @Test
    void isFresh_returnsTrueWhenPublishedTimestampEqualsExpected() {
        String content = pageWithDatetime("2026-03-21T17:00:00Z");
        Instant expectedAfter = Instant.parse("2026-03-21T17:00:00Z");

        assertThat(provider.isFresh(content, expectedAfter)).isTrue();
    }

    @Test
    void isFresh_returnsTrueWhenTimestampElementAbsent() {
        // Fail-open: if the page structure changes and #sea-forecast-time is missing,
        // assume content is fresh to avoid infinite retry loops.
        assertThat(provider.isFresh("<html><body>No timestamp here</body></html>",
                Instant.now())).isTrue();
    }

    // --- parse ---

    @Test
    void parse_returnsOneForecastPerGeoLocation() {
        assertThat(provider.parse(sampleBulletinHtml())).hasSameSizeAs(provider.geoLocations());
    }

    @Test
    void parse_forecastLocationsMatchGeoLocations() {
        List<ShippingForecast> forecasts = provider.parse(sampleBulletinHtml());

        assertThat(forecasts)
                .extracting(f -> f.location().name())
                .containsExactlyElementsOf(
                        provider.geoLocations().stream().map(GeoLocation::name).toList()
                );
    }

    @Test
    void parse_extractsForecastFieldsFromDefinitionList() {
        List<ShippingForecast> forecasts = provider.parse(sampleBulletinHtml());

        ShippingForecast viking = forecastFor(forecasts, "Viking");
        assertThat(viking.text()).contains("Wind: Southwesterly 4 or 5.");
        assertThat(viking.text()).contains("Sea state: Moderate or rough.");
        assertThat(viking.text()).contains("Weather: Rain.");
        assertThat(viking.text()).contains("Visibility: Good, occasionally poor.");
    }

    @Test
    void parse_eachAreaHasItsOwnForecast() {
        // Areas are now individual sections — Viking and North Utsire must have different text.
        List<ShippingForecast> forecasts = provider.parse(sampleBulletinHtml());

        ShippingForecast viking = forecastFor(forecasts, "Viking");
        ShippingForecast northUtsire = forecastFor(forecasts, "North Utsire");

        assertThat(viking.text()).isNotEqualTo(northUtsire.text());
        assertThat(viking.text()).contains("Southwesterly 4 or 5.");
        assertThat(northUtsire.text()).contains("Westerly 5 to 7.");
    }

    @Test
    void parse_prependsIssuedLineToEachNonEmptyArea() {
        List<ShippingForecast> forecasts = provider.parse(sampleBulletinHtml());

        for (ShippingForecast forecast : forecasts) {
            if (!forecast.text().isEmpty()) {
                assertThat(forecast.text()).startsWith("Met Office UK\nIssued: 17:25 (UTC) on Sat 21 Mar 2026");
            }
        }
    }

    @Test
    void parse_doesNotPrependIssuedLineWhenTimestampElementAbsent() {
        String htmlNoTimestamp = sampleBulletinHtml()
                .replace("<time datetime=\"2026-03-21T17:25:00Z\">17:25 (UTC) on Sat 21 Mar 2026</time>", "");

        List<ShippingForecast> forecasts = provider.parse(htmlNoTimestamp);

        ShippingForecast viking = forecastFor(forecasts, "Viking");
        assertThat(viking.text()).doesNotContain("Issued:");
        assertThat(viking.text()).contains("Southwesterly 4 or 5.");
    }

    @Test
    void parse_includesGaleWarningWhenPresent() {
        List<ShippingForecast> forecasts = provider.parse(sampleBulletinHtml());

        ShippingForecast bailey = forecastFor(forecasts, "Bailey");
        assertThat(bailey.text()).contains("⚠ Gale warning: Northwesterly gale force 8 imminent");
    }

    @Test
    void parse_emptyTextForMissingArea() {
        // An area absent from the page must yield an empty forecast, not a missing entry.
        // Removing the <h2> causes the parser to skip this section (null h2 guard).
        String htmlWithoutFairIsle = sampleBulletinHtml()
                .replace("<h2 class=\"card-name\">Fair Isle</h2>", "");

        List<ShippingForecast> forecasts = provider.parse(htmlWithoutFairIsle);

        ShippingForecast fairIsle = forecastFor(forecasts, "Fair Isle");
        assertThat(fairIsle.text()).isEmpty();
    }

    // --- helpers ---

    private ShippingForecast forecastFor(List<ShippingForecast> forecasts, String areaName) {
        return forecasts.stream()
                .filter(f -> f.location().name().equals(areaName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No forecast found for area: " + areaName));
    }

    private String pageWithDatetime(String datetime) {
        return "<html><body>"
                + "<div id=\"sea-forecast-time\">"
                + "<p>Issued at: <time datetime=\"" + datetime + "\">17:25 (UTC) on Sat 21 Mar 2026</time>.</p>"
                + "</div>"
                + "</body></html>";
    }

    /**
     * Minimal HTML mirroring the non-print Met Office page structure. Contains three areas:
     * Viking and North Utsire as plain sections, and Bailey with an active gale warning.
     * All other GeoLocations are intentionally absent so the parser returns empty text for them.
     */
    private String sampleBulletinHtml() {
        return "<html><body>"
                + "<div id=\"sea-forecast-time\">"
                + "<p>Issued at: <time datetime=\"2026-03-21T17:25:00Z\">17:25 (UTC) on Sat 21 Mar 2026</time>.</p>"
                + "</div>"
                + "<div id=\"shipping-forecast-areas\" class=\"cards\">"

                + "<section id=\"viking\">"
                + "<h2 class=\"card-name\">Viking</h2>"
                + "<div class=\"card-content\">"
                + "<div class=\"forecast-info\"><dl>"
                + "<dt>Wind</dt><dd>Southwesterly 4 or 5.</dd>"
                + "<dt>Sea state</dt><dd>Moderate or rough.</dd>"
                + "<dt>Weather</dt><dd>Rain.</dd>"
                + "<dt>Visibility</dt><dd>Good, occasionally poor.</dd>"
                + "</dl></div>"
                + "</div>"
                + "</section>"

                + "<section id=\"northutsire\">"
                + "<h2 class=\"card-name\">North Utsire</h2>"
                + "<div class=\"card-content\">"
                + "<div class=\"forecast-info\"><dl>"
                + "<dt>Wind</dt><dd>Westerly 5 to 7.</dd>"
                + "<dt>Sea state</dt><dd>Rough or very rough.</dd>"
                + "<dt>Weather</dt><dd>Rain.</dd>"
                + "<dt>Visibility</dt><dd>Moderate or poor.</dd>"
                + "</dl></div>"
                + "</div>"
                + "</section>"

                + "<section id=\"bailey\">"
                + "<h2 class=\"card-name warning\">Bailey</h2>"
                + "<div class=\"card-content\">"
                + "<dl class=\"warning-info\"><dt>Gale warning</dt><dd>"
                + "<div class=\"forecast-issue-time\">Issued: <time datetime=\"2026-03-21T15:55:00Z\">15:55 UTC</time></div>"
                + "<p>Northwesterly gale force 8 imminent</p>"
                + "</dd></dl>"
                + "<div class=\"forecast-info\"><dl>"
                + "<dt>Wind</dt><dd>Northwesterly 7 to severe gale 9.</dd>"
                + "<dt>Sea state</dt><dd>High.</dd>"
                + "<dt>Weather</dt><dd>Rain.</dd>"
                + "<dt>Visibility</dt><dd>Poor.</dd>"
                + "</dl></div>"
                + "</div>"
                + "</section>"

                + "<section id=\"fairisle\">"
                + "<h2 class=\"card-name\">Fair Isle</h2>"
                + "<div class=\"card-content\">"
                + "<div class=\"forecast-info\"><dl>"
                + "<dt>Wind</dt><dd>Northerly 4 or 5.</dd>"
                + "<dt>Sea state</dt><dd>Moderate or rough.</dd>"
                + "<dt>Weather</dt><dd>Showers.</dd>"
                + "<dt>Visibility</dt><dd>Good.</dd>"
                + "</dl></div>"
                + "</div>"
                + "</section>"

                + "</div>"
                + "</body></html>";
    }
}
