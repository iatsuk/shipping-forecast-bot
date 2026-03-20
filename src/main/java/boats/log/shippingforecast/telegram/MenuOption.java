package boats.log.shippingforecast.telegram;

/**
 * A single button in an inline keyboard menu.
 *
 * @param label        text displayed on the button
 * @param callbackData data sent back to the bot when the button is pressed;
 *                     must not exceed 64 bytes (Telegram limit)
 */
public record MenuOption(String label, String callbackData) {
}
