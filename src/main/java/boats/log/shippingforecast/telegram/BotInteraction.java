package boats.log.shippingforecast.telegram;

import java.util.List;

/**
 * Sending capabilities needed by bot command handlers.
 *
 * <p>Extends {@link MessageSender} with interactive menu support and callback query
 * acknowledgement. {@link boats.log.shippingforecast.forecast.scheduler.ForecastDispatcher}
 * continues to use only the plain {@link MessageSender} for push notifications.
 */
public interface BotInteraction extends MessageSender {

    /**
     * Sends a message with an attached inline keyboard.
     *
     * @param rows each inner list becomes one row of buttons
     */
    void sendMenu(long chatId, String text, List<List<MenuOption>> rows);

    /**
     * Acknowledges a callback query, dismissing the loading indicator on the button.
     * Must be called for every received {@code CallbackQuery}.
     */
    void answerCallbackQuery(String callbackQueryId);
}
