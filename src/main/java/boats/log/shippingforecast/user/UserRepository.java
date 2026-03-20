package boats.log.shippingforecast.user;

import java.util.Optional;

public interface UserRepository {

    /**
     * Registers a new user. Idempotent — has no effect if the user is already known.
     * Should be called on every bot interaction to ensure the user exists before subscribing.
     */
    void register(TelegramUser user);

    Optional<TelegramUser> findById(long chatId);
}