package dev.onelili.mstock.database;

import dev.onelili.mstock.model.Holding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HoldingRepositoryTest {

    private HoldingRepository repository;
    private UUID playerUuid;

    @BeforeEach
    void setUp() throws SQLException {
        String jdbcUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        DatabaseManager database = new DatabaseManager(Logger.getAnonymousLogger()) {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(jdbcUrl);
            }
        };
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE holdings (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        player_uuid VARCHAR(36) NOT NULL,
                        stock_code VARCHAR(20) NOT NULL,
                        amount INT NOT NULL,
                        avg_cost DOUBLE NOT NULL,
                        last_price DOUBLE NOT NULL,
                        last_fetched BIGINT NOT NULL,
                        UNIQUE (player_uuid, stock_code)
                    )
                    """);
        }
        repository = new HoldingRepository(database, Logger.getAnonymousLogger());
        playerUuid = UUID.randomUUID();
    }

    @Test
    void reducesAndDeletesHoldingAtomically() throws SQLException {
        repository.adminUpsert(playerUuid, "700", 100, 450.0);

        repository.reduceSell(playerUuid, "700", 40, 457.6);

        Holding remaining = repository.findByPlayerAndCode(playerUuid, "700");
        assertEquals(60, remaining.getAmount());
        assertEquals(457.6, remaining.getLastPrice(), 0.0001);

        repository.reduceSell(playerUuid, "700", 60, 458.0);
        assertNull(repository.findByPlayerAndCode(playerUuid, "700"));
    }

    @Test
    void rejectsInsufficientHoldingWithoutChangingIt() throws SQLException {
        repository.adminUpsert(playerUuid, "700", 50, 450.0);

        assertThrows(SQLException.class,
                () -> repository.reduceSell(playerUuid, "700", 60, 457.6));

        Holding unchanged = repository.findByPlayerAndCode(playerUuid, "700");
        assertEquals(50, unchanged.getAmount());
        assertEquals(450.0, unchanged.getLastPrice(), 0.0001);
    }
}
