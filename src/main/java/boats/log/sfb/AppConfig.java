package boats.log.sfb;

import boats.log.sfb.telegram.TelegramBot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

@Configuration
@PropertySource("classpath:application.properties")
@ComponentScan("boats.log.sfb")
public class AppConfig {

    @Value("${telegram.bot.token}")
    private String token;

    @Bean(destroyMethod = "close")
    public TelegramBotsLongPollingApplication botsApplication(TelegramBot bot) throws Exception {
        TelegramBotsLongPollingApplication app = new TelegramBotsLongPollingApplication();
        var session = app.registerBot(token, bot);
        System.out.println("Bot started: " + session.isRunning());
        return app;
    }
}
