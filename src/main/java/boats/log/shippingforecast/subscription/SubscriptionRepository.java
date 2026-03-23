package boats.log.shippingforecast.subscription;

import java.util.List;

public interface SubscriptionRepository {

    /**
     * Subscribes a user to a forecast area. Idempotent — has no effect if already subscribed.
     * The user must be registered in telegram_user before calling this.
     */
    void subscribe(long chatId, String area);

    void unsubscribe(long chatId, String area);

    List<String> findAreasByChatId(long chatId);

    /** Returns all chat IDs subscribed to the given area. Used when dispatching forecasts. */
    List<Long> findChatIdsByArea(String area);

    /** Returns the total number of active subscriptions across all users. */
    int count();

    /** Removes all subscriptions for a user. Called when a user blocks the bot. */
    void deleteAllByChatId(long chatId);
}