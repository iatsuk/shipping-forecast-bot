package boats.log.shippingforecast.forecast;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcForecastCacheRepository implements ForecastCacheRepository {

    private final JdbcTemplate jdbc;

    public JdbcForecastCacheRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(String url, String content, Instant fetchedAt) {
        int updated = jdbc.update(
                "UPDATE forecast_cache SET content = ?, fetched_at = ? WHERE url = ?",
                content, Timestamp.from(fetchedAt), url
        );
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO forecast_cache (url, content, fetched_at) VALUES (?, ?, ?)",
                    url, content, Timestamp.from(fetchedAt)
            );
        }
    }

    @Override
    public Optional<ForecastCache> findByUrl(String url) {
        List<ForecastCache> results = jdbc.query(
                "SELECT url, content, fetched_at FROM forecast_cache WHERE url = ?",
                (rs, rowNum) -> new ForecastCache(
                        rs.getString("url"),
                        rs.getString("content"),
                        rs.getTimestamp("fetched_at").toInstant()
                ),
                url
        );
        return results.stream().findFirst();
    }
}
