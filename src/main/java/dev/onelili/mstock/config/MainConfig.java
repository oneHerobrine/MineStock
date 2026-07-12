package dev.onelili.mstock.config;

import dev.onelili.mstock.MineStock;
import dev.onelili.mstock.api.ApiEntry;
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

    /**
     * 获取操作冷却时间（毫秒）。
     * 优先读取 api-cooldown-ms，不存在则兼容旧配置 api-cooldown-seconds × 1000。
     */
    public long getApiCooldownMs() {
        if (cfg().contains("api-cooldown-ms")) {
            return cfg().getLong("api-cooldown-ms", 1000L);
        }
        return cfg().getInt("api-cooldown-seconds", 30) * 1000L;
    }

    public double getPriceRatio() {
        return cfg().getDouble("price-ratio", 1.0);
    }

    /** 手续费费率，范围 [0, 1)，例如 0.005 表示 0.5%。 */
    public double getTransactionFeeRate() {
        double pct = cfg().getDouble("transaction-fee-percent", 0.5);
        return Math.max(0.0, pct) / 100.0;
    }

    public List<ApiEntry> getApiEntries(String configKey) {
        return getApiEntries(configKey, null);
    }

    /**
     * @param defaultTradeable 若配置中未指定 tradeable，使用此默认值；传 null 则默认 false
     */
    public List<ApiEntry> getApiEntries(String configKey, Boolean defaultTradeable) {
        List<ApiEntry> list = new ArrayList<>();
        List<?> raw = cfg().getList(configKey);
        if (raw == null) return list;
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object ifaceObj = m.get("interface");
            if (ifaceObj == null) continue;
            String iface = String.valueOf(ifaceObj).strip().toLowerCase();
            if (iface.isBlank()) continue;
            Object keyObj = m.get("apikey");
            String apiKey = keyObj != null ? String.valueOf(keyObj).strip() : null;
            boolean tradeable;
            if (m.containsKey("tradeable")) {
                Object tv = m.get("tradeable");
                tradeable = Boolean.parseBoolean(String.valueOf(tv));
            } else {
                tradeable = defaultTradeable != null && defaultTradeable;
            }
            list.add(new ApiEntry(iface, apiKey, tradeable));
        }
        return list;
    }

    public long getKlineCacheMs() {
        return cfg().getLong("kline-cache-ms", 300_000L);
    }
}
