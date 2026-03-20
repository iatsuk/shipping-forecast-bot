package boats.log.shippingforecast.forecast.infra;

import boats.log.shippingforecast.forecast.ForecastProvider;
import boats.log.shippingforecast.forecast.ImageCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

/**
 * Fetches and caches provider map images at application startup.
 *
 * <p>Only fetches if the image is not already stored — the map images are static assets
 * that do not change with each forecast update, so a one-time download is sufficient.
 */
@Component
public class ImageCacheService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ImageCacheService.class);

    private final List<ForecastProvider> providers;
    private final ImageCacheRepository imageCacheRepository;
    private final HttpClient httpClient;

    public ImageCacheService(List<ForecastProvider> providers, ImageCacheRepository imageCacheRepository) {
        this.providers = providers;
        this.imageCacheRepository = imageCacheRepository;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public void afterPropertiesSet() {
        for (ForecastProvider provider : providers) {
            provider.mapImageUrl().ifPresent(url -> fetchAndCacheIfAbsent(url, provider.name()));
        }
    }

    private void fetchAndCacheIfAbsent(String url, String providerName) {
        if (imageCacheRepository.findByUrl(url).isPresent()) {
            log.info("Map image for {} already cached", providerName);
            return;
        }
        try {
            log.info("Fetching map image for {} from {}", providerName, url);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                imageCacheRepository.save(url, response.body(), Instant.now());
                log.info("Map image for {} cached ({} bytes)", providerName, response.body().length);
            } else {
                log.warn("Failed to fetch map image for {}: HTTP {}", providerName, response.statusCode());
            }
        } catch (Exception e) {
            log.error("Error fetching map image for {}: {}", providerName, e.getMessage(), e);
        }
    }
}
