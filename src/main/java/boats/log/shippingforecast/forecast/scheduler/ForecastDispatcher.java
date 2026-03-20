package boats.log.shippingforecast.forecast.scheduler;

import boats.log.shippingforecast.forecast.ShippingForecast;
import boats.log.shippingforecast.subscription.SubscriptionRepository;
import boats.log.shippingforecast.telegram.MessageSender;
import boats.log.shippingforecast.telegram.UserBlockedBotException;
import boats.log.shippingforecast.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sends fresh shipping forecasts to subscribed users.
 *
 * <p>For each forecast area, looks up all chat IDs subscribed to that area and
 * delivers the forecast text via the {@link MessageSender}.
 */
@Component
public class ForecastDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ForecastDispatcher.class);

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final MessageSender messageSender;

    public ForecastDispatcher(
            SubscriptionRepository subscriptionRepository,
            UserRepository userRepository,
            MessageSender messageSender
    ) {
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.messageSender = messageSender;
    }

    /**
     * Dispatches each forecast to all users subscribed to the corresponding area.
     * Failures on individual deliveries are logged and do not abort the remaining sends.
     */
    public void dispatch(List<ShippingForecast> forecasts) {
        for (ShippingForecast forecast : forecasts) {
            String area = forecast.location().name();
            List<Long> chatIds = subscriptionRepository.findChatIdsByArea(area);
            for (long chatId : chatIds) {
                try {
                    messageSender.send(chatId, forecast.text());
                    log.debug("Dispatched forecast for '{}' to chat {}", area, chatId);
                } catch (UserBlockedBotException e) {
                    // User has blocked, stopped, or deleted the bot — erase all their data
                    // so we do not attempt further deliveries. Subscriptions are removed
                    // automatically via ON DELETE CASCADE.
                    log.info("User {} is unreachable (blocked/stopped/deleted); removing all user data", chatId);
                    userRepository.delete(chatId);
                } catch (Exception e) {
                    log.warn("Failed to send forecast for '{}' to chat {}: {}", area, chatId, e.getMessage());
                }
            }
            if (!chatIds.isEmpty()) {
                log.info("Dispatched forecast for '{}' to {} subscriber(s)", area, chatIds.size());
            }
        }
    }
}
