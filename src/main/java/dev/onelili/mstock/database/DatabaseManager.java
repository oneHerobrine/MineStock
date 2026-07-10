package dev.onelili.mstock.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.onelili.mstock.config.DatabaseConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

public class DatabaseManager {
    private HikariDataSource dataSource;
    private final Logger logger;

    public DatabaseManager(Logger logger) {
        this.logger = logger;
    }

    public void init(DatabaseConfig cfg, File pluginFolder) throws SQLException {
        HikariConfig hikari = new HikariConfig();
        String type = cfg.getType().toLowerCase();

        if ("mysql".equals(type)) {
            hikari.setJdbcUrl("jdbc:mysql://" + cfg.getMysqlHost() + ":" + cfg.getMysqlPort()
                    + "/" + cfg.getMysqlDatabase() + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8");
            hikari.setUsername(cfg.getMysqlUsername());
            hikari.setPassword(cfg.getMysqlPassword());
            hikari.setMaximumPoolSize(cfg.getMysqlPoolSize());
            hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
            logger.info("[MineStock] 使用 MySQL 数据库");
        } else {
            File dataDir = new File(pluginFolder, "data");
            dataDir.mkdirs();
            String h2Path = cfg.getH2File();
            hikari.setJdbcUrl("jdbc:h2:file:./" + h2Path + ";MODE=MySQL;AUTO_SERVER=TRUE");
            hikari.setMaximumPoolSize(2);
            hikari.setDriverClassName("org.h2.Driver");
            logger.info("[MineStock] 使用 H2 本地数据库");
        }

        hikari.setPoolName("MineStock-Pool");
        hikari.setConnectionTimeout(15000);
        hikari.setIdleTimeout(300000);
        hikari.setMaxLifetime(600000);

        dataSource = new HikariDataSource(hikari);
        createTables();
    }

    private void createTables() throws SQLException {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS holdings (
                        id           BIGINT AUTO_INCREMENT PRIMARY KEY,
                        player_uuid  VARCHAR(36) NOT NULL,
                        stock_code   VARCHAR(20) NOT NULL,
                        amount       INT NOT NULL,
                        avg_cost     DOUBLE NOT NULL,
                        last_price   DOUBLE NOT NULL,
                        last_fetched BIGINT NOT NULL,
                        UNIQUE (player_uuid, stock_code)
                    )
                    """);
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
