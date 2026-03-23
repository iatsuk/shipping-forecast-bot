package boats.log.shippingforecast.subscription;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcSubscriptionRepository implements SubscriptionRepository {

    private final JdbcTemplate jdbc;

    public JdbcSubscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void subscribe(long chatId, String area) {
        try {
            jdbc.update("INSERT INTO user_subscription (chat_id, area) VALUES (?, ?)", chatId, area);
        } catch (DuplicateKeyException ignored) {
            // Already subscribed — subscribe is intentionally idempotent.
        }
    }

    @Override
    public void unsubscribe(long chatId, String area) {
        jdbc.update("DELETE FROM user_subscription WHERE chat_id = ? AND area = ?", chatId, area);
    }

    @Override
    public List<String> findAreasByChatId(long chatId) {
        return jdbc.queryForList("SELECT area FROM user_subscription WHERE chat_id = ?", String.class, chatId);
    }

    @Override
    public List<Long> findChatIdsByArea(String area) {
        return jdbc.queryForList("SELECT chat_id FROM user_subscription WHERE area = ?", Long.class, area);
    }

    @Override
    public int count() {
        Integer result = jdbc.queryForObject("SELECT COUNT(*) FROM user_subscription", Integer.class);
        return result != null ? result : 0;
    }

    @Override
    public void deleteAllByChatId(long chatId) {
        jdbc.update("DELETE FROM user_subscription WHERE chat_id = ?", chatId);
    }
}
