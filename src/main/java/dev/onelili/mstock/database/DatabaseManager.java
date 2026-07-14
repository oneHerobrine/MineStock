package dev.onelili.mstock.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.onelili.mstock.config.DatabaseConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Enumeration;
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
            org.h2.Driver.load();
            hikari.setJdbcUrl("jdbc:h2:file:./" + h2Path + ";MODE=MySQL;AUTO_SERVER=TRUE");
            hikari.setMaximumPoolSize(2);
            hikari.setDriverClassName("org.h2.Driver");
            logger.info("[MineStock] 使用 H2 本地数据库");
        }

        hikari.setPoolName("MineStock-Pool");
        hikari.setConnectionTimeout(15000);
        hikari.setIdleTimeout(300000);
        hikari.setMaxLifetime(600000);

        initDataSource(hikari);
    }

    void initDataSource(HikariConfig hikari) throws SQLException {
        if (dataSource != null) close();
        dataSource = new HikariDataSource(hikari);
        try {
            createTables();
        } catch (SQLException e) {
            close();
            throw e;
        }
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
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("MineStock database is closed");
        }
        return dataSource.getConnection();
    }

    public synchronized void close() {
        HikariDataSource current = dataSource;
        dataSource = null;
        if (current != null && !current.isClosed()) {
            current.close();
        }

        ClassLoader pluginLoader = DatabaseManager.class.getClassLoader();
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() != pluginLoader) continue;
            try {
                if (driver.getClass().getName().equals("org.h2.Driver")) {
                    org.h2.Driver.unload();
                } else {
                    DriverManager.deregisterDriver(driver);
                }
            } catch (SQLException e) {
                logger.warning("[MineStock] 注销 JDBC 驱动失败: " + e.getMessage());
            }
        }
    }
}
