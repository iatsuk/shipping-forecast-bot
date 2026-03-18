package boats.log.shippingforecast.subscription;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcSubscriptionRepositoryTest {

    private EmbeddedDatabase db;
    private JdbcSubscriptionRepository repository;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.HSQL)
                .addScript("schema.sql")
                .build();
        repository = new JdbcSubscriptionRepository(new JdbcTemplate(db));
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    @Test
    void subscribe_persistsSubscription() {
        repository.subscribe(100L, "North Sea");

        List<String> areas = repository.findAreasByChatId(100L);

        assertThat(areas).containsExactly("North Sea");
    }

    @Test
    void subscribe_isIdempotent() {
        repository.subscribe(100L, "North Sea");
        repository.subscribe(100L, "North Sea");

        assertThat(repository.findAreasByChatId(100L)).hasSize(1);
    }

    @Test
    void unsubscribe_removesSubscription() {
        repository.subscribe(100L, "North Sea");
        repository.unsubscribe(100L, "North Sea");

        assertThat(repository.findAreasByChatId(100L)).isEmpty();
    }

    @Test
    void findAreasByChatId_returnsOnlyAreasForThatChat() {
        repository.subscribe(100L, "North Sea");
        repository.subscribe(100L, "Baltic Sea");
        repository.subscribe(200L, "Irish Sea");

        assertThat(repository.findAreasByChatId(100L))
                .containsExactlyInAnyOrder("North Sea", "Baltic Sea");
    }

    @Test
    void findChatIdsByArea_returnsAllSubscribersOfArea() {
        repository.subscribe(100L, "North Sea");
        repository.subscribe(200L, "North Sea");
        repository.subscribe(300L, "Baltic Sea");

        assertThat(repository.findChatIdsByArea("North Sea"))
                .containsExactlyInAnyOrder(100L, 200L);
    }

    @Test
    void deleteAllByChatId_removesAllSubscriptionsForChat() {
        repository.subscribe(100L, "North Sea");
        repository.subscribe(100L, "Baltic Sea");
        repository.subscribe(200L, "North Sea");

        repository.deleteAllByChatId(100L);

        assertThat(repository.findAreasByChatId(100L)).isEmpty();
        assertThat(repository.findChatIdsByArea("North Sea")).containsExactly(200L);
    }
}
