package boats.log.shippingforecast.subscription;

import boats.log.shippingforecast.user.JdbcUserRepository;
import boats.log.shippingforecast.user.TelegramUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcSubscriptionRepositoryTest {

    private EmbeddedDatabase db;
    private JdbcUserRepository userRepository;
    private JdbcSubscriptionRepository repository;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.HSQL)
                .addScript("schema.sql")
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(db);
        userRepository = new JdbcUserRepository(jdbc);
        repository = new JdbcSubscriptionRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    @Test
    void subscribe_persistsSubscription() {
        userRepository.register(new TelegramUser(100L));

        repository.subscribe(100L, "North Sea");

        assertThat(repository.findAreasByChatId(100L)).containsExactly("North Sea");
    }

    @Test
    void subscribe_isIdempotent() {
        userRepository.register(new TelegramUser(100L));

        repository.subscribe(100L, "North Sea");
        repository.subscribe(100L, "North Sea");

        assertThat(repository.findAreasByChatId(100L)).hasSize(1);
    }

    @Test
    void unsubscribe_removesSubscription() {
        userRepository.register(new TelegramUser(100L));
        repository.subscribe(100L, "North Sea");

        repository.unsubscribe(100L, "North Sea");

        assertThat(repository.findAreasByChatId(100L)).isEmpty();
    }

    @Test
    void findAreasByChatId_returnsOnlyAreasForThatChat() {
        userRepository.register(new TelegramUser(100L));
        userRepository.register(new TelegramUser(200L));
        repository.subscribe(100L, "North Sea");
        repository.subscribe(100L, "Baltic Sea");
        repository.subscribe(200L, "Irish Sea");

        assertThat(repository.findAreasByChatId(100L))
                .containsExactlyInAnyOrder("North Sea", "Baltic Sea");
    }

    @Test
    void findChatIdsByArea_returnsAllSubscribersOfArea() {
        userRepository.register(new TelegramUser(100L));
        userRepository.register(new TelegramUser(200L));
        userRepository.register(new TelegramUser(300L));
        repository.subscribe(100L, "North Sea");
        repository.subscribe(200L, "North Sea");
        repository.subscribe(300L, "Baltic Sea");

        assertThat(repository.findChatIdsByArea("North Sea"))
                .containsExactlyInAnyOrder(100L, 200L);
    }

    @Test
    void deleteAllByChatId_removesAllSubscriptionsForChat() {
        userRepository.register(new TelegramUser(100L));
        userRepository.register(new TelegramUser(200L));
        repository.subscribe(100L, "North Sea");
        repository.subscribe(100L, "Baltic Sea");
        repository.subscribe(200L, "North Sea");

        repository.deleteAllByChatId(100L);

        assertThat(repository.findAreasByChatId(100L)).isEmpty();
        assertThat(repository.findChatIdsByArea("North Sea")).containsExactly(200L);
    }
}