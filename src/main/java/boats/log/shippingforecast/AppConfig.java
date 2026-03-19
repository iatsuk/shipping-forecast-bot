package boats.log.shippingforecast;

import boats.log.shippingforecast.telegram.TelegramBot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

import javax.sql.DataSource;
import java.time.Clock;

@Configuration
@EnableScheduling
@PropertySource("classpath:application.properties")
@ComponentScan("boats.log.shippingforecast")
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

    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:hsqldb:file:./data/sfb-db");
        ds.setUsername("sa");
        ds.setPassword("");

        // Apply schema on startup — CREATE TABLE IF NOT EXISTS, so safe to run every time.
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("schema.sql"));
        populator.execute(ds);

        return ds;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
