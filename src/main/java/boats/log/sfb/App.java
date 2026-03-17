package boats.log.sfb;

import boats.log.sfb.telegram.TelegramBot;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

import java.io.InputStream;
import java.util.Properties;

public class App {

    public static void main(String[] args) throws Exception {
        Properties props = new Properties();
        try (InputStream in = App.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in == null) throw new IllegalStateException("application.properties not found on classpath");
            props.load(in);
        }
        final String TOKEN = props.getProperty("telegram.bot.token");
        if (TOKEN == null || TOKEN.isBlank()) throw new IllegalStateException("telegram.bot.token is not set");

        System.out.println("Starting bot...");
        try (TelegramBotsLongPollingApplication botsApplication = new TelegramBotsLongPollingApplication()) {
            var session = botsApplication.registerBot(TOKEN, new TelegramBot(TOKEN));
            System.out.println("Bot started: " + session.isRunning());
            Thread.currentThread().join();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
