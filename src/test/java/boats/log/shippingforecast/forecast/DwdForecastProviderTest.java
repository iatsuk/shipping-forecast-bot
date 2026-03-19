package boats.log.shippingforecast.forecast;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DwdForecastProviderTest {

    private final DwdForecastProvider provider = new DwdForecastProvider();

    // --- metadata ---

    @Test
    void updateTimes_areInUtc() {
        // DWD publishes at 00:15 and 12:15 CET (UTC+1) → 23:15 and 11:15 UTC
        assertThat(provider.updateTimes())
                .containsExactlyInAnyOrder(LocalTime.of(23, 15), LocalTime.of(11, 15));
    }

    @Test
    void geoLocations_coversAllNineAreas() {
        List<GeoLocation> locations = provider.geoLocations();
        assertThat(locations).hasSize(9);
        assertThat(locations).extracting(GeoLocation::name).containsExactly(
                "Southwestern North Sea",
                "German Bight",
                "Fischer",
                "Skagerrak",
                "Kattegat",
                "Belts and Sound",
                "Western Baltic",
                "Southern Baltic",
                "Southeastern Baltic"
        );
    }

    // --- isFresh ---

    @Test
    void isFresh_returnsTrueWhenPublishedTimestampIsAfterExpected() {
        // Bulletin issued at 12:30 CET = 11:30 UTC; expected update was at 11:15 UTC
        String content = bulletinWith("19.03.2026, 12:30 CET");
        Instant expectedAfter = Instant.parse("2026-03-19T11:15:00Z");

        assertThat(provider.isFresh(content, expectedAfter)).isTrue();
    }

    @Test
    void isFresh_returnsFalseWhenPublishedTimestampIsBeforeExpected() {
        // Bulletin still shows the 00:30 CET issue (= 23:30 UTC previous day) when the
        // 12:15 CET update was expected
        String content = bulletinWith("19.03.2026, 00:30 CET");
        Instant expectedAfter = Instant.parse("2026-03-19T11:15:00Z");

        assertThat(provider.isFresh(content, expectedAfter)).isFalse();
    }

    @Test
    void isFresh_returnsTrueWhenPublishedTimestampEqualsExpected() {
        // Exactly on the boundary: published == expectedAfter should count as fresh
        String content = bulletinWith("19.03.2026, 12:15 CET");
        Instant expectedAfter = Instant.parse("2026-03-19T11:15:00Z");

        assertThat(provider.isFresh(content, expectedAfter)).isTrue();
    }

    @Test
    void isFresh_handlesCestTimezone() {
        // Summer time: 12:30 CEST = UTC+2 → 10:30 UTC; expected update was 10:15 UTC (= 12:15 CEST)
        String content = bulletinWith("19.07.2026, 12:30 CEST");
        Instant expectedAfter = Instant.parse("2026-07-19T10:15:00Z");

        assertThat(provider.isFresh(content, expectedAfter)).isTrue();
    }

    @Test
    void isFresh_returnsTrueWhenNoTimestampFound() {
        // Fail-open: if the timestamp format changes, assume content is fresh rather than
        // triggering infinite retries
        assertThat(provider.isFresh("<html><body>No timestamp here</body></html>",
                Instant.now())).isTrue();
    }

    // --- parse ---

    @Test
    void parse_returnsOneForecastPerGeoLocation() {
        List<ShippingForecast> forecasts = provider.parse(sampleBulletinHtml());

        assertThat(forecasts).hasSameSizeAs(provider.geoLocations());
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
    void parse_extractsTextForEachArea() {
        List<ShippingForecast> forecasts = provider.parse(sampleBulletinHtml());

        ShippingForecast swNorthSea = forecasts.get(0);
        assertThat(swNorthSea.location().name()).isEqualTo("Southwestern North Sea");
        assertThat(swNorthSea.text()).contains("southeasterly");
        assertThat(swNorthSea.text()).doesNotContain("German Bight");

        ShippingForecast germanBight = forecasts.get(1);
        assertThat(germanBight.location().name()).isEqualTo("German Bight");
        assertThat(germanBight.text()).contains("northerly");
    }

    @Test
    void parse_emptyTextForMissingArea() {
        // If the page does not contain a known area heading, the text should be empty (not null)
        String htmlWithoutSkagerrak = sampleBulletinHtml().replace("Skagerrak:", "REMOVED:");
        List<ShippingForecast> forecasts = provider.parse(htmlWithoutSkagerrak);

        ShippingForecast skagerrak = forecasts.stream()
                .filter(f -> f.location().name().equals("Skagerrak"))
                .findFirst().orElseThrow();
        assertThat(skagerrak.text()).isEmpty();
    }

    // --- helpers ---

    private String bulletinWith(String timestamp) {
        return "<p>Weather and sea bulletin for the North and Baltic Sea "
                + "issued by marine weather service Hamburg " + timestamp + "</p>";
    }

    /**
     * Minimal HTML that contains all nine area headings with representative forecast text.
     * Mirrors the actual DWD page structure closely enough to exercise the parser.
     */
    private String sampleBulletinHtml() {
        return "<html><body>"
                + "<p>Weather and sea bulletin for the North and Baltic Sea "
                + "issued by marine weather service Hamburg 19.03.2026, 12:30 CET</p>"
                + "<p><b>Southwestern North Sea:</b><br/>"
                + "<b>Thursday:</b><br/>wind: southeasterly winds about 3.<br/>"
                + "visibility/weather: good.<br/>sea: 0.5 meter.<br/></p>"
                + "<p><b>German Bight:</b><br/>"
                + "<b>Thursday:</b><br/>wind: light and variable, later northerly 3 to 4.<br/>"
                + "sea: 0.5 meter.<br/></p>"
                + "<p><b>Fischer:</b><br/>"
                + "<b>Thursday:</b><br/>wind: westerly 4.<br/>sea: 1.0 meter.<br/></p>"
                + "<p><b>Skagerrak:</b><br/>"
                + "<b>Thursday:</b><br/>wind: southwesterly 5.<br/>sea: 1.5 meter.<br/></p>"
                + "<p><b>Kattegat:</b><br/>"
                + "<b>Thursday:</b><br/>wind: southwesterly 4.<br/>sea: 0.5 meter.<br/></p>"
                + "<p><b>Belts and Sound:</b><br/>"
                + "<b>Thursday:</b><br/>wind: southwesterly 3.<br/>sea: 0.3 meter.<br/></p>"
                + "<p><b>Western Baltic:</b><br/>"
                + "<b>Thursday:</b><br/>wind: westerly 4.<br/>sea: 0.5 meter.<br/></p>"
                + "<p><b>Southern Baltic:</b><br/>"
                + "<b>Thursday:</b><br/>wind: westerly 5.<br/>sea: 1.0 meter.<br/></p>"
                + "<p><b>Southeastern Baltic:</b><br/>"
                + "<b>Thursday:</b><br/>wind: northwesterly 4.<br/>sea: 1.0 meter.<br/></p>"
                + "</body></html>";
    }
}
