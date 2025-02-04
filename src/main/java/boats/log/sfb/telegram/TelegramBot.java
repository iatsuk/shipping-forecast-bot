package boats.log.sfb.telegram;

import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

public class TelegramBot implements LongPollingSingleThreadUpdateConsumer {
    private final TelegramClient client;

    public TelegramBot(String token) {
        this.client = new OkHttpTelegramClient(token);
    }

    @Override
    public void consume(Update update) {
        // check if the update has a message and the message has text
        if (update.hasMessage()) {
            if (update.getMessage().hasText()) {
                System.out.println("Received message: '" + update.getMessage().getText() + "' from " + update.getMessage().getFrom().getId());

                // create a send message object
                String message_text = update.getMessage().getText();
                long chat_id = update.getMessage().getChatId();
                SendMessage message = SendMessage
                        .builder()
                        .chatId(chat_id)
                        .text(message_text)
                        .build();
                try {
                    client.execute(message);
                } catch (TelegramApiException e) {
                    e.printStackTrace();
                }
            }
            if (update.getMessage().hasLocation()) {
                System.out.println("Received location: " + update.getMessage().getLocation().getLatitude() + ", " + update.getMessage().getLocation().getLongitude() + " from " + update.getMessage().getFrom().getId());
            }
        }
    }
}
