package boats.log.shippingforecast;

import boats.log.shippingforecast.telegram.TelegramBot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.random.RandomGenerator;

@Configuration
@EnableScheduling
// ignoreResourceNotFound=true: files are not required in the classpath.
// Runtime tokens are provided via environment variables (TELEGRAM_BOT_TOKEN, etc.),
// which Spring's StandardEnvironment maps to the corresponding dot-notation properties.
@PropertySource(value = "classpath:application.properties", ignoreResourceNotFound = true)
@PropertySource(value = "classpath:build.properties", ignoreResourceNotFound = true)
@ComponentScan("boats.log.shippingforecast")
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Value("${telegram.bot.token}")
    private String token;

    @Bean(destroyMethod = "close")
    public TelegramBotsLongPollingApplication botsApplication(TelegramBot bot) throws Exception {
        TelegramBotsLongPollingApplication app = new TelegramBotsLongPollingApplication();
        var session = app.registerBot(token, bot);
        log.info("Telegram bot registered, running: {}", session.isRunning());
        return app;
    }

    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl("jdbc:hsqldb:file:./data/sfb-db");
        ds.setUsername("sa");
        ds.setPassword("");

        // Apply schema on startup — CREATE TABLE IF NOT EXISTS, so safe to run every time.
        log.info("Initializing database at: ./data/sfb-db");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("schema.sql"));
        populator.execute(ds);
        log.info("Database schema applied");

        return ds;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public BuildInfo buildInfo(
            @Value("${build.commit:unknown}") String commit,
            @Value("${build.time:unknown}") String time) {
        return new BuildInfo(commit, time);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RandomGenerator random() {
        return RandomGenerator.getDefault();
    }
}
