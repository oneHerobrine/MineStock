package dev.onelili.mstock.config;

import dev.onelili.mstock.MineStock;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class DatabaseConfig {
    private final FileConfiguration cfg;

    public DatabaseConfig(MineStock plugin) {
        File file = new File(plugin.getDataFolder(), "database.yml");
        if (!file.exists()) {
            plugin.saveResource("database.yml", false);
        }
        cfg = YamlConfiguration.loadConfiguration(file);
        // merge defaults from jar
        InputStream defaultStream = plugin.getResource("database.yml");
        if (defaultStream != null) {
            cfg.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8)));
        }
    }

    public String getType() {
        return cfg.getString("type", "h2");
    }

    public String getH2File() {
        return cfg.getString("h2.file", "plugins/MineStock/data/minestock");
    }

    public String getMysqlHost() { return cfg.getString("mysql.host", "localhost"); }
    public int getMysqlPort() { return cfg.getInt("mysql.port", 3306); }
    public String getMysqlDatabase() { return cfg.getString("mysql.database", "minestock"); }
    public String getMysqlUsername() { return cfg.getString("mysql.username", "root"); }
    public String getMysqlPassword() { return cfg.getString("mysql.password", ""); }
    public int getMysqlPoolSize() { return cfg.getInt("mysql.pool-size", 5); }
}
