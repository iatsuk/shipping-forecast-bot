package boats.log.shippingforecast.forecast.infra;

import boats.log.shippingforecast.forecast.ForecastFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Component
public class HttpForecastFetcher implements ForecastFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpForecastFetcher.class);

    private final HttpClient httpClient;

    public HttpForecastFetcher() {
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public String fetch(String url) throws IOException {
        log.debug("Fetching {}", url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from: " + url);
            }
            log.debug("HTTP {} from {}, body {} chars", response.statusCode(), url, response.body().length());
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted for: " + url, e);
        }
    }
}
