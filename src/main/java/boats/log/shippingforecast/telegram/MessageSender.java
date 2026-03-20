package boats.log.shippingforecast.telegram;

/**
 * Sends a text message to a Telegram chat.
 *
 * <p>Abstracted so that the dispatch logic is not coupled to the Telegram client directly,
 * making it testable without real network calls.
 */
public interface MessageSender {

    void send(long chatId, String text);
}
