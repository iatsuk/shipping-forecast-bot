package boats.log.shippingforecast.telegram;

import boats.log.shippingforecast.forecast.ForecastCacheRepository;
import boats.log.shippingforecast.forecast.ForecastProvider;
import boats.log.shippingforecast.subscription.SubscriptionRepository;
import boats.log.shippingforecast.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

/**
 * Telegram long-polling consumer and message sender.
 *
 * <p>Implements {@link BotInteraction} and constructs {@link BotCommandHandler} directly
 * (passing {@code this}), avoiding a circular Spring dependency. All bot logic lives in
 * {@link BotCommandHandler}; this class is responsible only for Telegram API mechanics.
 */
@Component
public class TelegramBot implements LongPollingSingleThreadUpdateConsumer, BotInteraction {

    private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);

    private final TelegramClient client;
    private final BotCommandHandler commandHandler;

    public TelegramBot(
            @Value("${telegram.bot.token}") String token,
            List<ForecastProvider> providers,
            ForecastCacheRepository cacheRepository,
            UserRepository userRepository,
            SubscriptionRepository subscriptionRepository
    ) {
        this.client = new OkHttpTelegramClient(token);
        this.commandHandler = new BotCommandHandler(
                this, providers, cacheRepository, userRepository, subscriptionRepository);
    }

    // --- BotInteraction / MessageSender ---

    @Override
    public void send(long chatId, String text) {
        execute(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build(), chatId);
    }

    @Override
    public void sendMenu(long chatId, String text, List<List<MenuOption>> rows) {
        List<InlineKeyboardRow> keyboardRows = rows.stream()
                .map(row -> {
                    InlineKeyboardRow kr = new InlineKeyboardRow();
                    for (MenuOption opt : row) {
                        kr.add(InlineKeyboardButton.builder()
                                .text(opt.label())
                                .callbackData(opt.callbackData())
                                .build());
                    }
                    return kr;
                })
                .toList();

        execute(SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(new InlineKeyboardMarkup(keyboardRows))
                .build(), chatId);
    }

    @Override
    public void answerCallbackQuery(String callbackQueryId) {
        try {
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQueryId)
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Failed to answer callback query {}: {}", callbackQueryId, e.getMessage());
        }
    }

    // --- LongPollingSingleThreadUpdateConsumer ---

    @Override
    public void consume(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            long chatId = update.getMessage().getChatId();
            String text = update.getMessage().getText();
            String firstName = update.getMessage().getFrom().getFirstName();
            log.info("Message from chat {}: '{}'", chatId, text);

            if ("/start".equals(text)) {
                commandHandler.handleStart(chatId, firstName != null ? firstName : "sailor");
            } else if ("/stop".equals(text)) {
                commandHandler.handleStop(chatId);
            }
        }

        if (update.hasCallbackQuery()) {
            CallbackQuery callback = update.getCallbackQuery();
            long chatId = callback.getMessage().getChatId();
            String queryId = callback.getId();
            String data = callback.getData();
            log.info("Callback from chat {}: '{}'", chatId, data);
            commandHandler.handleCallback(chatId, queryId, data);
        }
    }

    // --- private ---

    private void execute(SendMessage message, long chatId) {
        try {
            client.execute(message);
        } catch (TelegramApiRequestException e) {
            // 403 means the user blocked, stopped, or deleted the bot — a permanent condition.
            if (e.getErrorCode() == 403) {
                throw new UserBlockedBotException(chatId, e);
            }
            log.error("Failed to send message to chat {}: {}", chatId, e.getMessage(), e);
            throw new RuntimeException("Telegram send failed for chat " + chatId, e);
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chat {}: {}", chatId, e.getMessage(), e);
            throw new RuntimeException("Telegram send failed for chat " + chatId, e);
        }
    }
}
