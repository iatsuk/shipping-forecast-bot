package boats.log.shippingforecast.telegram;

/**
 * Thrown by {@link MessageSender} implementations when the target user has blocked,
 * stopped, or deleted the bot. This is a permanent failure for that chat ID — the
 * caller should remove all subscriptions for the user to stop further send attempts.
 */
public class UserBlockedBotException extends RuntimeException {

    private final long chatId;

    public UserBlockedBotException(long chatId, Throwable cause) {
        super("User " + chatId + " has blocked or stopped the bot", cause);
        this.chatId = chatId;
    }

    public long chatId() {
        return chatId;
    }
}
