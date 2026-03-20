package boats.log.shippingforecast.user;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcUserRepositoryTest {

    private EmbeddedDatabase db;
    private JdbcUserRepository repository;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.HSQL)
                .addScript("schema.sql")
                .build();
        repository = new JdbcUserRepository(new JdbcTemplate(db));
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    @Test
    void register_persistsNewUser() {
        repository.register(new TelegramUser(42L));

        Optional<TelegramUser> result = repository.findById(42L);

        assertThat(result).isPresent();
        assertThat(result.get().chatId()).isEqualTo(42L);
    }

    @Test
    void register_isIdempotent() {
        repository.register(new TelegramUser(42L));
        repository.register(new TelegramUser(42L));

        assertThat(repository.findById(42L)).isPresent();
    }

    @Test
    void findById_returnsEmptyWhenUserNotFound() {
        assertThat(repository.findById(999L)).isEmpty();
    }

    @Test
    void delete_removesUser() {
        repository.register(new TelegramUser(42L));

        repository.delete(42L);

        assertThat(repository.findById(42L)).isEmpty();
    }

    @Test
    void delete_isNoOpForUnknownUser() {
        repository.delete(999L); // must not throw
    }
}
