package boats.log.shippingforecast.user;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcUserRepository implements UserRepository {

    private final JdbcTemplate jdbc;

    public JdbcUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void register(TelegramUser user) {
        try {
            jdbc.update("INSERT INTO telegram_user (chat_id) VALUES (?)", user.chatId());
        } catch (DuplicateKeyException ignored) {
            // User already registered — register is intentionally idempotent.
        }
    }

    @Override
    public Optional<TelegramUser> findById(long chatId) {
        List<TelegramUser> results = jdbc.query(
                "SELECT chat_id FROM telegram_user WHERE chat_id = ?",
                (rs, rowNum) -> new TelegramUser(rs.getLong("chat_id")),
                chatId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public int count() {
        Integer result = jdbc.queryForObject("SELECT COUNT(*) FROM telegram_user", Integer.class);
        return result != null ? result : 0;
    }

    @Override
    public void delete(long chatId) {
        jdbc.update("DELETE FROM telegram_user WHERE chat_id = ?", chatId);
    }
}