package boats.log.shippingforecast.user;

/**
 * Represents a Telegram user known to the bot.
 * The bot operates exclusively in private chats, so chatId is both the user identifier
 * and the chat destination for message dispatch.
 */
public record TelegramUser(long chatId) {}