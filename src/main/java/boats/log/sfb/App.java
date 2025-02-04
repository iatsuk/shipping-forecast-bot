package boats.log.sfb;

import boats.log.sfb.telegram.TelegramBot;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

public class App {

    public static void main(String[] args) {
        final String TOKEN = args[0];
        System.out.println("Starting bot with token: " + TOKEN);
        try (TelegramBotsLongPollingApplication botsApplication = new TelegramBotsLongPollingApplication()) {
            var session = botsApplication.registerBot(TOKEN, new TelegramBot(TOKEN));
            System.out.println("Bot started: " + session.isRunning());
            Thread.currentThread().join();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
