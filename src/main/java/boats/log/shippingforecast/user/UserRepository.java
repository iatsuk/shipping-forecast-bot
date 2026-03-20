package boats.log.shippingforecast.user;

import java.util.Optional;

public interface UserRepository {

    /**
     * Registers a new user. Idempotent — has no effect if the user is already known.
     * Should be called on every bot interaction to ensure the user exists before subscribing.
     */
    void register(TelegramUser user);

    Optional<TelegramUser> findById(long chatId);

    /**
     * Deletes the user and all associated data. Subscriptions are removed automatically
     * via the ON DELETE CASCADE constraint. Called when a user blocks or stops the bot,
     * or when their Telegram account has been deleted.
     */
    void delete(long chatId);
}