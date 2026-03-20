package boats.log.shippingforecast.forecast.infra;

import boats.log.shippingforecast.forecast.ImageCache;
import boats.log.shippingforecast.forecast.ImageCacheRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcImageCacheRepository implements ImageCacheRepository {

    private final JdbcTemplate jdbc;

    public JdbcImageCacheRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(String url, byte[] data, Instant fetchedAt) {
        int updated = jdbc.update(
                "UPDATE image_cache SET data = ?, fetched_at = ? WHERE url = ?",
                data, Timestamp.from(fetchedAt), url
        );
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO image_cache (url, data, fetched_at) VALUES (?, ?, ?)",
                    url, data, Timestamp.from(fetchedAt)
            );
        }
    }

    @Override
    public Optional<ImageCache> findByUrl(String url) {
        List<ImageCache> results = jdbc.query(
                "SELECT url, data, fetched_at FROM image_cache WHERE url = ?",
                (rs, rowNum) -> new ImageCache(
                        rs.getString("url"),
                        rs.getBytes("data"),
                        rs.getTimestamp("fetched_at").toInstant()
                ),
                url
        );
        return results.stream().findFirst();
    }
}
