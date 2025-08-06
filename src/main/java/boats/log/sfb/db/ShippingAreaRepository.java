package boats.log.sfb.db;

import org.h2.jdbcx.JdbcConnectionPool;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * Repository for shipping areas.
 * Table forecast_shipping_areas:
 * - id: BIGINT, primary key
 * - name: VARCHAR(255), unique
 * - data_provider_id: INTEGER
 */
public class ShippingAreaRepository {
    private final JdbcConnectionPool connectionPool;

    public ShippingAreaRepository(JdbcConnectionPool connectionPool) {
        this.connectionPool = connectionPool;
        initializeDatabase();
    }

    private void initializeDatabase() {
        try (Connection conn = connectionPool.getConnection();
             Statement stmt = conn.createStatement()) {

            // Create sequence for auto-increment
            stmt.execute("CREATE SEQUENCE IF NOT EXISTS area_id_seq START WITH 1 INCREMENT BY 1");

            // Create forecast_shipping_areas table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS forecast_shipping_areas (
                    id BIGINT DEFAULT NEXT VALUE FOR area_id_seq PRIMARY KEY,
                    name VARCHAR(255) NOT NULL UNIQUE,
                    data_provider_id INTEGER NOT NULL
                )
            """);

        } catch (SQLException e) {
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    public long insertArea(String name, int dataProviderId) throws SQLException {
        final String sql = "INSERT INTO forecast_shipping_areas (name, data_provider_id) VALUES (?, ?)";

        try (Connection conn = connectionPool.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            pstmt.setString(1, name);
            pstmt.setInt(2, dataProviderId);
            pstmt.executeUpdate();

            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                throw new SQLException("Insert failed, no ID obtained");
            }
        }
    }

    public Optional<String> findAreaById(long id) throws SQLException {
        final String sql = "SELECT name FROM forecast_shipping_areas WHERE id = ?";

        try (Connection conn = connectionPool.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString("name"));
                }
                return Optional.empty();
            }
        }
    }

    // Test the implementation
    public static void main(String[] args) {
        DatabaseInitializer initializer = new DatabaseInitializer();
        ShippingAreaRepository repository = new ShippingAreaRepository(initializer.getConnectionPool());

        try {
            long newId = repository.insertArea("North Sea", 1);
            System.out.println("Inserted ID: " + newId);

            Optional<String> area = repository.findAreaById(newId);
            area.ifPresentOrElse(
                    name -> System.out.println("Found area: " + name),
                    () -> System.out.println("Area not found")
            );

        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            initializer.shutdown();
            Runtime.getRuntime().addShutdownHook(new Thread(initializer::shutdown));
        }
    }
}