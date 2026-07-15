package dev.onelili.mstock.sign;

import org.bukkit.Location;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/** Small YAML store for managed sign locations. */
public final class StockSignRepository {
    private final File file;
    private final Logger logger;
    private final Map<String, StockSign> signs = new LinkedHashMap<>();

    public StockSignRepository(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "signs.yml");
        this.logger = logger;
        load();
    }

    public synchronized List<StockSign> all() {
        return List.copyOf(signs.values());
    }

    public synchronized StockSign find(Location location) {
        return signs.values().stream().filter(sign -> sign.isAt(location)).findFirst().orElse(null);
    }

    public synchronized void put(StockSign sign) {
        signs.put(sign.storageKey(), sign);
        save();
    }

    public synchronized boolean remove(Location location) {
        String key = signs.entrySet().stream()
                .filter(entry -> entry.getValue().isAt(location))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
        if (key == null) return false;
        signs.remove(key);
        save();
        return true;
    }

    private void load() {
        if (!file.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("signs");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) continue;
            try {
                StockSign sign = new StockSign(
                        UUID.fromString(section.getString("world", "")),
                        section.getInt("x"), section.getInt("y"), section.getInt("z"),
                        StockSign.Mode.valueOf(section.getString("mode", "")),
                        section.getString("argument", ""),
                        Side.valueOf(section.getString("side", Side.FRONT.name())));
                signs.put(sign.storageKey(), sign);
            } catch (IllegalArgumentException error) {
                logger.warning("[MineStock] 忽略损坏的告示牌记录 " + key + ": " + error.getMessage());
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (StockSign sign : new ArrayList<>(signs.values())) {
            String path = "signs." + sign.storageKey();
            yaml.set(path + ".world", sign.worldId().toString());
            yaml.set(path + ".x", sign.x());
            yaml.set(path + ".y", sign.y());
            yaml.set(path + ".z", sign.z());
            yaml.set(path + ".mode", sign.mode().name());
            yaml.set(path + ".argument", sign.argument());
            yaml.set(path + ".side", sign.side().name());
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            logger.warning("[MineStock] 无法创建告示牌存储目录: " + parent);
            return;
        }
        try {
            yaml.save(file);
        } catch (IOException error) {
            logger.warning("[MineStock] 保存告示牌失败: " + error.getMessage());
        }
    }
}
