package boats.log.sfb.subscription;

import java.util.List;

public interface SubscriptionRepository {

    /**
     * Subscribes a chat to a forecast area. Idempotent — has no effect if already subscribed.
     */
    void subscribe(long chatId, String area);

    void unsubscribe(long chatId, String area);

    List<String> findAreasByChatId(long chatId);

    /** Returns all chat IDs subscribed to the given area. Used when dispatching forecasts. */
    List<Long> findChatIdsByArea(String area);

    /** Removes all subscriptions for a chat. Called when a user blocks the bot. */
    void deleteAllByChatId(long chatId);
}
