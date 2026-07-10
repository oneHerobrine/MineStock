package dev.onelili.mstock.config;

import dev.onelili.mstock.MineStock;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainConfig {
    private final MineStock plugin;

    public MainConfig(MineStock plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public List<String> getRecommendedPool() {
        return cfg().getStringList("recommended-pool");
    }

    public int getRecommendedCount() {
        return cfg().getInt("recommended-count", 3);
    }

    public int getApiCooldownSeconds() {
        return cfg().getInt("api-cooldown-seconds", 30);
    }

    public double getPriceRatio() {
        return cfg().getDouble("price-ratio", 1.0);
    }

    public List<Map<?, ?>> getUsStockApis() {
        List<Map<?, ?>> list = new ArrayList<>();
        List<?> raw = cfg().getList("us-stock-apis");
        if (raw != null) {
            for (Object o : raw) {
                if (o instanceof Map<?, ?> m) list.add(m);
            }
        }
        return list;
    }
}
