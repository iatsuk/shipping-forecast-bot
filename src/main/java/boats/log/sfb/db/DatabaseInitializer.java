package boats.log.sfb.db;

import org.h2.jdbcx.JdbcConnectionPool;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseInitializer implements AutoCloseable {
    private static final String JDBC_URL = "jdbc:h2:./shipping_db;MVCC=TRUE;DB_CLOSE_ON_EXIT=FALSE";
    private static final String USER = "";
    private static final String PASS = "";

    private final JdbcConnectionPool connectionPool;

    public DatabaseInitializer() {
        this.connectionPool = JdbcConnectionPool.create(JDBC_URL, USER, PASS);
    }

    private void initializeTables() {
        try (Connection conn = connectionPool.getConnection();
             Statement stmt = conn.createStatement()) {

            // h3_areas table (multiple area_ids per h3_id)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS h3_areas (
                    h3_id INTEGER NOT NULL,
                    area_id INTEGER NOT NULL,
                    PRIMARY KEY (h3_id, area_id),
                    FOREIGN KEY (area_id) REFERENCES areas(area_id)
                )""");

            // New user_subscriptions table for multiple subscriptions
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS user_subscriptions (
                    user_id INTEGER NOT NULL,
                    area_id INTEGER NOT NULL,
                    PRIMARY KEY (user_id, area_id),
                    FOREIGN KEY (user_id) REFERENCES users(user_id),
                    FOREIGN KEY (area_id) REFERENCES areas(area_id)
                )""");

            // Updated users table (remove direct subscription)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    user_id INTEGER PRIMARY KEY,  // External ID
                    user_platform_id INTEGER NOT NULL,
                    FOREIGN KEY (user_platform_id) REFERENCES user_platforms(user_platform_id)
                )""");

        } catch (SQLException e) {
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    public JdbcConnectionPool getConnectionPool() {
        return connectionPool;
    }

    public void shutdown() {
        connectionPool.dispose();
    }

    @Override
    public void close() {
        shutdown();
    }
}
