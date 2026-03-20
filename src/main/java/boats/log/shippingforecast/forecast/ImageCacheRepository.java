package boats.log.shippingforecast.forecast;

import java.time.Instant;
import java.util.Optional;

/** Persistence contract for cached provider images. */
public interface ImageCacheRepository {

    void save(String url, byte[] data, Instant fetchedAt);

    Optional<ImageCache> findByUrl(String url);
}
