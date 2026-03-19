package boats.log.shippingforecast.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Component
public class TelegramBot implements LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelegramBot.class);

    private final TelegramClient client;

    public TelegramBot(@Value("${telegram.bot.token}") String token) {
        this.client = new OkHttpTelegramClient(token);
    }

    @Override
    public void consume(Update update) {
        if (update.hasMessage()) {
            if (update.getMessage().hasText()) {
                long userId = update.getMessage().getFrom().getId();
                long chatId = update.getMessage().getChatId();
                String text = update.getMessage().getText();
                log.info("Message from user {}: '{}'", userId, text);

                SendMessage message = SendMessage.builder()
                        .chatId(chatId)
                        .text(text)
                        .build();
                try {
                    client.execute(message);
                } catch (TelegramApiException e) {
                    log.error("Failed to send message to chat {}: {}", chatId, e.getMessage(), e);
                }
            }
            if (update.getMessage().hasLocation()) {
                long userId = update.getMessage().getFrom().getId();
                double lat = update.getMessage().getLocation().getLatitude();
                double lon = update.getMessage().getLocation().getLongitude();
                log.info("Location from user {}: {}, {}", userId, lat, lon);
            }
        }
    }
}
