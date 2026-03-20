package boats.log.shippingforecast.forecast.scheduler;

import boats.log.shippingforecast.forecast.GeoLocation;
import boats.log.shippingforecast.forecast.ShippingForecast;
import boats.log.shippingforecast.subscription.SubscriptionRepository;
import boats.log.shippingforecast.telegram.MessageSender;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ForecastDispatcherTest {

    @Test
    void dispatch_sendsEachForecastToSubscribedChatIds() {
        // Arrange
        GeoLocation northSea = new GeoLocation("North Sea", 55.0, 5.0);
        GeoLocation baltic = new GeoLocation("Baltic", 58.0, 18.0);

        ShippingForecast northSeaForecast = new ShippingForecast(northSea, "North Sea: winds SW 4.");
        ShippingForecast balticForecast = new ShippingForecast(baltic, "Baltic: winds NE 3.");

        Map<String, List<Long>> subscribersByArea = Map.of(
                "North Sea", List.of(101L, 102L),
                "Baltic", List.of(103L)
        );

        RecordingMessageSender sender = new RecordingMessageSender();
        ForecastDispatcher dispatcher = new ForecastDispatcher(
                new StubSubscriptionRepository(subscribersByArea),
                sender
        );

        // Act
        dispatcher.dispatch(List.of(northSeaForecast, balticForecast));

        // Assert
        assertThat(sender.sent).containsExactlyInAnyOrder(
                new SentMessage(101L, "North Sea: winds SW 4."),
                new SentMessage(102L, "North Sea: winds SW 4."),
                new SentMessage(103L, "Baltic: winds NE 3.")
        );
    }

    @Test
    void dispatch_skipsAreaWithNoSubscribers() {
        GeoLocation area = new GeoLocation("Skagerrak", 57.0, 9.0);
        ShippingForecast forecast = new ShippingForecast(area, "Skagerrak: calm.");

        RecordingMessageSender sender = new RecordingMessageSender();
        ForecastDispatcher dispatcher = new ForecastDispatcher(
                new StubSubscriptionRepository(Map.of()),
                sender
        );

        dispatcher.dispatch(List.of(forecast));

        assertThat(sender.sent).isEmpty();
    }

    @Test
    void dispatch_continuesAfterSendFailure() {
        GeoLocation area = new GeoLocation("North Sea", 55.0, 5.0);
        ShippingForecast forecast = new ShippingForecast(area, "Winds SW 5.");

        RecordingMessageSender sender = new RecordingMessageSender();
        ForecastDispatcher dispatcher = new ForecastDispatcher(
                new StubSubscriptionRepository(Map.of("North Sea", List.of(201L, 202L))),
                (chatId, text) -> {
                    if (chatId == 201L) throw new RuntimeException("network error");
                    sender.send(chatId, text);
                }
        );

        dispatcher.dispatch(List.of(forecast));

        // Second subscriber still receives the message despite the first failing.
        assertThat(sender.sent).containsExactly(new SentMessage(202L, "Winds SW 5."));
    }

    // --- test doubles ---

    record SentMessage(long chatId, String text) {}

    private static class RecordingMessageSender implements MessageSender {
        final List<SentMessage> sent = new ArrayList<>();

        @Override
        public void send(long chatId, String text) {
            sent.add(new SentMessage(chatId, text));
        }
    }

    private static class StubSubscriptionRepository implements SubscriptionRepository {
        private final Map<String, List<Long>> subscribersByArea;

        StubSubscriptionRepository(Map<String, List<Long>> subscribersByArea) {
            this.subscribersByArea = subscribersByArea;
        }

        @Override public void subscribe(long chatId, String area) {}
        @Override public void unsubscribe(long chatId, String area) {}
        @Override public List<String> findAreasByChatId(long chatId) { return List.of(); }
        @Override public List<Long> findChatIdsByArea(String area) {
            return subscribersByArea.getOrDefault(area, List.of());
        }
        @Override public void deleteAllByChatId(long chatId) {}
    }
}
