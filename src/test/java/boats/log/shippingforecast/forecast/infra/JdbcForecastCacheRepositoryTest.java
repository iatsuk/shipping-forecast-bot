package boats.log.shippingforecast.forecast.infra;

import boats.log.shippingforecast.forecast.ForecastCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcForecastCacheRepositoryTest {

    private EmbeddedDatabase db;
    private JdbcForecastCacheRepository repository;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.HSQL)
                .addScript("schema.sql")
                .build();
        repository = new JdbcForecastCacheRepository(new JdbcTemplate(db));
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    @Test
    void save_persistsCacheEntry() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        repository.save("https://example.com/forecast", "<html>forecast</html>", now);

        Optional<ForecastCache> result = repository.findByUrl("https://example.com/forecast");
        assertThat(result).isPresent();
        assertThat(result.get().content()).isEqualTo("<html>forecast</html>");
        assertThat(result.get().fetchedAt()).isEqualTo(now);
    }

    @Test
    void save_replacesExistingEntry() {
        Instant first = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        Instant second = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        repository.save("https://example.com/forecast", "old content", first);
        repository.save("https://example.com/forecast", "new content", second);

        Optional<ForecastCache> result = repository.findByUrl("https://example.com/forecast");
        assertThat(result).isPresent();
        assertThat(result.get().content()).isEqualTo("new content");
        assertThat(result.get().fetchedAt()).isEqualTo(second);
    }

    @Test
    void findByUrl_returnsEmptyWhenNotPresent() {
        Optional<ForecastCache> result = repository.findByUrl("https://unknown.com");

        assertThat(result).isEmpty();
    }

    @Test
    void save_keepsEntriesIsolatedPerUrl() {
        Instant t1 = Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        Instant t2 = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        repository.save("https://a.com", "content-a", t1);
        repository.save("https://b.com", "content-b", t2);
        repository.save("https://a.com", "content-a-updated", t2);

        assertThat(repository.findByUrl("https://a.com").get().content()).isEqualTo("content-a-updated");
        assertThat(repository.findByUrl("https://b.com").get().content()).isEqualTo("content-b");
    }
}
