package boats.log.shippingforecast.forecast.infra;

import boats.log.shippingforecast.forecast.ImageCache;
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

class JdbcImageCacheRepositoryTest {

    private EmbeddedDatabase db;
    private JdbcImageCacheRepository repository;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.HSQL)
                .addScript("schema.sql")
                .build();
        repository = new JdbcImageCacheRepository(new JdbcTemplate(db));
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    @Test
    void save_persistsImageEntry() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        byte[] data = new byte[]{1, 2, 3, 4, 5};

        repository.save("https://example.com/map.jpg", data, now);

        Optional<ImageCache> result = repository.findByUrl("https://example.com/map.jpg");
        assertThat(result).isPresent();
        assertThat(result.get().data()).isEqualTo(data);
        assertThat(result.get().fetchedAt()).isEqualTo(now);
    }

    @Test
    void save_replacesExistingEntry() {
        Instant first = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        Instant second = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        byte[] oldData = new byte[]{1, 2, 3};
        byte[] newData = new byte[]{4, 5, 6};

        repository.save("https://example.com/map.jpg", oldData, first);
        repository.save("https://example.com/map.jpg", newData, second);

        Optional<ImageCache> result = repository.findByUrl("https://example.com/map.jpg");
        assertThat(result).isPresent();
        assertThat(result.get().data()).isEqualTo(newData);
        assertThat(result.get().fetchedAt()).isEqualTo(second);
    }

    @Test
    void findByUrl_returnsEmptyWhenNotPresent() {
        Optional<ImageCache> result = repository.findByUrl("https://unknown.com/map.jpg");

        assertThat(result).isEmpty();
    }

    @Test
    void save_keepsEntriesIsolatedPerUrl() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        byte[] dataA = new byte[]{1, 2};
        byte[] dataB = new byte[]{3, 4};

        repository.save("https://a.com/map.jpg", dataA, now);
        repository.save("https://b.com/map.jpg", dataB, now);

        assertThat(repository.findByUrl("https://a.com/map.jpg").get().data()).isEqualTo(dataA);
        assertThat(repository.findByUrl("https://b.com/map.jpg").get().data()).isEqualTo(dataB);
    }
}
