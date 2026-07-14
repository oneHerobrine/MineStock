package dev.onelili.mstock.database;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseManagerLifecycleTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void restoreH2DriverForOtherTests() {
        org.h2.Driver.unload();
        org.h2.Driver.load();
    }

    @Test
    void closesPoolDeregistersOwnedDriverAndCanReopenData() throws Exception {
        org.h2.Driver.load();
        String jdbcUrl = "jdbc:h2:file:"
                + tempDir.resolve("minestock").toAbsolutePath().toString().replace('\\', '/')
                + ";MODE=MySQL";
        UUID player = UUID.randomUUID();

        DatabaseManager first = new DatabaseManager(Logger.getAnonymousLogger());
        first.initDataSource(config(jdbcUrl, "first"));
        HoldingRepository firstRepository = new HoldingRepository(first, Logger.getAnonymousLogger());
        firstRepository.adminUpsert(player, "700", 20, 450.0);
        assertTrue(hasOwnedH2Driver());

        first.close();
        first.close();
        assertFalse(hasOwnedH2Driver());
        assertThrows(SQLException.class, first::getConnection);

        DatabaseManager second = new DatabaseManager(Logger.getAnonymousLogger());
        second.initDataSource(config(jdbcUrl, "second"));
        HoldingRepository secondRepository = new HoldingRepository(second, Logger.getAnonymousLogger());
        assertNotNull(secondRepository.findByPlayerAndCode(player, "700"));
        assertEquals(20, secondRepository.findByPlayerAndCode(player, "700").getAmount());
        second.close();
    }

    private HikariConfig config(String jdbcUrl, String suffix) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setDriverClassName("org.h2.Driver");
        config.setMaximumPoolSize(1);
        config.setPoolName("MineStock-Test-" + suffix);
        return config;
    }

    private boolean hasOwnedH2Driver() {
        ClassLoader loader = DatabaseManager.class.getClassLoader();
        return DriverManager.drivers()
                .map(Driver::getClass)
                .anyMatch(type -> type.getName().equals("org.h2.Driver")
                        && type.getClassLoader() == loader);
    }
}
